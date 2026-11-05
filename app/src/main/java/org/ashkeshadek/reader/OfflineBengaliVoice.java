package org.ashkeshadek.reader;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

final class OfflineBengaliVoice {
    interface Listener { void onStatus(String message); void onPartial(String text); void onResult(String text); void onError(String message); }
    private static final int MODEL_RATE=16000;
    private static final int[] CAPTURE_RATES={16000,48000,44100};
    private static final String MODEL_DIR="sherpa-onnx-bn-vosk-int8-19d0fc5";
    private static final Object RECOGNIZER_LOCK = new Object();
    private static final Object SESSION_LOCK = new Object();
    private static volatile OnlineRecognizer cachedRecognizer;
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile AudioRecord recorder;
    private volatile boolean stopRequested,cancelled,beginRequested;
    private Thread worker;

    OfflineBengaliVoice(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){if(worker!=null)return;worker=new Thread(this::runRecognition,"ashke-bengali-asr");worker.start();}
    void beginListening(){synchronized(this){beginRequested=true;notifyAll();}}
    void finish(){stopRequested=true;stopRecorder();}
    void cancel(){cancelled=true;stopRequested=true;stopRecorder();}
    private void stopRecorder(){
        AudioRecord current=recorder;
        if(current!=null)try{if(current.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)current.stop();}catch(IllegalStateException ignored){}
    }
    private void status(String value){main.post(()->{if(!cancelled)listener.onStatus(value);});}
    private void partial(String value){main.post(()->{if(!cancelled)listener.onPartial(value);});}
    private void fail(String value){main.post(()->{if(!cancelled)listener.onError(value);});}

    private static final class AudioInput{
        final AudioRecord record;final int sampleRate;
        AudioInput(AudioRecord record,int sampleRate){this.record=record;this.sampleRate=sampleRate;}
    }
    private AudioInput createAudioInput(){
        for(int rate:CAPTURE_RATES){
            int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)continue;
            AudioRecord candidate=null;
            try{
                candidate=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,rate*2/5));
                if(candidate.getState()==AudioRecord.STATE_INITIALIZED)return new AudioInput(candidate,rate);
            }catch(IllegalArgumentException ignored){}
            if(candidate!=null)try{candidate.release();}catch(Exception ignored){}
        }
        throw new IllegalStateException("এই ফোনের মাইক্রোফোনে সমর্থিত অডিও রেট পাওয়া যায়নি।");
    }
    private float[] toModelSamples(short[] pcm,int count,int sourceRate){
        if(sourceRate==MODEL_RATE){float[] out=new float[count];for(int i=0;i<count;i++)out[i]=pcm[i]/32768.0f;return out;}
        int size=(int)Math.floor(count*(MODEL_RATE/(double)sourceRate));
        if(size<=0)return new float[0];
        float[] out=new float[size];double ratio=sourceRate/(double)MODEL_RATE;
        for(int i=0;i<size;i++){
            double pos=i*ratio;int left=Math.min((int)pos,count-1),right=Math.min(left+1,count-1);
            float f=(float)(pos-left),a=pcm[left]/32768.0f,b=pcm[right]/32768.0f;
            out[i]=a+(b-a)*f;
        }
        return out;
    }

    private void runRecognition(){
        synchronized (SESSION_LOCK) {
            runRecognitionSession();
        }
    }

    private void runRecognitionSession(){
        OnlineRecognizer recognizer=null;OnlineStream stream=null;AudioRecord audio=null;
        StringBuilder completedText=new StringBuilder();String latestText="";
        try{
            if(cancelled||stopRequested)return;
            status("বাংলা অফলাইন মডেল চালু হচ্ছে…");
            FeatureConfig feature=new FeatureConfig();feature.setSampleRate(MODEL_RATE);feature.setFeatureDim(80);
            OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
            transducer.setEncoder(MODEL_DIR+"/encoder.int8.onnx");
            transducer.setDecoder(MODEL_DIR+"/decoder.onnx");
            transducer.setJoiner(MODEL_DIR+"/joiner.int8.onnx");
            OnlineModelConfig model=new OnlineModelConfig();
            model.setTransducer(transducer);model.setTokens(MODEL_DIR+"/tokens.txt");
            model.setNumThreads(Math.max(1,Math.min(2,Runtime.getRuntime().availableProcessors())));
            model.setDebug(false);model.setProvider("cpu");model.setModelType("zipformer2");
            OnlineRecognizerConfig config=new OnlineRecognizerConfig();
            config.setFeatConfig(feature);config.setModelConfig(model);

            // Endpoint boundaries split phrases; they must never end this session.
            // The pinned AAR's default rule can also detect initial silence, so
            // the capture loop below resets the stream and continues listening.
            config.setEnableEndpoint(true);

            synchronized (RECOGNIZER_LOCK) {
                if (cachedRecognizer == null) {
                    cachedRecognizer = new OnlineRecognizer(context.getAssets(), config);
                }
                recognizer = cachedRecognizer;
            }
            stream=recognizer.createStream("");

            status("প্রস্তুত। শুরু করুন চাপুন, তারপর শুধু গানের লাইনটি বলুন।");
            synchronized (this) {
                while (!beginRequested && !stopRequested && !cancelled) wait();
            }
            if (cancelled || stopRequested) return;

            AudioInput input=createAudioInput();
            audio=input.record;
            recorder=audio;
            // The user may cancel while the speech model is being initialized.
            // Do not open the microphone after that cancellation.
            if(cancelled||stopRequested)return;
            audio.startRecording();
            if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("মাইক্রোফোন রেকর্ডিং শুরু করতে পারেনি।");
            status("শুনছি — একটি ছোট গানের লাইন বলুন।");
            short[] buffer=new short[Math.max(1600,input.sampleRate/10)];

            while(!stopRequested&&!cancelled){
                int count=audio.read(buffer,0,buffer.length);
                if(count<0){
                    if(stopRequested||cancelled)break;
                    throw new IllegalStateException("মাইক্রোফোন থেকে অডিও পাওয়া যাচ্ছে না ("+count+")।");
                }
                if(count==0)continue;
                float[] samples=toModelSamples(buffer,count,input.sampleRate);
                if(samples.length==0)continue;
                stream.acceptWaveform(samples,MODEL_RATE);
                while(recognizer.isReady(stream))recognizer.decode(stream);

                OnlineRecognizerResult result=recognizer.getResult(stream);
                latestText=result==null||result.getText()==null?"":result.getText().trim();
                String preview=joinTranscript(completedText.toString(),latestText);
                if(!preview.isEmpty())partial(preview);

                // Endpoint marks a phrase boundary, not the end of the entire
                // voice-search session. Preserve the phrase, reset this stream,
                // and keep listening for the next phrase or the user's Finish tap.
                if(recognizer.isEndpoint(stream)){
                    OnlineRecognizerResult finalResult=recognizer.getResult(stream);
                    String phrase=finalResult==null||finalResult.getText()==null
                            ?latestText:finalResult.getText().trim();
                    if(!phrase.isEmpty()){
                        if(completedText.length()>0)completedText.append(' ');
                        completedText.append(phrase);
                    }
                    latestText="";
                    recognizer.reset(stream);
                    if(completedText.length()>0)partial(completedText.toString());
                }
            }

            if(stopRequested&&!cancelled){
                stream.inputFinished();
                while(recognizer.isReady(stream))recognizer.decode(stream);
                OnlineRecognizerResult finalResult=recognizer.getResult(stream);
                latestText=finalResult==null||finalResult.getText()==null
                        ?"":finalResult.getText().trim();
                String phrase=joinTranscript(completedText.toString(),latestText);
                if(!phrase.isEmpty()){
                    String finalPhrase=phrase;
                    main.post(()->{if(!cancelled)listener.onResult(finalPhrase);});
                }else{
                    fail("কোনো কথা শনাক্ত হয়নি। আবার বলুন অথবা লিখে গান খুঁজুন।");
                }
            }
        }catch(Throwable error){
            Log.e("AshkeShadekVoice","Offline Bengali recognition failed",error);
            String message=error.getMessage();
            fail(message==null||message.trim().isEmpty()
                    ?"অফলাইন ভয়েস সার্চ চালু করা যায়নি। লিখে গান খুঁজুন।"
                    :"ভয়েস সার্চ: "+message);
        }finally{
            recorder=null;
            if(audio!=null){
                try{if(audio.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)audio.stop();}catch(Exception ignored){}
                try{audio.release();}catch(Exception ignored){}
            }
            if(stream!=null)try{stream.release();}catch(Exception ignored){}
        }
    }

    private static String joinTranscript(String first,String second){
        String a=first==null?"":first.trim();
        String b=second==null?"":second.trim();
        if(a.isEmpty())return b;
        if(b.isEmpty())return a;
        return a+" "+b;
    }
}

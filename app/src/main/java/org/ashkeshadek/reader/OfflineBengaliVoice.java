package org.ashkeshadek.reader;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

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
    private static final String MODEL_DIR="sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09";
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile AudioRecord recorder;
    private volatile boolean stopRequested,cancelled;
    private Thread worker;

    OfflineBengaliVoice(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){if(worker!=null)return;worker=new Thread(this::runRecognition,"ashke-bengali-asr");worker.start();}
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
        OnlineRecognizer recognizer=null;OnlineStream stream=null;AudioRecord audio=null;boolean delivered=false;
        try{
            status("বাংলা অফলাইন মডেল চালু হচ্ছে…");
            FeatureConfig feature=new FeatureConfig();feature.setSampleRate(MODEL_RATE);feature.setFeatureDim(80);
            OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
            transducer.setEncoder(MODEL_DIR+"/encoder.onnx");
            transducer.setDecoder(MODEL_DIR+"/decoder.onnx");
            transducer.setJoiner(MODEL_DIR+"/joiner.onnx");
            OnlineModelConfig model=new OnlineModelConfig();
            model.setTransducer(transducer);model.setTokens(MODEL_DIR+"/tokens.txt");
            model.setNumThreads(Math.max(1,Math.min(2,Runtime.getRuntime().availableProcessors())));
            model.setDebug(false);model.setProvider("cpu");model.setModelType("zipformer2");
            OnlineRecognizerConfig config=new OnlineRecognizerConfig();
            config.setFeatConfig(feature);config.setModelConfig(model);config.setEnableEndpoint(true);
            recognizer=new OnlineRecognizer(context.getAssets(),config);stream=recognizer.createStream();

            AudioInput input=createAudioInput();audio=input.record;recorder=audio;audio.startRecording();
            if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("মাইক্রোফোন রেকর্ডিং শুরু করতে পারেনি।");
            status("শুনছি… বাংলায় গানের নাম বা লাইন বলুন।");
            short[] buffer=new short[Math.max(1600,input.sampleRate/10)];String latestText="";
            while(!stopRequested&&!cancelled){
                int count=audio.read(buffer,0,buffer.length);
                if(count<0){if(stopRequested||cancelled)break;throw new IllegalStateException("মাইক্রোফোন থেকে অডিও পাওয়া যাচ্ছে না ("+count+")।");}
                if(count==0)continue;
                float[] samples=toModelSamples(buffer,count,input.sampleRate);if(samples.length==0)continue;
                stream.acceptWaveform(samples,MODEL_RATE);while(recognizer.isReady(stream))recognizer.decode(stream);
                OnlineRecognizerResult result=recognizer.getResult(stream);
                if(result!=null&&result.getText()!=null){latestText=result.getText().trim();if(!latestText.isEmpty())partial(latestText);}
                if(recognizer.isEndpoint(stream)){
                    OnlineRecognizerResult finalResult=recognizer.getResult(stream);
                    String phrase=finalResult==null?latestText:finalResult.getText().trim();
                    if(!phrase.isEmpty()&&!cancelled){String finalPhrase=phrase;main.post(()->{if(!cancelled)listener.onResult(finalPhrase);});delivered=true;}
                    break;
                }
            }
            if(stopRequested&&!cancelled&&!delivered){
                stream.inputFinished();while(recognizer.isReady(stream))recognizer.decode(stream);
                OnlineRecognizerResult finalResult=recognizer.getResult(stream);
                String phrase=finalResult==null?latestText:finalResult.getText().trim();
                if(!phrase.isEmpty()){String finalPhrase=phrase;main.post(()->{if(!cancelled)listener.onResult(finalPhrase);});delivered=true;}
            }
            if(!cancelled&&!delivered&&stopRequested)fail("কোনো কথা শনাক্ত হয়নি। আবার বলুন অথবা লিখে খুঁজুন।");
        }catch(Throwable error){
            String message=error.getMessage();
            fail(message==null||message.trim().isEmpty()?"অফলাইন ভয়েস সার্চ চালু করা যায়নি। লিখে গান খুঁজুন।":"ভয়েস সার্চ: "+message);
        }finally{
            recorder=null;
            if(audio!=null){
                try{if(audio.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)audio.stop();}catch(Exception ignored){}
                try{audio.release();}catch(Exception ignored){}
            }
            if(stream!=null)try{stream.release();}catch(Exception ignored){}
            if(recognizer!=null)try{recognizer.release();}catch(Exception ignored){}
        }
    }
}

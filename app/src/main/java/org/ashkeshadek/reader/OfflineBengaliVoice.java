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
    interface Listener {
        void onStatus(String message);
        void onPartial(String text);
        void onResult(String text);
        void onError(String message);
        void onFinished();
    }

    private static final int MODEL_RATE = 16000;
    private static final int[] CAPTURE_RATES = {16000, 48000, 44100};
    private static final String MODEL_DIR = "sherpa-onnx-bn-vosk-int8-19d0fc5";
    private static final Object SESSION_LOCK = new Object();

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile AudioRecord recorder;
    private volatile Thread worker;
    private volatile boolean releaseRequested;
    private volatile boolean modelReady;
    private volatile boolean waitingForBegin;
    private volatile boolean stopRequested;
    private volatile boolean sessionCancelled;
    private boolean beginRequested;

    OfflineBengaliVoice(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start() {
        if (isRunning()) return;
        releaseRequested = false;
        modelReady = false;
        waitingForBegin = false;
        stopRequested = false;
        sessionCancelled = false;
        beginRequested = false;
        worker = new Thread(this::runRecognition, "ashke-bengali-asr");
        worker.start();
    }

    synchronized boolean isRunning() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    boolean isModelReady() { return modelReady; }
    boolean isWaitingForBegin() { return waitingForBegin; }
    boolean isReleasing() { return releaseRequested; }

    synchronized void beginListening() {
        if (releaseRequested || !modelReady) return;
        sessionCancelled = false;
        stopRequested = false;
        beginRequested = true;
        notifyAll();
    }

    void finish() {
        stopRequested = true;
        stopRecorder();
        synchronized (this) { notifyAll(); }
    }

    void cancel() {
        if (!waitingForBegin) {
            sessionCancelled = true;
            stopRequested = true;
            stopRecorder();
        }
        synchronized (this) { notifyAll(); }
    }

    void release() {
        releaseRequested = true;
        sessionCancelled = true;
        stopRequested = true;
        stopRecorder();
        synchronized (this) { notifyAll(); }
    }

    void awaitStopped(long timeoutMs) {
        Thread current = worker;
        if (current == null || current == Thread.currentThread()) return;
        try { current.join(timeoutMs); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopRecorder() {
        AudioRecord current = recorder;
        if (current != null) {
            try {
                if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop();
            } catch (IllegalStateException ignored) { }
        }
    }

    private void status(String value) {
        main.post(() -> { if (!sessionCancelled && !releaseRequested) listener.onStatus(value); });
    }

    private void partial(String value) {
        main.post(() -> { if (!sessionCancelled && !releaseRequested) listener.onPartial(value); });
    }

    private void error(String value) {
        main.post(() -> { if (!releaseRequested) listener.onError(value); });
    }

    private static final class AudioInput {
        final AudioRecord record;
        final int sampleRate;
        AudioInput(AudioRecord record, int sampleRate) {
            this.record = record;
            this.sampleRate = sampleRate;
        }
    }

    private AudioInput createAudioInput() {
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            for (int rate : CAPTURE_RATES) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * 2 / 5));
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        return new AudioInput(candidate, rate);
                    }
                } catch (IllegalArgumentException ignored) { }
                if (candidate != null) try { candidate.release(); } catch (Exception ignored) { }
            }
        }
        throw new IllegalStateException("এই ফোনের মাইক্রোফোনে সমর্থিত অডিও রেট পাওয়া যায়নি।");
    }

    private float[] toModelSamples(short[] pcm, int count, int sourceRate) {
        if (sourceRate == MODEL_RATE) {
            float[] out = new float[count];
            for (int i = 0; i < count; i++) out[i] = pcm[i] / 32768.0f;
            return out;
        }
        int size = (int) Math.floor(count * (MODEL_RATE / (double) sourceRate));
        if (size <= 0) return new float[0];
        float[] out = new float[size];
        double ratio = sourceRate / (double) MODEL_RATE;
        for (int i = 0; i < size; i++) {
            double pos = i * ratio;
            int left = Math.min((int) pos, count - 1);
            int right = Math.min(left + 1, count - 1);
            float fraction = (float) (pos - left);
            float a = pcm[left] / 32768.0f;
            float b = pcm[right] / 32768.0f;
            out[i] = a + (b - a) * fraction;
        }
        return out;
    }

    private void runRecognition() {
        synchronized (SESSION_LOCK) { runRecognitionLoop(); }
    }

    private void runRecognitionLoop() {
        OnlineRecognizer recognizer = null;
        try {
            status("বাংলা অফলাইন মডেল চালু হচ্ছে…");
            recognizer = createRecognizer();
            modelReady = true;
            waitingForBegin = true;
            status("প্রস্তুত। এখন গানের একটি লাইন বলুন—মিল পাওয়া গেলে গানটি ছুঁয়ে খুলুন।");

            while (!releaseRequested) {
                synchronized (this) {
                    while (!beginRequested && !releaseRequested) wait();
                    if (releaseRequested) break;
                    beginRequested = false;
                    stopRequested = false;
                    sessionCancelled = false;
                    waitingForBegin = false;
                }
                runListeningSession(recognizer);
                if (!releaseRequested) waitingForBegin = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!releaseRequested) error("ভয়েস সার্চ বন্ধ হয়ে গেছে। আবার চেষ্টা করুন।");
        } catch (Throwable failure) {
            Log.e("AshkeShadekVoice", "Offline Bengali model failed", failure);
            if (!releaseRequested) error(messageFor(failure));
        } finally {
            waitingForBegin = false;
            modelReady = false;
            if (recognizer != null) try { recognizer.release(); } catch (Exception ignored) { }
            synchronized (this) {
                worker = null;
                notifyAll();
            }
        }
    }

    private OnlineRecognizer createRecognizer() {
        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(MODEL_RATE);
        feature.setFeatureDim(80);
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(MODEL_DIR + "/encoder.int8.onnx");
        transducer.setDecoder(MODEL_DIR + "/decoder.onnx");
        transducer.setJoiner(MODEL_DIR + "/joiner.int8.onnx");
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(MODEL_DIR + "/tokens.txt");
        model.setNumThreads(1);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setModelType("zipformer2");
        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setEnableEndpoint(true);
        return new OnlineRecognizer(context.getAssets(), config);
    }

    private void runListeningSession(OnlineRecognizer recognizer) {
        OnlineStream stream = null;
        AudioRecord audio = null;
        StringBuilder completedText = new StringBuilder();
        String latestText = "";
        try {
            if (releaseRequested || stopRequested || sessionCancelled) return;
            stream = recognizer.createStream("");
            AudioInput input = createAudioInput();
            audio = input.record;
            recorder = audio;
            if (releaseRequested || stopRequested || sessionCancelled) return;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("মাইক্রোফোন রেকর্ডিং শুরু করতে পারেনি।");
            }
            status("শুনছি — একটি ছোট গানের লাইন বলুন।");
            short[] buffer = new short[Math.max(1600, input.sampleRate / 10)];

            while (!stopRequested && !releaseRequested && !sessionCancelled) {
                int count = audio.read(buffer, 0, buffer.length);
                if (count < 0) {
                    if (stopRequested || releaseRequested || sessionCancelled) break;
                    throw new IllegalStateException("মাইক্রোফোন থেকে অডিও পাওয়া যাচ্ছে না (" + count + ")।");
                }
                if (count == 0) continue;
                float[] samples = toModelSamples(buffer, count, input.sampleRate);
                if (samples.length == 0) continue;
                stream.acceptWaveform(samples, MODEL_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);

                OnlineRecognizerResult result = recognizer.getResult(stream);
                latestText = result == null || result.getText() == null ? "" : result.getText().trim();
                String preview = joinTranscript(completedText.toString(), latestText);
                if (!preview.isEmpty()) partial(preview);

                if (recognizer.isEndpoint(stream)) {
                    OnlineRecognizerResult finalResult = recognizer.getResult(stream);
                    String phrase = finalResult == null || finalResult.getText() == null
                            ? latestText : finalResult.getText().trim();
                    if (!phrase.isEmpty()) {
                        if (completedText.length() > 0) completedText.append(' ');
                        completedText.append(phrase);
                    }
                    latestText = "";
                    recognizer.reset(stream);
                    if (completedText.length() > 0) partial(completedText.toString());
                }
            }

            if (stopRequested && !sessionCancelled && !releaseRequested) {
                stream.inputFinished();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult finalResult = recognizer.getResult(stream);
                latestText = finalResult == null || finalResult.getText() == null
                        ? "" : finalResult.getText().trim();
                String phrase = joinTranscript(completedText.toString(), latestText);
                if (!phrase.isEmpty()) {
                    String finalPhrase = phrase;
                    main.post(() -> { if (!releaseRequested) listener.onResult(finalPhrase); });
                } else {
                    error("কোনো কথা শনাক্ত হয়নি। আবার বলুন অথবা লিখে গান খুঁজুন।");
                }
            }
        } catch (Throwable failure) {
            Log.e("AshkeShadekVoice", "Offline Bengali recognition failed", failure);
            if (!releaseRequested && !sessionCancelled) error(messageFor(failure));
        } finally {
            recorder = null;
            if (audio != null) {
                try { if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop(); }
                catch (Exception ignored) { }
                try { audio.release(); } catch (Exception ignored) { }
            }
            if (stream != null) try { stream.release(); } catch (Exception ignored) { }
            if (!releaseRequested) main.post(listener::onFinished);
        }
    }

    private static String messageFor(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "অফলাইন ভয়েস সার্চ চালু করা যায়নি। লিখে গান খুঁজুন।"
                : "ভয়েস সার্চ: " + message;
    }

    private static String joinTranscript(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }
}

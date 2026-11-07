package org.ashkeshadek.reader;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;

/**
 * Runs Bengali ASR in a private process so native ONNX allocations stay out of
 * the UI process. The model can remain warm briefly between searches and is
 * released when the app leaves the foreground or the idle timer expires.
 */
public final class VoiceRecognitionService extends Service {
    public static final String ACTION_START = "org.ashkeshadek.reader.voice.START";
    public static final String ACTION_PREWARM = "org.ashkeshadek.reader.voice.PREWARM";
    public static final String ACTION_BEGIN = "org.ashkeshadek.reader.voice.BEGIN";
    public static final String ACTION_FINISH = "org.ashkeshadek.reader.voice.FINISH";
    public static final String ACTION_CANCEL = "org.ashkeshadek.reader.voice.CANCEL";
    public static final String ACTION_RELEASE = "org.ashkeshadek.reader.voice.RELEASE";
    public static final String EXTRA_RECEIVER = "voice_result_receiver";

    public static final int RESULT_STATUS = 1;
    public static final int RESULT_PARTIAL = 2;
    public static final int RESULT_TEXT = 3;
    public static final int RESULT_ERROR = 4;
    public static final int RESULT_FINISHED = 5;

    private ResultReceiver receiver;
    private OfflineBengaliVoice engine;
    private int latestStartId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable idleShutdown;
    private boolean stopAfterFinish;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            receiver = intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        } else {
            //noinspection deprecation
            receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
        }

        String action = intent.getAction();
        if (ACTION_PREWARM.equals(action)) {
            cancelIdleShutdown();
            if (engine == null) createEngine();
            if (!engine.isRunning()) engine.start();
        } else if (ACTION_START.equals(action)) {
            cancelIdleShutdown();
            if (engine == null) {
                createEngine();
                engine.start();
            } else if (engine.isWaitingForBegin() && engine.isModelReady()) {
                sendText(RESULT_STATUS, "প্রস্তুত। এখন গানের একটি লাইন বলুন—মিল পাওয়া গেলে গানটি ছুঁয়ে খুলুন।");
            } else if (engine.isRunning()) {
                // A prewarm that is still loading will report readiness through
                // the receiver as soon as its model finishes loading.
                if (engine.isReleasing()) {
                    sendError("ভয়েস মডেল বন্ধ হচ্ছে। কয়েক সেকেন্ড পর আবার চেষ্টা করুন।");
                } else if (engine.isModelReady()) {
                    sendError("আগের ভয়েস সেশনটি এখনো বন্ধ হচ্ছে। আবার চেষ্টা করুন।");
                }
            } else {
                engine.start();
            }
        } else if (ACTION_BEGIN.equals(action)) {
            cancelIdleShutdown();
            if (engine != null) engine.beginListening();
            else finishMissingSession(startId);
        } else if (ACTION_FINISH.equals(action)) {
            cancelIdleShutdown();
            if (engine != null) engine.finish();
            else finishMissingSession(startId);
        } else if (ACTION_CANCEL.equals(action)) {
            if (engine != null) engine.cancel();
            scheduleIdleShutdown();
        } else if (ACTION_RELEASE.equals(action)) {
            cancelIdleShutdown();
            stopAfterFinish = true;
            if (engine != null) {
                engine.release();
                waitForEngineToStop(engine);
            } else {
                stopSelfResult(startId);
            }
        }
        return START_NOT_STICKY;
    }

    private void createEngine() {
        engine = new OfflineBengaliVoice(this, new OfflineBengaliVoice.Listener() {
                @Override public void onStatus(String message) {
                    sendText(RESULT_STATUS, message);
                    if (message.startsWith("প্রস্তুত")) logMemory("voice-model-loaded");
                    else if (message.startsWith("শুনছি")) logMemory("voice-capture-active");
                    if (message.startsWith("প্রস্তুত") && engine != null
                            && engine.isWaitingForBegin()) scheduleIdleShutdown();
                }

                @Override public void onPartial(String text) {
                    sendText(RESULT_PARTIAL, text);
                }

                @Override public void onResult(String text) {
                    sendText(RESULT_TEXT, text);
                }

                @Override public void onError(String message) {
                    sendError(message);
                    scheduleIdleShutdown();
                }

                @Override public void onFinished() {
                    sendEmpty(RESULT_FINISHED);
                    if (stopAfterFinish) finishShutdown();
                    else scheduleIdleShutdown();
                }
            });
            logMemory("before-model");
    }

    private void cancelIdleShutdown() {
        if (idleShutdown != null) handler.removeCallbacks(idleShutdown);
        idleShutdown = null;
        stopAfterFinish = false;
    }

    private void scheduleIdleShutdown() {
        cancelIdleShutdown();
        idleShutdown = () -> {
            OfflineBengaliVoice current = engine;
            if (current == null) {
                stopSelfResult(latestStartId);
                return;
            }
            if (current.isRunning() && !current.isWaitingForBegin()) {
                scheduleIdleShutdown();
                return;
            }
            stopAfterFinish = true;
            current.release();
            waitForEngineToStop(current);
        };
        handler.postDelayed(idleShutdown, 120_000L);
    }

    private void waitForEngineToStop(OfflineBengaliVoice current) {
        handler.postDelayed(() -> {
            if (engine != current) return;
            if (current.isRunning()) waitForEngineToStop(current);
            else finishShutdown();
        }, 100L);
    }

    private void finishShutdown() {
        cancelIdleShutdown();
        if (engine != null) {
            OfflineBengaliVoice old = engine;
            engine = null;
            old.release();
        }
        stopAfterFinish = false;
        stopSelfResult(latestStartId);
    }

    private void finishMissingSession(int startId) {
        sendError("ভয়েস প্রসেসটি বন্ধ হয়ে গিয়েছিল। আবার ভয়েস সার্চ শুরু করুন।");
        sendEmpty(RESULT_FINISHED);
        stopSelfResult(startId);
    }

    private void sendText(int resultCode, String value) {
        ResultReceiver target = receiver;
        if (target == null) return;
        Bundle data = new Bundle();
        data.putString("text", value);
        target.send(resultCode, data);
    }

    private void sendError(String message) {
        sendText(RESULT_ERROR, message);
    }

    private void sendEmpty(int resultCode) {
        ResultReceiver target = receiver;
        if (target != null) target.send(resultCode, Bundle.EMPTY);
    }

    private void logMemory(String phase) {
        android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(memory);
        Log.i("AshkeMemory", "voice-" + phase + " pssKb=" + memory.getTotalPss()
                + " nativeHeapKb=" + (android.os.Debug.getNativeHeapAllocatedSize() / 1024L));
    }

    private final IBinder processConnection = new android.os.Binder();

    @Override public IBinder onBind(Intent intent) {
        // The binder is only a liveness signal. Commands and results travel
        // through explicit startService intents and a ResultReceiver.
        return processConnection;
    }

    @Override public void onDestroy() {
        cancelIdleShutdown();
        if (engine != null) {
            engine.release();
            engine.awaitStopped(2000L);
            engine = null;
        }
        super.onDestroy();
        logMemory("service-destroy");
        // This component always runs in the private :voice process.
        // Exit only after the recognizer release path has completed.
        Process.killProcess(Process.myPid());
    }
}

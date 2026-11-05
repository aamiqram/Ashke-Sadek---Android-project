package org.ashkeshadek.reader;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;

/**
 * Runs Bengali ASR in a short-lived private process. Killing this process after
 * each session guarantees that native ONNX allocations cannot remain resident
 * in the UI process or in a cached service process.
 */
public final class VoiceRecognitionService extends Service {
    public static final String ACTION_START = "org.ashkeshadek.reader.voice.START";
    public static final String ACTION_BEGIN = "org.ashkeshadek.reader.voice.BEGIN";
    public static final String ACTION_FINISH = "org.ashkeshadek.reader.voice.FINISH";
    public static final String ACTION_CANCEL = "org.ashkeshadek.reader.voice.CANCEL";
    public static final String EXTRA_RECEIVER = "voice_result_receiver";

    public static final int RESULT_STATUS = 1;
    public static final int RESULT_PARTIAL = 2;
    public static final int RESULT_TEXT = 3;
    public static final int RESULT_ERROR = 4;
    public static final int RESULT_FINISHED = 5;

    private ResultReceiver receiver;
    private OfflineBengaliVoice engine;
    private int latestStartId;

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
        if (ACTION_START.equals(action)) {
            if (engine != null) {
                engine.cancel();
                sendError("আগের ভয়েস সেশনটি এখনো বন্ধ হচ্ছে। আবার চেষ্টা করুন।");
                return START_NOT_STICKY;
            }
            engine = new OfflineBengaliVoice(this, new OfflineBengaliVoice.Listener() {
                @Override public void onStatus(String message) {
                    sendText(RESULT_STATUS, message);
                }

                @Override public void onPartial(String text) {
                    sendText(RESULT_PARTIAL, text);
                }

                @Override public void onResult(String text) {
                    sendText(RESULT_TEXT, text);
                }

                @Override public void onError(String message) {
                    sendError(message);
                }

                @Override public void onFinished() {
                    sendEmpty(RESULT_FINISHED);
                    engine = null;
                    stopSelfResult(latestStartId);
                }
            });
            logMemory("before-model");
            engine.start();
        } else if (ACTION_BEGIN.equals(action)) {
            if (engine != null) engine.beginListening();
            else finishMissingSession(startId);
        } else if (ACTION_FINISH.equals(action)) {
            if (engine != null) engine.finish();
            else finishMissingSession(startId);
        } else if (ACTION_CANCEL.equals(action)) {
            if (engine != null) engine.cancel();
            else stopSelfResult(startId);
        }
        return START_NOT_STICKY;
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

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        if (engine != null) {
            engine.cancel();
            engine = null;
        }
        super.onDestroy();
        logMemory("service-destroy");
        // This component always runs in the private :voice process.
        // Exit only after the recognizer release path has completed.
        Process.killProcess(Process.myPid());
    }
}

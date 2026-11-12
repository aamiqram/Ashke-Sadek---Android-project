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
    /** Values "speech" (default) or "song"; selects the microphone pipeline. */
    public static final String EXTRA_MODE = "voice_capture_mode";

    public static final int RESULT_STATUS = 1;
    public static final int RESULT_PARTIAL = 2;
    public static final int RESULT_TEXT = 3;
    public static final int RESULT_ERROR = 4;
    public static final int RESULT_FINISHED = 5;
    public static final int RESULT_AUDIO_HINT = 6;

    private ResultReceiver receiver;
    private OfflineBengaliVoice engine;
    private int latestStartId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable idleShutdown;
    private boolean stopAfterFinish;

    /** True while this process holds foreground rank only for a prewarm load. */
    private boolean prewarmForeground;

    /** Speech capture by default; song capture is opt-in via EXTRA_MODE. */
    private int captureMode = OfflineBengaliVoice.MODE_SPEECH;

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

        ensureCaptureMode(
                "song".equals(intent.getStringExtra(EXTRA_MODE))
                        ? OfflineBengaliVoice.MODE_SONG : OfflineBengaliVoice.MODE_SPEECH);

        String action = intent.getAction();
        if (ACTION_PREWARM.equals(action)) {
            cancelIdleShutdown();
            if (engine == null) createEngine();
            if (!engine.isRunning()) {
                // A prewarm load runs unattended: hold foreground rank so
                // memory keepers cannot kill it mid-load on low-memory phones.
                prewarmForeground = true;
                promoteToForeground(true);
                engine.start();
            }
        } else if (ACTION_START.equals(action)) {
            cancelIdleShutdown();
            prewarmForeground = false;
            promoteToForeground();
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

    private static final int FOREGROUND_NOTIFICATION_ID = 1041;
    private static final String FOREGROUND_CHANNEL_ID = "ashke_voice_session";

    /**
     * Promotes this process to foreground rank while the heavy model loads and
     * the microphone stays open. Aggressive memory keepers (MIUI and friends)
     * reclaim background service processes at a fraction of their free-memory
     * thresholds, which killed the loader mid-run on every tested device; a
     * foreground service is the platform-sanctioned protection against that.
     */
    private void promoteToForeground() {
        promoteToForeground(false);
    }

    private void promoteToForeground(boolean loading) {
        try {
            android.app.Notification notification = buildForegroundNotification(loading);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
        } catch (Exception failure) {
            Log.w("AshkeShadekVoice", "Foreground promotion unavailable", failure);
        }
    }

    /** Session over or process shutting down: drop the rank and the notification. */
    private void demoteFromForeground() {
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    private android.app.Notification buildForegroundNotification() {
        return buildForegroundNotification(false);
    }

    private android.app.Notification buildForegroundNotification(boolean loading) {
        String title = loading ? TITLE_LOADING : TITLE_LISTENING;
        String text = loading ? TEXT_LOADING : TEXT_LISTENING;
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(FOREGROUND_CHANNEL_ID) == null) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        FOREGROUND_CHANNEL_ID, "কণ্ঠ খোঁজা",
                        android.app.NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("মাইক্রোফোন শোনার সময় স্ট্যাটাস বারে দেখা যায়।");
                channel.setSound(null, null);
                manager.createNotificationChannel(channel);
            }
        }
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                : new android.app.Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_book)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private static final String TITLE_LISTENING = "\u098f\u09b6\u0995\u09c7 \u099b\u09be\u09a6\u09c7\u0995 \u2014 \u0995\u09a3\u09cd\u09a0 \u09b6\u09c1\u09a8\u099b\u09c7";
    private static final String TEXT_LISTENING = "\u0997\u09be\u09a8\u09c7\u09b0 \u098f\u0995\u099f\u09bf \u09b2\u09be\u0987\u09a8 \u09ac\u09b2\u09c1\u09a8\u2014\u09b8\u09ae\u09cd\u09ad\u09be\u09ac\u09cd\u09af \u0997\u09be\u09a8 \u09b8\u09be\u09ae\u09a8\u09c7 \u0986\u09b8\u09ac\u09c7\u0964";
    private static final String TITLE_LOADING = "\u098f\u09b6\u0995\u09c7 \u099b\u09be\u09a6\u09c7\u0995 \u2014 \u0995\u09a3\u09cd\u09a0 \u09aa\u09cd\u09b0\u09b8\u09cd\u09a4\u09c1\u09a4 \u09b9\u099a\u09cd\u099b\u09c7";
    private static final String TEXT_LOADING = "\u09ad\u09df\u09c7\u09b8 \u09ae\u09a1\u09c7\u09b2 \u09b2\u09cb\u09a1 \u09b9\u099a\u09cd\u099b\u09c7; \u09ae\u09be\u0987\u0995\u09cd\u09b0\u09cb\u09ab\u09cb\u09a8 \u098f\u0996\u09a8\u09cb \u099a\u09be\u09b2\u09c1 \u09b9\u09df\u09a8\u09bf\u0964";

    /**
     * The capture mode picks the microphone source and hardware effect set.
     * The engine caches the loaded model, so a mode change is the one case
     * where the warm model is given up and reloaded (about eight seconds on
     * the tested device); ordinary speech sessions reuse the warm engine
     * untouched. If a prewarm load was mid-flight (loading notification
     * showing), that load dies here — drop its notification with it or it
     * would linger forever, since the released worker never reports ready.
     */
    private void ensureCaptureMode(int requestedMode) {
        if (captureMode == requestedMode) return;
        captureMode = requestedMode;
        if (prewarmForeground) {
            prewarmForeground = false;
            demoteFromForeground();
        }
        if (engine != null) {
            engine.release();
            waitForEngineToStop(engine);
            engine = null;
        }
    }

    private void createEngine() {
        engine = new OfflineBengaliVoice(this, captureMode, new OfflineBengaliVoice.Listener() {
                @Override public void onStatus(String message) {
                    sendText(RESULT_STATUS, message);
                    if (message.startsWith("প্রস্তুত")) logMemory("voice-model-loaded");
                    if (prewarmForeground) {
                        // Load finished and no session asked for the mic:
                        // drop the loading notification, keep the warm model.
                        prewarmForeground = false;
                        demoteFromForeground();
                    }
                    else if (message.startsWith("শুনছি")) logMemory("voice-capture-active");
                    if (message.startsWith("প্রস্তুত") && engine != null
                            && engine.isWaitingForBegin()) scheduleIdleShutdown();
                }

                @Override public void onPartial(String text) {
                    sendText(RESULT_PARTIAL, text);
                }

                @Override public void onAudioHint(String message) {
                    // Empty text means the hint cleared: restore a normal
                    // listening line instead of leaving stale wording.
                    sendText(RESULT_AUDIO_HINT, message == null ? "" : message);
                }

                @Override public void onResult(String text) {
                    sendText(RESULT_TEXT, text);
                }

                @Override public void onError(String message) {
                    sendError(message);
                    scheduleIdleShutdown();
                }

                @Override public void onFinished() {
                    demoteFromForeground();
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
        handler.postDelayed(idleShutdown, idleWindowMs());
    }

    /**
     * How long a loaded-but-idle engine stays warm. Holding the loaded model
     * is the whole point of prewarming — the next mic tap opens instantly —
     * but the memory must eventually go back. Roomy devices keep it warm for
     * ten minutes; tight devices keep the original two minutes.
     */
    private long idleWindowMs() {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager != null) {
                android.app.ActivityManager.MemoryInfo memory =
                        new android.app.ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                if (memory.totalMem >= 2L * 1024L * 1024L * 1024L
                        && memory.availMem >= 256L * 1024L * 1024L) {
                    return 600_000L;
                }
            }
        } catch (Exception ignored) {
        }
        return 120_000L;
    }

    private void waitForEngineToStop(OfflineBengaliVoice current) {
        handler.postDelayed(() -> {
            if (engine != current) return;
            if (current.isRunning()) waitForEngineToStop(current);
            else finishShutdown();
        }, 100L);
    }

    private void finishShutdown() {
        demoteFromForeground();
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
        demoteFromForeground();
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

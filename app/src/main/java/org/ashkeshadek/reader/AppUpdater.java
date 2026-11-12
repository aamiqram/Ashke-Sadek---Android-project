package org.ashkeshadek.reader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class AppUpdater {
    private static final String RELEASES_API =
            "https://api.github.com/repos/aamiqram/Ashke-Sadek---Android-project/releases?per_page=10";
    private static final String RELEASE_ASSET_PREFIX =
            "https://github.com/aamiqram/Ashke-Sadek---Android-project/releases/download/";
    // Raw CDN mirror of the newest update.json (orphan branch pushed by the
    // release workflow). raw.githubusercontent.com has no per-IP API quota, so
    // shared broadband/carrier addresses can never exhaust it.
    private static final String MIRROR_MANIFEST_URL =
            "https://raw.githubusercontent.com/aamiqram/Ashke-Sadek---Android-project/update-mirror/update.json";
    private static final String PREFS = "ashke_update_state";
    private static final String TAG = "AshkeUpdater";
    /** startActivityForResult code for the Android 5-7 installer confirm. */
    static final int REQUEST_INSTALL_APK = 514;
    private static final String PENDING_PATH = "pending_path";
    private static final String PENDING_SHA = "pending_sha";
    private static final String PENDING_CERT = "pending_cert";
    private static final String PENDING_VERSION = "pending_version";
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context appContext;
    private static SSLSocketFactory updaterSslFactory;

    interface CheckCallback {
        void onUpdateAvailable(UpdateInfo update);
        void onNoUpdate();
        void onError(String message);
    }

    interface InstallCallback {
        void onStatus(String message);
        void onPermissionRequired();
        void onMigrationRequired(String message);
        void onError(String message);
    }

    static final class UpdateInfo {
        final long versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String certificateSha256;
        final String releaseNotes;
        final String releasePageUrl;
        final boolean requiresMigration;

        UpdateInfo(long versionCode, String versionName, String apkUrl, String sha256,
                   String certificateSha256, String releaseNotes, String releasePageUrl,
                   boolean requiresMigration) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.certificateSha256 = certificateSha256;
            this.releaseNotes = releaseNotes;
            this.releasePageUrl = releasePageUrl;
            this.requiresMigration = requiresMigration;
        }
    }

    private AppUpdater() { }

    static void checkForUpdates(Context context, CheckCallback callback) {
        Context app = context.getApplicationContext();
        appContext = app;
        new Thread(() -> {
            long installedVersion;
            String installedCertificate;
            try {
                installedVersion = installedVersionCode(app);
                installedCertificate = installedCertificate(app);
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(describeNetworkFailure(error)));
                return;
            }

            Exception failure = null;
            UpdateInfo newest = null;
            boolean mirrorAnswered = false;
            // Mirror first: one CDN request with no API quota. A stale mirror is
            // tolerated by re-confirming against the API without failing the
            // check; the API escalates to a visible error only when the mirror
            // itself could not be reached.
            try {
                newest = newestFromMirror(installedVersion, installedCertificate);
                mirrorAnswered = true;
            } catch (Exception error) {
                failure = error;
            }
            if (newest == null && !mirrorAnswered) {
                Exception apiFailure = null;
                for (int attempt = 0; attempt < 2 && newest == null; attempt++) {
                    if (attempt > 0) {
                        try {
                            Thread.sleep(1500L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    try {
                        newest = newestFromReleasesApi(installedVersion, installedCertificate);
                        apiFailure = null;
                    } catch (Exception error) {
                        apiFailure = error;
                    }
                }
                if (newest == null && apiFailure != null) failure = apiFailure;
            } else if (newest == null) {
                // Mirror answered "already current". Confirm once against the API
                // in case the mirror is stale; ignore API failures here so a
                // rate-limited shared IP can never fake an error.
                try {
                    newest = newestFromReleasesApi(installedVersion, installedCertificate);
                } catch (Exception ignored) {
                    // The mirror answer stands.
                }
            }
            if (newest != null) failure = null; // an authoritative answer was reached

            Exception reported = failure;
            UpdateInfo result = newest;
            MAIN.post(() -> {
                if (result != null) callback.onUpdateAvailable(result);
                else if (reported != null) callback.onError(describeNetworkFailure(reported));
                else callback.onNoUpdate();
            });
        }, "ashke-update-check").start();
    }

    /** Newest manifest straight from the raw CDN mirror; null when the app is current. */
    private static UpdateInfo newestFromMirror(long installedVersion, String installedCertificate)
            throws Exception {
        JSONObject manifest = new JSONObject(readText(MIRROR_MANIFEST_URL, 128 * 1024));
        long versionCode = manifest.getLong("versionCode");
        if (versionCode <= installedVersion) return null;
        String apkUrl = manifest.optString("apkUrl", "");
        String sha = manifest.optString("sha256", "");
        String cert = manifest.optString("certificateSha256", "");
        if (!apkUrl.startsWith(RELEASE_ASSET_PREFIX) || !isSha256(sha) || !isSha256(cert)) {
            throw new IllegalStateException("মিরর ম্যানিফেস্টে আপডেটের তথ্য অসম্পূর্ণ।");
        }
        String versionName = manifest.optString("versionName", "নতুন সংস্করণ");
        return new UpdateInfo(versionCode, versionName, apkUrl, sha, cert,
                manifest.optString("releaseNotes", ""),
                "https://github.com/aamiqram/Ashke-Sadek---Android-project/releases/tag/v"
                        + versionName,
                !sameFingerprint(installedCertificate, cert));
    }

    private static UpdateInfo newestFromReleasesApi(long installedVersion,
                                                    String installedCertificate) throws Exception {
        JSONArray releases = new JSONArray(readText(RELEASES_API, 1024 * 1024));
        UpdateInfo newest = null;

        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false)) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            String manifestUrl = null;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if ("update.json".equals(asset.optString("name"))) {
                    manifestUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
            if (manifestUrl == null || !manifestUrl.startsWith(RELEASE_ASSET_PREFIX)) continue;

            JSONObject manifest = new JSONObject(readText(manifestUrl, 128 * 1024));
            long versionCode = manifest.getLong("versionCode");
            if (versionCode <= installedVersion
                    || (newest != null && versionCode <= newest.versionCode)) continue;

            String apkUrl = manifest.optString("apkUrl", "");
            String sha = manifest.optString("sha256", "");
            String cert = manifest.optString("certificateSha256", "");
            if (!apkUrl.startsWith(RELEASE_ASSET_PREFIX) || !isSha256(sha) || !isSha256(cert)) {
                continue;
            }

            newest = new UpdateInfo(versionCode,
                    manifest.optString("versionName", "নতুন সংস্করণ"),
                    apkUrl, sha, cert,
                    manifest.optString("releaseNotes", ""),
                    release.optString("html_url", ""),
                    !sameFingerprint(installedCertificate, cert));
        }

        return newest;
    }

    /**
     * Explains a failed update check in Bengali with the underlying cause class,
     * so "no internet" complaints can be diagnosed from the dialog itself.
     */
    private static String describeNetworkFailure(Exception error) {
        if (error == null) return "আপডেট খোঁজা যায়নি।";
        String detail = error.getMessage();
        if (detail != null && detail.length() > 140) detail = detail.substring(0, 140) + "…";
        String suffix = detail == null || detail.trim().isEmpty() ? "" : "\n" + detail.trim();
        if (error instanceof java.net.UnknownHostException) {
            return "ইন্টারনেট ঠিকানা (DNS) পাওয়া যায়নি — ওয়াই-ফাই বা মোবাইল ডেটা পুনরায় পরীক্ষা করুন।" + suffix;
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "নিরাপদ সংযোগ (SSL) স্থাপন করা যায়নি।" + suffix;
        }
        if (error instanceof java.net.SocketTimeoutException) {
            return "সংযোগে সময় শেষ — নেটওয়ার্ক ধীর বা ব্যস্ত।" + suffix;
        }
        if (error instanceof java.net.ConnectException) {
            return "সার্ভারে সংযোগ করা যায়নি।" + suffix;
        }
        if (error instanceof java.net.SocketException || error instanceof java.io.IOException) {
            return "সংযোগ মাঝপথে বিচ্ছিন্ন হয়েছে।" + suffix;
        }
        return "আপডেট খোঁজা যায়নি।" + suffix;
    }

    static void downloadAndInstall(Activity activity, UpdateInfo update, InstallCallback callback) {
        appContext = activity.getApplicationContext();
        if (update.requiresMigration) {
            MAIN.post(() -> callback.onMigrationRequired(
                    "এই আপডেটটি বর্তমান ইনস্টল করা অ্যাপের সাইনিং সার্টিফিকেটে সই করা নয়। "
                    + "একবার নতুন সাইনিং কী-তে মাইগ্রেশন করতে হবে; পুরোনো অ্যাপ আনইনস্টল করলে "
                    + "স্থানীয় প্রিয় গান ও বুকমার্ক মুছে যেতে পারে।"));
            return;
        }

        new Thread(() -> {
            File apk = null;
            try {
                postStatus(callback, "নতুন সংস্করণ ডাউনলোড হচ্ছে…");
                File directory = new File(activity.getFilesDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("আপডেট সংরক্ষণের জায়গা তৈরি করা যায়নি।");
                }
                apk = new File(directory, UpdateApkProvider.UPDATE_APK_FILE);
                downloadApk(update.apkUrl, apk);

                postStatus(callback, "ডাউনলোড যাচাই করা হচ্ছে…");
                if (!sameFingerprint(sha256(apk), update.sha256)) {
                    apk.delete();
                    throw new IllegalStateException("ডাউনলোড করা APK-এর SHA-256 মেলেনি।");
                }
                PackageInfo archive = archivePackageInfo(activity, apk);
                if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
                    apk.delete();
                    throw new IllegalStateException("এই APK-টি এশকে ছাদেক অ্যাপের নয়।");
                }
                if (versionCode(archive) != update.versionCode) {
                    apk.delete();
                    throw new IllegalStateException("APK সংস্করণ নম্বরের সঙ্গে আপডেটের তথ্য মেলেনি।");
                }

                String archiveCertificate = certificateSha256(archive);
                String installed = installedCertificate(activity);
                if (!sameFingerprint(archiveCertificate, update.certificateSha256)
                        || !sameFingerprint(archiveCertificate, installed)) {
                    apk.delete();
                    postMigration(callback, "এই APK-এর সাইনিং সার্টিফিকেট বর্তমান অ্যাপের সঙ্গে মেলেনি। "
                            + "নিরাপদ in-place update করা যাবে না।");
                    return;
                }

                if (Build.VERSION.SDK_INT >= 26
                        && !activity.getPackageManager().canRequestPackageInstalls()) {
                    savePending(activity, apk, update);
                    MAIN.post(() -> {
                        callback.onPermissionRequired();
                        try {
                            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(settings);
                        } catch (Exception error) {
                            callback.onError("Android-এর অ্যাপ ইনস্টল অনুমতি সেটিংস খোলা যায়নি।");
                        }
                    });
                    return;
                }
                installVerifiedApk(activity, apk, callback);
            } catch (Exception error) {
                if (apk != null && apk.exists()) apk.delete();
                String message = error.getMessage();
                postError(callback, message == null
                        ? "আপডেট ইনস্টল করার প্রস্তুতি ব্যর্থ হয়েছে।" : message);
            }
        }, "ashke-update-download").start();
    }

    static void resumePendingInstall(Activity activity) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) return;
        SharedPreferences state = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String path = state.getString(PENDING_PATH, "");
        String sha = state.getString(PENDING_SHA, "");
        String cert = state.getString(PENDING_CERT, "");
        long version = state.getLong(PENDING_VERSION, -1L);
        if (path == null || path.isEmpty()) return;

        File apk = new File(path);
        if (!apk.isFile() || !isSha256(sha) || !isSha256(cert)) {
            clearPending(state);
            return;
        }

        appContext = activity.getApplicationContext();
        new Thread(() -> {
            try {
                if (version > 0 && installedVersionCode(activity) >= version) {
                    // The pending update already landed (the installer confirm
                    // completed while the app was dead): discard the leftover
                    // file and record instead of reinstalling the same build.
                    apk.delete();
                    clearPending(state);
                    return;
                }
                if (!sameFingerprint(sha256(apk), sha)) {
                    apk.delete();
                    clearPending(state);
                    return;
                }
                PackageInfo archive = archivePackageInfo(activity, apk);
                if (archive == null || !activity.getPackageName().equals(archive.packageName)
                        || versionCode(archive) != version
                        || !sameFingerprint(certificateSha256(archive), cert)
                        || !sameFingerprint(installedCertificate(activity), cert)) {
                    apk.delete();
                    clearPending(state);
                    return;
                }
                installVerifiedApk(activity, apk, new InstallCallback() {
                    @Override public void onStatus(String message) { }
                    @Override public void onPermissionRequired() { }
                    @Override public void onMigrationRequired(String message) { }
                    @Override public void onError(String message) {
                        MAIN.post(() -> android.widget.Toast.makeText(activity, message,
                                android.widget.Toast.LENGTH_LONG).show());
                    }
                });
            } catch (Exception error) {
                apk.delete();
                clearPending(state);
                MAIN.post(() -> android.widget.Toast.makeText(activity,
                        "অপেক্ষমাণ আপডেটটি যাচাই করা যায়নি। আবার আপডেট খুঁজুন।",
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }, "ashke-update-resume").start();
    }

    private static void savePending(Context context, File apk, UpdateInfo update) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_PATH, apk.getAbsolutePath())
                .putString(PENDING_SHA, update.sha256)
                .putString(PENDING_CERT, update.certificateSha256)
                .putLong(PENDING_VERSION, update.versionCode)
                .apply();
    }

    private static void clearPending(SharedPreferences state) {
        state.edit().remove(PENDING_PATH).remove(PENDING_SHA)
                .remove(PENDING_CERT).remove(PENDING_VERSION).apply();
    }

    private static void installVerifiedApk(Activity activity, File apk, InstallCallback callback)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 26) installViaSession(activity, apk, callback);
        else installViaInstallerIntent(activity, apk, callback);
    }

    /**
     * Android 8+ session flow: the app streams the verified APK into a
     * PackageInstaller session and commits it; the platform then asks the
     * user to confirm (gated by the "install unknown apps" permission).
     */
    private static void installViaSession(Context context, File apk, InstallCallback callback)
            throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = installer.openSession(sessionId);
            try (InputStream input = new BufferedInputStream(new FileInputStream(apk));
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent statusIntent = new Intent(context, UpdateInstallReceiver.class)
                    .setAction(context.getPackageName() + ".UPDATE_INSTALL_STATUS");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent status = PendingIntent.getBroadcast(context, sessionId,
                    statusIntent, flags);
            session.commit(status.getIntentSender());
            committed = true;
            clearPending(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
            apk.delete(); // PackageInstaller has copied the verified APK into its session.
            postStatus(callback, "Android-এর ইনস্টল নিশ্চিতকরণ খুলছে…");
        } finally {
            if (session != null) session.close();
            if (!committed) installer.abandonSession(sessionId);
        }
    }

    /**
     * Android 5-7 fallback: MIUI rejects the app's own programmatic
     * PackageInstaller commits with "Permission Denied" (observed on a
     * Redmi Note 4 - MIUILOG Install Reject uid 10459, PackageInstaller
     * Commit Permission Denied). Hand the verified APK to the platform
     * installer UI instead, so the user confirms with the same dialog any
     * file manager's APK install shows. API 24/25 must pass a content
     * URI (a file:// URI trips StrictMode on apps targeting N and later);
     * API 21-23 accept the file path directly.
     */
    private static void installViaInstallerIntent(Activity activity, File apk,
            InstallCallback callback) {
        // The installer reads the APK while its dialog is up, so the file
        // and the pending record survive until the install lands or the
        // user cancels (resumePendingInstall re-offers it on next launch).
        MAIN.post(() -> {
            try {
                Uri apkUri = Build.VERSION.SDK_INT >= 24
                        ? UpdateApkProvider.uriFor(activity, apk)
                        : Uri.fromFile(apk);
                Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(apkUri, UpdateApkProvider.APK_MIME)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.i(TAG, "Opening the platform installer on API "
                        + Build.VERSION.SDK_INT);
                activity.startActivityForResult(install, REQUEST_INSTALL_APK);
                postStatus(callback, "Android-এর ইনস্টল নিশ্চিতকরণ খুলছে…");
            } catch (Exception error) {
                Log.i(TAG, "Platform installer could not open: " + error);
                postError(callback, "Android-এর ইনস্টলার খোলা যায়নি। আবার আপডেট খুঁজুন।");
            }
        });
    }

    /** Result of the Android 5-7 installer confirm; OK means the update landed. */
    static void onInstallResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_INSTALL_APK || appContext == null) return;
        Log.i(TAG, "Installer confirm returned " + resultCode);
        // Final for this attempt either way: resumePendingInstall fires on
        // every onResume, so keeping the record on a cancel would re-open
        // the installer dialog the instant the user returns to the app.
        // A cancelled update can be fetched again from the update check.
        UpdateApkProvider.pendingApkFile(appContext).delete();
        clearPending(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    private static PackageInfo archivePackageInfo(Context context, File apk) {
        return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), packageInfoFlags());
    }

    private static int packageInfoFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static long installedVersionCode(Context context) throws Exception {
        return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String installedCertificate(Context context) throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), packageInfoFlags());
        return certificateSha256(installed);
    }

    private static String certificateSha256(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length != 1) {
            throw new IllegalStateException("অ্যাপের সাইনিং সার্টিফিকেট যাচাই করা যায়নি।");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(signatures[0].toByteArray()));
    }

    /**
     * System trust anchors plus the bundled modern roots: Android 5-7 devices
     * shipped with trust stores too old for GitHub's current certificate
     * chains, which failed every update check with "trust anchor not found".
     * The bundled files add widely-used public roots only; hostname
     * verification remains the platform default.
     */
    private static synchronized SSLSocketFactory trustedSslFactory() throws Exception {
        if (updaterSslFactory != null) return updaterSslFactory;
        TrustManagerFactory system = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore) null);
        KeyStore merged = KeyStore.getInstance(KeyStore.getDefaultType());
        merged.load(null, null);
        int index = 0;
        for (TrustManager manager : system.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                for (X509Certificate anchor : ((X509TrustManager) manager).getAcceptedIssuers()) {
                    merged.setCertificateEntry("system-" + index++, anchor);
                }
            }
        }
        CertificateFactory parser = CertificateFactory.getInstance("X.509");
        if (appContext != null) {
            for (String assetName : appContext.getAssets().list("certs")) {
                try (InputStream in = appContext.getAssets().open("certs/" + assetName)) {
                    int copy = 0;
                    for (Certificate cert : parser.generateCertificates(in)) {
                        merged.setCertificateEntry(assetName + "-" + copy++, cert);
                    }
                }
            }
        }
        TrustManagerFactory mergedFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        mergedFactory.init(merged);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, mergedFactory.getTrustManagers(), null);
        updaterSslFactory = ssl.getSocketFactory();
        return updaterSslFactory;
    }

    private static void downloadApk(String url, File destination) throws Exception {
        if (!url.startsWith(RELEASE_ASSET_PREFIX)) {
            throw new IllegalStateException("অনুমোদিত GitHub release URL ছাড়া APK ডাউনলোড করা যাবে না।");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AshkeShadek-Android-Updater/1.0");
        if (connection instanceof HttpsURLConnection && appContext != null) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(trustedSslFactory());
        }
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IllegalStateException("GitHub থেকে APK পাওয়া যায়নি (HTTP " + response + ")।");
            }
            // getContentLength() is available from Android 1.1; the 250 MiB
            // cap safely fits its signed 32-bit return value on minSdk 21.
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_APK_BYTES) {
                throw new IllegalStateException("APK ফাইলটি অনুমোদিত আকারের চেয়ে বড়।");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK_BYTES) throw new IllegalStateException("APK ফাইলটি অনুমোদিত আকারের চেয়ে বড়।");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(String url, int maxBytes) throws Exception {
        if (!url.startsWith(RELEASE_ASSET_PREFIX) && !url.equals(RELEASES_API)
                && !url.equals(MIRROR_MANIFEST_URL)) {
            throw new IllegalStateException("অনুমোদিত আপডেট ঠিকানা নয়।");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "AshkeShadek-Android-Updater/1.0");
        if (connection instanceof HttpsURLConnection && appContext != null) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(trustedSslFactory());
        }
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                String remaining = connection.getHeaderField("X-RateLimit-Remaining");
                if (response == 403 && "0".equals(remaining)) {
                    throw new IllegalStateException(
                            "GitHub অনুরোধ-সীমা শেষ (HTTP 403) — ভাগ করা ইন্টারনেটে সাধারণ; কিছুক্ষণ পর আবার চেষ্টা করুন।");
                }
                throw new IllegalStateException("আপডেট সার্ভার সাড়া দেয়নি (HTTP " + response + ")।");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) throw new IllegalStateException("আপডেটের তথ্য অস্বাভাবিক বড়।");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static boolean sameFingerprint(String first, String second) {
        return normalizeFingerprint(first).equals(normalizeFingerprint(second))
                && !normalizeFingerprint(first).isEmpty();
    }

    private static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replace(":", "").replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void postStatus(InstallCallback callback, String message) {
        MAIN.post(() -> callback.onStatus(message));
    }

    private static void postError(InstallCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }

    private static void postMigration(InstallCallback callback, String message) {
        MAIN.post(() -> callback.onMigrationRequired(message));
    }
}

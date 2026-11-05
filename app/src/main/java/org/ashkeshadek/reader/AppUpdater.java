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

final class AppUpdater {
    private static final String RELEASES_API =
            "https://api.github.com/repos/aamiqram/Ashke-Sadek---Android-project/releases?per_page=10";
    private static final String RELEASE_ASSET_PREFIX =
            "https://github.com/aamiqram/Ashke-Sadek---Android-project/releases/download/";
    private static final String PREFS = "ashke_update_state";
    private static final String PENDING_PATH = "pending_path";
    private static final String PENDING_SHA = "pending_sha";
    private static final String PENDING_CERT = "pending_cert";
    private static final String PENDING_VERSION = "pending_version";
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
        new Thread(() -> {
            try {
                long installedVersion = installedVersionCode(app);
                String installedCertificate = installedCertificate(app);
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

                UpdateInfo result = newest;
                MAIN.post(() -> {
                    if (result == null) callback.onNoUpdate();
                    else callback.onUpdateAvailable(result);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                MAIN.post(() -> callback.onError(message == null
                        ? "এখন আপডেট খোঁজা যাচ্ছে না। পরে আবার চেষ্টা করুন।" : message));
            }
        }, "ashke-update-check").start();
    }

    static void downloadAndInstall(Activity activity, UpdateInfo update, InstallCallback callback) {
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
                apk = new File(directory, "ashke-shadek-update.apk");
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

        new Thread(() -> {
            try {
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

    private static void installVerifiedApk(Context context, File apk, InstallCallback callback)
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

    private static void downloadApk(String url, File destination) throws Exception {
        if (!url.startsWith(RELEASE_ASSET_PREFIX)) {
            throw new IllegalStateException("অনুমোদিত GitHub release URL ছাড়া APK ডাউনলোড করা যাবে না।");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AshkeShadek-Android-Updater/1.0");
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IllegalStateException("GitHub থেকে APK পাওয়া যায়নি (HTTP " + response + ")।");
            }
            if (connection.getContentLengthLong() > MAX_APK_BYTES) {
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
        if (!url.startsWith(RELEASE_ASSET_PREFIX) && !url.equals(RELEASES_API)) {
            throw new IllegalStateException("অনুমোদিত আপডেট ঠিকানা নয়।");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "AshkeShadek-Android-Updater/1.0");
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
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

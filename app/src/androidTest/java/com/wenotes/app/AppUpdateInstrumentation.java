package com.wenotes.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Map;

import org.json.JSONObject;

/** Exercises Android's archive certificate parsing and the actual non-exported file provider. */
public final class AppUpdateInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int count;

    @Override public void onCreate(Bundle args) { arguments = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (arguments != null && "true".equals(arguments.getString("quoteShare"))) {
                result.putString("stream", "\nPassed " + QuoteShareChecks.run(this) + " quote share checks.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (arguments != null && "true".equals(arguments.getString("readingCards"))) {
                result.putString("stream", "\nPassed " + ReadingCardsChecks.run(this) + " reading card checks.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (arguments != null && "true".equals(arguments.getString("quoteStack"))) {
                result.putString("stream", "\nPassed " + QuoteStackChecks.run(this) + " quote stack transition checks.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            Context context = getTargetContext();
            int signatureFlags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), signatureFlags);
            byte[] certificate = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners()[0].toByteArray() : installed.signatures[0].toByteArray();
            String signer = ApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(certificate));
            File current = new File(context.getApplicationInfo().sourceDir);
            ApkUpdateSpec currentSpec = spec(current, installed, signer);
            test("installedCertificateMatches", () -> require(ApkPackageVerifier.signerMatches(context, currentSpec)));
            test("differentCertificateIsRejected", () -> require(!ApkPackageVerifier.signerMatches(context,
                    new ApkUpdateSpec(currentSpec.versionCode, currentSpec.versionName, currentSpec.url,
                            currentSpec.size, currentSpec.sha256, otherSigner()))));
            test("sameVersionCannotBeInstalledAsUpgrade", () -> expect("package", () -> ApkPackageVerifier.verify(context, current, currentSpec)));
            File outside = new File(context.getFilesDir(), "outside-update.apk");
            test("fileProviderRejectsOutsideDirectory", () -> {
                try { FileProvider.getUriForFile(context, context.getPackageName() + ".updates", outside); }
                catch (IllegalArgumentException expected) { return; }
                throw new AssertionError("File provider exposed an unrelated private file");
            });
            if (arguments != null && "true".equals(arguments.getString("upgradeFixture"))) {
                File fixture = new File(new File(context.getFilesDir(), "app-updates"), "test-upgrade.apk");
                require(fixture.getParentFile().isDirectory() || fixture.getParentFile().mkdirs());
                try {
                    try (InputStream input = getContext().getAssets().open("upgrade-fixture.apk"); FileOutputStream output = new FileOutputStream(fixture)) {
                        byte[] buffer = new byte[32768]; int length;
                        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                    }
                    PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(fixture.getAbsolutePath(), signatureFlags);
                    require(archive != null);
                    ApkUpdateSpec upgrade = spec(fixture, archive, signer);
                    test("signedHigherVersionPassesAndroidArchiveChecks", () -> ApkPackageVerifier.verify(context, fixture, upgrade));
                    test("wrongSignerFailsEvenForValidHigherVersion", () -> expect("signature", () -> ApkPackageVerifier.verify(context, fixture,
                            new ApkUpdateSpec(upgrade.versionCode, upgrade.versionName, upgrade.url, upgrade.size, upgrade.sha256, otherSigner()))));
                    test("verifiedApkCanBeSharedByReadOnlyContentUri", () -> {
                        require(FileProvider.getUriForFile(context, context.getPackageName() + ".updates", fixture).toString().startsWith("content://"));
                    });
                    if ("true".equals(arguments.getString("updateUi"))) {
                        test("updateActionOpensSystemInstallFlow", () -> exerciseUpdateUi(context, fixture, upgrade));
                    }
                } finally { fixture.delete(); }
            }
            result.putString("stream", "\nPassed " + count + " Android update checks.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nUpdate check failed: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void test(String name, Check check) throws Exception {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName()); status.putString("test", name);
        status.putInt("current", ++count); status.putString("id", "InstrumentationTestRunner");
        sendStatus(1, status);
        try { check.run(); sendStatus(0, status); }
        catch (Throwable failure) { status.putString("stack", failure.toString()); sendStatus(-2, status); throw failure; }
    }

    private static ApkUpdateSpec spec(File file, PackageInfo info, String signer) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        int code = (int) (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        return new ApkUpdateSpec(code, info.versionName, UpdateConfiguration.RELEASES_URL + "/download/v" + info.versionName + "/app.apk",
                file.length(), ApkDownloader.hex(digest.digest()), signer);
    }

    private static void expect(String kind, Check check) throws Exception {
        try { check.run(); }
        catch (UpdateException expected) { require(kind.equals(expected.kind)); return; }
        throw new AssertionError("Expected rejection: " + kind);
    }

    private void exerciseUpdateUi(Context context, File fixture, ApkUpdateSpec upgrade) throws Exception {
        SharedPreferences metadata = context.getSharedPreferences(ApiUpdateManager.PREFS, Context.MODE_PRIVATE);
        SharedPreferences downloads = AppUpdateManager.prefs(context);
        Map<String, ?> originalMetadata = metadata.getAll();
        Map<String, ?> originalDownloads = downloads.getAll();
        File readyFile = AppUpdateManager.file(context, upgrade);
        boolean existed = readyFile.exists();
        Activity[] screen = new Activity[1];
        try {
            try (InputStream input = new FileInputStream(fixture); FileOutputStream output = new FileOutputStream(readyFile)) {
                byte[] buffer = new byte[32768]; int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            }
            String bundle;
            try (InputStream input = context.getAssets().open("compatibility.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                bundle = output.toString("UTF-8");
            }
            JSONObject config = new JSONObject(bundle);
            config.put("revision", 999999).put("app", upgrade.toJson().put("updateUrl", UpdateConfiguration.RELEASES_URL)
                    .put("releaseNotes", "本地更新流程测试"));
            metadata.edit().putString("configuration", config.toString()).putLong("last_attempt", System.currentTimeMillis())
                    .putBoolean("app_check_ok", true).commit();
            downloads.edit().putString("spec", upgrade.toJson().toString()).putString("status", "ready").commit();
            screen[0] = startActivitySync(new Intent(context, AppUpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> require(screen[0].findViewById(R.id.app_update_action).performClick()));
            boolean allowed = context.getPackageManager().canRequestPackageInstalls();
            boolean found = false;
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (!found && SystemClock.uptimeMillis() < deadline) {
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    if (allowed) {
                        String owner = String.valueOf(root.getPackageName());
                        found = owner.contains("packageinstaller") || owner.contains("permissioncontroller");
                    } else found = !root.findAccessibilityNodeInfosByText(context.getString(R.string.app_update_permission_title)).isEmpty();
                }
                if (!found) SystemClock.sleep(200);
            }
            require(found);
            getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true);
            getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true);
        } finally {
            if (screen[0] != null) runOnMainSync(() -> screen[0].finish());
            restore(metadata, originalMetadata);
            restore(downloads, originalDownloads);
            if (!existed) readyFile.delete();
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
        }
        editor.commit();
    }

    private static void require(boolean condition) { if (!condition) throw new AssertionError("Condition failed"); }
    private static String otherSigner() { return new String(new char[64]).replace('\0', 'a'); }
    private interface Check { void run() throws Exception; }
}

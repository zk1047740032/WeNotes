package com.wenotes.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Rechecks the private file before sharing it with the system installer. */
final class ApkPackageVerifier {
    private ApkPackageVerifier() { }

    static boolean signerMatches(Context context, ApkUpdateSpec spec) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), flags());
            return signer(installed).equals(spec.signerSha256);
        } catch (Exception ignored) { return false; }
    }

    static void verify(Context context, File file, ApkUpdateSpec spec) throws Exception {
        if (!file.isFile() || file.length() != spec.size) throw new UpdateException("integrity");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        if (!ApkDownloader.hex(digest.digest()).equals(spec.sha256)) throw new UpdateException("integrity");
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags());
        if (archive == null || !context.getPackageName().equals(archive.packageName)
                || versionCode(archive) != spec.versionCode || !spec.versionName.equals(archive.versionName)
                || spec.versionCode <= BuildConfig.VERSION_CODE) throw new UpdateException("package");
        if (!signerMatches(context, spec) || !signer(archive).equals(spec.signerSha256)) {
            throw new UpdateException("signature");
        }
    }

    @SuppressWarnings("deprecation")
    private static int flags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static String signer(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length != 1) throw new UpdateException("signature");
        return ApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
}

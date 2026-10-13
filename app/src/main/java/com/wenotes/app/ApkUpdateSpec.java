package com.wenotes.app;

import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Public, bounded release metadata. Old feeds may omit the APK entirely. */
final class ApkUpdateSpec {
    static final long MAX_SIZE = 150L * 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    final int versionCode;
    final String versionName;
    final String url;
    final long size;
    final String sha256;
    final String signerSha256;

    ApkUpdateSpec(int versionCode, String versionName, String url, long size, String sha256, String signerSha256) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.url = url;
        this.size = size;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
        this.signerSha256 = signerSha256.toLowerCase(Locale.ROOT);
    }

    static ApkUpdateSpec parse(JSONObject app) throws Exception {
        JSONObject apk = app.getJSONObject("apk");
        int code = app.getInt("versionCode");
        String name = app.getString("versionName");
        String url = apk.getString("url");
        long size = apk.getLong("size");
        String hash = apk.getString("sha256");
        String signer = apk.getString("signerSha256");
        if (code < 1 || !UpdateConfiguration.isVersion(name) || !isReleaseUrl(url)
                || size < 1024 || size > MAX_SIZE || !HASH.matcher(hash).matches()
                || !HASH.matcher(signer).matches()) throw new IllegalArgumentException("Invalid APK metadata");
        return new ApkUpdateSpec(code, name, url, size, hash, signer);
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("versionCode", versionCode).put("versionName", versionName)
                .put("apk", new JSONObject().put("url", url).put("size", size)
                        .put("sha256", sha256).put("signerSha256", signerSha256));
    }

    static boolean isReleaseUrl(String address) {
        try {
            URI uri = new URI(address);
            return isHttps(uri) && "github.com".equals(uri.getHost())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().matches("/zk1047740032/WeNotes/releases/download/"
                    + "v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}/[A-Za-z0-9._-]+\\.apk");
        } catch (Exception ignored) { return false; }
    }

    static boolean isDownloadAddress(URI uri) {
        if (!isHttps(uri) || uri.getRawFragment() != null) return false;
        String host = uri.getHost();
        return ("github.com".equals(host) && isReleaseUrl(uri.toString()))
                || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host)
                || "github-releases.githubusercontent.com".equals(host);
    }

    private static boolean isHttps(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getPort() == -1;
    }
}

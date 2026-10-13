package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class ApkUpdateTest {
    private static final String APK_URL = UpdateConfiguration.RELEASES_URL + "/download/v1.2.1/WeNotes-1.2.1.apk";
    private static final String SIGNER = "a".repeat(64);

    private static JSONObject app(byte[] bytes) throws Exception {
        return new JSONObject().put("versionCode", BuildConfig.VERSION_CODE + 1).put("versionName", "1.2.1")
                .put("updateUrl", UpdateConfiguration.RELEASES_URL).put("releaseNotes", "更新说明")
                .put("apk", new JSONObject().put("url", APK_URL).put("size", bytes.length)
                        .put("sha256", ApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .put("signerSha256", SIGNER));
    }

    private static JSONObject feed(JSONObject app) throws Exception {
        return new JSONObject().put("schema", 1).put("revision", 2).put("app", app)
                .put("profiles", new JSONArray().put(new JSONObject().put("skillVersion", "1.0.5")
                        .put("adapter", UpdateConfiguration.ADAPTER).put("minAppVersionCode", 2).put("pageSize", 100)));
    }

    @Test public void extendedFeedAndOldFeedBothRemainReadable() throws Exception {
        JSONObject app = app(new byte[4096]);
        UpdateConfiguration update = UpdateConfiguration.parse(feed(app).toString());
        assertEquals("更新说明", update.releaseNotes);
        assertEquals(4096, update.apk.size);
        assertEquals(update.apk.sha256, ApkUpdateSpec.parse(update.apk.toJson()).sha256);
        app.remove("apk");
        assertNull(UpdateConfiguration.parse(feed(app).toString()).apk);
    }

    @Test public void rejectsMalformedApkAndUnboundedReleaseNotes() throws Exception {
        for (String field : new String[]{"url", "size", "sha256", "signerSha256"}) {
            JSONObject app = app(new byte[4096]);
            app.getJSONObject("apk").remove(field);
            assertThrows(Exception.class, () -> UpdateConfiguration.parse(feed(app).toString()));
        }
        for (long size : new long[]{0, -1, 1023, ApkUpdateSpec.MAX_SIZE + 1}) {
            JSONObject app = app(new byte[4096]);
            app.getJSONObject("apk").put("size", size);
            assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(feed(app).toString()));
        }
        JSONObject app = app(new byte[4096]).put("releaseNotes", "x".repeat(4001));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(feed(app).toString()));
    }

    @Test public void acceptsOnlyPublisherApksAndHttpsAssetRedirects() {
        assertTrue(ApkUpdateSpec.isReleaseUrl(APK_URL));
        for (String address : new String[]{"http" + APK_URL.substring(5), APK_URL + "?token=abc",
                APK_URL + "#x", APK_URL.replace("WeNotes/releases", "Other/releases"),
                APK_URL.replace("WeNotes-1.2.1.apk", "../../app.apk"),
                APK_URL.replace("WeNotes-1.2.1.apk", "%2e%2e.apk"),
                "https://github.com@evil.example/zk1047740032/WeNotes/releases/download/v1.2.1/a.apk"}) {
            assertFalse(address, ApkUpdateSpec.isReleaseUrl(address));
        }
        assertTrue(ApkUpdateSpec.isDownloadAddress(URI.create("https://release-assets.githubusercontent.com/a?sig=xyz")));
        assertFalse(ApkUpdateSpec.isDownloadAddress(URI.create("http://release-assets.githubusercontent.com/a")));
        assertFalse(ApkUpdateSpec.isDownloadAddress(URI.create("https://release-assets.githubusercontent.com.evil.example/a")));
        assertFalse(ApkUpdateSpec.isDownloadAddress(URI.create("https://evil.example/a")));
    }

    @Test public void exactContentIsRequiredAndProgressReachesCompletion() throws Exception {
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 42);
        ApkUpdateSpec spec = ApkUpdateSpec.parse(app(bytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger progress = new AtomicInteger();
        ApkDownloader.copyVerified(new ByteArrayInputStream(bytes), output, spec, progress::set, () -> false);
        assertArrayEquals(bytes, output.toByteArray());
        assertEquals(100, progress.get());
        byte[] modified = bytes.clone();
        modified[0] ^= 1;
        for (byte[] bad : new byte[][]{Arrays.copyOf(bytes, 1024), Arrays.copyOf(bytes, 4097), modified}) {
            UpdateException error = assertThrows(UpdateException.class, () -> ApkDownloader.copyVerified(
                    new ByteArrayInputStream(bad), new ByteArrayOutputStream(), spec, value -> {}, () -> false));
            assertEquals("integrity", error.kind);
        }
        ByteArrayOutputStream cancelled = new ByteArrayOutputStream();
        UpdateException error = assertThrows(UpdateException.class, () -> ApkDownloader.copyVerified(
                new ByteArrayInputStream(bytes), cancelled, spec, value -> {}, () -> true));
        assertEquals("cancelled", error.kind);
        assertEquals(0, cancelled.size());
    }

    @Test public void officialInterfaceOutageDoesNotHideSuccessfulApplicationCheck() throws Exception {
        String json = feed(app(new byte[4096])).toString();
        UpdateConfiguration.Profile profile = UpdateConfiguration.parse(json).choose("1.0.5", BuildConfig.VERSION_CODE, null);
        UpdateConfiguration.Refresh result = UpdateConfiguration.refresh(json, profile, "1.0.5", BuildConfig.VERSION_CODE,
                new UpdateConfiguration.Source() {
                    public String configuration() { return json; }
                    public String officialSkill() throws Exception { throw new java.io.IOException("offline"); }
                });
        assertTrue(result.appChecked);
        assertFalse(result.checked);
        assertNotNull(UpdateConfiguration.parse(result.json).apk);
    }

    @Test public void localWriteFailureIsReportedAsStorageRatherThanNetwork() throws Exception {
        byte[] bytes = new byte[4096];
        ApkUpdateSpec spec = ApkUpdateSpec.parse(app(bytes));
        UpdateException error = assertThrows(UpdateException.class, () -> ApkDownloader.copyVerified(
                new ByteArrayInputStream(bytes), new java.io.OutputStream() {
                    @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("disk full"); }
                }, spec, value -> {}, () -> false));
        assertEquals("storage", error.kind);
    }
}

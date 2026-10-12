package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ApiUpdateStatusTest {
    @Test
    public void newerApkIsOfferedWithoutAdoptingItsUnsupportedAdapter() throws Exception {
        JSONObject root = new JSONObject().put("schema", 1).put("revision", 2)
                .put("app", new JSONObject().put("versionCode", BuildConfig.VERSION_CODE + 1)
                        .put("versionName", "1.2.0").put("updateUrl", UpdateConfiguration.RELEASES_URL))
                .put("profiles", new JSONArray().put(new JSONObject().put("skillVersion", "1.0.6")
                        .put("adapter", "weread-library-v2").put("minAppVersionCode", BuildConfig.VERSION_CODE + 1)
                        .put("pageSize", 100)));
        UpdateConfiguration configuration = UpdateConfiguration.parse(root.toString());
        UpdateConfiguration.Profile previous = new UpdateConfiguration.Profile("1.0.5", UpdateConfiguration.ADAPTER,
                BuildConfig.VERSION_CODE, 100);
        UpdateConfiguration.Profile selected = configuration.choose("1.0.6", BuildConfig.VERSION_CODE, previous);
        ApiUpdateManager.Snapshot status = new ApiUpdateManager.Snapshot(root.toString(), configuration,
                selected, "1.0.6", true, true);
        assertTrue(status.appUpdateAvailable());
        assertTrue(status.compatibilityPending());
        assertEquals("1.0.5", status.profile.version);
        assertEquals(UpdateConfiguration.RELEASES_URL, status.configuration.updateUrl);
    }

    @Test
    public void unreviewedInterfaceUpdateDoesNotClaimThereIsANewApk() throws Exception {
        JSONObject root = new JSONObject().put("schema", 1).put("revision", 1)
                .put("app", new JSONObject().put("versionCode", BuildConfig.VERSION_CODE)
                        .put("versionName", BuildConfig.VERSION_NAME).put("updateUrl", UpdateConfiguration.RELEASES_URL))
                .put("profiles", new JSONArray().put(new JSONObject().put("skillVersion", "1.0.5")
                        .put("adapter", UpdateConfiguration.ADAPTER).put("minAppVersionCode", BuildConfig.VERSION_CODE)
                        .put("pageSize", 100)));
        UpdateConfiguration configuration = UpdateConfiguration.parse(root.toString());
        UpdateConfiguration.Profile selected = configuration.choose("1.0.6", BuildConfig.VERSION_CODE, null);
        ApiUpdateManager.Snapshot status = new ApiUpdateManager.Snapshot(root.toString(), configuration,
                selected, "1.0.6", true, true);
        assertFalse(status.appUpdateAvailable());
        assertTrue(status.compatibilityPending());
        assertEquals("1.0.5", status.profile.version);
    }
}

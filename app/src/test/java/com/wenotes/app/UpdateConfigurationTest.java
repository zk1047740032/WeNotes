package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class UpdateConfigurationTest {
    private static JSONObject profile(String version, String adapter, int appCode, int pageSize) throws Exception {
        return new JSONObject().put("skillVersion", version).put("adapter", adapter)
                .put("minAppVersionCode", appCode).put("pageSize", pageSize);
    }

    private static JSONObject config(long revision, JSONObject... profiles) throws Exception {
        JSONArray entries = new JSONArray();
        for (JSONObject profile : profiles) entries.put(profile);
        return new JSONObject().put("schema", 1).put("revision", revision).put("profiles", entries)
                .put("app", new JSONObject().put("versionCode", 2).put("versionName", "1.1.0")
                        .put("updateUrl", UpdateConfiguration.RELEASES_URL));
    }

    private static UpdateConfiguration.Profile baseline() {
        return new UpdateConfiguration.Profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 100);
    }

    private static String document(String version) { return "---\nname: weread-skills\nversion: " + version + "\n---\n# Skill"; }

    @Test
    public void publishedBundleSupportsThisApk() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(System.getProperty("wenotes.updateConfig"))),
                StandardCharsets.UTF_8);
        UpdateConfiguration configuration = UpdateConfiguration.parse(json);
        UpdateConfiguration.Profile profile = configuration.choose("1.0.5", BuildConfig.VERSION_CODE, null);
        assertNotNull(profile);
        assertEquals("1.0.5", profile.version);
        assertEquals(BuildConfig.VERSION_CODE, configuration.appVersionCode);
        assertEquals(BuildConfig.VERSION_NAME, configuration.appVersionName);
    }

    @Test
    public void adoptsOnlyReviewedVersionsSupportedByCurrentAdapter() throws Exception {
        String json = config(2, profile("1.0.6", UpdateConfiguration.ADAPTER, 2, 20)).toString();
        UpdateConfiguration.Profile selected = UpdateConfiguration.parse(json).choose("1.0.6", 2, baseline());
        assertEquals("1.0.6", selected.version);
        assertEquals(20, selected.pageSize);
        assertEquals("1.0.5", UpdateConfiguration.parse(json).choose("1.0.5", 2, baseline()).version);
    }

    @Test
    public void unknownOfficialVersionAndUnsupportedProfilesKeepWorkingAdapter() throws Exception {
        String json = config(2, profile("1.0.6", "new-library-adapter", 2, 100),
                profile("1.0.7", UpdateConfiguration.ADAPTER, 3, 100)).toString();
        assertEquals("1.0.5", UpdateConfiguration.parse(json).choose("1.0.7", 2, baseline()).version);
        json = config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 100)).toString();
        assertEquals("1.0.5", UpdateConfiguration.parse(json).choose("9.0.0", 2, baseline()).version);
    }

    @Test
    public void unavailableMetadataKeepsCachedApprovedConfiguration() throws Exception {
        String cached = config(4, profile("1.0.6", UpdateConfiguration.ADAPTER, 2, 50)).toString();
        UpdateConfiguration.Profile active = UpdateConfiguration.parse(cached).choose("1.0.6", 2, baseline());
        UpdateConfiguration.Refresh result = UpdateConfiguration.refresh(cached, active, "1.0.6", 2,
                new UpdateConfiguration.Source() {
                    public String configuration() throws Exception { throw new java.io.IOException("offline"); }
                    public String officialSkill() throws Exception { throw new java.io.IOException("offline"); }
                });
        assertEquals(cached, result.json);
        assertEquals("1.0.6", result.profile.version);
        assertEquals(50, result.profile.pageSize);
        assertFalse(result.checked);
    }

    @Test
    public void malformedAndStaleFeedsNeverReplaceCachedConfiguration() throws Exception {
        String cached = config(4, profile("1.0.6", UpdateConfiguration.ADAPTER, 2, 50)).toString();
        UpdateConfiguration.Profile active = UpdateConfiguration.parse(cached).choose("1.0.6", 2, baseline());
        for (String candidate : new String[]{"not-json",
                config(3, profile("1.0.7", UpdateConfiguration.ADAPTER, 2, 100)).toString()}) {
            UpdateConfiguration.Refresh result = UpdateConfiguration.refresh(cached, active, "1.0.6", 2,
                    new UpdateConfiguration.Source() {
                        public String configuration() { return candidate; }
                        public String officialSkill() { return document("1.0.7"); }
                    });
            assertEquals(cached, result.json);
            assertEquals("1.0.6", result.profile.version);
            assertEquals("1.0.7", result.officialVersion);
        }
    }

    @Test
    public void officialMetadataAloneCannotApproveVersion() throws Exception {
        String cached = config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 100)).toString();
        UpdateConfiguration.Refresh result = UpdateConfiguration.refresh(cached, baseline(), "1.0.5", 2,
                new UpdateConfiguration.Source() {
                    public String configuration() { return cached; }
                    public String officialSkill() { return document("9.9.9"); }
                });
        assertEquals("9.9.9", result.officialVersion);
        assertEquals("1.0.5", result.profile.version);
        assertTrue(result.checked);
    }

    @Test
    public void validatesFeedAndLimits() throws Exception {
        JSONObject valid = config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 100));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(
                new JSONObject(valid.toString()).put("schema", 2).toString()));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(
                config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 0)).toString()));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(
                config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 101)).toString()));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.parse(
                config(1, profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 100),
                        profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 20)).toString()));
    }

    @Test
    public void updateLinksAreRestrictedToPublisherReleases() {
        assertTrue(UpdateConfiguration.isUpdateUrl(UpdateConfiguration.RELEASES_URL));
        assertTrue(UpdateConfiguration.isUpdateUrl(UpdateConfiguration.RELEASES_URL + "/download/v1.1.0/app.apk"));
        for (String invalid : new String[]{"http://github.com/zk1047740032/WeNotes/releases",
                "https://example.com/app.apk", "https://github.com/other/repo/releases",
                UpdateConfiguration.RELEASES_URL + "/../../other", UpdateConfiguration.RELEASES_URL + "/%2e%2e/other",
                "https://github.com@evil.example/zk1047740032/WeNotes/releases"}) {
            assertFalse(invalid, UpdateConfiguration.isUpdateUrl(invalid));
        }
    }

    @Test
    public void parsesOnlySkillFrontMatterAndComparesVersionsNumerically() {
        assertEquals("1.0.5", UpdateConfiguration.officialVersion(document("1.0.5").replace("\n", "\r\n")));
        assertThrows(IllegalArgumentException.class, () -> UpdateConfiguration.officialVersion("# Skill\nversion: 9.9.9"));
        assertTrue(UpdateConfiguration.compare("1.0.10", "1.0.9") > 0);
        assertFalse(UpdateConfiguration.isVersion("1.0.5;run"));
        assertFalse(UpdateConfiguration.isVersion("999999999.0.0"));
    }
}

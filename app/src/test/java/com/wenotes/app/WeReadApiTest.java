package com.wenotes.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class WeReadApiTest {
    private static final class Profiles implements WeReadApi.Profiles {
        UpdateConfiguration.Profile profile = new UpdateConfiguration.Profile("1.0.5", UpdateConfiguration.ADAPTER, 2, 20);
        String approvedUpdate;
        int refreshCount;
        public UpdateConfiguration.Profile current() { return profile; }
        public void refresh() {
            refreshCount++;
            if (approvedUpdate != null) {
                profile = new UpdateConfiguration.Profile(approvedUpdate, UpdateConfiguration.ADAPTER, 2, 20);
            }
        }
    }

    @Test
    public void requestsUseApprovedVersionAndPreserveFlatPagination() throws Exception {
        JSONObject parameters = new JSONObject().put("count", 100).put("lastSort", 123L)
                .put("skill_version", "9.9.9").put("api_name", "/wrong");
        JSONObject body = WeReadApi.request("/user/notebooks", parameters, new Profiles().current());
        assertEquals("1.0.5", body.getString("skill_version"));
        assertEquals("/user/notebooks", body.getString("api_name"));
        assertEquals(20, body.getInt("count"));
        assertEquals(123L, body.getLong("lastSort"));
        assertFalse(body.has("params"));
        assertEquals(100, parameters.getInt("count"));
    }

    @Test
    public void upgradeNotificationRefreshesAndRetriesOnlyAfterApprovedUpdate() throws Exception {
        Profiles profiles = new Profiles();
        profiles.approvedUpdate = "1.0.6";
        List<String> versions = new ArrayList<>();
        WeReadApi api = new WeReadApi(profiles, body -> {
            versions.add(body.getString("skill_version"));
            return new WeReadApi.Response(200, versions.size() == 1
                    ? new JSONObject().put("upgrade_info", new JSONObject().put("latest_version", "9.9.9"))
                    : new JSONObject().put("books", new org.json.JSONArray()));
        });
        assertTrue(api.call("/shelf/sync", new JSONObject()).has("books"));
        assertEquals(java.util.Arrays.asList("1.0.5", "1.0.6"), versions);
        assertEquals(1, profiles.refreshCount);
    }

    @Test
    public void unapprovedUpgradeStopsWithoutConsumingPartialData() throws Exception {
        Profiles profiles = new Profiles();
        AtomicInteger requests = new AtomicInteger();
        WeReadApi api = new WeReadApi(profiles, body -> {
            requests.incrementAndGet();
            return new WeReadApi.Response(200, new JSONObject().put("books", new org.json.JSONArray())
                    .put("upgrade_info", new JSONObject().put("message", "download SKILL.md"))
                    .put("latest_version", "9.9.9"));
        });
        Exception error = assertThrows(WeReadApi.UpgradeRequiredException.class,
                () -> api.call("/shelf/sync", new JSONObject()));
        assertEquals(1, requests.get());
        assertEquals(1, profiles.refreshCount);
        assertFalse(error.getMessage().contains("SKILL.md"));
        assertTrue(error.getMessage().contains("检查更新"));
    }

    @Test
    public void repeatedUpgradeNotificationCannotLoopOrBeIgnored() throws Exception {
        Profiles profiles = new Profiles();
        profiles.approvedUpdate = "1.0.6";
        AtomicInteger requests = new AtomicInteger();
        WeReadApi api = new WeReadApi(profiles, body -> {
            requests.incrementAndGet();
            return new WeReadApi.Response(200, new JSONObject().put("upgrade_info", new JSONObject()));
        });
        assertThrows(WeReadApi.UpgradeRequiredException.class, () -> api.call("/shelf/sync", new JSONObject()));
        assertEquals(2, requests.get());
        assertEquals(1, profiles.refreshCount);
    }

    @Test
    public void authenticationErrorsNeverTriggerCompatibilityRetry() {
        Profiles profiles = new Profiles();
        WeReadApi api = new WeReadApi(profiles, body -> new WeReadApi.Response(401,
                new JSONObject().put("errcode", 401).put("errmsg", "密钥无效")));
        Exception error = assertThrows(IllegalStateException.class, () -> api.call("/shelf/sync", new JSONObject()));
        assertTrue(error.getMessage().contains("401"));
        assertEquals(0, profiles.refreshCount);
    }

    @Test
    public void nullUpgradeFieldDoesNotBlockSuccessfulResponse() throws Exception {
        WeReadApi api = new WeReadApi(new Profiles(), body -> new WeReadApi.Response(200,
                new JSONObject().put("upgrade_info", JSONObject.NULL).put("books", new org.json.JSONArray())));
        assertTrue(api.call("/shelf/sync", new JSONObject()).has("books"));
    }

    @Test
    public void interruptedSyncDoesNotMakeRequest() {
        AtomicInteger requests = new AtomicInteger();
        WeReadApi api = new WeReadApi(new Profiles(), body -> {
            requests.incrementAndGet();
            return new WeReadApi.Response(200, new JSONObject());
        });
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> api.call("/shelf/sync", new JSONObject()));
            assertEquals(0, requests.get());
        } finally {
            Thread.interrupted();
        }
    }
}

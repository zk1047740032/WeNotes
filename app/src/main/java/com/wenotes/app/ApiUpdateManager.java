package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps public compatibility metadata separate from credentials and reading data. */
final class ApiUpdateManager implements WeReadApi.Profiles {
    static final String PREFS = "api_update_state";
    static final String CONFIG_URL =
            "https://raw.githubusercontent.com/zk1047740032/WeNotes/main/updates/compatibility.json";
    static final String OFFICIAL_URL =
            "https://raw.githubusercontent.com/Tencent/WeChatReading/main/skills/SKILL.md";
    private static final String CONFIG_API_URL =
            "https://api.github.com/repos/zk1047740032/WeNotes/contents/updates/compatibility.json?ref=main";
    private static final String OFFICIAL_API_URL =
            "https://api.github.com/repos/Tencent/WeChatReading/contents/skills/SKILL.md?ref=main";
    private static final long CHECK_INTERVAL = 6L * 60 * 60 * 1000;
    private static final Object CHECK_LOCK = new Object();
    private static final Object BUNDLE_LOCK = new Object();
    private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor();
    private static volatile String bundledJson;
    private final Context context;
    private final SharedPreferences prefs;

    ApiUpdateManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void checkAsync(Context context, boolean force, Runnable completed) {
        Context app = context.getApplicationContext();
        CHECKER.execute(() -> {
            try { new ApiUpdateManager(app).check(force); }
            finally {
                if (completed != null) new Handler(Looper.getMainLooper()).post(completed);
            }
        });
    }

    void check(boolean force) {
        synchronized (CHECK_LOCK) {
            long now = System.currentTimeMillis();
            long attempted = prefs.getLong("last_attempt", 0);
            if (!force && attempted > 0 && now >= attempted && now - attempted < CHECK_INTERVAL) return;
            Snapshot previous = snapshot();
            try {
                UpdateConfiguration.Refresh refreshed = UpdateConfiguration.refresh(previous.json,
                        previous.profile, previous.officialVersion, BuildConfig.VERSION_CODE,
                        new UpdateConfiguration.Source() {
                            @Override public String configuration() throws Exception {
                                return MetadataHttp.githubFile(CONFIG_URL, CONFIG_API_URL, 32 * 1024);
                            }
                            @Override public String officialSkill() throws Exception {
                                return MetadataHttp.githubFile(OFFICIAL_URL, OFFICIAL_API_URL, 64 * 1024);
                            }
                        });
                SharedPreferences.Editor editor = prefs.edit()
                        .putString("configuration", refreshed.json)
                        .putString("official_version", refreshed.officialVersion)
                        .putString("active_version", refreshed.profile.version)
                        .putInt("active_page_size", refreshed.profile.pageSize)
                        .putLong("last_attempt", now).putBoolean("last_check_ok", refreshed.checked);
                editor.putBoolean("app_check_ok", refreshed.appChecked);
                if (refreshed.checked) editor.putLong("last_successful_check", now);
                editor.apply();
            } catch (Exception error) {
                throw new IllegalStateException("Bundled compatibility configuration is invalid", error);
            }
        }
    }

    @Override public UpdateConfiguration.Profile current() { return snapshot().profile; }
    @Override public void refresh() { check(true); }

    Snapshot snapshot() {
        try {
            String baseJson = bundle();
            UpdateConfiguration base = UpdateConfiguration.parse(baseJson);
            UpdateConfiguration.Profile fallback = base.choose("", BuildConfig.VERSION_CODE, null);
            if (fallback == null) throw new IllegalStateException("No bundled adapter for this APK");
            Map<String, ?> state = prefs.getAll();
            String json = string(state, "configuration", baseJson);
            UpdateConfiguration configuration;
            try {
                configuration = UpdateConfiguration.parse(json);
                if (configuration.revision < base.revision) throw new IllegalArgumentException("Stale configuration");
            } catch (Exception ignored) {
                json = baseJson;
                configuration = base;
            }
            String active = string(state, "active_version", "");
            Object pageValue = state.get("active_page_size");
            int pageSize = pageValue instanceof Integer ? (Integer) pageValue : 0;
            if (UpdateConfiguration.isVersion(active) && pageSize >= 1 && pageSize <= 100
                    && UpdateConfiguration.compare(active, fallback.version) >= 0) {
                fallback = new UpdateConfiguration.Profile(active, UpdateConfiguration.ADAPTER, 1, pageSize);
            }
            String official = string(state, "official_version", "");
            if (!UpdateConfiguration.isVersion(official)) official = "";
            UpdateConfiguration.Profile profile = configuration.choose(official, BuildConfig.VERSION_CODE, fallback);
            return new Snapshot(json, configuration, profile, official,
                    Boolean.TRUE.equals(state.get("last_check_ok")), state.containsKey("last_attempt"));
        } catch (Exception error) {
            throw new IllegalStateException("Bundled compatibility configuration is invalid", error);
        }
    }

    private String bundle() throws Exception {
        if (bundledJson == null) {
            synchronized (BUNDLE_LOCK) {
                if (bundledJson == null) {
                    try (InputStream input = context.getAssets().open("compatibility.json");
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int length;
                        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                        bundledJson = output.toString(StandardCharsets.UTF_8.name());
                    }
                }
            }
        }
        return bundledJson;
    }

    private static String string(Map<String, ?> state, String key, String fallback) {
        Object value = state.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    static String readableSyncStatus(String status) {
        if (status.contains("接口要求更新") || status.contains("SKILL.md") || status.contains("upgrade_url")) {
            return "此前同步因接口更新中断。请在“我的”检查更新后重新同步，已有笔记仍可查看。";
        }
        return status;
    }

    static final class Snapshot {
        final String json;
        final UpdateConfiguration configuration;
        final UpdateConfiguration.Profile profile;
        final String officialVersion;
        final boolean checked;
        final boolean attempted;

        Snapshot(String json, UpdateConfiguration configuration, UpdateConfiguration.Profile profile,
                 String officialVersion, boolean checked, boolean attempted) {
            this.json = json;
            this.configuration = configuration;
            this.profile = profile;
            this.officialVersion = officialVersion;
            this.checked = checked;
            this.attempted = attempted;
        }

        boolean appUpdateAvailable() { return configuration.appVersionCode > BuildConfig.VERSION_CODE; }

        boolean compatibilityPending() {
            return UpdateConfiguration.isVersion(officialVersion)
                    && UpdateConfiguration.compare(officialVersion, profile.version) > 0;
        }

        String description(Context context) {
            int status = appUpdateAvailable() ? R.string.update_app_available
                    : compatibilityPending() ? R.string.update_compatibility_pending
                    : !attempted ? R.string.update_automatic_check
                    : checked ? R.string.update_current : R.string.update_check_unavailable;
            String message = appUpdateAvailable() ? context.getString(status, configuration.appVersionName)
                    : context.getString(status);
            return context.getString(R.string.update_versions, BuildConfig.VERSION_NAME, profile.version)
                    + "\n" + message;
        }
    }
}

package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only publisher-reviewed profiles using an adapter already in this APK are eligible. */
final class UpdateConfiguration {
    static final String ADAPTER = "weread-library-v1";
    static final String RELEASES_URL = "https://github.com/zk1047740032/WeNotes/releases";
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    private static final Pattern FRONT_MATTER_VERSION =
            Pattern.compile("(?m)^version:[ \\t]*([0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6})[ \\t]*$");

    final long revision;
    final int appVersionCode;
    final String appVersionName;
    final String updateUrl;
    final String releaseNotes;
    final ApkUpdateSpec apk;
    final List<Profile> profiles;

    private UpdateConfiguration(long revision, int appVersionCode, String appVersionName,
                                String updateUrl, String releaseNotes, ApkUpdateSpec apk, List<Profile> profiles) {
        this.revision = revision;
        this.appVersionCode = appVersionCode;
        this.appVersionName = appVersionName;
        this.updateUrl = updateUrl;
        this.releaseNotes = releaseNotes;
        this.apk = apk;
        this.profiles = profiles;
    }

    static UpdateConfiguration parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (root.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported update schema");
        long revision = root.getLong("revision");
        JSONObject app = root.getJSONObject("app");
        int appCode = app.getInt("versionCode");
        String appName = app.getString("versionName");
        String updateUrl = app.getString("updateUrl");
        String releaseNotes = app.optString("releaseNotes", "");
        if (releaseNotes.length() > 4000) throw new IllegalArgumentException("Update notes are too long");
        ApkUpdateSpec apk = app.has("apk") ? ApkUpdateSpec.parse(app) : null;
        if (revision < 1 || appCode < 1 || !isVersion(appName) || !isUpdateUrl(updateUrl)) {
            throw new IllegalArgumentException("Invalid update metadata");
        }
        JSONArray entries = root.getJSONArray("profiles");
        if (entries.length() == 0 || entries.length() > 32) {
            throw new IllegalArgumentException("Invalid profile count");
        }
        List<Profile> profiles = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String version = entry.getString("skillVersion");
            String adapter = entry.getString("adapter");
            int minimum = entry.getInt("minAppVersionCode");
            int pageSize = entry.getInt("pageSize");
            if (!isVersion(version) || adapter.isEmpty() || minimum < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("Invalid compatibility profile");
            }
            for (Profile previous : profiles) {
                if (previous.version.equals(version)) throw new IllegalArgumentException("Duplicate profile");
            }
            profiles.add(new Profile(version, adapter, minimum, pageSize));
        }
        return new UpdateConfiguration(revision, appCode, appName, updateUrl, releaseNotes, apk, profiles);
    }

    Profile choose(String officialVersion, int appCode, Profile fallback) {
        Profile chosen = fallback;
        for (Profile profile : profiles) {
            if (!ADAPTER.equals(profile.adapter) || profile.minAppVersionCode > appCode) continue;
            if (isVersion(officialVersion) && compare(profile.version, officialVersion) > 0) continue;
            if (chosen == null || compare(profile.version, chosen.version) >= 0) chosen = profile;
        }
        return chosen;
    }

    static String officialVersion(String markdown) {
        String text = markdown.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("Missing skill metadata");
        int end = text.indexOf("\n---", 4);
        if (end < 0 || end > 2048) throw new IllegalArgumentException("Invalid skill metadata");
        Matcher match = FRONT_MATTER_VERSION.matcher(text.substring(4, end));
        if (!match.find()) throw new IllegalArgumentException("Missing skill version");
        return match.group(1);
    }

    interface Source {
        String configuration() throws Exception;
        String officialSkill() throws Exception;
    }

    static Refresh refresh(String currentJson, Profile currentProfile, String currentOfficial,
                           int appCode, Source source) throws Exception {
        String json = currentJson;
        UpdateConfiguration current = parse(json);
        String official = currentOfficial;
        boolean configChecked = false;
        boolean officialChecked = false;
        try {
            String candidateJson = source.configuration();
            UpdateConfiguration candidate = parse(candidateJson);
            if (candidate.revision >= current.revision) json = candidateJson;
            configChecked = true;
        } catch (Exception ignored) {
            // Keep the last reviewed configuration if the feed is invalid or unavailable.
        }
        try {
            official = officialVersion(source.officialSkill());
            officialChecked = true;
        } catch (Exception ignored) {
            // Fetching a new document version alone never approves a new adapter.
        }
        UpdateConfiguration configuration = parse(json);
        return new Refresh(json, official, configuration.choose(official, appCode, currentProfile),
                configChecked && officialChecked, configChecked);
    }

    static final class Refresh {
        final String json;
        final String officialVersion;
        final Profile profile;
        final boolean checked;
        final boolean appChecked;

        Refresh(String json, String officialVersion, Profile profile, boolean checked, boolean appChecked) {
            this.json = json;
            this.officialVersion = officialVersion;
            this.profile = profile;
            this.checked = checked;
            this.appChecked = appChecked;
        }
    }

    static boolean isVersion(String value) { return value != null && VERSION.matcher(value).matches(); }

    static int compare(String first, String second) {
        if (!isVersion(first) || !isVersion(second)) throw new IllegalArgumentException("Invalid version");
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }

    static boolean isUpdateUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String path = uri.getPath();
            String normalized = new java.net.URI(null, null, path, null).normalize().getPath();
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && path.equals(normalized) && !path.contains("\\")
                    && ("/zk1047740032/WeNotes/releases".equals(path)
                    || path.startsWith("/zk1047740032/WeNotes/releases/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static final class Profile {
        final String version;
        final String adapter;
        final int minAppVersionCode;
        final int pageSize;

        Profile(String version, String adapter, int minAppVersionCode, int pageSize) {
            this.version = version;
            this.adapter = adapter;
            this.minAppVersionCode = minAppVersionCode;
            this.pageSize = pageSize;
        }
    }
}

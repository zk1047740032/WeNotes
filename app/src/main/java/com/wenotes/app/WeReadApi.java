package com.wenotes.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class WeReadApi implements LibrarySync.Api {
    private static final String URL_STRING = "https://i.weread.qq.com/api/agent/gateway";
    interface Profiles {
        UpdateConfiguration.Profile current();
        void refresh();
    }
    interface Transport { Response send(JSONObject body) throws Exception; }
    private final Profiles profiles;
    private final Transport transport;
    private boolean refreshedAfterUpgrade;

    WeReadApi(String key, Profiles profiles) {
        this(profiles, body -> post(key, body));
    }

    WeReadApi(Profiles profiles, Transport transport) {
        this.profiles = profiles;
        this.transport = transport;
    }

    @Override
    public JSONObject call(String name, JSONObject parameters) throws Exception {
        for (int upgradeAttempt = 0; upgradeAttempt < 2; upgradeAttempt++) {
            UpdateConfiguration.Profile profile = profiles.current();
            JSONObject body = request(name, parameters, profile);
            Response response = sendWithRetry(body);
            if (response.json.has("upgrade_info") && !response.json.isNull("upgrade_info")) {
                if (!refreshedAfterUpgrade) {
                    refreshedAfterUpgrade = true;
                    profiles.refresh();
                    UpdateConfiguration.Profile updated = profiles.current();
                    if (UpdateConfiguration.compare(updated.version, profile.version) > 0) continue;
                }
                throw new UpgradeRequiredException();
            }
            if (response.code < 200 || response.code >= 300) {
                throw new IllegalStateException("接口返回 HTTP " + response.code + "："
                        + response.json.optString("errmsg", "请检查密钥或网络"));
            }
            if (response.json.has("errcode") && response.json.optInt("errcode") != 0) {
                throw new IllegalStateException(response.json.optString("errmsg", "接口错误")
                        + " (" + response.json.optInt("errcode") + ")");
            }
            return response.json;
        }
        throw new UpgradeRequiredException();
    }

    private Response sendWithRetry(JSONObject body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Sync stopped");
            try {
                return transport.send(body);
            } catch (IOException error) {
                if (attempt == 2 || Thread.currentThread().isInterrupted()) throw error;
                Thread.sleep(400L * (attempt + 1));
            }
        }
    }

    static JSONObject request(String name, JSONObject parameters, UpdateConfiguration.Profile profile)
            throws Exception {
        JSONObject body = new JSONObject();
        if (parameters != null) {
            for (java.util.Iterator<String> keys = parameters.keys(); keys.hasNext();) {
                String field = keys.next();
                if (!"api_name".equals(field) && !"skill_version".equals(field)) {
                    body.put(field, parameters.get(field));
                }
            }
        }
        body.put("api_name", name);
        body.put("skill_version", profile.version);
        if (parameters != null && parameters.has("count")
                && ("/user/notebooks".equals(name) || "/review/list/mine".equals(name))) {
            body.put("count", Math.min(parameters.getInt("count"), profile.pageSize));
        }
        return body;
    }

    private static Response post(String key, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL_STRING).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IllegalStateException("接口返回 HTTP " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            }
            return new Response(code, new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
        } finally {
            connection.disconnect();
        }
    }

    static final class Response {
        final int code;
        final JSONObject json;
        Response(int code, JSONObject json) { this.code = code; this.json = json; }
    }

    static final class UpgradeRequiredException extends Exception {
        UpgradeRequiredException() {
            super("微信读书接口需要新的适配。请在“我的”检查更新后重试，已保存的笔记仍可查看。");
        }
    }
}

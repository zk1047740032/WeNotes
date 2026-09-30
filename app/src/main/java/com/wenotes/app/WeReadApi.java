package com.wenotes.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class WeReadApi {
    private static final String URL_STRING = "https://i.weread.qq.com/api/agent/gateway";
    private static final String SKILL_VERSION = "1.0.4";
    private final String key;

    WeReadApi(String key) {
        this.key = key;
    }

    JSONObject call(String name, JSONObject parameters) throws Exception {
        JSONObject body = new JSONObject();
        body.put("api_name", name);
        body.put("skill_version", SKILL_VERSION);
        if (parameters != null) {
            for (java.util.Iterator<String> keys = parameters.keys(); keys.hasNext();) {
                String field = keys.next();
                body.put(field, parameters.get(field));
            }
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(URL_STRING).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
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
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            }
            JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            JSONObject upgrade = result.optJSONObject("upgrade_info");
            if (upgrade != null) {
                throw new IllegalStateException("接口要求更新：" + upgrade.optString("message", "请更新应用"));
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("接口返回 HTTP " + code + "："
                        + result.optString("errmsg", "请检查密钥或网络"));
            }
            if (result.has("errcode") && result.optInt("errcode") != 0) {
                throw new IllegalStateException(result.optString("errmsg", "接口错误 ")
                        + " (" + result.optInt("errcode") + ")");
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }
}

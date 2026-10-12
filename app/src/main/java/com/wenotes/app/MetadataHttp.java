package com.wenotes.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Public update metadata uses a separate connection and never receives account credentials. */
final class MetadataHttp {
    private MetadataHttp() { }

    static String githubFile(String rawAddress, String apiAddress, int limit) throws IOException {
        try { return get(rawAddress, limit); }
        catch (IOException unavailable) { return get(apiAddress, limit); }
    }

    static String get(String address, int limit) throws IOException {
        String uncached = address + (address.contains("?") ? "&" : "?")
                + "wenotes_check=" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(uncached).openConnection();
        try {
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "WeNotes/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/vnd.github.raw+json");
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Update metadata returned HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || output.size() + length > limit) {
                        throw new IOException("Update metadata interrupted or oversized");
                    }
                    output.write(buffer, 0, length);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException error) {
            if (BuildConfig.DEBUG) android.util.Log.w("WeNotesUpdates",
                    "Public metadata check failed for " + connection.getURL().getHost(), error);
            throw error;
        } finally {
            connection.disconnect();
        }
    }
}

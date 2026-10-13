package com.wenotes.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;

/** Downloads only publisher releases and HTTPS GitHub asset redirects, without credentials. */
final class ApkDownloader {
    interface Progress { void changed(int percent); }
    interface Cancellation { boolean cancelled(); }

    private ApkDownloader() { }

    static void download(ApkUpdateSpec spec, File partial, File destination,
                         Progress progress, Cancellation cancellation) throws IOException {
        HttpURLConnection connection = null;
        boolean completed = false;
        try {
            URI address = URI.create(spec.url);
            for (int redirect = 0; redirect <= 5; redirect++) {
                if (cancellation.cancelled()) throw new UpdateException("cancelled");
                if (!ApkUpdateSpec.isDownloadAddress(address)) throw new UpdateException("address");
                connection = (HttpURLConnection) address.toURL().openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "WeNotes/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == 5) throw new UpdateException("address");
                    address = address.resolve(location);
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (status != 200) throw new IOException("APK download unavailable");
                long declared = connection.getContentLengthLong();
                if (declared != -1 && declared != spec.size) throw new UpdateException("integrity");
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    copyVerified(input, output, spec, progress, cancellation);
                    try { output.getFD().sync(); }
                    catch (IOException storage) { throw new UpdateException("storage"); }
                } catch (FileNotFoundException storage) {
                    throw new UpdateException("storage");
                }
                if (cancellation.cancelled()) throw new UpdateException("cancelled");
                if (!partial.renameTo(destination)) throw new UpdateException("storage");
                completed = true;
                return;
            }
            throw new UpdateException("address");
        } finally {
            if (connection != null) connection.disconnect();
            if (!completed && partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }

    static void copyVerified(InputStream input, OutputStream output, ApkUpdateSpec spec,
                             Progress progress, Cancellation cancellation) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception unavailable) { throw new IOException(unavailable); }
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int lastPercent = -1;
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) {
                throw new UpdateException("cancelled");
            }
            total += length;
            if (total > spec.size) throw new UpdateException("integrity");
            try { output.write(buffer, 0, length); }
            catch (IOException storage) { throw new UpdateException("storage"); }
            digest.update(buffer, 0, length);
            int percent = (int) (total * 100 / spec.size);
            if (percent != lastPercent) { progress.changed(percent); lastPercent = percent; }
        }
        if (total != spec.size || !hex(digest.digest()).equals(spec.sha256)) {
            throw new UpdateException("integrity");
        }
    }

    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] value = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            value[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            value[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(value);
    }
}

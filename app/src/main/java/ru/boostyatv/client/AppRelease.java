package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

record AppRelease(String version, String notes, String apkUrl, String sha256) {
    static final String PROJECT = "https://github.com/cergo666/BoostyATV";
    private static final String LATEST = "https://api.github.com/repos/cergo666/BoostyATV/releases/latest";
    private static final String APK = "BoostyATV.apk";
    private static final Pattern TAG = Pattern.compile("v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final long MAX_APK = 100L * 1024 * 1024;

    static AppRelease latest() throws Exception {
        HttpsURLConnection connection = connect(LATEST);
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (data.size() + count > 1024 * 1024) throw new IOException("Release metadata is too large");
                    data.write(buffer, 0, count);
                }
                return parse(new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }

    static AppRelease parse(JSONObject release) throws IOException {
        String tag = release.optString("tag_name");
        var match = TAG.matcher(tag);
        if (!match.matches() || release.optBoolean("draft") || release.optBoolean("prerelease")) {
            throw new IOException("Unexpected release version");
        }
        String version = match.group(1) + "." + match.group(2) + "." + match.group(3);
        String expected = PROJECT + "/releases/download/" + tag + "/" + APK;
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && APK.equals(asset.optString("name"))
                && expected.equals(asset.optString("browser_download_url"))) {
                String digest = asset.optString("digest", "");
                if (!digest.startsWith("sha256:")) throw new IOException("Release asset digest is missing");
                String sha256 = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
                if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid asset digest");
                return new AppRelease(version, release.optString("body", ""), expected, sha256);
            }
        }
        throw new IOException("Release APK is missing");
    }

    boolean newerThan(String installed) {
        String[] latest = version.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Integer.compare(part(latest, i), part(current, i));
            if (order != 0) return order > 0;
        }
        return false;
    }

    private static int part(String[] segments, int index) {
        String value = index < segments.length ? segments[index] : "0";
        int suffix = value.indexOf('-');
        if (suffix >= 0) value = value.substring(0, suffix);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // Treat a malformed segment as zero so a check never crashes.
            return 0;
        }
    }

    File download(File cache, Progress progress) throws IOException {
        File directory = new File(cache, "updates");
        Files.createDirectories(directory.toPath());
        File temporary = File.createTempFile("update-", ".part", directory);
        HttpsURLConnection connection = connect(apkUrl);
        try {
            long total = connection.getContentLengthLong();
            MessageDigest digest = sha256Digest();
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary.toPath())) {
                byte[] buffer = new byte[32768];
                long downloaded = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    downloaded += count;
                    if (downloaded > MAX_APK) throw new IOException("APK is too large");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (progress != null && !progress.onBytes(downloaded, total)) {
                        throw new InterruptedIOException("Download cancelled");
                    }
                }
                if (downloaded == 0) throw new IOException("APK is empty");
            }
            if (!sha256.equals(hex(digest.digest()))) throw new IOException("APK checksum mismatch");
            File target = new File(directory, APK);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File[] leftovers = directory.listFiles();
            if (leftovers != null) for (File file : leftovers) {
                if (!file.equals(target)) Files.deleteIfExists(file.toPath());
            }
            return target;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /** Download progress callback; return false to cancel the download. */
    interface Progress {
        boolean onBytes(long downloaded, long total);
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static HttpsURLConnection connect(String url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        return connection;
    }
}

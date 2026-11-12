package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

record AppRelease(String version, String notes, String apkUrl) {
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
                return new AppRelease(version, release.optString("body", ""), expected);
            }
        }
        throw new IOException("Release APK is missing");
    }

    boolean newerThan(String installed) {
        String[] latest = version.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Integer.compare(Integer.parseInt(latest[i]), Integer.parseInt(current[i]));
            if (order != 0) return order > 0;
        }
        return false;
    }

    File download(File cache) throws IOException {
        File directory = new File(cache, "updates");
        Files.createDirectories(directory.toPath());
        File temporary = File.createTempFile("update-", ".part", directory);
        HttpsURLConnection connection = connect(apkUrl);
        try {
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary.toPath())) {
                byte[] buffer = new byte[32768];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK) throw new IOException("APK is too large");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("APK is empty");
            }
            File target = new File(directory, APK);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static HttpsURLConnection connect(String url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        return connection;
    }
}

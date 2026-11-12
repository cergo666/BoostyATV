package ru.boostyatv.client;

import org.json.JSONObject;

import java.net.URLDecoder;

record WebSession(String access, String refresh, long expiry, String device) {
    static WebSession parse(String auth, String device) throws Exception {
        if (auth.length() > 32_768 || !device.matches("[A-Fa-f0-9-]{36}")) {
            throw new BoostyApi.Problem("Сайт ещё не завершил вход в Boosty.");
        }
        JSONObject data = new JSONObject(auth.startsWith("{") ? auth : URLDecoder.decode(auth, "UTF-8"));
        String access = first(data, "accessToken", "access_token");
        String refresh = first(data, "refreshToken", "refresh_token");
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new BoostyApi.Problem("Сайт ещё не завершил вход в Boosty.");
        }
        String time = first(data, "expiresAt", "expires_at", "expiresIn", "expires_in");
        long expiry = 0;
        if (time.matches("[0-9]{1,14}")) {
            expiry = Long.parseLong(time);
            if (expiry >= 1_000_000_000_000L) expiry /= 1000;
            if (expiry > 0 && expiry < 315_360_000L) expiry += System.currentTimeMillis() / 1000;
        }
        return new WebSession(access, refresh, expiry, device);
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("access", access).put("refresh", refresh)
            .put("expiry", expiry).put("device", device);
    }

    private static String first(JSONObject data, String... keys) {
        for (String key : keys) {
            String value = Post.string(data, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}

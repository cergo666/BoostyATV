package ru.boostyatv.client;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

record WebSession(String access, String refresh, long expiry, String device) {
    static WebSession parse(String auth, String device) throws Exception {
        if (auth.length() > 32_768 || !device.matches("[A-Fa-f0-9-]{36}")) {
            throw new BoostyApi.Problem(R.string.login_web_session_incomplete);
        }
        JSONObject data = new JSONObject(auth.startsWith("{") ? auth : URLDecoder.decode(auth, "UTF-8"));
        String access = first(data, "accessToken", "access_token");
        String refresh = first(data, "refreshToken", "refresh_token");
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new BoostyApi.Problem(R.string.login_web_session_incomplete);
        }
        String time = first(data, "expiresAt", "expires_at", "expiresIn", "expires_in");
        return new WebSession(access, refresh, parseExpiry(time), device);
    }

    // Boosty returns either an absolute epoch time, a relative number of seconds or
    // an ISO-8601 timestamp; without the timestamp branch the session looks expired
    // and every request triggers a refresh, while the TV refuses to import it.
    private static long parseExpiry(String time) {
        if (time.matches("[0-9]{1,14}")) {
            long value = Long.parseLong(time);
            if (value >= 1_000_000_000_000L) value /= 1000;
            if (value > 0 && value < 315_360_000L) value += System.currentTimeMillis() / 1000;
            return value;
        }
        try {
            return OffsetDateTime.parse(time).toEpochSecond();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
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

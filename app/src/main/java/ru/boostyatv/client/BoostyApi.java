package ru.boostyatv.client;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BoostyApi {
    static final String ORIGIN = "https://boosty.to";
    private static final String API = "https://api.boosty.to";
    private static final String PHONE_PATH = "/auth/phone/verification_code/";
    private final SessionStore store;
    private final String deviceId;
    private final Exchange exchange;
    private volatile JSONObject session;
    private final Map<String, List<Reactions.Type>> reactionTypes = new HashMap<>();

    BoostyApi(SessionStore store) {
        this(store, null);
    }

    BoostyApi(SessionStore store, Exchange exchange) {
        this.store = store;
        deviceId = store.deviceId();
        this.exchange = exchange == null ? this::request : exchange;
        session = store.read();
    }

    interface Exchange {
        JSONObject send(String method, String path, String token, String form) throws Exception;
    }

    boolean hasSession() {
        return session != null;
    }

    record Challenge(String phone, String id, String transport) {}
    record Page(List<Post> posts, String next) {}
    record Author(String slug, String name, String image) {}
    record Authors(List<Author> items, int next) {}
    record Tag(String id, String name) {}
    record Comment(long id, String reactionId, String author, long created, List<Post.Block> blocks,
                   int replyCount, Reactions reactions) {}
    record Comments(List<Comment> items, String next) {}
    record AccessSession(String access, long expiry, String device) {}
    record Profile(String name, String email, String avatar) {}

    synchronized Challenge sendCode(String phone) throws Exception {
        try {
            JSONObject payload = exchange.send("POST", PHONE_PATH + "send", null,
                form("phone", phone, "device_id", deviceId, "device_os", "web"));
            JSONObject code = payload.getJSONObject("data").getJSONObject("phoneCode");
            String id = Post.string(code, "code");
            if (id.trim().isEmpty()) throw new Problem(R.string.login_error_not_sent);
            return new Challenge(phone, id, Post.string(code, "sentTransport"));
        } catch (HttpError e) {
            throw phoneError(e, false);
        }
    }

    synchronized void confirm(Challenge challenge, String code) throws Exception {
        try {
            saveSession(exchange.send("PUT", PHONE_PATH + "confirm", null,
                form("phone", challenge.phone(), "code", challenge.id(), "sms_code", code,
                    "device_id", deviceId, "device_os", "web")), deviceId);
        } catch (HttpError e) {
            throw phoneError(e, true);
        }
    }

    synchronized void importWebSession(WebSession imported) throws Exception {
        if (!imported.device().matches("[A-Fa-f0-9-]{36}") || imported.access().isEmpty()
            || imported.refresh().isEmpty()) throw new Problem(R.string.error_session_incomplete);
        JSONObject identity = requestForDevice("GET", "/v1/user/current", imported.access(), null, imported.device());
        if (identity.optLong("id") <= 0) throw new Problem(R.string.error_account_unconfirmed);
        JSONObject next = imported.json();
        store.save(next);
        session = next;
    }

    /** Reads the signed-in Boosty profile for the account tab. */
    synchronized Profile currentUser() throws Exception {
        JSONObject user = authenticated("/v1/user/current");
        String name = Post.string(user, "name").trim();
        if (name.isEmpty()) name = Post.string(user, "displayName").trim();
        if (name.isEmpty()) name = Post.string(user, "username").trim();
        return new Profile(name, Post.string(user, "email").trim(),
            Post.string(user, "avatarUrl").trim());
    }

    synchronized AccessSession shareAccess() throws Exception {        if (session == null) throw new SignedOut();
        if (session.optLong("expiry") - System.currentTimeMillis() / 1000L < 60) refresh();
        return new AccessSession(session.getString("access"), session.getLong("expiry"),
            session.optString("device", deviceId));
    }

    synchronized void importSharedAccess(AccessSession imported) throws Exception {
        if (!imported.device().matches("[A-Fa-f0-9-]{36}") || imported.access().isEmpty()
            || imported.expiry() <= System.currentTimeMillis() / 1000L) {
            throw new Problem(R.string.error_phone_session_missing);
        }
        JSONObject identity = requestForDevice("GET", "/v1/user/current", imported.access(), null, imported.device());
        if (identity.optLong("id") <= 0) throw new Problem(R.string.error_account_unconfirmed);
        JSONObject next = new JSONObject().put("access", imported.access()).put("expiry", imported.expiry())
            .put("device", imported.device());
        store.save(next);
        session = next;
    }

    synchronized void signOut() {
        session = null;
        reactionTypes.clear();
        store.clear();
    }

    synchronized Page posts(String author, String offset) throws Exception {
        return posts(author, offset, "", "");
    }

    synchronized Page posts(String author, String offset, String search, String tags) throws Exception {
        String path = author == null ? "/v1/feed/post/" : "/v1/blog/" + Uri.encode(author) + "/post/";
        String query = "?limit=18&comments_limit=0&" + (author == null ? "only_allowed" : "is_only_allowed") + "=true";
        boolean searching = author != null && !search.isEmpty();
        if (searching) {
            path = "/v1/search/blog/post/";
            query += "&blog_url=" + Uri.encode(author) + "&search_query=" + Uri.encode(search);
        }
        if (!tags.isEmpty()) query += "&tags_ids=" + Uri.encode(tags);
        if (!offset.isEmpty()) query += "&offset=" + Uri.encode(offset);
        JSONObject result = authenticated(path + query);
        JSONObject data = result.optJSONObject("data");
        JSONArray blogs = data == null ? null : data.optJSONArray("blogs");
        if (blogs != null) for (int i = 0; i < blogs.length(); i++) {
            JSONObject blog = blogs.optJSONObject(i);
            String slug = Post.string(blog, "blogUrl");
            if (!slug.isEmpty() && (blog.has("allowedReactionList") || blog.optBoolean("reactionsDisabled"))) {
                reactionTypes.put(slug, Reactions.allowed(blog));
            }
        }
        JSONArray rows;
        if (searching) {
            JSONArray matches = result.getJSONObject("data").getJSONArray("searchPosts");
            rows = new JSONArray();
            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.getJSONObject(i).optJSONObject("post");
                if (match != null) rows.put(match);
            }
        } else {
            rows = author == null ? result.getJSONObject("data").getJSONArray("posts") : result.getJSONArray("data");
        }
        JSONObject extra = result.optJSONObject("extra");
        String next = extra == null || extra.optBoolean("isLast") ? "" : Post.string(extra, "offset");
        return new Page(Post.parseList(rows), next.equals(offset) ? "" : next);
    }

    synchronized List<Tag> tags(String author) throws Exception {
        JSONArray rows = authenticated("/v1/blog/" + Uri.encode(author) + "/post/tag/").getJSONArray("data");
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String id = Post.string(row, "id");
            String title = Post.string(row, "title");
            if (!id.isEmpty() && !title.isEmpty()) tags.add(new Tag(id, title));
        }
        return tags;
    }

    synchronized Comments comments(Post post, long parent, String offset) throws Exception {
        String path = postPath(post) + "/comment/";
        String query = "?limit=20&reply_limit=0&order=top";
        if (parent > 0) query += "&parent_id=" + parent;
        if (!offset.isEmpty()) query += "&offset=" + Uri.encode(offset);
        JSONObject result = authenticated(path + query);
        JSONArray rows = result.getJSONArray("data");
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optBoolean("isDeleted") || row.optBoolean("isBlocked")) continue;
            comments.add(new Comment(row.optLong("intId"), Post.string(row, "id"), Post.string(row.optJSONObject("author"), "name"),
                row.optLong("createdAt"), Post.blocks(row.optJSONArray("data"), false), row.optInt("replyCount"), Reactions.parse(row)));
        }
        JSONObject extra = result.optJSONObject("extra");
        String next = extra == null || extra.optBoolean("isLast") ? "" : Post.string(extra, "offset");
        return new Comments(comments, next.equals(offset) ? "" : next);
    }

    synchronized void setReaction(Post post, String commentId, String choice) throws Exception {
        String path = postPath(post);
        if (!commentId.isEmpty()) path += "/comment/" + Uri.encode(commentId);
        path += "/reaction?from_page=post";
        if (choice.isEmpty()) authenticated("DELETE", path, null);
        else authenticated("POST", path, form("reaction", choice));
    }

    synchronized List<Reactions.Type> reactionTypes(String author) throws Exception {
        List<Reactions.Type> result = reactionTypes.get(author);
        if (result != null) return result;
        result = Reactions.allowed(authenticated("/v1/blog/" + Uri.encode(author)));
        reactionTypes.put(author, result);
        return result;
    }

    private static String postPath(Post post) {
        return "/v1/blog/" + Uri.encode(post.slug()) + "/post/" + Uri.encode(post.id());
    }

    synchronized void updateTimecode(String videoId, long seconds) throws Exception {
        try {
            authenticated("PUT", "/v1/viewstat/video/" + Uri.encode(videoId) + "/timecode/",
                form("time_code", Long.toString(seconds)));
        } catch (HttpError e) {
            if (e.status != 400 && e.status != 403 && e.status != 404 && e.status != 410 && e.status != 422) throw e;
            // Keep the bookmark locally when this video cannot be updated on Boosty.
        }
    }

    synchronized Authors authors(int offset) throws Exception {
        JSONObject result = authenticated("/v1/user/subscriptions?limit=30&with_follow=true&offset=" + offset);
        JSONArray rows = result.getJSONArray("data");
        List<Author> authors = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            JSONObject blog = row == null ? null : row.optJSONObject("blog");
            String slug = Post.string(blog, "blogUrl");
            if (slug.trim().isEmpty()) continue;
            JSONObject owner = blog.optJSONObject("owner");
            String name = Post.string(owner, "name");
            if (name.trim().isEmpty()) name = Post.string(blog, "title");
            authors.add(new Author(slug, name.trim().isEmpty() ? slug : name, Post.string(owner, "avatarUrl")));
        }
        int end = offset + rows.length();
        int total = result.optInt("total", offset + (rows.length() == 30 ? 31 : rows.length()));
        return new Authors(authors, rows.length() > 0 && end < total ? end : -1);
    }

    private JSONObject authenticated(String path) throws Exception {
        return authenticated("GET", path, null);
    }

    private JSONObject authenticated(String method, String path, String form) throws Exception {
        if (session == null) throw new SignedOut();
        long margin = session.has("refresh") ? 60 : 0;
        if (session.optLong("expiry") - System.currentTimeMillis() / 1000L <= margin) refresh();
        try {
            return exchange.send(method, path, session.getString("access"), form);
        } catch (HttpError e) {
            if (e.status != 401) throw e;
            refresh();
            try {
                return exchange.send(method, path, session.getString("access"), form);
            } catch (HttpError retry) {
                if (retry.status != 401) throw retry;
                signOut();
                throw new SignedOut();
            }
        }
    }

    private void refresh() throws Exception {
        if (session.optString("refresh").isEmpty()) {
            signOut();
            throw new SignedOut(R.string.error_phone_session_expired);
        }
        String sessionDevice = session.optString("device", deviceId);
        try {
            saveSession(exchange.send("POST", "/oauth/token/", null,
                form("grant_type", "refresh_token", "refresh_token", session.getString("refresh"),
                    "device_id", sessionDevice, "device_os", "web")), sessionDevice);
        } catch (HttpError e) {
            if (e.status != 400 && e.status != 401) throw e;
            signOut();
            throw new SignedOut();
        }
    }

    private void saveSession(JSONObject data, String sessionDevice) throws Exception {
        String access = Post.string(data, "access_token");
        String refresh = Post.string(data, "refresh_token");
        long seconds = data.optLong("expires_in");
        if (access.trim().isEmpty() || refresh.trim().isEmpty() || seconds <= 0) {
            throw new Problem(R.string.error_login_incomplete);
        }
        JSONObject next = new JSONObject()
            .put("access", access).put("refresh", refresh)
            .put("expiry", System.currentTimeMillis() / 1000L + seconds).put("device", sessionDevice);
        store.save(next);
        session = next;
    }

    private static String form(String... pairs) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) result.append('&');
            result.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return result.toString();
    }

    private JSONObject request(String method, String path, String token, String form) throws Exception {
        String requestDevice = session == null || path.startsWith(PHONE_PATH)
            ? deviceId : session.optString("device", deviceId);
        return requestForDevice(method, path, token, form, requestDevice);
    }

    private static JSONObject requestForDevice(String method, String path, String token, String form,
                                               String requestDevice) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(18_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            setRequestHeaders(connection, requestDevice);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if ("DELETE".equals(method)) connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (form != null) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                String result = read(connection.getInputStream());
                return result.trim().isEmpty() ? new JSONObject() : new JSONObject(result);
            }
            String error = "";
            try {
                error = new JSONObject(read(connection.getErrorStream())).optString("error");
            } catch (JSONException | IOException ignored) {
                // Keep the status even if the server returns an HTML or empty error response.
            }
            throw new HttpError(status, error);
        } finally {
            connection.disconnect();
        }
    }

    static void setRequestHeaders(HttpURLConnection connection, String deviceId) {
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        connection.setRequestProperty("Origin", ORIGIN);
        connection.setRequestProperty("Referer", ORIGIN + "/");
        connection.setRequestProperty("X-App", "web");
        connection.setRequestProperty("X-Locale", "en_US");
        connection.setRequestProperty("X-Referer", "");
        connection.setRequestProperty("X-From-Id", deviceId);
        connection.setRequestProperty("Cookie", "_clientId=" + deviceId);
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > 8_000_000) throw new IOException("Response too large");
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private static Problem phoneError(HttpError error, boolean confirmation) {
        return switch (error.code) {
            case "invalid_check" -> new Problem(R.string.login_error_captcha);
            case "denied_phone_prefix" -> new Problem(R.string.login_error_unsupported_prefix);
            case "denied_phone_registration" -> new Problem(R.string.login_error_not_registered);
            case "sms_gate_ratelimit" -> new Problem(R.string.login_error_ratelimit);
            default -> error.status == 400 || error.status == 401 || error.status == 422
                ? new Problem(confirmation ? R.string.login_error_code_invalid
                    : R.string.login_error_invalid_number)
                : error;
        };
    }

    static class Problem extends Exception {
        final int message;
        final Object[] args;

        Problem(int message, Object... args) {
            this.message = message;
            this.args = args;
        }

        String resolve(android.content.Context context) {
            return args.length == 0 ? context.getString(message) : context.getString(message, args);
        }
    }

    static final class SignedOut extends Problem {
        SignedOut() { super(R.string.error_session_expired); }
        SignedOut(int message) { super(message); }
    }

    private static final class HttpError extends Problem {
        final int status;
        final String code;

        HttpError(int status, String code) {
            super(status == 429 ? R.string.error_too_many_requests : R.string.error_http, status);
            this.status = status;
            this.code = code;
        }
    }
}

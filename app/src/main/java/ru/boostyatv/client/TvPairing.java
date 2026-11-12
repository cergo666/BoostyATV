package ru.boostyatv.client;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class TvPairing {
    static final int LIFETIME_MS = 240_000;
    private static final int FRAME_LIMIT = 48_000;
    private static final int BASE64_FLAGS = Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE;
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Handler {
        JSONObject handle(String action, JSONObject input) throws Exception;
    }

    static final class Receiver implements AutoCloseable {
        private final ServerSocket listener;
        private final byte[] key = new byte[32];
        private final Handler handler;
        private final long deadline = SystemClock.elapsedRealtime() + LIFETIME_MS;

        Receiver(InetAddress address, Handler handler) throws IOException {
            RANDOM.nextBytes(key);
            this.handler = handler;
            listener = new ServerSocket();
            try {
                listener.bind(new InetSocketAddress(address, 0), 3);
                listener.setSoTimeout(1000);
            } catch (IOException e) {
                listener.close();
                throw e;
            }
        }

        String invitation() {
            return new Uri.Builder().scheme("boostyatv").authority("pair").appendPath("v1")
                .appendQueryParameter("host", listener.getInetAddress().getHostAddress())
                .appendQueryParameter("port", Integer.toString(listener.getLocalPort()))
                .appendQueryParameter("key", Base64.encodeToString(key, BASE64_FLAGS)).build().toString();
        }

        boolean await() {
            Set<String> used = new HashSet<>();
            try {
                while (!listener.isClosed() && SystemClock.elapsedRealtime() < deadline && used.size() < 24) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(3000);
                        JSONObject input = read(socket, key, "request");
                        if (SystemClock.elapsedRealtime() >= deadline) return false;
                        String id = input.getString("id");
                        if (id.length() != 36 || !used.add(id)) continue;
                        String action = input.getString("action");
                        JSONObject response;
                        boolean finished = false;
                        try {
                            response = handler.handle(action, input).put("ok", true);
                            finished = "confirm".equals(action) || "web".equals(action) || "access".equals(action);
                        } catch (BoostyApi.Problem e) {
                            response = new JSONObject().put("error", e.getMessage());
                        } catch (Exception e) {
                            response = new JSONObject().put("error", "Телевизор не смог завершить запрос к Boosty.");
                        }
                        try {
                            write(socket, key, "response", response.put("id", id));
                        } catch (Exception e) {
                            if (!finished) throw e;
                        }
                        if (finished) return true;
                    } catch (SocketTimeoutException ignored) {
                        // Recheck the expiry after an idle accept or read.
                    } catch (Exception ignored) {
                        // Invalid packets do not change the sign-in state.
                    }
                }
                return false;
            } finally {
                close();
            }
        }

        @Override public void close() {
            try { listener.close(); } catch (IOException ignored) {}
        }
    }

    static final class Sender {
        private final InetSocketAddress address;
        private final byte[] key;

        private Sender(InetSocketAddress address, byte[] key) {
            this.address = address;
            this.key = key;
        }

        static Sender parse(String link) throws Exception {
            Uri uri = Uri.parse(link);
            if (!"boostyatv".equals(uri.getScheme()) || !"pair".equals(uri.getAuthority())
                || !"/v1".equals(uri.getPath())) throw new IOException("Invalid invitation");
            String host = uri.getQueryParameter("host");
            if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("Invalid address");
            InetAddress ip = InetAddress.getByName(host);
            if (!ip.isSiteLocalAddress()) throw new IOException("Invalid address");
            int port = Integer.parseInt(uri.getQueryParameter("port"));
            if (port < 1024 || port > 65535) throw new IOException("Invalid port");
            byte[] key = Base64.decode(uri.getQueryParameter("key"), BASE64_FLAGS);
            if (key.length != 32) throw new IOException("Invalid key");
            return new Sender(new InetSocketAddress(ip, port), key);
        }

        BoostyApi.Challenge sendCode(String phone) throws Exception {
            JSONObject response = send("send", new JSONObject().put("phone", phone));
            return new BoostyApi.Challenge(phone, "", Post.string(response, "transport"));
        }

        void confirm(String code) throws Exception {
            send("confirm", new JSONObject().put("code", code));
        }

        void transfer(WebSession session) throws Exception {
            send("web", session.json());
        }

        void transferAccess(BoostyApi.AccessSession session) throws Exception {
            send("access", new JSONObject().put("access", session.access())
                .put("expiry", session.expiry()).put("device", session.device()));
        }

        private JSONObject send(String action, JSONObject input) throws Exception {
            String id = UUID.randomUUID().toString();
            try (Socket socket = new Socket()) {
                socket.connect(address, 4000);
                socket.setSoTimeout(38_000);
                write(socket, key, "request", input.put("action", action).put("id", id));
                JSONObject result = read(socket, key, "response");
                if (!id.equals(result.getString("id"))) throw new IOException("Response mismatch");
                if (!result.optBoolean("ok")) throw new BoostyApi.Problem(result.optString("error",
                    "Телевизор не подтвердил вход."));
                return result;
            } catch (BoostyApi.Problem e) {
                throw e;
            } catch (Exception e) {
                throw new BoostyApi.Problem("Телевизор не отвечает. Проверьте общую сеть и откройте новый QR-код.");
            }
        }
    }

    private static void write(Socket socket, byte[] key, String direction, JSONObject json) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] data = cipher(Cipher.ENCRYPT_MODE, key, nonce, direction)
            .doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        if (data.length + nonce.length > FRAME_LIMIT) throw new IOException("Frame too large");
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(nonce.length + data.length);
        output.write(nonce);
        output.write(data);
        output.flush();
    }

    private static JSONObject read(Socket socket, byte[] key, String direction) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        if (length < 28 || length > FRAME_LIMIT) throw new IOException("Invalid frame");
        byte[] nonce = new byte[12];
        byte[] data = new byte[length - nonce.length];
        input.readFully(nonce);
        input.readFully(data);
        byte[] json = cipher(Cipher.DECRYPT_MODE, key, nonce, direction).doFinal(data);
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, String direction) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("boostyatv/pair/v1/" + direction).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

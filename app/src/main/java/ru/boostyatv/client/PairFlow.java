package ru.boostyatv.client;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** TV pairing flow: QR invitation, code confirmation and the TV-side receiver. */
final class PairFlow {
    interface Host {
        Context activity();
        Ui ui();
        BoostyApi api();
        boolean isTv();
        boolean busy();
        View currentScreen();
        void replace(View view, boolean push);
        void showFeed(String slug, String name, boolean push);
        void showForm();
        void showWebLogin(TvPairing.Sender target);
        BoostyApi.Challenge challenge();
        void setChallenge(BoostyApi.Challenge challenge);
        void clearDraft();
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done);
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed);
    }

    private final Host host;
    private TvPairing.Sender sender;
    private View pairReturn;
    private AlertDialog pairDialog;
    private Thread pairThread;

    PairFlow(Host host) {
        this.host = host;
    }

    TvPairing.Sender sender() {
        return sender;
    }

    boolean hasPair() {
        return pairReturn != null;
    }

    void openInvitation(String link) {
        Context activity = host.activity();
        TvPairing.Sender invitation;
        try {
            invitation = TvPairing.Sender.parse(link, activity.getResources());
        } catch (Exception e) {
            Log.w("BoostyATV", "Pairing invitation parsing failed", e);
            Toast.makeText(activity, activity.getString(R.string.login_qr_not_pairing),
                Toast.LENGTH_LONG).show();
            return;
        }
        boolean signedIn = host.api().hasSession();
        String[] choices = signedIn
            ? new String[]{activity.getString(R.string.login_pair_transfer_current),
                activity.getString(R.string.login_pair_by_phone),
                activity.getString(R.string.login_pair_via_social)}
            : new String[]{activity.getString(R.string.login_pair_by_phone),
                activity.getString(R.string.login_pair_via_social)};
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.login_pair_title))
            .setItems(choices, (dialog, item) -> {
                int method = signedIn ? item - 1 : item;
                if (method == -1) confirmTransferAccess(invitation);
                else if (method == 1) host.showWebLogin(invitation);
                else {
                    sender = invitation;
                    pairReturn = host.currentScreen();
                    host.setChallenge(null);
                    host.clearDraft();
                    host.showForm();
                }
            }).setNegativeButton(activity.getString(R.string.cancel), null).show();
    }

    private void confirmTransferAccess(TvPairing.Sender invitation) {
        Context activity = host.activity();
        Ui ui = host.ui();
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        content.addView(ui.label(activity.getString(R.string.login_pair_confirm_message),
            16, Ui.INK, false));
        TextView error = ui.label("", 14, Ui.ACCENT, false);
        error.setVisibility(View.GONE);
        ui.margin(content, error, Ui.MATCH, Ui.WRAP, 8);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.login_pair_confirm_title)).setView(content)
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .setPositiveButton(activity.getString(R.string.login_pair_confirm_grant), null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                if (host.busy()) return;
                host.perform(() -> {
                    invitation.transferAccess(host.api().shareAccess());
                    return true;
                }, error, done -> {
                    dialog.dismiss();
                    Toast.makeText(activity, activity.getString(R.string.login_pair_connected),
                        Toast.LENGTH_SHORT).show();
                }, () -> {
                    if (!host.api().hasSession()) dialog.dismiss();
                });
            }));
        dialog.show();
    }

    void finishPhonePair() {
        View previous = pairReturn;
        pairReturn = null;
        sender = null;
        host.setChallenge(null);
        host.clearDraft();
        if (previous != null) host.replace(previous, false);
    }

    /** Returns true when the pair screen consumed the back press. */
    boolean handleBack() {
        if (pairReturn == null) return false;
        if (host.challenge() == null) finishPhonePair();
        else {
            host.setChallenge(null);
            host.showForm();
        }
        return true;
    }

    void showTvPairing() {
        Context activity = host.activity();
        Ui ui = host.ui();
        InetAddress local = localNetworkAddress();
        if (local == null) {
            Toast.makeText(activity, activity.getString(R.string.login_pair_tv_not_connected),
                Toast.LENGTH_LONG).show();
            return;
        }
        TvPairing.Receiver receiver;
        try {
            receiver = new TvPairing.Receiver(local, new TvPairing.Handler() {
                private BoostyApi.Challenge pending;

                @Override public org.json.JSONObject handle(String action, org.json.JSONObject input)
                    throws Exception {
                    if (host.api().hasSession()) {
                        throw new BoostyApi.Problem(R.string.login_pair_already_connected);
                    }
                    switch (action) {
                        case "send" -> {
                            String phone = input.getString("phone");
                            if (!phone.matches("\\+[1-9][0-9]{6,14}")) {
                                throw new BoostyApi.Problem(R.string.login_pair_invalid_phone_format);
                            }
                            pending = host.api().sendCode(phone);
                            return new org.json.JSONObject().put("transport", pending.transport());
                        }
                        case "confirm" -> {
                            if (pending == null) {
                                throw new BoostyApi.Problem(R.string.login_pair_request_code_first);
                            }
                            String code = input.getString("code");
                            if (!code.matches("[0-9]{6}")) {
                                throw new BoostyApi.Problem(R.string.login_pair_invalid_code_format);
                            }
                            host.api().confirm(pending, code);
                        }
                        case "web" -> host.api().importWebSession(new WebSession(
                            input.getString("access"), input.getString("refresh"),
                            input.getLong("expiry"), input.getString("device")));
                        case "access" -> host.api().importSharedAccess(new BoostyApi.AccessSession(
                            input.getString("access"), input.getLong("expiry"),
                            input.getString("device")));
                        default -> throw new BoostyApi.Problem(R.string.login_pair_unknown_method);
                    }
                    return new org.json.JSONObject();
                }
            }, activity.getResources());
        } catch (Exception e) {
            Log.w("BoostyATV", "Pairing receiver failed to start", e);
            Toast.makeText(activity, activity.getString(R.string.login_pair_cannot_open),
                Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout content = ui.column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(12));
        TextView instruction = ui.label(activity.getString(R.string.login_pair_instruction),
            16, Ui.INK, false);
        ui.margin(content, instruction, Ui.MATCH, Ui.WRAP, 6);
        ImageView qr = new ImageView(activity);
        try {
            var matrix = new QRCodeWriter().encode(receiver.invitation(), BarcodeFormat.QR_CODE, 400, 400);
            int[] pixels = new int[400 * 400];
            for (int y = 0; y < 400; y++) {
                for (int x = 0; x < 400; x++) {
                    pixels[y * 400 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, 400, 400, Bitmap.Config.ARGB_8888));
        } catch (Exception e) {
            receiver.close();
            Log.w("BoostyATV", "Pairing QR rendering failed", e);
            Toast.makeText(activity, activity.getString(R.string.login_pair_cannot_create_qr),
                Toast.LENGTH_LONG).show();
            return;
        }
        qr.setContentDescription(activity.getString(R.string.login_pair_qr_description));
        ui.margin(content, qr, ui.dp(230), ui.dp(230), 14);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.login_pair_phone_title)).setView(content)
            .setNegativeButton(activity.getString(R.string.nav_close), null).create();
        dialog.setOnDismissListener(value -> {
            receiver.close();
            stopPairThread();
            if (pairDialog == dialog) pairDialog = null;
        });
        pairDialog = dialog;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();
        pairThread = AppExecutors.thread("boosty-tv-pair", () -> {
            boolean connected = receiver.await();
            if (activity instanceof android.app.Activity target) {
                target.runOnUiThread(() -> {
                    if (pairDialog != dialog || target.isDestroyed() || target.isFinishing()) return;
                    dialog.dismiss();
                    if (connected) {
                        host.setChallenge(null);
                        host.clearDraft();
                        host.showFeed(null, null, false);
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.login_pair_qr_expired),
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        pairThread.start();
    }

    void stop() {
        stopPairThread();
        if (pairDialog != null) pairDialog.dismiss();
    }

    void reset() {
        stopPairThread();
        if (pairDialog != null) pairDialog.dismiss();
        pairDialog = null;
        sender = null;
        pairReturn = null;
    }

    private void stopPairThread() {
        Thread worker = pairThread;
        pairThread = null;
        if (worker != null && worker.isAlive()) worker.interrupt();
    }

    private InetAddress localNetworkAddress() {
        Context activity = host.activity();
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        for (Network candidate : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(candidate);
            LinkProperties properties = manager.getLinkProperties(candidate);
            if (properties == null || capabilities == null
                || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address;
            }
        }
        return null;
    }
}

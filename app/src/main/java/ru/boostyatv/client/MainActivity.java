package ru.boostyatv.client;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BG = 0xff101015;
    private static final int PANEL = 0xff201e27;
    private static final int PANEL_HOT = 0xff3d2c35;
    private static final int INK = 0xfff7f1ef;
    private static final int MUTED = 0xffbdb1bf;
    private static final int ACCENT = 0xffffaa81;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Artwork artwork = new Artwork();
    private final ArrayDeque<View> history = new ArrayDeque<>();
    private record ReactionTarget(String post, String comment) {}
    private final Map<ReactionTarget, Reactions> localReactions = new HashMap<>();
    private final Set<ReactionTarget> pendingReactions = new HashSet<>();
    private int sessionEpoch;
    private BoostyApi api;
    private FrameLayout host;
    private boolean tv;
    private boolean busy;
    private BoostyApi.Challenge challenge;
    private TvPairing.Sender pairSender;
    private View pairReturn;
    private AlertDialog pairDialog;
    private BoostyWebLogin webLogin;
    private final ActivityResultLauncher<ScanOptions> scanTv = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) openPairInvitation(result.getContents());
    });
    private String phoneDraft = "";
    private List<PhoneCountry> phoneCountries;
    private PhoneCountry phoneCountry;
    private long resendAfter;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private PlayerView playerView;
    private Post.Block video;
    private Post.Block externalVideo;
    private PlaybackOptions.App externalApp;
    private WatchHistory watchHistory;
    private PlaybackOptions playbackOptions;
    private List<Post.Source> playbackSources;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncingProgress = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener progressChanged = (preferences, key) -> {
        if (key != null && key.endsWith(".position") && host != null && !isFinishing()) {
            refreshPlaybackLabels(host);
            syncWatchProgress();
        }
    };
    private long lastWrittenTime = -1;
    private boolean playbackStarted;
    private int revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable savePlaybackTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (player.isPlaying()) savePlayback();
            handler.postDelayed(this, 15_000);
        }
    };
    private final ActivityResultLauncher<Intent> externalPlayer = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Post.Block item = externalVideo;
            PlaybackOptions.App app = externalApp;
            externalVideo = null;
            externalApp = null;
            if (item == null || app == null) return;
            WatchHistory.Progress progress = app.result(result.getResultCode(), result.getData(), item.durationMs());
            if (progress != null) {
                recordProgress(item, progress.position(), progress.duration());
                refreshPlaybackLabels(host);
            }
        });
    private int sourceIndex;
    private long playbackPosition;
    private boolean resumePlayback = true;
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (!busy) goBack();
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        UiModeManager modes = getSystemService(UiModeManager.class);
        tv = modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        api = new BoostyApi(new SessionStore(this));
        watchHistory = new WatchHistory(this);
        watchHistory.listen(progressChanged);
        playbackOptions = new PlaybackOptions(this);
        if (savedState != null && savedState.containsKey("external_player")) {
            externalApp = PlaybackOptions.App.valueOf(savedState.getString("external_player"));
            externalVideo = new Post.Block("video", "", "", java.util.Collections.emptyList(),
                savedState.getString("external_video", ""), 0, savedState.getLong("external_duration"));
        }
        host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        setContentView(host);
        getOnBackPressedDispatcher().addCallback(this, back);
        if (api.hasSession()) showFeed(null, null, false);
        else showLogin();
    }

    private void showLogin() {
        final boolean confirming = challenge != null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout outer = column();
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(tv ? 42 : 22), dp(tv ? 20 : 24), dp(tv ? 42 : 22), dp(24));
        scroll.addView(outer);

        LinearLayout layout = tv ? row() : column();
        layout.setGravity(Gravity.CENTER_VERTICAL);
        int screenWidth = getResources().getConfiguration().screenWidthDp;
        int maxWidth = tv ? 1020 : 490;
        int available = screenWidth - (tv ? 84 : 44);
        outer.addView(layout, new LinearLayout.LayoutParams(available > maxWidth ? dp(maxWidth) : MATCH, WRAP));
        LinearLayout intro = column();
        LinearLayout form = column();
        if (tv) {
            layout.addView(intro, new LinearLayout.LayoutParams(0, WRAP, 1));
            LinearLayout.LayoutParams panel = new LinearLayout.LayoutParams(0, WRAP, 1);
            panel.leftMargin = dp(32);
            layout.addView(form, panel);
        } else {
            layout.addView(intro, new LinearLayout.LayoutParams(MATCH, WRAP));
            margin(layout, form, MATCH, WRAP, 30);
        }

        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.app_icon);
        mark.setBackground(shape(PANEL, 12, 0));
        mark.setClipToOutline(true);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = label("Boosty ATV", 17, ACCENT, true);
        name.setPadding(dp(12), 0, 0, 0);
        brand.addView(name);
        intro.addView(brand);
        margin(intro, label(confirming ? "Введите\nкод" : pairReturn != null ? "Вход на ТВ" : "Вход\nпо телефону", tv ? 43 : 39, INK, true),
            MATCH, WRAP, 20);
        String hint = confirming ? deliveryHint(challenge.transport())
            : "Введите номер, привязанный к вашему аккаунту Boosty.";
        margin(intro, label(hint, tv ? 17 : 16, MUTED, false), MATCH, WRAP, 10);

        form.setPadding(dp(20), dp(18), dp(20), dp(18));
        form.setBackground(shape(PANEL, 20, 0));
        form.addView(label(confirming ? "Код из сообщения" : "Номер телефона", 16, INK, true));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(21);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(control(0xff14131a, 0xff25212b, 10));
        input.setInputType(confirming ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_PHONE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(confirming ? 6 : 24)});
        input.setHint(confirming ? "000000" : "Без кода");
        if (!confirming) input.setText(phoneDraft);
        input.setShowSoftInputOnFocus(!tv);
        input.setSaveEnabled(false);
        if (confirming) margin(form, input, MATCH, dp(49), 8);
        else addPhoneField(form, input);
        TextView error = label("", 14, ACCENT, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        margin(form, error, MATCH, WRAP, 7);
        View firstDigit = tv ? keypad(form, input, confirming) : null;

        String actionText = confirming ? "Войти" : "Получить код";
        TextView submit = button(actionText, true, () -> {
            if (busy) return;
            String typed = input.getText().toString();
            String value = confirming ? typed.trim() : phoneCountry.normalize(typed);
            if (value.isEmpty() || (confirming && !value.matches("[0-9]{6}"))) {
                showError(error, confirming ? "Введите 6 цифр из сообщения."
                    : "Выберите страну и введите номер или вставьте полный номер с +.");
                return;
            }
            hideKeyboard(input);
            if (confirming) {
                BoostyApi.Challenge active = challenge;
                TvPairing.Sender pair = pairSender;
                perform(() -> {
                    if (pair == null) api.confirm(active, value);
                    else pair.confirm(value);
                    return true;
                }, error, result -> {
                    challenge = null;
                    phoneDraft = "";
                    if (pair != null) {
                        finishPhonePair();
                        Toast.makeText(this, "Телевизор подключён.", Toast.LENGTH_SHORT).show();
                    } else showFeed(null, null, false);
                });
            } else {
                phoneDraft = typed;
                sendLoginCode(value, error);
            }
        });
        margin(form, submit, MATCH, dp(49), 10);
        input.setOnEditorActionListener((view, action, event) -> {
            submit.performClick();
            return true;
        });
        if (confirming) {
            LinearLayout options = row();
            weighted(options, button("Изменить номер", false, () -> {
                if (!busy) {
                    challenge = null;
                    showLogin();
                }
            }), 0, dp(44));
            weighted(options, button("Выслать снова", false, () -> {
                if (busy) return;
                long seconds = (resendAfter - SystemClock.elapsedRealtime() + 999) / 1000;
                if (seconds > 0) showError(error, "Повторная отправка через " + seconds + " с.");
                else sendLoginCode(challenge.phone(), error);
            }), 6, dp(44));
            margin(form, options, MATCH, WRAP, 8);
        } else if (pairReturn != null) {
            margin(form, button("Отменить подключение", false, this::finishPhonePair), MATCH, dp(44), 8);
        } else if (tv) {
            margin(form, button("Войти с телефона", false, this::showTvPairing), MATCH, dp(44), 8);
        } else {
            LinearLayout options = row();
            weighted(options, button("Через соцсеть", false, () -> showWebLogin(null)), 0, dp(44));
            margin(form, options, MATCH, WRAP, 8);
        }
        replace(scroll, false);
        if (firstDigit != null) firstDigit.requestFocus();
    }

    private void sendLoginCode(String phone, TextView error) {
        TvPairing.Sender pair = pairSender;
        perform(() -> pair == null ? api.sendCode(phone) : pair.sendCode(phone), error, result -> {
            challenge = result;
            resendAfter = SystemClock.elapsedRealtime() + 60_000;
            showLogin();
        });
    }

    private void scanTvCode() {
        ScanOptions options = new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Наведите камеру на QR-код Boosty ATV").setBeepEnabled(false)
            .setCaptureActivity(PortraitQrActivity.class).setOrientationLocked(false);
        scanTv.launch(options);
    }

    private void openPairInvitation(String link) {
        TvPairing.Sender sender;
        try {
            sender = TvPairing.Sender.parse(link);
        } catch (Exception e) {
            Toast.makeText(this, "Это не QR-код подключения Boosty ATV.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean signedIn = api.hasSession();
        String[] choices = signedIn
            ? new String[]{"Передать текущий вход", "По номеру телефона", "Через соцсеть на сайте"}
            : new String[]{"По номеру телефона", "Через соцсеть на сайте"};
        new AlertDialog.Builder(this).setTitle("Вход на телевизоре")
            .setItems(choices, (dialog, item) -> {
                int method = signedIn ? item - 1 : item;
                if (method == -1) confirmTransferAccess(sender);
                else if (method == 1) showWebLogin(sender);
                else {
                    pairSender = sender;
                    pairReturn = host.getChildAt(0);
                    challenge = null;
                    phoneDraft = "";
                    showLogin();
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void confirmTransferAccess(TvPairing.Sender sender) {
        LinearLayout content = column();
        content.setPadding(dp(22), dp(8), dp(22), 0);
        content.addView(label("Телевизор получит временный доступ к вашему аккаунту Boosty. Когда срок доступа истечёт, его можно передать снова.", 16, INK, false));
        TextView error = label("", 14, ACCENT, false);
        error.setVisibility(View.GONE);
        margin(content, error, MATCH, WRAP, 8);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Передать вход на ТВ?")
            .setView(content).setNegativeButton("Отмена", null).setPositiveButton("Передать", null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy) return;
            perform(() -> { sender.transferAccess(api.shareAccess()); return true; }, error, done -> {
                dialog.dismiss();
                Toast.makeText(this, "Телевизор подключён.", Toast.LENGTH_SHORT).show();
            }, () -> { if (!api.hasSession()) dialog.dismiss(); });
        }));
        dialog.show();
    }

    private void finishPhonePair() {
        View previous = pairReturn;
        pairReturn = null;
        pairSender = null;
        challenge = null;
        phoneDraft = "";
        if (previous != null) replace(previous, false);
    }

    private void showTvPairing() {
        InetAddress local = localNetworkAddress();
        if (local == null) {
            Toast.makeText(this, "Подключите телевизор к домашней сети.", Toast.LENGTH_LONG).show();
            return;
        }
        TvPairing.Receiver receiver;
        try {
            receiver = new TvPairing.Receiver(local, new TvPairing.Handler() {
                private BoostyApi.Challenge pending;

                @Override public org.json.JSONObject handle(String action, org.json.JSONObject input) throws Exception {
                    if (api.hasSession()) throw new BoostyApi.Problem("Телевизор уже подключён.");
                    switch (action) {
                        case "send" -> {
                            String phone = input.getString("phone");
                            if (!phone.matches("\\+[1-9][0-9]{6,14}")) {
                                throw new BoostyApi.Problem("Введите номер в международном формате.");
                            }
                            pending = api.sendCode(phone);
                            return new org.json.JSONObject().put("transport", pending.transport());
                        }
                        case "confirm" -> {
                            if (pending == null) throw new BoostyApi.Problem("Сначала запросите код.");
                            String code = input.getString("code");
                            if (!code.matches("[0-9]{6}")) throw new BoostyApi.Problem("Введите шесть цифр.");
                            api.confirm(pending, code);
                        }
                        case "web" -> api.importWebSession(new WebSession(input.getString("access"),
                            input.getString("refresh"), input.getLong("expiry"), input.getString("device")));
                        case "access" -> api.importSharedAccess(new BoostyApi.AccessSession(input.getString("access"),
                            input.getLong("expiry"), input.getString("device")));
                        default -> throw new BoostyApi.Problem("Неизвестный способ входа.");
                    }
                    return new org.json.JSONObject();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть подключение к телевизору.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), 0, dp(24), dp(12));
        TextView instruction = label("Откройте Boosty ATV на телефоне, нажмите «QR» и отсканируйте код. Устройства должны быть в одной сети.",
            16, INK, false);
        margin(content, instruction, MATCH, WRAP, 6);
        ImageView qr = new ImageView(this);
        try {
            var matrix = new QRCodeWriter().encode(receiver.invitation(), BarcodeFormat.QR_CODE, 400, 400);
            int[] pixels = new int[400 * 400];
            for (int y = 0; y < 400; y++) for (int x = 0; x < 400; x++) {
                pixels[y * 400 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, 400, 400, Bitmap.Config.ARGB_8888));
        } catch (Exception e) {
            receiver.close();
            Toast.makeText(this, "Не удалось создать QR-код.", Toast.LENGTH_LONG).show();
            return;
        }
        qr.setContentDescription("QR-код подключения");
        margin(content, qr, dp(230), dp(230), 14);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Вход с телефона").setView(content)
            .setNegativeButton("Закрыть", null).create();
        dialog.setOnDismissListener(value -> {
            receiver.close();
            if (pairDialog == dialog) pairDialog = null;
        });
        pairDialog = dialog;
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();
        new Thread(() -> {
            boolean connected = receiver.await();
            runOnUiThread(() -> {
                if (pairDialog != dialog || isDestroyed() || isFinishing()) return;
                dialog.dismiss();
                if (connected) {
                    challenge = null;
                    phoneDraft = "";
                    showFeed(null, null, false);
                } else Toast.makeText(this, "QR-код истёк. Откройте новый код для входа.", Toast.LENGTH_LONG).show();
            });
        }, "boosty-tv-pair").start();
    }

    private InetAddress localNetworkAddress() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network candidate : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(candidate);
            LinkProperties properties = manager.getLinkProperties(candidate);
            if (properties == null || capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address;
            }
        }
        return null;
    }

    private void showWebLogin(TvPairing.Sender target) {
        LinearLayout screen = column();
        LinearLayout bar = toolbar("boosty.to", true);
        TextView address = (TextView) bar.getChildAt(1);
        TextView error = label("", 14, ACCENT, false);
        error.setPadding(dp(16), dp(5), dp(16), dp(5));
        error.setVisibility(View.GONE);
        BoostyWebLogin browser = new BoostyWebLogin(this, address::setText, text -> showError(error, text));
        webLogin = browser;
        Consumer<WebSession> finish = session -> {
            if (webLogin != browser) return;
            perform(() -> {
                if (target == null) api.importWebSession(session);
                else target.transfer(session);
                return true;
            }, error, result -> {
                browser.close(true);
                webLogin = null;
                if (target == null) {
                    watchHistory.clear();
                    challenge = null;
                    phoneDraft = "";
                    history.clear();
                    showFeed(null, null, false);
                } else {
                    goBack();
                    Toast.makeText(this, "Телевизор подключён.", Toast.LENGTH_SHORT).show();
                }
            });
        };
        browser.onAuthorized(target == null ? finish : session -> {
            browser.closePopup();
            showError(error, "Вход в Boosty выполнен. Нажмите «Передать на ТВ».");
        });
        bar.addView(button(target == null ? "Завершить вход" : "Передать на ТВ", true, () -> {
            error.setVisibility(View.GONE);
            browser.readSession(finish);
        }));
        screen.addView(bar);
        screen.addView(error);
        screen.addView(browser, new LinearLayout.LayoutParams(MATCH, 0, 1));
        replace(screen, true);
    }

    private static String deliveryHint(String transport) {
        return switch (transport) {
            case "sms" -> "Boosty выбрал SMS. Проверьте сообщения на телефоне.";
            case "telegram" -> "Boosty выбрал Telegram. Проверьте сообщения от бота Boosty.";
            default -> "Boosty принял запрос. Проверьте личные сообщения в приложении Boosty, Telegram-бот и SMS.";
        };
    }

    private void addPhoneField(LinearLayout form, EditText input) {
        if (phoneCountries == null) {
            phoneCountries = PhoneCountry.load(this);
            String region = getPreferences(MODE_PRIVATE).getString("phone_region", Locale.getDefault().getCountry());
            phoneCountry = phoneCountries.stream().filter(country -> country.region().equals(region))
                .findFirst().orElse(PhoneCountry.RUSSIA);
        }
        LinearLayout field = row();
        TextView prefix = button("", false, () -> {});
        prefix.setTextSize(16);
        prefix.setBackground(control(0xff2c2833, PANEL_HOT, 10));
        field.addView(prefix, new LinearLayout.LayoutParams(WRAP, dp(49)));
        weighted(field, input, 8, dp(49));
        margin(form, field, MATCH, WRAP, 8);

        Consumer<String> update = value -> {
            PhoneCountry displayed = PhoneCountry.fromInternational(value, phoneCountry, phoneCountries);
            prefix.setText(displayed.flag() + "  " + displayed.prefix() + "  ▾");
            prefix.setContentDescription("Код страны, " + displayed.name() + ", " + displayed.prefix());
        };
        update.accept(input.getText().toString());
        watchText(input, update);
        prefix.setOnClickListener(view -> {
            if (busy) return;
            hideKeyboard(input);
            String typed = input.getText().toString();
            PhoneCountry displayed = PhoneCountry.fromInternational(typed, phoneCountry, phoneCountries);
            showCountryPicker(displayed, country -> {
                phoneCountry = country;
                getPreferences(MODE_PRIVATE).edit().putString("phone_region", country.region()).apply();
                if (!country.prefix().equals(displayed.prefix())) {
                    String national = displayed.nationalPart(typed);
                    if (!national.equals(typed)) {
                        input.setText(national);
                        input.setSelection(input.length());
                    }
                }
                update.accept(input.getText().toString());
                if (tv) prefix.requestFocus();
            });
        });
    }

    private void showCountryPicker(PhoneCountry current, Consumer<PhoneCountry> select) {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("Страна или код");
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(control(0xff14131a, 0xff25212b, 10));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(search, new LinearLayout.LayoutParams(MATCH, dp(48)));

        FrameLayout results = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(shape(PANEL_HOT, 10, ACCENT));
        list.setId(View.generateViewId());
        search.setNextFocusDownId(list.getId());
        results.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView empty = label("Страны не найдены", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        list.setEmptyView(empty);
        int height = Math.min(tv ? 340 : 380, getResources().getConfiguration().screenHeightDp / 2);
        margin(content, results, MATCH, dp(height), 4);

        ArrayAdapter<PhoneCountry> rows = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout item = (LinearLayout) recycled;
                if (item == null) {
                    item = row();
                    item.setPadding(dp(12), 0, dp(12), 0);
                    item.setMinimumHeight(dp(tv ? 49 : 46));
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    item.addView(label("", 19, INK, false), new LinearLayout.LayoutParams(dp(33), WRAP));
                    TextView name = label("", 16, INK, false);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    item.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
                    TextView code = label("", 15, ACCENT, true);
                    code.setPadding(dp(8), 0, 0, 0);
                    item.addView(code);
                }
                PhoneCountry country = getItem(position);
                ((TextView) item.getChildAt(0)).setText(country.flag());
                ((TextView) item.getChildAt(1)).setText(country.name());
                ((TextView) item.getChildAt(2)).setText(country.prefix());
                return item;
            }
        };
        list.setAdapter(rows);
        Consumer<String> filter = typed -> {
            String query = typed.trim().toLowerCase(Locale.ROOT);
            List<PhoneCountry> visible = new ArrayList<>();
            if (query.isEmpty()) visible.add(current);
            for (PhoneCountry country : phoneCountries) {
                if (query.isEmpty() ? !country.equals(current) : country.matches(query)) visible.add(country);
            }
            rows.setNotifyOnChange(false);
            rows.clear();
            rows.addAll(visible);
            rows.notifyDataSetChanged();
            list.setSelection(0);
        };
        filter.accept("");
        watchText(search, filter);
        search.setOnEditorActionListener((view, action, event) -> {
            hideKeyboard(search);
            list.requestFocus();
            return true;
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CountryPickerTheme).setTitle("Код страны")
            .setView(content).setNegativeButton("Отмена", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            select.accept(rows.getItem(position));
            dialog.dismiss();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        if (tv) list.requestFocus();
    }

    private static void watchText(EditText input, Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                changed.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private View keypad(LinearLayout form, EditText input, boolean confirming) {
        View first = null;
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫"};
        for (int i = 0; i < keys.length; i += 3) {
            LinearLayout keysRow = row();
            for (int j = i; j < i + 3; j++) {
                String key = keys[j];
                TextView digit = button(key, false, () -> {
                    if (busy) return;
                    if (key.equals("⌫")) {
                        int length = input.length();
                        if (length > 0) input.getText().delete(length - 1, length);
                    } else {
                        input.append(key);
                    }
                });
                digit.setTextSize(17);
                digit.setBackground(control(0xff2c2833, PANEL_HOT, 9));
                if (key.equals("⌫")) digit.setContentDescription("Удалить цифру");
                if (confirming && key.equals("+")) digit.setVisibility(View.INVISIBLE);
                if (first == null) first = digit;
                weighted(keysRow, digit, j == i ? 0 : 6, dp(37));
            }
            margin(form, keysRow, MATCH, WRAP, 4);
        }
        return first;
    }

    private void showFeed(String authorSlug, String authorName, boolean push) {
        boolean home = authorSlug == null;
        LinearLayout screen = column();
        LinearLayout toolbar = toolbar(home ? "Лента" : authorName, !home);
        if (home) {
            TextView subscriptions = button(tv ? "Подписки" : "☰", false, this::showAuthors);
            subscriptions.setContentDescription("Подписки");
            toolbar.addView(subscriptions, 0, new LinearLayout.LayoutParams(tv ? WRAP : dp(48), dp(46)));
            toolbar.getChildAt(1).setPadding(dp(12), 0, 0, 0);
            if (!tv) {
                TextView qr = button("QR", false, this::scanTvCode);
                qr.setContentDescription("Сканировать QR для подключения ТВ");
                toolbar.addView(qr);
            }
            TextView menu = button("⋯", false, this::showAccountMenu);
            menu.setContentDescription("Меню");
            toolbar.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }
        screen.addView(toolbar);
        RecyclerView list = new RecyclerView(this);
        list.setClipToPadding(false);
        list.setPadding(dp(tv ? 38 : 16), dp(8), dp(tv ? 38 : 16), dp(24));
        screen.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1));
        Feed feed = new Feed(list, authorSlug);
        replace(screen, push);
        feed.load("");
        if (home) syncWatchProgress();
    }

    private final class Feed {
        private final LinearLayout content = column();
        private final RecyclerView list;
        private final LinearLayoutManager layout = new LinearLayoutManager(MainActivity.this);
        private final FeedRows rows = new FeedRows();
        private final String author;
        private final Set<String> seen = new HashSet<>();
        private final Set<String> requested = new HashSet<>();
        private int columns;
        private final TextView error = label("", 15, ACCENT, false);
        private final List<Post> visible = new ArrayList<>();
        private boolean loading;
        private boolean failed;
        private String next = "";
        private int generation;
        private boolean videosOnly;
        private String search = "";
        private final Set<String> selectedTags = new java.util.LinkedHashSet<>();
        private List<BoostyApi.Tag> channelTags;
        private TextView filterSummary;
        private final java.util.ArrayList<Post> loaded = new java.util.ArrayList<>();

        Feed(RecyclerView list, String author) {
            this.list = list;
            this.author = author;
            int width = getResources().getConfiguration().screenWidthDp;
            columns = columnsFor(width);
            if (author == null) {
                LinearLayout filters = row();
                TextView all = button("Все посты", true, () -> switchFilter(false));
                TextView videos = button("Видео", false, () -> switchFilter(true));
                all.setSelected(true);
                filters.addView(all);
                LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(WRAP, WRAP);
                second.leftMargin = dp(8);
                filters.addView(videos, second);
                all.setOnClickListener(view -> {
                    if (busy || !videosOnly) return;
                    all.setBackground(control(ACCENT, 0xffffc3a7, 12));
                    all.setTextColor(BG);
                    all.setSelected(true);
                    videos.setBackground(control(PANEL, PANEL_HOT, 12));
                    videos.setTextColor(INK);
                    videos.setSelected(false);
                    switchFilter(false);
                });
                videos.setOnClickListener(view -> {
                    if (busy || videosOnly) return;
                    videos.setBackground(control(ACCENT, 0xffffc3a7, 12));
                    videos.setTextColor(BG);
                    videos.setSelected(true);
                    all.setBackground(control(PANEL, PANEL_HOT, 12));
                    all.setTextColor(INK);
                    all.setSelected(false);
                    switchFilter(true);
                });
                content.addView(filters);
            } else {
                LinearLayout filters = row();
                weighted(filters, button("Поиск видео", false, this::searchChannel), 0, dp(44));
                weighted(filters, button("Теги", false, this::chooseTags), 8, dp(44));
                content.addView(filters);
                filterSummary = label("", 14, MUTED, false);
                filterSummary.setVisibility(View.GONE);
                margin(content, filterSummary, MATCH, WRAP, 6);
            }
            content.addView(error);
            error.setVisibility(View.GONE);
            list.setLayoutManager(layout);
            list.setItemAnimator(null);
            list.setAdapter(rows);
            list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                    view.post(Feed.this::maybeLoadMore);
                }
            });
            list.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft) list.post(this::resizeRows);
            });
            onAttach(list, () -> list.post(this::maybeLoadMore));
        }

        private int columnsFor(int widthDp) {
            return tv ? (widthDp >= 920 ? 4 : 3) : (widthDp >= 760 ? 3 : widthDp >= 560 ? 2 : 1);
        }

        private void resizeRows() {
            int desired = columnsFor(Math.round(list.getWidth() / getResources().getDisplayMetrics().density));
            if (desired == columns || list.getWidth() == 0) return;
            int firstRow = layout.findFirstVisibleItemPosition();
            int firstPost = Math.max(0, firstRow - 1) * columns;
            columns = desired;
            rows.notifyDataSetChanged();
            layout.scrollToPositionWithOffset(firstRow <= 0 ? 0 : Math.min(firstPost / columns + 1, rowCount()), 0);
        }

        private void searchChannel() {
            EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true);
            input.setHint("Название видео");
            input.setText(search);
            input.setSelection(input.length());
            LinearLayout form = column();
            form.setPadding(dp(20), 0, dp(20), 0);
            form.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
            new AlertDialog.Builder(MainActivity.this).setTitle("Поиск по каналу").setView(form)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Сбросить", (dialog, which) -> { search = ""; reload(); })
                .setPositiveButton("Найти", (dialog, which) -> {
                    search = input.getText().toString().trim();
                    reload();
                }).show();
        }

        private void chooseTags() {
            if (channelTags != null) showTags();
            else perform(() -> api.tags(author), error, tags -> {
                channelTags = tags;
                showTags();
            });
        }

        private void showTags() {
            if (channelTags.isEmpty()) {
                new AlertDialog.Builder(MainActivity.this).setMessage("У канала пока нет тегов.")
                    .setPositiveButton("ОК", null).show();
                return;
            }
            Set<String> draft = new java.util.LinkedHashSet<>(selectedTags);
            String[] names = channelTags.stream().map(BoostyApi.Tag::name).toArray(String[]::new);
            boolean[] checked = new boolean[names.length];
            for (int i = 0; i < names.length; i++) checked[i] = draft.contains(channelTags.get(i).id());
            new AlertDialog.Builder(MainActivity.this).setTitle("Теги канала")
                .setMultiChoiceItems(names, checked, (dialog, which, enabled) -> {
                    String id = channelTags.get(which).id();
                    if (enabled) draft.add(id);
                    else draft.remove(id);
                })
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Сбросить", (dialog, which) -> { selectedTags.clear(); reload(); })
                .setPositiveButton("Применить", (dialog, which) -> {
                    selectedTags.clear();
                    selectedTags.addAll(draft);
                    reload();
                }).show();
        }

        private void reload() {
            List<String> active = new ArrayList<>();
            if (!search.isEmpty()) active.add("Название: " + search);
            if (channelTags != null) {
                for (BoostyApi.Tag tag : channelTags) {
                    if (selectedTags.contains(tag.id())) active.add("#" + tag.name());
                }
            }
            filterSummary.setText(String.join("  ·  ", active));
            filterSummary.setVisibility(active.isEmpty() ? View.GONE : View.VISIBLE);
            generation++;
            loaded.clear();
            seen.clear();
            requested.clear();
            visible.clear();
            next = "";
            loading = false;
            failed = false;
            rows.notifyDataSetChanged();
            list.scrollToPosition(0);
            load("");
        }

        private void switchFilter(boolean value) {
            videosOnly = value;
            visible.clear();
            for (Post post : loaded) if (matches(post)) visible.add(post);
            rows.notifyDataSetChanged();
            list.scrollToPosition(0);
            list.post(this::maybeLoadMore);
        }

        private boolean matches(Post post) {
            return (search.isEmpty() || (post.hasVideo()
                && post.title().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))))
                && (!videosOnly || post.hasVideo());
        }

        private void maybeLoadMore() {
            if (!list.isAttachedToWindow() || loading || failed || next.isEmpty()) return;
            if (visible.isEmpty() || layout.findLastVisibleItemPosition() >= rowCount() - 1) load(next);
        }

        private int rowCount() {
            return (visible.size() + columns - 1) / columns;
        }

        void load(String offset) {
            if (loading || !requested.add(offset)) return;
            loading = true;
            failed = false;
            rows.notifyItemChanged(rowCount() + 1);
            int requestGeneration = generation;
            String query = search;
            String tags = String.join(",", selectedTags);
            readInBackground(() -> api.posts(author, offset, query, tags), page -> {
                if (requestGeneration != generation) return;
                int previousSize = visible.size();
                int previousRows = rowCount();
                for (Post post : page.posts()) {
                    if (seen.add(post.id())) {
                        loaded.add(post);
                        if (matches(post)) visible.add(post);
                    }
                }
                next = requested.contains(page.next()) ? "" : page.next();
                loading = false;
                int count = rowCount();
                if (count > previousRows) rows.notifyItemRangeInserted(previousRows + 1, count - previousRows);
                if (visible.size() > previousSize && previousSize % columns != 0) {
                    rows.notifyItemChanged(previousRows);
                }
                rows.notifyItemChanged(count + 1);
                list.post(this::maybeLoadMore);
            }, failure -> {
                if (requestGeneration != generation) return;
                requested.remove(offset);
                next = offset;
                loading = false;
                failed = true;
                rows.notifyItemChanged(rowCount() + 1);
            });
        }

        private final class FeedRows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            @Override public int getItemCount() { return rowCount() + 2; }

            @Override public int getItemViewType(int position) {
                if (position == 0) return 0;
                return position == rowCount() + 1 ? 2 : 1;
            }

            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                View item;
                if (type == 0) item = content;
                else if (type == 1) {
                    LinearLayout line = row();
                    line.setPadding(0, dp(10), 0, 0);
                    item = line;
                } else item = button("", false, () -> { if (failed) load(next); });
                item.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));
                return new RecyclerView.ViewHolder(item) {};
            }

            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                if (position == 0) return;
                if (getItemViewType(position) == 2) {
                    TextView footer = (TextView) holder.itemView;
                    footer.setText(failed ? "Не удалось загрузить. Повторить" : loading ? "Загрузка…"
                        : !next.isEmpty() ? "Загрузка публикаций…" : "Подходящих публикаций нет.");
                    footer.setVisibility(failed || loading || !next.isEmpty() || visible.isEmpty() ? View.VISIBLE : View.GONE);
                    footer.setFocusable(failed);
                    footer.setClickable(failed);
                    return;
                }
                LinearLayout line = (LinearLayout) holder.itemView;
                line.removeAllViews();
                line.setWeightSum(columns);
                int start = (position - 1) * columns;
                for (int i = start; i < Math.min(start + columns, visible.size()); i++) {
                    weighted(line, postCard(visible.get(i)), i == start ? 0 : 8, WRAP);
                }
            }
        }
    }

    private final class VideoPreview extends FrameLayout {
        VideoPreview() { super(MainActivity.this); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            int height = Math.min(Math.round(width * 9f / 16), dp(tv ? 420 : 320));
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        }
    }

    private View postCard(Post post) {
        LinearLayout card = column();
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setBackground(control(PANEL, PANEL_HOT, 14));
        card.setClipToOutline(true);
        FrameLayout cover = new VideoPreview();
        cover.setBackground(shape(0xff302633, 12, 0));
        cover.setClipToOutline(true);
        if (post.cover().isEmpty()) {
            TextView placeholder = label(post.hasVideo() ? "▶" : "▤", 35, ACCENT, true);
            placeholder.setGravity(Gravity.CENTER);
            cover.addView(placeholder, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            cover.addView(image(post.cover(), ImageView.ScaleType.FIT_CENTER), new FrameLayout.LayoutParams(MATCH, MATCH));
            if (post.hasVideo()) {
                TextView play = label("▶", 15, INK, true);
                play.setGravity(Gravity.CENTER);
                play.setBackground(shape(0xe61a141b, 10, 0));
                FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(dp(38), dp(32), Gravity.BOTTOM | Gravity.END);
                corner.setMargins(0, 0, dp(8), dp(8));
                cover.addView(play, corner);
            }
        }
        if (post.hasVideo()) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff382d36));
            cover.addView(progress, new FrameLayout.LayoutParams(MATCH, dp(5), Gravity.BOTTOM));
            onAttach(progress, () -> {
                int percent = post.blocks().stream().filter(block -> !block.sources().isEmpty())
                    .mapToInt(block -> watchHistory.get(block).percentage()).max().orElse(0);
                progress.setProgress(percent);
                progress.setContentDescription("Просмотрено " + percent + "%");
                progress.setVisibility(percent == 0 ? View.GONE : View.VISIBLE);
            });
        }
        card.addView(cover, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout meta = column();
        meta.setPadding(dp(10), dp(9), dp(10), dp(9));
        TextView author = label(post.author().isEmpty() ? "Boosty" : post.author(), 12, ACCENT, true);
        author.setSingleLine(true);
        author.setEllipsize(TextUtils.TruncateAt.END);
        TextView title = label(post.title(), tv ? 16 : 15, INK, true);
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title);
        margin(meta, author, MATCH, WRAP, 4);
        card.addView(meta);
        card.setContentDescription(post.title() + ", " + author.getText()
            + (post.hasVideo() ? ", видео" : ""));
        makeAction(card, () -> showPost(post));
        return card;
    }

    private void showAuthors() {
        LinearLayout screen = column();
        screen.addView(toolbar("Подписки", true));
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(tv ? 38 : 16), dp(8), dp(tv ? 38 : 16), dp(24));
        LinearLayout authors = column();
        scroll.addView(authors);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        TextView error = label("", 15, ACCENT, false);
        error.setVisibility(View.GONE);
        authors.addView(error);
        replace(screen, true);
        loadAuthors(authors, error, 0);
    }

    private void loadAuthors(LinearLayout authors, TextView error, int offset) {
        TextView more = button("Загрузка…", false, () -> {});
        margin(authors, more, MATCH, dp(48), 8);
        more.setOnClickListener(view -> {
            if (busy) return;
            authors.removeView(more);
            loadAuthors(authors, error, offset);
        });
        perform(() -> api.authors(offset), error, page -> {
            boolean focused = more.hasFocus();
            authors.removeView(more);
            View first = null;
            for (BoostyApi.Author author : page.items()) {
                LinearLayout item = row();
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(12), 0, dp(12), 0);
                item.setBackground(control(PANEL, PANEL_HOT, 12));
                ImageView avatar = image(author.image(), ImageView.ScaleType.CENTER_CROP);
                avatar.setBackground(shape(0xff332b38, 23, 0));
                avatar.setClipToOutline(true);
                item.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
                TextView name = label(author.name(), 17, INK, true);
                name.setPadding(dp(14), 0, dp(14), 0);
                item.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
                item.addView(label("›", 26, ACCENT, false));
                makeAction(item, () -> showFeed(author.slug(), author.name(), true));
                margin(authors, item, MATCH, dp(64), 8);
                if (first == null) first = item;
            }
            if (page.next() >= 0) {
                TextView next = button("Загрузить ещё", false, () -> {});
                next.setOnClickListener(view -> {
                    if (busy) return;
                    authors.removeView(next);
                    loadAuthors(authors, error, page.next());
                });
                margin(authors, next, MATCH, dp(48), 10);
                if (first == null) first = next;
            } else if (offset == 0 && first == null) {
                margin(authors, label("У вас пока нет подписок.", 17, MUTED, false), MATCH, WRAP, 10);
            }
            if (tv && (offset == 0 || focused) && first != null) first.requestFocus();
        }, () -> more.setText("Повторить"));
    }

    private void showPost(Post post) {
        LinearLayout screen = column();
        LinearLayout bar = toolbar("Публикация", true);
        screen.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout article = column();
        int horizontal = tv ? Math.max(38, (getResources().getConfiguration().screenWidthDp - 760) / 2) : 20;
        article.setPadding(dp(horizontal), dp(10), dp(horizontal), dp(30));
        scroll.addView(article);
        screen.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        String subtitle = post.author();
        if (post.published() > 0) {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag("ru"))
                .format(new Date(post.published() * 1000L));
            subtitle = subtitle.isEmpty() ? date : subtitle + "  ·  " + date;
        }
        article.addView(label(subtitle, 15, ACCENT, true));
        margin(article, label(post.title(), tv ? 33 : 28, INK, true), MATCH, WRAP, 8);
        if (!post.allowed()) {
            margin(article, label("Для этой публикации нужен доступ на Boosty.", 16, ACCENT, false), MATCH, WRAP, 16);
        }
        View firstVideo = null;
        for (Post.Block block : post.blocks()) {
            switch (block.kind()) {
                case "text" -> {
                    TextView body = label(block.text(), tv ? 18 : 16, INK, false);
                    body.setLineSpacing(dp(4), 1f);
                    margin(article, body, MATCH, WRAP, 14);
                }
                case "image" -> {
                    ImageView photo = image(block.image(), ImageView.ScaleType.FIT_CENTER);
                    photo.setBackground(shape(PANEL, 12, 0));
                    photo.setClipToOutline(true);
                    margin(article, photo, MATCH, dp(tv ? 290 : 230), 14);
                }
                case "video" -> {
                    if (!block.image().isEmpty()) {
                        FrameLayout preview = new VideoPreview();
                        preview.addView(image(block.image(), ImageView.ScaleType.FIT_CENTER),
                            new FrameLayout.LayoutParams(MATCH, MATCH));
                        preview.setBackground(shape(PANEL, 12, 0));
                        preview.setClipToOutline(true);
                        margin(article, preview, MATCH, WRAP, 14);
                    }
                    if (block.sources().isEmpty()) {
                        margin(article, label("Видео пока недоступно.", 15, MUTED, false), MATCH, WRAP, 8);
                    } else {
                        String name = block.text().isEmpty() ? "▶  Смотреть видео" : "▶  " + block.text();
                        TextView play = button(name, true, () -> openPlayer(block));
                        onAttach(play, () -> {
                            long position = watchHistory.get(block).resume();
                            play.setText(position > 0 ? "▶  Продолжить с " + timestamp(position) : name);
                        });
                        margin(article, play, MATCH, dp(49), 8);
                        if (firstVideo == null) firstVideo = play;
                    }
                }
            }
        }
        if (post.allowed() && !post.reactionsDisabled() && !post.slug().isEmpty()) {
            addReactions(article, post, "", post.reactions());
        }
        boolean hasComments = post.allowed() && !post.commentsDenied() && !post.slug().isEmpty();
        LinearLayout comments = column();
        if (hasComments) {
            TextView title = label("Комментарии", 22, INK, true);
            title.setFocusable(tv);
            comments.addView(title);
            margin(article, comments, MATCH, WRAP, 22);
            bar.addView(button("Комментарии", false, () -> {
                if (tv) title.requestFocus();
                scroll.smoothScrollTo(0, comments.getTop());
            }));
        }
        replace(screen, true);
        if (tv && firstVideo != null) firstVideo.requestFocus();
        else if (tv) scroll.requestFocus();
        if (hasComments) loadComments(comments, post, 0, "");
    }

    private void loadComments(LinearLayout list, Post post, long parent, String offset) {
        TextView more = button("Загрузка…", false, () -> {});
        more.setClickable(false);
        more.setFocusable(false);
        margin(list, more, MATCH, dp(48), 10);
        readInBackground(() -> api.comments(post, parent, offset), page -> {
            list.removeView(more);
            for (BoostyApi.Comment comment : page.items()) {
                renderComment(list, post, comment, parent == 0);
            }
            if (!page.next().isEmpty()) {
                TextView next = button("Загрузить ещё", false, () -> {});
                next.setOnClickListener(view -> {
                    if (busy) return;
                    list.removeView(next);
                    loadComments(list, post, parent, page.next());
                });
                margin(list, next, MATCH, dp(48), 10);
            } else if (page.items().isEmpty() && offset.isEmpty()) {
                margin(list, label(parent == 0 ? "Комментариев пока нет." : "Ответов пока нет.",
                    16, MUTED, false), MATCH, WRAP, 8);
            }
        }, failure -> {
            more.setText("Не удалось загрузить. Повторить");
            makeAction(more, () -> {
                list.removeView(more);
                loadComments(list, post, parent, offset);
            });
        });
    }

    private void renderComment(LinearLayout list, Post post, BoostyApi.Comment comment, boolean showReplies) {
        LinearLayout item = column();
        item.setFocusable(tv);
        item.setBackground(control(PANEL, PANEL_HOT, 12));
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        String author = comment.author().isEmpty() ? "Пользователь Boosty" : comment.author();
        if (comment.created() > 0) {
            author += "  ·  " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag("ru"))
                .format(new Date(comment.created() * 1000L));
        }
        item.addView(label(author, 14, ACCENT, true));
        for (Post.Block block : comment.blocks()) {
            if (block.kind().equals("text")) {
                margin(item, label(block.text(), 16, INK, false), MATCH, WRAP, 7);
            } else if (block.kind().equals("image")) {
                margin(item, image(block.image(), ImageView.ScaleType.FIT_CENTER), MATCH, dp(140), 7);
            }
        }
        if (!post.reactionsDisabled() && !comment.reactionId().isEmpty()) {
            addReactions(item, post, comment.reactionId(), comment.reactions());
        }
        if (showReplies && comment.id() > 0 && comment.replyCount() > 0) {
            LinearLayout replies = column();
            replies.setPadding(dp(12), 0, 0, 0);
            replies.setVisibility(View.GONE);
            String expand = "Ответы (" + comment.replyCount() + ")";
            TextView toggle = button(expand, false, () -> {});
            makeAction(toggle, () -> {
                boolean opening = replies.getVisibility() != View.VISIBLE;
                replies.setVisibility(opening ? View.VISIBLE : View.GONE);
                toggle.setText(opening ? "Свернуть ответы" : expand);
                if (opening && replies.getChildCount() == 0) loadComments(replies, post, comment.id(), "");
            });
            margin(item, toggle, WRAP, dp(42), 8);
            item.addView(replies);
        }
        margin(list, item, MATCH, WRAP, 8);
    }

    private void addReactions(LinearLayout section, Post post, String comment, Reactions initial) {
        ReactionTarget target = new ReactionTarget(post.id(), comment);
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView change = button("Реакция", false, () -> {});
        line.addView(change, new LinearLayout.LayoutParams(WRAP, dp(42)));
        TextView counts = label("", 15, MUTED, false);
        counts.setPadding(dp(12), 0, 0, 0);
        line.addView(counts, new LinearLayout.LayoutParams(0, WRAP, 1));
        Runnable redraw = () -> {
            Reactions state = localReactions.getOrDefault(target, initial);
            List<String> totals = new ArrayList<>();
            String selected = "Реакция";
            for (Reactions.Type type : Reactions.Type.values()) {
                int count = state.count(type);
                if (count > 0) totals.add(type.emoji + " " + count);
                if (type.code.equals(state.selected())) selected = type.emoji + " " + type.label;
            }
            change.setText(selected);
            counts.setText(String.join("   ", totals));
            change.setEnabled(!pendingReactions.contains(target));
        };
        redraw.run();
        onAttach(change, redraw);
        makeAction(change, () -> {
            if (!pendingReactions.add(target)) return;
            redraw.run();
            readInBackground(() -> api.reactionTypes(post.slug()), types -> {
                pendingReactions.remove(target);
                refreshPlaybackLabels(host);
                if (change.isAttachedToWindow()) showReactionPicker(post, comment, initial, target, types, redraw);
            }, failure -> {
                pendingReactions.remove(target);
                refreshPlaybackLabels(host);
                Toast.makeText(this, "Не удалось загрузить реакции канала.", Toast.LENGTH_LONG).show();
            });
        });
        margin(section, line, MATCH, WRAP, 10);
    }

    private void showReactionPicker(Post post, String comment, Reactions initial, ReactionTarget target,
                                    List<Reactions.Type> types, Runnable redraw) {
        Reactions state = localReactions.getOrDefault(target, initial);
        if (types.isEmpty()) {
            Toast.makeText(this, "Реакции отключены автором.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = new String[types.size() + 1];
        int checked = -1;
        for (int i = 0; i < types.size(); i++) {
            Reactions.Type type = types.get(i);
            options[i] = type.emoji + "  " + type.label;
            if (type.code.equals(state.selected())) checked = i;
        }
        options[types.size()] = "Убрать реакцию";
        new AlertDialog.Builder(this).setTitle("Реакция")
            .setSingleChoiceItems(options, checked, (dialog, index) -> {
                dialog.dismiss();
                String selected = index == types.size() ? "" : types.get(index).code;
                if (index < types.size() && selected.equals(state.selected())) selected = "";
                String choice = selected;
                if (!pendingReactions.add(target)) return;
                redraw.run();
                readInBackground(() -> { api.setReaction(post, comment, choice); return true; }, done -> {
                    localReactions.put(target, state.withSelected(choice));
                    pendingReactions.remove(target);
                    redraw.run();
                    refreshPlaybackLabels(host);
                }, failure -> {
                    pendingReactions.remove(target);
                    redraw.run();
                    refreshPlaybackLabels(host);
                    Toast.makeText(this, "Boosty не сохранил реакцию. Попробуйте ещё раз.", Toast.LENGTH_LONG).show();
                });
            }).setNegativeButton("Отмена", null).show();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void openPlayer(Post.Block block) {
        PlaybackOptions.App app = playbackOptions.player();
        if (app != PlaybackOptions.App.BUILT_IN && launchExternal(block, app)) return;
        video = block;
        playbackSources = playbackOptions.sources(block);
        sourceIndex = 0;
        playbackPosition = watchHistory.get(block).resume();
        lastWrittenTime = -1;
        playbackStarted = false;
        resumePlayback = true;
        FrameLayout screen = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return handlePlayerKey(event) || super.dispatchKeyEvent(event);
            }
        };
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setControllerShowTimeoutMs(tv ? 8000 : 5000);
        playerView.setControllerAnimationEnabled(!tv);
        playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true);
        screen.addView(playerView, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView settings = button("⋯  Видео", false, this::showVideoMenu);
        settings.setContentDescription("Управление видео");
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(WRAP, dp(44), Gravity.TOP | Gravity.END);
        top.setMargins(0, dp(16), dp(18), 0);
        screen.addView(settings, top);
        PlayerView controls = playerView;
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            settings.setVisibility(visibility);
            if (tv && visibility != View.VISIBLE && controls.isAttachedToWindow()) controls.requestFocus();
        });
        replace(screen, true);
        fullscreen(true);
        startPlayer();
        playerView.showController();
        if (tv) playerView.requestFocus();
    }

    private boolean launchExternal(Post.Block block, PlaybackOptions.App app) {
        Post.Source source = playbackOptions.sources(block).get(0);
        long position = watchHistory.get(block).resume();
        Intent intent = app.intent(source, block.text(), position);
        if (app == PlaybackOptions.App.VIMU && intent.resolveActivity(getPackageManager()) == null) {
            intent.setPackage(PlaybackOptions.VIMU_RUSTORE);
        }
        try {
            if (app == PlaybackOptions.App.JUST_PLUS) {
                JustPlusPlayback.configure(this, watchHistory, intent, block, position);
                startActivity(intent);
            } else if (app == PlaybackOptions.App.OTHER) {
                if (getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                    throw new ActivityNotFoundException();
                }
                startActivity(Intent.createChooser(intent, "Открыть видео в плеере"));
            } else {
                externalVideo = block;
                externalApp = app;
                externalPlayer.launch(intent);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            externalVideo = null;
            externalApp = null;
            Toast.makeText(this, "Сторонний плеер не найден. Открываю встроенный.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void startPlayer() {
        if (video == null || player != null) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", BoostyApi.ORIGIN);
        headers.put("Referer", BoostyApi.ORIGIN + "/");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build();
        player.setSeekParameters(SeekParameters.EXACT);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true);
        playerView.setPlayer(player);
        mediaSession = new MediaSession.Builder(this, player).build();
        applyQualityLimit();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) playbackStarted = true;
                else if (state == Player.STATE_ENDED) savePlayback();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing && playerView.isControllerFullyVisible()) playerView.showController();
                else if (!playing && player != null && player.getPlaybackState() != Player.STATE_BUFFERING) savePlayback();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (video == null || player == null) return;
                if (playbackStarted) playbackPosition = player.getCurrentPosition();
                resumePlayback = player.getPlayWhenReady();
                if (++sourceIndex < playbackSources.size()) {
                    prepareSource();
                } else {
                    sourceIndex = playbackSources.size() - 1;
                    new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Не удалось воспроизвести видео. Обновите ленту и откройте публикацию снова.")
                        .setPositiveButton("К публикации", (dialog, which) -> goBack())
                        .setOnCancelListener(dialog -> goBack()).show();
                }
            }
        });
        prepareSource();
        handler.postDelayed(savePlaybackTick, 15_000);
    }

    private void applyQualityLimit() {
        int height = PlaybackOptions.HEIGHTS[playbackOptions.qualityIndex()];
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setMaxVideoSize(Integer.MAX_VALUE, height == 0 ? Integer.MAX_VALUE : height).build());
    }

    private void prepareSource() {
        Post.Source source = playbackSources.get(sourceIndex);
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.url());
        if (source.type().contains("hls")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (source.type().contains("dash")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaItem(item.build(), playbackPosition);
        player.setPlayWhenReady(resumePlayback);
        player.prepare();
    }

    private void releasePlayer() {
        if (player == null) return;
        handler.removeCallbacks(savePlaybackTick);
        savePlayback();
        if (playbackStarted) playbackPosition = player.getCurrentPosition();
        resumePlayback = player.getPlayWhenReady();
        playerView.setPlayer(null);
        mediaSession.release();
        mediaSession = null;
        player.release();
        player = null;
    }

    private void savePlayback() {
        if (player == null || video == null || !playbackStarted) return;
        long duration = player.getDuration() > 0 ? player.getDuration() : video.durationMs();
        long position = player.getPlaybackState() == Player.STATE_ENDED && duration > 0
            ? duration : player.getCurrentPosition();
        long seconds = position / 1000;
        if (seconds == lastWrittenTime || (seconds == 0 && lastWrittenTime < 0)) return;
        lastWrittenTime = seconds;
        recordProgress(video, position, duration);
    }

    private void recordProgress(Post.Block item, long position, long duration) {
        if (item.videoId().isEmpty()) return;
        watchHistory.save(item, position, duration);
        syncWatchProgress();
    }

    private void syncWatchProgress() {
        if (!api.hasSession() || network.isShutdown() || !syncingProgress.compareAndSet(false, true)) return;
        network.execute(() -> {
            boolean sentAll = false;
            try {
                for (WatchHistory.Pending item : watchHistory.pending()) {
                    api.updateTimecode(item.id(), item.position() / 1000);
                    watchHistory.confirmSync(item);
                }
                sentAll = true;
            } catch (Exception ignored) {
                // Keep unsent bookmarks for the next playback update or feed refresh.
            } finally {
                syncingProgress.set(false);
                if (sentAll && !watchHistory.pending().isEmpty()) handler.post(this::syncWatchProgress);
            }
        });
    }

    private void showVideoMenu() {
        if (player == null) return;
        new AlertDialog.Builder(this).setTitle("Видео")
            .setItems(new String[]{"Перейти ко времени", "Качество видео", "С начала"}, (dialog, item) -> {
                if (player == null) return;
                if (item == 0) showSeekDialog();
                else if (item == 1) chooseQuality(true);
                else seekPlayback(0);
            }).show();
    }

    private void showSeekDialog() {
        if (player == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("ЧЧ:ММ:СС");
        input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(timestamp(player.getCurrentPosition()));
        input.selectAll();
        LinearLayout field = column();
        field.setPadding(dp(20), 0, dp(20), 0);
        field.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Перейти ко времени").setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Перейти", null).create();
        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (player == null) { dialog.dismiss(); return; }
            long time = parseTimestamp(input.getText().toString());
            if (time < 0 || (player.getDuration() > 0 && time > player.getDuration())) {
                input.setError("Введите время в пределах видео.");
                return;
            }
            seekPlayback(time);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void seekPlayback(long time) {
        player.seekTo(time);
        if (time == 0 && playbackStarted && watchHistory.get(video).position() > 0) {
            lastWrittenTime = 0;
            recordProgress(video, 0, player.getDuration() > 0 ? player.getDuration() : video.durationMs());
        }
    }

    static long parseTimestamp(String value) {
        String[] fields = value.trim().split(":", -1);
        if (fields.length > 3) return -1;
        long seconds = 0;
        for (int i = 0; i < fields.length; i++) {
            if (!fields[i].matches("[0-9]{1,6}")) return -1;
            int number = Integer.parseInt(fields[i]);
            if (i > 0 && number >= 60) return -1;
            seconds = seconds * 60 + number;
        }
        return seconds * 1000;
    }

    private static String timestamp(long position) {
        long seconds = Math.max(0, position / 1000);
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private boolean handlePlayerKey(KeyEvent event) {
        if (player == null || playerView == null) return false;
        int code = event.getKeyCode();
        if (code == revealedByKey && event.getAction() == KeyEvent.ACTION_UP) {
            revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showVideoMenu();
            return true;
        }
        if (playerView.dispatchMediaKeyEvent(event)) {
            playerView.showController();
            return true;
        }
        boolean direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (direction && event.getAction() == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible()) {
            revealedByKey = code;
            playerView.showController();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack();
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) player.seekForward();
            return true;
        }
        return false;
    }

    private void fullscreen(boolean active) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (active) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else controller.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(active
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        }
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onStart() {
        super.onStart();
        if (video != null) startPlayer();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPlaybackLabels(host);
        syncWatchProgress();
    }

    @Override protected void onStop() {
        if (pairDialog != null) pairDialog.dismiss();
        releasePlayer();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (externalVideo != null && externalApp != null) {
            out.putString("external_player", externalApp.name());
            out.putString("external_video", externalVideo.videoId());
            out.putLong("external_duration", externalVideo.durationMs());
        }
    }

    @Override protected void onDestroy() {
        if (webLogin != null) webLogin.close(false);
        handler.removeCallbacks(savePlaybackTick);
        watchHistory.stopListening(progressChanged);
        network.shutdown();
        artwork.close();
        super.onDestroy();
    }

    private boolean goBack() {
        if (webLogin != null) {
            if (webLogin.closePopup()) return true;
            webLogin.close(true);
            webLogin = null;
        }
        if (pairReturn != null) {
            if (challenge == null) finishPhonePair();
            else {
                challenge = null;
                showLogin();
            }
            return true;
        }
        if (video != null) {
            releasePlayer();
            video = null;
            playerView = null;
            playbackSources = null;
            revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
            fullscreen(false);
        }
        if (!history.isEmpty()) {
            View previous = history.pop();
            replace(previous, false);
            if (tv && previous.getTag() instanceof View focus) focus.requestFocus();
            return true;
        }
        if (challenge != null) {
            challenge = null;
            showLogin();
            return true;
        }
        return false;
    }

    private void showAccountMenu() {
        if (busy) return;
        new AlertDialog.Builder(this)
            .setItems(new String[]{"Обновить ленту", "Настройки", "О программе", "Выйти из аккаунта"}, (dialog, item) -> {
                if (item == 0) {
                    showFeed(null, null, false);
                } else if (item == 1) {
                    showSettings();
                } else if (item == 2) {
                    showAbout();
                } else {
                    new AlertDialog.Builder(this).setMessage("Выйти из аккаунта Boosty на этом устройстве?")
                        .setNegativeButton("Отмена", null).setPositiveButton("Выйти", (confirm, button) -> {
                            api.signOut();
                            clearSessionScreen();
                        }).show();
                }
            }).show();
    }

    private boolean updatingApp;

    private void showAbout() {
        LinearLayout screen = column();
        screen.addView(toolbar("О программе", true));
        LinearLayout content = column();
        content.setPadding(dp(tv ? 38 : 16), dp(12), dp(tv ? 38 : 16), 0);
        content.addView(label("Boosty ATV  " + BuildConfig.VERSION_NAME, 24, INK, true));
        margin(content, label("Автор проекта: Sergey Molchanov (cergo666)", 16, INK, false), MATCH, WRAP, 12);
        TextView status = label("", 15, MUTED, false);
        margin(content, button("Проверить обновления", true, () -> checkAppUpdate(status)), MATCH, dp(50), 16);
        margin(content, button("Проект на GitHub", false, () -> openProjectLink(AppRelease.PROJECT)), MATCH, dp(48), 8);
        margin(content, status, MATCH, WRAP, 10);
        screen.addView(content);
        replace(screen, true);
    }

    private void checkAppUpdate(TextView status) {
        if (updatingApp) return;
        updatingApp = true;
        status.setText("Проверяем обновления…");
        readInBackground(AppRelease::latest, release -> {
            updatingApp = false;
            if (!status.isAttachedToWindow()) return;
            if (!release.newerThan(BuildConfig.VERSION_NAME)) {
                status.setText("Установлена последняя версия.");
                return;
            }
            status.setText("Доступна версия " + release.version());
            new AlertDialog.Builder(this).setTitle("Boosty ATV " + release.version())
                .setMessage(release.notes().isBlank() ? "Описание изменений отсутствует." : release.notes())
                .setNegativeButton("Позже", null)
                .setPositiveButton("Скачать и установить", (dialog, which) -> installAppUpdate(release, status))
                .show();
        }, failure -> {
            updatingApp = false;
            status.setText("Не удалось проверить обновления. Попробуйте ещё раз.");
        });
    }

    private void installAppUpdate(AppRelease release, TextView status) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("Разрешите установку для Boosty ATV, затем снова проверьте обновления.");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                status.setText("Установка обновления недоступна. APK можно скачать на GitHub.");
            }
            return;
        }
        updatingApp = true;
        status.setText("Скачиваем обновление…");
        readInBackground(() -> release.download(getCacheDir()), apk -> {
            updatingApp = false;
            if (!status.isAttachedToWindow()) return;
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                status.setText("Подтвердите установку в Android.");
            } catch (ActivityNotFoundException e) {
                status.setText("Системный установщик недоступен. APK можно скачать на GitHub.");
            }
        }, failure -> {
            updatingApp = false;
            status.setText("Не удалось скачать APK. Попробуйте ещё раз.");
        });
    }

    private void openProjectLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void showSettings() {
        LinearLayout screen = column();
        screen.addView(toolbar("Настройки", true));
        LinearLayout options = column();
        options.setPadding(dp(tv ? 38 : 16), dp(8), dp(tv ? 38 : 16), 0);
        TextView quality = button("", false, () -> chooseQuality(false));
        TextView playerChoice = button("", false, this::choosePlayer);
        onAttach(quality, () -> quality.setText("Качество видео  ·  " + PlaybackOptions.QUALITY[playbackOptions.qualityIndex()]));
        onAttach(playerChoice, () -> playerChoice.setText("Плеер  ·  " + playbackOptions.player().label));
        margin(options, quality, MATCH, dp(52), 8);
        margin(options, playerChoice, MATCH, dp(52), 8);
        if (!tv) margin(options, button("Подключить ТВ", false, this::scanTvCode), MATCH, dp(52), 8);
        screen.addView(options);
        replace(screen, true);
        if (tv) quality.requestFocus();
    }

    private void chooseQuality(boolean playing) {
        new AlertDialog.Builder(this).setTitle("Качество видео")
            .setSingleChoiceItems(PlaybackOptions.QUALITY, playbackOptions.qualityIndex(), (dialog, index) -> {
                playbackOptions.setQuality(index);
                dialog.dismiss();
                if (playing && player != null) {
                    playbackPosition = player.getCurrentPosition();
                    resumePlayback = player.getPlayWhenReady();
                    playbackSources = playbackOptions.sources(video);
                    sourceIndex = 0;
                    applyQualityLimit();
                    prepareSource();
                } else updateSettingLabel(0, "Качество видео  ·  " + PlaybackOptions.QUALITY[index]);
            }).setNegativeButton("Отмена", null).show();
    }

    private void choosePlayer() {
        PlaybackOptions.App[] apps = PlaybackOptions.App.values();
        String[] labels = java.util.Arrays.stream(apps).map(app -> app.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Плеер")
            .setSingleChoiceItems(labels, playbackOptions.player().ordinal(), (dialog, index) -> {
                playbackOptions.setPlayer(apps[index]);
                updateSettingLabel(1, "Плеер  ·  " + apps[index].label);
                dialog.dismiss();
            }).setNegativeButton("Отмена", null).show();
    }

    private void updateSettingLabel(int index, String text) {
        LinearLayout screen = (LinearLayout) host.getChildAt(0);
        LinearLayout options = (LinearLayout) screen.getChildAt(1);
        ((TextView) options.getChildAt(index)).setText(text);
    }

    private static void onAttach(View view, Runnable action) {
        view.setTag(R.id.playback_refresh, action);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { action.run(); }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
    }

    private static void refreshPlaybackLabels(View view) {
        if (view.getTag(R.id.playback_refresh) instanceof Runnable refresh) refresh.run();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) refreshPlaybackLabels(group.getChildAt(i));
        }
    }

    private <T> void perform(Callable<T> request, TextView error, Consumer<T> done) {
        perform(request, error, done, () -> {});
    }

    private <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed) {
        if (isDestroyed() || isFinishing() || network.isShutdown()) return;
        int epoch = sessionEpoch;
        network.execute(() -> {
            try {
                T value = request.call();
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && epoch == sessionEpoch) done.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    if (failure instanceof BoostyApi.SignedOut && !api.hasSession()) {
                        clearSessionScreen();
                        Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show();
                    } else failed.accept(failure);
                });
            }
        });
    }

    private void clearSessionScreen() {
        updatingApp = false;
        if (video != null) goBack();
        sessionEpoch++;
        busy = false;
        history.clear();
        watchHistory.clear();
        localReactions.clear();
        pendingReactions.clear();
        showLogin();
    }

    private <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed) {
        if (busy) return;
        int epoch = sessionEpoch;
        busy = true;
        error.setVisibility(View.GONE);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams indicator = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
        indicator.setMargins(0, dp(8), dp(8), 0);
        host.addView(progress, indicator);
        network.execute(() -> {
            try {
                T value = request.call();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    host.removeView(progress);
                    busy = false;
                    done.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    host.removeView(progress);
                    busy = false;
                    failed.run();
                    if (failure instanceof BoostyApi.SignedOut) {
                        clearSessionScreen();
                        Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show();
                    } else {
                        showError(error, failure instanceof BoostyApi.Problem ? failure.getMessage()
                            : "Не удалось получить ответ Boosty. Проверьте соединение и повторите.");
                    }
                });
            }
        });
    }

    private void replace(View view, boolean push) {
        if (push && host.getChildCount() != 0) {
            View previous = host.getChildAt(0);
            previous.setTag(previous.findFocus());
            history.push(previous);
        }
        host.removeAllViews();
        host.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        back.setEnabled(!history.isEmpty() || challenge != null || pairReturn != null);
    }

    private LinearLayout toolbar(String title, boolean back) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(tv ? 38 : 16), dp(8), dp(tv ? 38 : 12), dp(7));
        if (back) {
            TextView arrow = button("‹", false, () -> { if (!busy) goBack(); });
            arrow.setContentDescription("Назад");
            bar.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView heading = label(title, tv ? 32 : 27, INK, true);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        if (back) heading.setPadding(dp(12), 0, 0, 0);
        bar.addView(heading, new LinearLayout.LayoutParams(0, WRAP, 1));
        return bar;
    }

    private TextView button(String title, boolean primary, Runnable action) {
        TextView text = label(title, 15, primary ? BG : INK, true);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(10), 0, dp(10), 0);
        text.setMinHeight(dp(42));
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setBackground(primary ? control(ACCENT, 0xffffc3a7, 12) : control(PANEL, PANEL_HOT, 12));
        makeAction(text, action);
        return text;
    }

    private void makeAction(View view, Runnable action) {
        view.setFocusable(true);
        view.setOnClickListener(clicked -> { if (!busy) action.run(); });
    }

    private ImageView image(String url, ImageView.ScaleType scale) {
        ImageView view = new ImageView(this);
        view.setScaleType(scale);
        artwork.bind(view, url);
        return view;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        return new LinearLayout(this);
    }

    private void margin(LinearLayout parent, View child, int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private void weighted(LinearLayout parent, View child, int left, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
        params.leftMargin = dp(left);
        parent.addView(child, params);
    }

    private StateListDrawable control(int normal, int focused, int radius) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, shape(focused, radius, INK));
        result.addState(new int[]{android.R.attr.state_pressed}, shape(focused, radius, ACCENT));
        result.addState(new int[]{}, shape(normal, radius, 0));
        return result;
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(2), stroke);
        return result;
    }

    private void hideKeyboard(View view) {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private static void showError(TextView view, String text) {
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

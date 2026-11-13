package ru.boostyatv.client;

import android.content.Context;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Sign-in by phone, QR pairing and the Boosty web login. */
final class LoginScreen implements PairFlow.Host, CountryPicker.Host {
    interface Host {
        Ui ui();
        BoostyApi api();
        boolean isTv();
        boolean busy();
        View currentScreen();
        void replace(View view, boolean push);
        void showFeed(String slug, String name, boolean push);
        void back();
        void onSignedIn();
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done);
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed);
    }

    private final ComponentActivity activity;
    private final Host host;
    private final Ui ui;
    private final ActivityResultLauncher<ScanOptions> scanTv;

    private BoostyApi.Challenge challenge;
    private final PairFlow pairs;
    private BoostyWebLogin webLogin;
    private String phoneDraft = "";
    private List<PhoneCountry> phoneCountries;
    private PhoneCountry phoneCountry;
    private long resendAfter;

    LoginScreen(ComponentActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.pairs = new PairFlow(this);
        scanTv = activity.registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) pairs.openInvitation(result.getContents());
        });
    }

    void show() {
        boolean tv = host.isTv();
        final boolean confirming = challenge != null;
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout outer = ui.column();
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(ui.dp(tv ? 42 : 22), ui.dp(tv ? 20 : 24), ui.dp(tv ? 42 : 22), ui.dp(24));
        scroll.addView(outer);

        LinearLayout layout = tv ? ui.row() : ui.column();
        layout.setGravity(Gravity.CENTER_VERTICAL);
        int screenWidth = activity.getResources().getConfiguration().screenWidthDp;
        int maxWidth = tv ? 1020 : 490;
        int available = screenWidth - (tv ? 84 : 44);
        outer.addView(layout, new LinearLayout.LayoutParams(
            available > maxWidth ? ui.dp(maxWidth) : Ui.MATCH, Ui.WRAP));
        LinearLayout intro = ui.column();
        LinearLayout form = ui.column();
        if (tv) {
            layout.addView(intro, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            LinearLayout.LayoutParams panel = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
            panel.leftMargin = ui.dp(32);
            layout.addView(form, panel);
        } else {
            layout.addView(intro, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            ui.margin(layout, form, Ui.MATCH, Ui.WRAP, 30);
        }

        LinearLayout brand = ui.row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(activity);
        mark.setImageResource(R.drawable.app_icon);
        mark.setBackground(ui.shape(ui.surface, 12, 0));
        mark.setClipToOutline(true);
        brand.addView(mark, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
        TextView name = ui.label(activity.getString(R.string.app_name), 17, Ui.ACCENT, true);
        name.setPadding(ui.dp(12), 0, 0, 0);
        brand.addView(name);
        intro.addView(brand);
        ui.margin(intro, ui.label(confirming
                ? activity.getString(R.string.login_title_code)
                : pairs.hasPair() ? activity.getString(R.string.login_title_tv)
                : activity.getString(R.string.login_title_phone),
            tv ? 43 : 39, Ui.INK, true), Ui.MATCH, Ui.WRAP, 20);
        String hint = confirming ? deliveryHint(challenge.transport())
            : activity.getString(R.string.login_hint_phone);
        ui.margin(intro, ui.label(hint, tv ? 17 : 16, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 10);

        form.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));
        form.setBackground(ui.shape(ui.surface, 20, 0));
        form.addView(ui.label(confirming
            ? activity.getString(R.string.login_label_code)
            : activity.getString(R.string.login_label_phone), 16, Ui.INK, true));
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(21);
        input.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        input.setBackground(ui.control(0xff14131a, 0xff25212b, 10));
        input.setInputType(confirming ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_PHONE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(confirming ? 6 : 24)});
        input.setHint(confirming
            ? activity.getString(R.string.login_hint_code)
            : activity.getString(R.string.login_hint_no_code));
        if (!confirming) input.setText(phoneDraft);
        input.setShowSoftInputOnFocus(!tv);
        input.setSaveEnabled(false);
        if (confirming) ui.margin(form, input, Ui.MATCH, ui.dp(49), 8);
        else addPhoneField(form, input);
        TextView error = ui.label("", 14, Ui.ACCENT, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        ui.margin(form, error, Ui.MATCH, Ui.WRAP, 7);
        View firstDigit = tv ? keypad(form, input, confirming) : null;

        String actionText = confirming
            ? activity.getString(R.string.login_action_sign_in)
            : activity.getString(R.string.login_action_get_code);
        TextView submit = ui.button(actionText, true, () -> {
            if (host.busy()) return;
            String typed = input.getText().toString();
            String value = confirming ? typed.trim() : phoneCountry.normalize(typed);
            if (value.isEmpty() || (confirming && !value.matches("[0-9]{6}"))) {
                Ui.showError(error, confirming
                    ? activity.getString(R.string.login_error_code_format)
                    : activity.getString(R.string.login_error_select_country));
                return;
            }
            ui.hideKeyboard(input);
            if (confirming) {
                BoostyApi.Challenge active = challenge;
                TvPairing.Sender pair = pairs.sender();
                host.perform(() -> {
                    if (pair == null) host.api().confirm(active, value);
                    else pair.confirm(value);
                    return true;
                }, error, result -> {
                    challenge = null;
                    phoneDraft = "";
                    if (pair != null) {
                        pairs.finishPhonePair();
                        Toast.makeText(activity, activity.getString(R.string.login_pair_connected),
                            Toast.LENGTH_SHORT).show();
                    } else host.showFeed(null, null, false);
                });
            } else {
                phoneDraft = typed;
                sendLoginCode(value, error);
            }
        });
        ui.margin(form, submit, Ui.MATCH, ui.dp(49), 10);
        input.setOnEditorActionListener((view, action, event) -> {
            submit.performClick();
            return true;
        });
        if (confirming) {
            LinearLayout options = ui.row();
            ui.weighted(options, ui.button(activity.getString(R.string.login_action_change_number), false, () -> {
                if (!host.busy()) {
                    challenge = null;
                    show();
                }
            }), 0, ui.dp(44));
            ui.weighted(options, ui.button(activity.getString(R.string.login_action_resend), false, () -> {
                if (host.busy()) return;
                long seconds = (resendAfter - SystemClock.elapsedRealtime() + 999) / 1000;
                if (seconds > 0) {
                    Ui.showError(error, activity.getString(R.string.login_resend_in, seconds));
                } else sendLoginCode(challenge.phone(), error);
            }), 6, ui.dp(44));
            ui.margin(form, options, Ui.MATCH, Ui.WRAP, 8);
        } else if (pairs.hasPair()) {
            ui.margin(form, ui.button(activity.getString(R.string.login_action_cancel_pair),
                false, pairs::finishPhonePair), Ui.MATCH, ui.dp(44), 8);
        } else if (tv) {
            ui.margin(form, ui.button(activity.getString(R.string.login_action_tv_phone),
                false, pairs::showTvPairing), Ui.MATCH, ui.dp(44), 8);
        } else {
            LinearLayout options = ui.row();
            ui.weighted(options, ui.button(activity.getString(R.string.login_action_social),
                false, () -> showWebLogin(null)), 0, ui.dp(44));
            ui.margin(form, options, Ui.MATCH, Ui.WRAP, 8);
        }
        host.replace(scroll, false);
        if (firstDigit != null) firstDigit.requestFocus();
    }

    boolean hasState() {
        return webLogin != null || pairs.hasPair() || challenge != null;
    }

    void scan() {
        ScanOptions options = new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(activity.getString(R.string.login_qr_prompt)).setBeepEnabled(false)
            .setCaptureActivity(PortraitQrActivity.class).setOrientationLocked(false);
        scanTv.launch(options);
    }

    boolean handleBack() {
        if (webLogin != null) {
            if (webLogin.closePopup()) return true;
            webLogin.close(true);
            webLogin = null;
        }
        if (pairs.handleBack()) return true;
        if (challenge != null) {
            challenge = null;
            show();
            return true;
        }
        return false;
    }

    void stop() {
        pairs.stop();
    }

    void destroy() {
        if (webLogin != null) webLogin.close(false);
        pairs.stop();
    }

    void reset() {
        if (webLogin != null) {
            webLogin.close(true);
            webLogin = null;
        }
        pairs.reset();
        challenge = null;
        phoneDraft = "";
        resendAfter = 0;
    }

    private void sendLoginCode(String phone, TextView error) {
        TvPairing.Sender pair = pairs.sender();
        host.perform(() -> pair == null ? host.api().sendCode(phone) : pair.sendCode(phone), error, result -> {
            challenge = result;
            resendAfter = SystemClock.elapsedRealtime() + 60_000;
            show();
        });
    }


    @Override public void showWebLogin(TvPairing.Sender target) {
        LinearLayout screen = ui.column();
        TextView[] address = new TextView[1];
        LinearLayout bar = ui.toolbar(activity.getString(R.string.login_web_title), true, host::back,
            heading -> address[0] = heading);
        TextView error = ui.label("", 14, Ui.ACCENT, false);
        error.setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(5));
        error.setVisibility(View.GONE);
        BoostyWebLogin browser = new BoostyWebLogin(activity, text -> address[0].setText(text),
            text -> Ui.showError(error, text));
        webLogin = browser;
        Consumer<WebSession> finish = session -> {
            if (webLogin != browser) return;
            host.perform(() -> {
                if (target == null) host.api().importWebSession(session);
                else target.transfer(session);
                return true;
            }, error, result -> {
                browser.close(true);
                webLogin = null;
                if (target == null) {
                    challenge = null;
                    phoneDraft = "";
                    host.onSignedIn();
                    host.showFeed(null, null, false);
                } else {
                    host.back();
                    Toast.makeText(activity, activity.getString(R.string.login_pair_connected),
                        Toast.LENGTH_SHORT).show();
                }
            });
        };
        browser.onAuthorized(target == null ? finish : session -> {
            browser.closePopup();
            Ui.showError(error, activity.getString(R.string.login_web_read_session));
        });
        bar.addView(ui.button(target == null
            ? activity.getString(R.string.login_web_finish)
            : activity.getString(R.string.login_web_transfer), true, () -> {
                error.setVisibility(View.GONE);
                browser.readSession(finish);
            }));
        screen.addView(bar);
        screen.addView(error);
        screen.addView(browser, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        host.replace(screen, true);
    }

    @Override public Context activity() { return activity; }
    @Override public Ui ui() { return ui; }
    @Override public BoostyApi api() { return host.api(); }
    @Override public boolean isTv() { return host.isTv(); }
    @Override public boolean busy() { return host.busy(); }
    @Override public View currentScreen() { return host.currentScreen(); }
    @Override public void replace(View view, boolean push) { host.replace(view, push); }
    @Override public void showFeed(String slug, String name, boolean push) { host.showFeed(slug, name, push); }
    @Override public void showForm() { show(); }
    @Override public BoostyApi.Challenge challenge() { return challenge; }
    @Override public void setChallenge(BoostyApi.Challenge value) { challenge = value; }
    @Override public void clearDraft() { phoneDraft = ""; }

    @Override public List<PhoneCountry> countries() {
        if (phoneCountries == null) {
            phoneCountries = PhoneCountry.load(activity);
            String region = activity.getPreferences(Context.MODE_PRIVATE)
                .getString("phone_region", Locale.getDefault().getCountry());
            phoneCountry = phoneCountries.stream()
                .filter(country -> country.region().equals(region))
                .findFirst().orElse(PhoneCountry.RUSSIA);
        }
        return phoneCountries;
    }

    @Override public <T> void perform(Callable<T> request, TextView error, Consumer<T> done) {
        host.perform(request, error, done);
    }

    @Override public <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed) {
        host.perform(request, error, done, failed);
    }

    private String deliveryHint(String transport) {
        return switch (transport) {
            case "sms" -> activity.getString(R.string.login_delivery_sms);
            case "telegram" -> activity.getString(R.string.login_delivery_telegram);
            default -> activity.getString(R.string.login_delivery_default);
        };
    }

    private void addPhoneField(LinearLayout form, EditText input) {
        boolean tv = host.isTv();
        countries();
        LinearLayout field = ui.row();
        TextView prefix = ui.button("", false, () -> {});
        prefix.setTextSize(16);
        prefix.setBackground(ui.control(0xff2c2833, ui.focusedSurface, 10));
        field.addView(prefix, new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(49)));
        ui.weighted(field, input, 8, ui.dp(49));
        ui.margin(form, field, Ui.MATCH, Ui.WRAP, 8);

        Consumer<String> update = value -> {
            PhoneCountry displayed = PhoneCountry.fromInternational(value, phoneCountry, phoneCountries);
            prefix.setText(activity.getString(R.string.login_country_prefix,
                displayed.flag(), displayed.prefix()));
            prefix.setContentDescription(activity.getString(R.string.login_country_code_desc,
                displayed.name(), displayed.prefix()));
        };
        update.accept(input.getText().toString());
        CountryPicker.watchText(input, update);
        prefix.setOnClickListener(view -> {
            if (host.busy()) return;
            ui.hideKeyboard(input);
            String typed = input.getText().toString();
            PhoneCountry displayed = PhoneCountry.fromInternational(typed, phoneCountry, phoneCountries);
            CountryPicker.show(this, displayed, country -> {
                phoneCountry = country;
                activity.getPreferences(Context.MODE_PRIVATE).edit()
                    .putString("phone_region", country.region()).apply();
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

    private View keypad(LinearLayout form, EditText input, boolean confirming) {
        View first = null;
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫"};
        for (int i = 0; i < keys.length; i += 3) {
            LinearLayout keysRow = ui.row();
            for (int j = i; j < i + 3; j++) {
                String key = keys[j];
                TextView digit = ui.button(key, false, () -> {
                    if (host.busy()) return;
                    if (key.equals("⌫")) {
                        int length = input.length();
                        if (length > 0) input.getText().delete(length - 1, length);
                    } else {
                        input.append(key);
                    }
                });
                digit.setTextSize(17);
                digit.setBackground(ui.control(0xff2c2833, ui.focusedSurface, 9));
                if (key.equals("⌫")) {
                    digit.setContentDescription(activity.getString(R.string.login_keypad_delete));
                }
                if (confirming && key.equals("+")) digit.setVisibility(View.INVISIBLE);
                if (first == null) first = digit;
                ui.weighted(keysRow, digit, j == i ? 0 : 6, ui.dp(37));
            }
            ui.margin(form, keysRow, Ui.MATCH, Ui.WRAP, 4);
        }
        return first;
    }
}

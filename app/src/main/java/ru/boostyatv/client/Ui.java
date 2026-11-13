package ru.boostyatv.client;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared programmatic UI construction: spacing, colours and the small set of
 * widgets used across screens. Keeping it in one place makes the screens
 * readable and keeps the theme consistent on phones and TVs.
 */
final class Ui {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int INK = 0xfff7f1ef;
    static final int MUTED = 0xffbdb1bf;
    static final int ACCENT = 0xffffaa81;
    static final int ICON = 0xffddd2da;
    static final int ICON_SURFACE = 0xff2a2831;

    final int background;
    final int surface;
    final int focusedSurface;

    private final Context context;
    private final Artwork artwork;
    private final BooleanSupplier enabled;
    private final float density;
    private final boolean tv;

    Ui(Context context, Artwork artwork, boolean oled, boolean tv, BooleanSupplier enabled) {
        this.context = context;
        this.artwork = artwork;
        this.enabled = enabled;
        this.tv = tv;
        density = context.getResources().getDisplayMetrics().density;
        background = oled ? Color.BLACK : 0xff101015;
        surface = oled ? 0xff151515 : 0xff201e27;
        focusedSurface = oled ? 0xff343434 : 0xff3d2c35;
    }

    Context context() {
        return context;
    }

    int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        return new LinearLayout(context);
    }

    LinearLayout toolbar(String title, boolean back, Runnable backAction) {
        return toolbar(title, back, backAction, ignored -> {});
    }

    LinearLayout toolbar(String title, boolean back, Runnable backAction,
                         java.util.function.Consumer<TextView> heading) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(tv ? 38 : 16), dp(8), dp(tv ? 38 : 12), dp(7));
        if (back) {
            TextView arrow = button("‹", false, backAction);
            arrow.setContentDescription(context.getString(R.string.nav_back));
            bar.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView titleView = label(title, tv ? 32 : 27, INK, true);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        if (back) titleView.setPadding(dp(12), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1));
        heading.accept(titleView);
        return bar;
    }

    TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    TextView button(String title, boolean primary, Runnable action) {
        TextView text = label(title, 15, primary ? background : INK, true);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(10), 0, dp(10), 0);
        text.setMinHeight(dp(42));
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setBackground(primary ? control(ACCENT, 0xffffc3a7, 12) : control(surface, focusedSurface, 12));
        makeAction(text, action);
        return text;
    }

    TextView chip(String title, Runnable action) {
        TextView text = label(title, 14, INK, true);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(16), 0, dp(16), 0);
        text.setMinHeight(dp(36));
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_selected}, shape(ACCENT, 18, 0));
        bg.addState(new int[]{android.R.attr.state_focused}, shape(focusedSurface, 18, ACCENT));
        bg.addState(new int[]{}, shape(surface, 18, 0));
        text.setBackground(bg);
        text.setTextColor(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
            new int[]{background, INK}));
        makeAction(text, action);
        return text;
    }

    ImageView image(String url, ImageView.ScaleType scale) {
        ImageView view = new ImageView(context);
        bindImage(view, url, scale);
        return view;
    }

    ImageView icon(int resource, int sizeDp, int color) {
        ImageView view = new ImageView(context);
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return view;
    }

    ImageView iconButton(int resource, String description, Runnable action) {
        ImageView view = new ImageView(context);
        view.setImageResource(resource);
        view.setImageTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
            new int[]{ACCENT, INK}));
        view.setPadding(dp(11), dp(11), dp(11), dp(11));
        view.setBackground(control(surface, focusedSurface, 14));
        view.setContentDescription(description);
        makeAction(view, action);
        return view;
    }

    LinearLayout actionChip(int resource, String title, Runnable action) {
        LinearLayout chip = row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), 0, dp(16), 0);
        chip.setBackground(control(surface, focusedSurface, 18));
        chip.addView(icon(resource, 18, ICON));
        TextView label = label(title, 14, INK, true);
        label.setPadding(dp(8), 0, 0, 0);
        chip.addView(label);
        makeAction(chip, action);
        return chip;
    }

    TextView settingsSection(String title) {
        TextView section = label(title.toUpperCase(Locale.getDefault()), 12, MUTED, true);
        section.setLetterSpacing(0.08f);
        section.setPadding(dp(18), dp(20), dp(18), dp(4));
        return section;
    }

    LinearLayout settingsRow(int iconResource, String title, String subtitle, String value,
                             Runnable action) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setBackground(control(surface, focusedSurface, 18));
        row.addView(settingsIcon(iconResource), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, dp(8), 0);
        text.addView(label(title, 16, INK, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView hint = label(subtitle, 13, MUTED, false);
            hint.setSingleLine(true);
            hint.setEllipsize(TextUtils.TruncateAt.END);
            hint.setPadding(0, dp(3), 0, 0);
            text.addView(hint);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView valueView = label(value == null ? "" : value, 14, MUTED, false);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setTag(R.id.settings_value);
        row.addView(valueView);
        row.addView(icon(R.drawable.ic_chevron, 18, MUTED));
        makeAction(row, action);
        return row;
    }

    LinearLayout settingsToggle(int iconResource, String title, String subtitle, boolean checked,
                                Consumer<Boolean> changed) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        row.setBackground(control(surface, focusedSurface, 18));
        row.addView(settingsIcon(iconResource), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout text = column();
        text.setPadding(dp(14), 0, dp(8), 0);
        text.addView(label(title, 16, INK, true));
        TextView hint = label(subtitle, 13, MUTED, false);
        hint.setSingleLine(true);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setPadding(0, dp(3), 0, 0);
        text.addView(hint);
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setClickable(false);
        toggle.setFocusable(false);
        toggle.setShowText(false);
        toggle.setThumbTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{0xffffffff, 0xff8b8491}));
        toggle.setTrackTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{0xffffaa81, 0xff3a373f}));
        row.addView(toggle);
        makeAction(row, () -> {
            boolean next = !toggle.isChecked();
            toggle.setChecked(next);
            changed.accept(next);
        });
        return row;
    }

    void setSettingsValue(View row, String value) {
        View target = row.findViewWithTag(R.id.settings_value);
        if (target instanceof TextView text) text.setText(value);
    }

    private FrameLayout settingsIcon(int resource) {
        FrameLayout badge = new FrameLayout(context);
        badge.setBackground(shape(ICON_SURFACE, 14, 0));
        ImageView icon = icon(resource, 22, ICON);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        badge.addView(icon, params);
        return badge;
    }

    LinearLayout bottomNav(int selected, Runnable home, Runnable subscriptions,
                           Runnable settings, Runnable account) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(background);
        addNavItem(bar, 0, selected, R.drawable.ic_home, context.getString(R.string.nav_home), home);
        addNavItem(bar, 1, selected, R.drawable.ic_subscriptions,
            context.getString(R.string.feed_subscriptions), subscriptions);
        addNavItem(bar, 2, selected, R.drawable.ic_settings,
            context.getString(R.string.settings_title), settings);
        addNavItem(bar, 3, selected, R.drawable.ic_person,
            context.getString(R.string.nav_account), account);
        return bar;
    }

    private void addNavItem(LinearLayout bar, int index, int selected, int iconResource,
                            String title, Runnable action) {
        boolean active = index == selected;
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, dp(7), 0, dp(5));
        if (active) item.setBackground(shape(0x1affaa81, 18, 0));
        ImageView icon = icon(iconResource, 23, active ? ACCENT : MUTED);
        item.addView(icon);
        TextView label = label(title, 11, active ? ACCENT : MUTED, active);
        label.setSingleLine(true);
        label.setPadding(0, dp(3), 0, 0);
        item.addView(label);
        makeAction(item, action);
        bar.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1));
    }

    void bindImage(ImageView view, String url, ImageView.ScaleType scale) {
        view.setScaleType(scale);
        artwork.bind(view, url);
    }

    FrameLayout videoPreview(boolean tv) {
        return new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec);
                int height = Math.min(Math.round(width * 9f / 16), dp(tv ? 420 : 320));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            }
        };
    }

    void makeAction(View view, Runnable action) {
        view.setFocusable(true);
        view.setOnClickListener(clicked -> {
            if (enabled.getAsBoolean()) action.run();
        });
    }

    void margin(LinearLayout parent, View child, int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    void weighted(LinearLayout parent, View child, int left, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
        params.leftMargin = dp(left);
        parent.addView(child, params);
    }

    StateListDrawable control(int normal, int focused, int radius) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, shape(focused, radius, INK));
        result.addState(new int[]{android.R.attr.state_pressed}, shape(focused, radius, ACCENT));
        result.addState(new int[]{}, shape(normal, radius, 0));
        return result;
    }

    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(2), stroke);
        return result;
    }

    void hideKeyboard(View view) {
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    static void onAttach(View view, Runnable action) {
        view.setTag(R.id.refresh_tag, action);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { action.run(); }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
    }

    static void refreshRegistered(View view) {
        if (view.getTag(R.id.refresh_tag) instanceof Runnable refresh) refresh.run();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) refreshRegistered(group.getChildAt(i));
        }
    }

    static void showError(TextView view, String text) {
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }
}

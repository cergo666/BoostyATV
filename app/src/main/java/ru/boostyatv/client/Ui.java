package ru.boostyatv.client;

import android.content.Context;
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
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.BooleanSupplier;

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

    ImageView image(String url, ImageView.ScaleType scale) {
        ImageView view = new ImageView(context);
        bindImage(view, url, scale);
        return view;
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

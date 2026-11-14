package ru.boostyatv.client;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable country picker for the phone login field. */
final class CountryPicker {
    interface Host {
        Context activity();
        Ui ui();
        boolean isTv();
        List<PhoneCountry> countries();
    }

    private CountryPicker() {}

    static void show(Host host, PhoneCountry current, Consumer<PhoneCountry> select) {
        Context activity = host.activity();
        Ui ui = host.ui();
        boolean tv = host.isTv();
        List<PhoneCountry> countries = host.countries();
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint(activity.getString(R.string.login_country_search_hint));
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        search.setBackground(ui.control(0xff14131a, 0xff25212b, 10));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(search, new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(48)));

        FrameLayout results = new FrameLayout(activity);
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(ui.shape(ui.focusedSurface, Ui.RADIUS_CONTROL, Ui.ACCENT));
        list.setId(View.generateViewId());
        search.setNextFocusDownId(list.getId());
        results.addView(list, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        TextView empty = ui.label(activity.getString(R.string.login_country_not_found),
            16, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        list.setEmptyView(empty);
        int height = Math.min(tv ? 340 : 380,
            activity.getResources().getConfiguration().screenHeightDp / 2);
        ui.margin(content, results, Ui.MATCH, ui.dp(height), 4);

        ArrayAdapter<PhoneCountry> rows = new ArrayAdapter<>(activity,
            android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout item = (LinearLayout) recycled;
                TextView[] row;
                if (item == null) {
                    item = ui.row();
                    item.setPadding(ui.dp(12), 0, ui.dp(12), 0);
                    item.setMinimumHeight(ui.dp(tv ? 49 : 46));
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    TextView flag = ui.label("", 19, Ui.INK, false);
                    item.addView(flag, new LinearLayout.LayoutParams(ui.dp(33), Ui.WRAP));
                    TextView name = ui.label("", 16, Ui.INK, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    item.addView(name, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                    TextView code = ui.label("", 15, Ui.ACCENT, true);
                    code.setPadding(ui.dp(8), 0, 0, 0);
                    item.addView(code);
                    row = new TextView[]{flag, name, code};
                    item.setTag(row);
                } else {
                    row = (TextView[]) item.getTag();
                }
                PhoneCountry country = getItem(position);
                row[0].setText(country.flag());
                row[1].setText(country.name());
                row[2].setText(country.prefix());
                return item;
            }
        };
        list.setAdapter(rows);
        Consumer<String> filter = typed -> {
            String query = typed.trim().toLowerCase(Locale.ROOT);
            List<PhoneCountry> visible = new ArrayList<>();
            if (query.isEmpty()) visible.add(current);
            for (PhoneCountry country : countries) {
                if (query.isEmpty() ? !country.equals(current) : country.matches(query)) {
                    visible.add(country);
                }
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
            ui.hideKeyboard(search);
            list.requestFocus();
            return true;
        });

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.CountryPickerTheme)
            .setTitle(activity.getString(R.string.login_country_picker_title)).setView(content)
            .setNegativeButton(activity.getString(R.string.cancel), null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            select.accept(rows.getItem(position));
            dialog.dismiss();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        if (tv) list.requestFocus();
    }

    static void watchText(EditText input, Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                changed.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }
}

package ru.boostyatv.client;

import android.content.Context;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

record PhoneCountry(String region, String prefix, String name, String originalName) {
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    static final PhoneCountry RUSSIA = new PhoneCountry("RU", "+7", "Russia", "Russia");

    PhoneCountry localized(Locale display) {
        if (region.isEmpty()) return this;
        String name = new Locale("", region).getDisplayCountry(display);
        return name.equals(region) || name.isEmpty() ? this : new PhoneCountry(region, prefix, name, originalName);
    }

    static List<PhoneCountry> load(Context context) {
        List<PhoneCountry> countries = new ArrayList<>();
        Locale display = context.getResources().getConfiguration().getLocales().get(0);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            context.getResources().openRawResource(R.raw.phone_codes), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            JSONArray data = new JSONArray(text.toString());
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i);
                String iso = row.getString("code");
                String prefix = row.getString("dialCode");
                if (!iso.matches("[A-Z]{2}") || !prefix.matches("\\+[1-9][0-9]{0,4}")) continue;
                String originalName = row.getString("name");
                String localizedName = new Locale("", iso).getDisplayCountry(display);
                countries.add(new PhoneCountry(iso, prefix,
                    localizedName.equals(iso) || localizedName.isEmpty() ? originalName : localizedName, originalName));
            }
        } catch (IOException | org.json.JSONException e) {
            // The form also accepts complete international numbers if the list is unavailable.
            Log.w("BoostyATV", "Phone code list is unavailable; using the fallback entry", e);
        }
        if (countries.isEmpty()) countries.add(RUSSIA.localized(display));
        countries.sort(Comparator.comparing(PhoneCountry::name, Collator.getInstance(display)));
        return countries;
    }

    String flag() {
        return new String(new int[]{0x1F1E6 + region.charAt(0) - 'A',
            0x1F1E6 + region.charAt(1) - 'A'}, 0, 2);
    }

    boolean matches(String query) {
        return name.toLowerCase(RUSSIAN).replace('ё', 'е').contains(query.replace('ё', 'е'))
            || originalName.toLowerCase(Locale.ROOT).contains(query)
            || region.toLowerCase(Locale.ROOT).equals(query)
            || prefix.contains(query.replace(" ", ""));
    }

    static PhoneCountry fromInternational(String input, PhoneCountry selected, List<PhoneCountry> countries) {
        String value = compact(input);
        if (!value.startsWith("+")) return selected;
        PhoneCountry best = value.startsWith(selected.prefix()) ? selected : null;
        for (PhoneCountry country : countries) {
            if (value.startsWith(country.prefix())
                && (best == null || country.prefix().length() > best.prefix().length())) best = country;
        }
        return best == null ? selected : best;
    }

    String normalize(String input) {
        String value = compact(input);
        if (value.startsWith("+")) return valid(value);
        if (!value.matches("[0-9]+")) return "";
        if (prefix.equals("+7") && value.matches("[78][0-9]{10}")) {
            return "+7" + value.substring(1);
        }
        String formatted = PhoneNumberUtils.formatNumberToE164(value, region);
        return valid(formatted == null ? prefix + value : formatted);
    }

    String nationalPart(String input) {
        String value = compact(input);
        return value.startsWith(prefix) ? value.substring(prefix.length()) : input;
    }

    private static String compact(String input) {
        return input.replaceAll("[\\p{Z}\\s().\\-]", "");
    }

    private static String valid(String value) {
        return value.matches("\\+[1-9][0-9]{6,14}") ? value : "";
    }
}

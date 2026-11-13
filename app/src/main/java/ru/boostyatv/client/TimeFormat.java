package ru.boostyatv.client;

import android.content.Context;

/** Playback position formatting shared by the feed and the player. */
final class TimeFormat {
    private TimeFormat() {}

    static String format(Context context, long position) {
        long seconds = Math.max(0, position / 1000);
        if (seconds >= 3600) {
            return context.getString(R.string.time_hms, seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return context.getString(R.string.time_ms, seconds / 60, seconds % 60);
    }

    static long parse(String value) {
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
}

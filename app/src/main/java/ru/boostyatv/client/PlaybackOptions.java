package ru.boostyatv.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

final class PlaybackOptions {
    static final int[] HEIGHTS = {0, 240, 360, 480, 720, 1080, 1440, 2160};
    /** Quality table labels; the auto label is localized by {@link #qualityLabels(Context)}. */
    static final String AUTO = "auto";
    static final String[] QUALITY = {AUTO, "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p"};
    static final String VIMU_RUSTORE = "net.gtvbox.vimuhd";
    enum App {
        BUILT_IN("", ""), VLC("VLC", "org.videolan.vlc"),
        MX_FREE("MX Player", "com.mxtech.videoplayer.ad"), MX_PRO("MX Player Pro", "com.mxtech.videoplayer.pro"),
        JUST_PLUS("Just+ Player", "com.justplus.player"), VIMU("ViMu Media Player", "net.gtvbox.videoplayer"),
        OTHER("", "");

        final String label;
        final String packageName;

        App(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        String label(Context context) {
            return switch (this) {
                case BUILT_IN -> context.getString(R.string.player_builtin);
                case OTHER -> context.getString(R.string.player_other);
                default -> label;
            };
        }

        Intent intent(Post.Source source, String title, long position) {
            Intent result = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(source.url()), "video/*");
            result.putExtra("title", title);
            if (!packageName.isEmpty()) result.setPackage(packageName);
            int millis = (int) Math.min(Integer.MAX_VALUE, position);
            switch (this) {
                case VLC -> result.putExtra("position", position).putExtra("from_start", position == 0);
                case MX_FREE, MX_PRO -> result.putExtra("position", millis).putExtra("return_result", true);
                case VIMU -> result.putExtra("forcename", title).putExtra("startfrom", millis);
            }
            return result;
        }

        WatchHistory.Progress result(int code, Intent data, long duration) {
            if (this == VIMU) {
                if (code == 1) return duration > 0 ? new WatchHistory.Progress(duration, duration) : null;
                if (code != 0 || data == null) return null;
                long position = data.getIntExtra("position", -1);
                int reported = data.getIntExtra("duration", -1);
                return position < 0 ? null : new WatchHistory.Progress(position, reported > 0 ? reported : duration);
            }
            if (code != Activity.RESULT_OK || data == null) return null;
            long position;
            switch (this) {
                case VLC -> {
                    position = data.getLongExtra("extra_position", -1);
                    duration = data.getLongExtra("extra_duration", duration);
                }
                case MX_FREE, MX_PRO -> {
                    int reported = data.getIntExtra("duration", -1);
                    if (reported > 0) duration = reported;
                    position = "playback_completion".equals(data.getStringExtra("end_by"))
                        ? (duration > 0 ? duration : -1) : data.getIntExtra("position", -1);
                }
                default -> { return null; }
            }
            return position < 0 ? null : new WatchHistory.Progress(position, Math.max(0, duration));
        }
    }

    static String[] qualityLabels(Context context) {
        String[] labels = QUALITY.clone();
        labels[0] = context.getString(R.string.player_auto);
        return labels;
    }

    static LinkedHashMap<String, String> qualityVariants(List<Post.Source> sources) {
        LinkedHashMap<String, String> variants = new LinkedHashMap<>();
        for (Post.Source source : sources) {
            int height = heightOf(source.type());
            if (height > 0) variants.putIfAbsent(height + "p", source.url());
            else if (source.type().contains("hls") || source.type().contains("dash")) {
                variants.putIfAbsent(AUTO, source.url());
            }
        }
        return variants;
    }

    private final SharedPreferences preferences;

    PlaybackOptions(Context context) {
        preferences = context.getSharedPreferences("playback_options", Context.MODE_PRIVATE);
    }

    boolean oled() {
        return preferences.getBoolean("oled_theme", false);
    }

    void setOled(boolean enabled) {
        preferences.edit().putBoolean("oled_theme", enabled).apply();
    }

    int qualityIndex() {
        int index = preferences.getInt("quality", 0);
        return index >= 0 && index < HEIGHTS.length ? index : 0;
    }

    void setQuality(int index) {
        preferences.edit().putInt("quality", index).apply();
    }

    App player() {
        try {
            return App.valueOf(preferences.getString("player", App.BUILT_IN.name()));
        } catch (IllegalArgumentException e) {
            return App.BUILT_IN;
        }
    }

    void setPlayer(App app) {
        preferences.edit().putString("player", app.name()).apply();
    }

    List<Post.Source> sources(Post.Block video) {
        int target = HEIGHTS[qualityIndex()];
        if (target == 0) return video.sources();
        List<Post.Source> ranked = new ArrayList<>(video.sources());
        ranked.sort(Comparator.comparingInt(source -> sourceRank(source.type(), target)));
        return ranked;
    }

    private static int sourceRank(String type, int target) {
        int height = heightOf(type);
        if (height == target) return 0;
        if (type.contains("hls") || type.contains("dash")) return 1;
        if (height == 0) return 10_000;
        return height <= target ? 2 + target - height : 3000 + height - target;
    }

    private static int heightOf(String type) {
        return switch (type) {
            case "ultra_hd" -> 2160;
            case "quad_hd" -> 1440;
            case "full_hd" -> 1080;
            case "high" -> 720;
            case "medium" -> 480;
            case "low" -> 360;
            case "lowest" -> 240;
            case "tiny" -> 144;
            default -> 0;
        };
    }
}

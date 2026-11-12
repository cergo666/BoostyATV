package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record Reactions(String selected, Map<String, Integer> counts) {
    enum Type {
        LIKE("like", "👍", "Нравится"), DISLIKE("dislike", "👎", "Не нравится"),
        FIRE("fire", "🔥", "Огонь"), HEART("heart", "❤️", "Сердце"), LAUGH("laugh", "😂", "Смешно"),
        WONDER("wonder", "😮", "Удивление"), SAD("sad", "😢", "Грустно"), ANGRY("angry", "😡", "Злюсь"),
        FUNNY("funny", "😜", "Забавно"), CHECK("check", "✅", "Согласен"),
        THINKING("thinking", "🤔", "Думаю"), CLOWN("clown", "🤡", "Клоун"), MOAI("moai", "🗿", "Моаи"),
        SWEARING("swearing", "🤬", "Возмущение"), APPLAUSE("applause", "👏", "Аплодисменты"),
        CALL_ME("call-me", "🤙", "Позвони"), COLD("cold", "🥶", "Холодно"), EGGPLANT("eggplant", "🍆", "Баклажан"),
        BLOWING_UP("blowing-up", "🤯", "Взрыв мозга"), SCREAM("scream", "😱", "Крик"),
        CROWN("crown", "👑", "Корона"), DIAMOND("diamond", "💎", "Бриллиант"),
        MELTING("melting", "🫠", "Таю"), MONEY("money", "💰", "Деньги"), NAUSEA("nausea", "🤢", "Тошнит"),
        CONFETTI("confetti", "🎉", "Праздник"), BANANA("banana", "🍌", "Банан"),
        PEACH("peach", "🍑", "Персик"), PIZZA("pizza", "🍕", "Пицца"),
        SKULL("skull", "💀", "Череп"), LOVE("love", "😍", "Влюблён"), ROCKET("rocket", "🚀", "Ракета"),
        SPLASH("splash", "💦", "Брызги"), LIGHTNING("lightning", "⚡", "Молния"),
        CRY("cry", "😭", "Плачу"), FOLDED_HANDS("folded-hands", "🙏", "Спасибо");

        final String code;
        final String emoji;
        final String label;

        Type(String code, String emoji, String label) {
            this.code = code;
            this.emoji = emoji;
            this.label = label;
        }
    }

    static List<Type> allowed(JSONObject blog) {
        if (blog.optBoolean("reactionsDisabled")) return java.util.Collections.emptyList();
        JSONArray list = blog.optJSONArray("allowedReactionList");
        if (list == null) return Arrays.asList(Type.values()).subList(0, 8);
        List<Type> types = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            String code = list.optString(i);
            for (Type type : Type.values()) {
                if (type.code.equals(code) && !types.contains(type)) types.add(type);
            }
        }
        return types;
    }

    static Reactions parse(JSONObject value) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        JSONObject aggregate = value.optJSONObject("reactions");
        JSONObject count = value.optJSONObject("count");
        if (aggregate == null && count != null) aggregate = count.optJSONObject("reactions");
        if (aggregate != null) {
            for (Type type : Type.values()) {
                counts.put(type.code, Math.max(0, aggregate.optInt(type.code, aggregate.optInt("laugh".equals(type.code) ? "laught" : type.code))));
            }
        }
        JSONArray counters = value.optJSONArray("reactionCounters");
        if (counters != null) {
            for (int i = 0; i < counters.length(); i++) {
                JSONObject counter = counters.optJSONObject(i);
                String code = Post.string(counter, "type");
                if (counter != null && !code.isEmpty()) counts.put(code, Math.max(0, counter.optInt("count")));
            }
        }
        String actor = Post.string(value.optJSONObject("reacted"), "actor");
        if ("laught".equals(actor)) actor = "laugh";
        return new Reactions(actor, counts);
    }

    int count(Type type) {
        int value = counts.getOrDefault(type.code, 0);
        if (type == Type.LAUGH) value = Math.max(value, counts.getOrDefault("laught", 0));
        return value;
    }

    Reactions withSelected(String choice) {
        if (selected.equals(choice)) return this;
        Map<String, Integer> updated = new LinkedHashMap<>(counts);
        if (!selected.isEmpty()) updated.put(selected, Math.max(0, updated.getOrDefault(selected, 0) - 1));
        if (!choice.isEmpty()) updated.put(choice, updated.getOrDefault(choice, 0) + 1);
        return new Reactions(choice, updated);
    }
}

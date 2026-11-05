package derpcraft.api;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Small key/value store for mod data: on item stacks ({@code stack.tag()}), on blocks ({@code ctx.data()}),
 * per world ({@link GameApi#worldData}) and in mod configs. Values are strings with typed helpers; it is saved
 * automatically with whatever owns it. Two tags are equal when they hold the same pairs.
 */
public final class DataTag {
    private final TreeMap<String, String> map = new TreeMap<>();
    /** Called after every change (owners use it to mark themselves for saving). */
    public Runnable onChange;

    public DataTag() {}

    public boolean isEmpty() { return map.isEmpty(); }
    public boolean has(String key) { return map.containsKey(key); }
    public Set<String> keys() { return Collections.unmodifiableSet(map.keySet()); }
    public int size() { return map.size(); }

    public String getString(String key) { return map.getOrDefault(key, ""); }
    public String getString(String key, String def) { return map.getOrDefault(key, def); }

    public int getInt(String key) { return getInt(key, 0); }
    public int getInt(String key, int def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public long getLong(String key, long def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public float getFloat(String key) { return getFloat(key, 0); }
    public float getFloat(String key, float def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public double getDouble(String key, double def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public boolean getBool(String key) { return Boolean.parseBoolean(map.get(key)); }
    public boolean getBool(String key, boolean def) { String v = map.get(key); return v == null ? def : Boolean.parseBoolean(v); }

    /** A nested tag stored under 'key' (a copy; write it back with {@link #putTag}). */
    public DataTag getTag(String key) { return decode(map.get(key)); }

    public DataTag put(String key, String value) {
        if (value == null) return remove(key);
        if (!value.equals(map.put(key, value))) changed();
        return this;
    }

    public DataTag put(String key, int value) { return put(key, Integer.toString(value)); }
    public DataTag put(String key, long value) { return put(key, Long.toString(value)); }
    public DataTag put(String key, float value) { return put(key, Float.toString(value)); }
    public DataTag put(String key, double value) { return put(key, Double.toString(value)); }
    public DataTag put(String key, boolean value) { return put(key, Boolean.toString(value)); }
    public DataTag putTag(String key, DataTag tag) { return put(key, tag == null ? null : tag.encode()); }

    public DataTag remove(String key) {
        if (map.remove(key) != null) changed();
        return this;
    }

    public DataTag clear() {
        if (!map.isEmpty()) { map.clear(); changed(); }
        return this;
    }

    /** Adds 'delta' to an int value and returns the new value. */
    public int add(String key, int delta) {
        int v = getInt(key) + delta;
        put(key, v);
        return v;
    }

    public DataTag copy() {
        DataTag t = new DataTag();
        t.map.putAll(map);
        return t;
    }

    public void copyFrom(DataTag other) {
        map.clear();
        if (other != null) map.putAll(other.map);
        changed();
    }

    private void changed() { if (onChange != null) onChange.run(); }

    // ------------------------------------------------------------------ text form
    /** Compact, separator-free text (base64 of "key=value" lines) for embedding in save files. */
    public String encode() {
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) sb.append(escape(e.getKey())).append('=').append(escape(e.getValue())).append('\n');
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static DataTag decode(String s) {
        DataTag t = new DataTag();
        if (s == null || s.isEmpty()) return t;
        try {
            String text = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) t.map.put(unescape(line.substring(0, i)), unescape(line.substring(i + 1)));
            }
        } catch (IllegalArgumentException ignored) {}
        return t;
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e"); }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 'e' ? '=' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    @Override public boolean equals(Object o) { return o instanceof DataTag t && t.map.equals(map); }
    @Override public int hashCode() { return map.hashCode(); }
    @Override public String toString() { return map.toString(); }
}

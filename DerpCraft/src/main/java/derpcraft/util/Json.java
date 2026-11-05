package derpcraft.util;

import java.util.*;

/** Tiny JSON parser (objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Double). Lenient. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        return j.value();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : Collections.emptyList(); }

    public static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else if (Character.isWhitespace(c) || c == '﻿') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            while (i < s.length() && s.charAt(i) != '}') {
                int before = i;
                ws();
                String k = String.valueOf(value());
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') i++;
                ws();
                if (i == before) i++;      // stray character (broken file, e.g. ']' in an object): skip it instead of looping forever
            }
            i++;
            return m;
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            while (i < s.length() && s.charAt(i) != ']') {
                int before = i;
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') i++;
                ws();
                if (i == before) i++;
            }
            i++;
            return l;
        }
        if (c == '"') {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char ch = s.charAt(i++);
                if (ch == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int end = i;
                            while (end < s.length() && end < i + 4 && Character.digit(s.charAt(end), 16) >= 0) end++;
                            sb.append(end == i ? '?' : (char) Integer.parseInt(s.substring(i, end), 16));
                            i = end;
                        }
                        default -> sb.append(e);
                    }
                } else sb.append(ch);
            }
            i++;
            return sb.toString();
        }
        int start = i;
        while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
        String tok = s.substring(start, i);
        if (tok.equals("true")) return Boolean.TRUE;
        if (tok.equals("false")) return Boolean.FALSE;
        if (tok.equals("null")) return null;
        try { return Double.parseDouble(tok); } catch (NumberFormatException e) { return tok; }
    }
}

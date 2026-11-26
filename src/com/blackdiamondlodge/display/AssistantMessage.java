package com.blackdiamondlodge.display;

import java.util.HashMap;
import java.util.Map;

/**
 * A text message from the voice app on the listen socket, read as a flat JSON
 * object (nested values are skipped). Pure Java so it runs in the JVM tests.
 * Unlike {@link ListenTarget#field}, strings are fully unescaped: transcripts
 * arrive with {@code \"} and {@code \u2019} escapes.
 *
 * <p>Assistant messages: {@code state} {state}, {@code transcript} {role, text,
 * final}, {@code speak_start}, {@code speak_end}, {@code speak_stop},
 * {@code conversation_end} {reason, message?}.
 */
final class AssistantMessage {
    final String type;
    private final Map<String, Object> fields;

    private AssistantMessage(Map<String, Object> fields) {
        this.fields = fields;
        Object t = fields.get("type");
        this.type = t instanceof String ? (String) t : "";
    }

    /** Parses a message; null when it isn't a JSON object. */
    static AssistantMessage parse(String json) {
        if (json == null) return null;
        try {
            Parser p = new Parser(json);
            Map<String, Object> m = p.object();
            p.ws();
            return p.i == json.length() ? new AssistantMessage(m) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    String str(String name) {
        Object v = fields.get(name);
        return v instanceof String ? (String) v : null;
    }

    boolean bool(String name) {
        return Boolean.TRUE.equals(fields.get(name));
    }

    /** True for the messages that drive conversation playback/UI. */
    boolean isAssistant() {
        switch (type) {
            case "state": case "transcript": case "speak_start": case "speak_end":
            case "speak_stop": case "conversation_end":
                return true;
            default:
                return false;
        }
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            return s.charAt(i);
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("want " + c + " at " + i);
            i++;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new HashMap<>();
            expect('{');
            if (peek() == '}') { i++; return m; }
            while (true) {
                String k = string();
                expect(':');
                m.put(k, value());
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("bad object at " + i);
            }
        }

        /** A value; objects and arrays are skipped (returned as a marker). */
        Object value() {
            char c = peek();
            if (c == '"') return string();
            if (c == '{') { object(); return Boolean.class; }
            if (c == '[') {
                i++;
                if (peek() == ']') { i++; return Boolean.class; }
                while (true) {
                    value();
                    char d = peek();
                    i++;
                    if (d == ']') return Boolean.class;
                    if (d != ',') throw new IllegalArgumentException("bad array at " + i);
                }
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad value at " + i);
            return Double.valueOf(s.substring(start, i));
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}

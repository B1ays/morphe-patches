/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.translation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Translation of Reddit rich text documents (Link.rtjson).
 *
 * The document is a tree: {"document":[{"e":"par","c":[{"e":"text","t":"...","f":[[style,start,length]]}]}]}.
 * Only the "t" values of text and link nodes are translated. Formatting ranges ("f") are turned into
 * {@code <span class="sSTYLE">} markup, which the translator keeps, and recalculated afterwards.
 */
public final class RichTextTranslator {

    /** Inline code style bit. */
    private static final int STYLE_CODE = 64;

    private final JSONObject root;
    private final List<JSONObject> nodes = new ArrayList<>();

    private RichTextTranslator(JSONObject root) {
        this.root = root;
        collect(root);
    }

    /** @return null if the document has nothing to translate. */
    public static RichTextTranslator prepare(String richText) throws JSONException {
        RichTextTranslator translator = new RichTextTranslator(new JSONObject(richText));
        return translator.nodes.isEmpty() ? null : translator;
    }

    /** HTML fragments to translate, one per text node. */
    public List<String> fragments() throws JSONException {
        List<String> fragments = new ArrayList<>(nodes.size());
        for (JSONObject node : nodes) {
            fragments.add(toHtml(node));
        }
        return fragments;
    }

    /** Writes the translated fragments back and returns the document JSON. */
    public String apply(List<String> translated) throws JSONException {
        for (int i = 0; i < nodes.size(); i++) {
            Parsed parsed = parse(translated.get(i));
            JSONObject node = nodes.get(i);
            node.put("t", parsed.text);
            if (node.has("f")) {
                if (parsed.ranges.isEmpty()) {
                    node.remove("f");
                } else {
                    JSONArray ranges = new JSONArray();
                    for (int[] range : parsed.ranges) {
                        ranges.put(new JSONArray().put(range[0]).put(range[1]).put(range[2]));
                    }
                    node.put("f", ranges);
                }
            }
        }
        return root.toString();
    }

    private void collect(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String element = object.optString("e");
            if ("code".equals(element) || "raw".equals(element)) {
                return; // Code blocks stay as is.
            }
            if (("text".equals(element) || "link".equals(element)) && object.has("t")) {
                if (hasLetters(object.optString("t"))) {
                    nodes.add(object);
                }
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collect(object.opt(keys.next()));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collect(array.opt(i));
            }
        }
    }

    private static String toHtml(JSONObject node) throws JSONException {
        String text = node.getString("t");
        JSONArray formats = node.optJSONArray("f");
        if (formats == null || formats.length() == 0) {
            return escape(text);
        }

        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < formats.length(); i++) {
            JSONArray range = formats.optJSONArray(i);
            if (range == null || range.length() < 3) {
                continue;
            }
            int style = range.optInt(0);
            int start = range.optInt(1);
            int length = range.optInt(2);
            if (start < 0 || length <= 0 || start + length > text.length()) {
                continue;
            }
            ranges.add(new int[]{style, start, length});
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[1] - b[1];
            }
        });

        StringBuilder html = new StringBuilder();
        int position = 0;
        for (int[] range : ranges) {
            if (range[1] < position) {
                continue; // Overlapping ranges are not expected; skip them.
            }
            html.append(escape(text.substring(position, range[1])));
            html.append("<span class=\"s").append(range[0]).append('"');
            if ((range[0] & STYLE_CODE) != 0) {
                html.append(" translate=\"no\"");
            }
            html.append('>')
                    .append(escape(text.substring(range[1], range[1] + range[2])))
                    .append("</span>");
            position = range[1] + range[2];
        }
        html.append(escape(text.substring(position)));
        return html.toString();
    }

    public static final class Parsed {
        public final String text;
        /** [style, start, length] in UTF-16 units. */
        public final List<int[]> ranges;

        Parsed(String text, List<int[]> ranges) {
            this.text = text;
            this.ranges = ranges;
        }
    }

    /** Converts translated HTML back to plain text and formatting ranges. */
    public static Parsed parse(String html) {
        StringBuilder text = new StringBuilder(html.length());
        List<int[]> ranges = new ArrayList<>();
        Deque<int[]> open = new ArrayDeque<>();
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                int end = html.indexOf('>', i);
                if (end < 0) {
                    text.append(html.substring(i));
                    break;
                }
                String tag = html.substring(i + 1, end).trim();
                if (tag.startsWith("/")) {
                    if (!open.isEmpty()) {
                        int[] span = open.pop();
                        int length = text.length() - span[1];
                        if (span[0] >= 0 && length > 0) {
                            ranges.add(new int[]{span[0], span[1], length});
                        }
                    }
                } else if (!tag.endsWith("/") && !tag.startsWith("br")) {
                    open.push(new int[]{styleOf(tag), text.length()});
                }
                i = end + 1;
            } else if (c == '&') {
                int end = html.indexOf(';', i);
                String decoded = end > i && end - i <= 10 ? decodeEntity(html.substring(i + 1, end)) : null;
                if (decoded != null) {
                    text.append(decoded);
                    i = end + 1;
                } else {
                    text.append(c);
                    i++;
                }
            } else {
                text.append(c);
                i++;
            }
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[1] - b[1];
            }
        });
        return new Parsed(text.toString(), ranges);
    }

    private static int styleOf(String tag) {
        int index = tag.indexOf("class=\"s");
        if (index < 0) {
            return -1;
        }
        int start = index + 8;
        int end = start;
        while (end < tag.length() && Character.isDigit(tag.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(tag.substring(start, end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String decodeEntity(String entity) {
        switch (entity) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "nbsp":
                return " ";
            default:
                break;
        }
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
            }
            if (entity.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(entity.substring(1))));
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return null;
    }

    public static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}

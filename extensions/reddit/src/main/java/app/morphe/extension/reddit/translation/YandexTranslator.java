/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.translation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.translation.TextTranslator.TranslationHttpException;

/**
 * Client of the Yandex Browser translation API (no key required).
 * Only GET is supported by the service, so texts are split into small requests.
 */
public final class YandexTranslator {

    private static final String API_URL = "https://browser.translate.yandex.net/api/v1/tr.json/translate";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** The service rejects a text of ~2000+ characters with HTTP 413. */
    private static final int MAX_CHUNK_CHARS = 1000;
    private static final int MAX_REQUEST_CHARS = 1500;
    private static final int MAX_REQUEST_URL = 7000;
    private static final int MAX_TEXTS_PER_REQUEST = 10;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    /** Rate limits and server errors are usually transient: retry with a growing pause. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    /** An error response can be a whole HTML page, of which only the start is worth logging. */
    private static final int MAX_ERROR_CHARS = 200;

    private static final Random RANDOM = new Random();

    private YandexTranslator() {
    }

    /**
     * Translates HTML fragments into the target language. Always call off the main thread.
     *
     * @return Translations in the same order as the input.
     * @throws IOException              On a network failure or an invalid response.
     * @throws TranslationHttpException If the service answers with an error status.
     */
    public static List<String> translateHtml(List<String> texts, String targetLanguage)
            throws IOException, TranslationHttpException {
        Utils.verifyOffMainThread();

        // Split long texts, remembering which input every chunk belongs to.
        List<String> chunks = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            for (String chunk : split(texts.get(i))) {
                chunks.add(chunk);
                owners.add(i);
            }
        }

        List<String> translated = new ArrayList<>(chunks.size());
        int start = 0;
        while (start < chunks.size()) {
            int end = start;
            int chars = 0;
            int urlLength = 300;
            while (end < chunks.size() && end - start < MAX_TEXTS_PER_REQUEST) {
                String chunk = chunks.get(end);
                int encoded = encode(chunk).length() + 6;
                if (end > start && (chars + chunk.length() > MAX_REQUEST_CHARS
                        || urlLength + encoded > MAX_REQUEST_URL)) {
                    break;
                }
                chars += chunk.length();
                urlLength += encoded;
                end++;
            }
            translated.addAll(request(chunks.subList(start, end), targetLanguage));
            start = end;
        }

        StringBuilder[] joined = new StringBuilder[texts.size()];
        for (int i = 0; i < translated.size(); i++) {
            int owner = owners.get(i);
            String part = translated.get(i);
            if (joined[owner] == null) {
                joined[owner] = new StringBuilder(part);
            } else {
                StringBuilder sb = joined[owner];
                if (sb.length() > 0 && part.length() > 0
                        && !Character.isWhitespace(sb.charAt(sb.length() - 1))
                        && !Character.isWhitespace(part.charAt(0))) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        List<String> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            result.add(joined[i] != null ? joined[i].toString() : texts.get(i));
        }
        return result;
    }

    private static List<String> request(List<String> texts, String targetLanguage)
            throws IOException, TranslationHttpException {
        StringBuilder url = new StringBuilder(API_URL)
                .append("?translateMode=auto&context_title=Reddit&id=").append(sessionId()).append("-0-0")
                .append("&srv=yabrowser&lang=").append(encode(targetLanguage))
                .append("&format=html&options=2&version=v12_joinAll");
        for (String text : texts) {
            url.append("&text=").append(encode(text));
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return requestOnce(url.toString(), texts.size());
            } catch (TranslationHttpException ex) {
                if (!isRetryable(ex.statusCode) || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
            } catch (IOException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
            }
            sleepQuietly(INITIAL_BACKOFF_MS * attempt);
        }
    }

    private static List<String> requestOnce(String url, int textCount)
            throws IOException, TranslationHttpException {
        HttpURLConnection connection = Requester.openConnection(url);
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new TranslationHttpException(status,
                        "Yandex HTTP status: " + status + " response: " + abbreviate(Requester.parseErrorString(connection)));
            }

            JSONObject json = new JSONObject(Requester.parseString(connection));
            int code = json.optInt("code");
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new TranslationHttpException(code,
                        "Yandex code: " + code + " message: " + json.optString("message"));
            }
            JSONArray array = json.getJSONArray("text");
            if (array.length() != textCount) {
                throw new IOException("Yandex returned " + array.length() + " texts for " + textCount);
            }
            List<String> result = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                result.add(array.getString(i));
            }
            return result;
        } catch (org.json.JSONException ex) {
            throw new IOException("Invalid Yandex response", ex);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Splits a long HTML fragment at sentence boundaries outside of tags. */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= MAX_CHUNK_CHARS) {
            parts.add(text);
            return parts;
        }
        int start = 0;
        while (text.length() - start > MAX_CHUNK_CHARS) {
            int limit = start + MAX_CHUNK_CHARS;
            int sentenceCut = -1;
            int spaceCut = -1;
            boolean inTag = false;
            int depth = 0;
            for (int i = start; i < limit; i++) {
                char c = text.charAt(i);
                if (c == '<') {
                    inTag = true;
                    if (i + 1 < text.length() && text.charAt(i + 1) == '/') {
                        depth = Math.max(0, depth - 1);
                    } else if (!text.startsWith("<br", i)) {
                        depth++;
                    }
                } else if (c == '>') {
                    inTag = false;
                } else if (!inTag && depth == 0 && Character.isWhitespace(c) && i > start) {
                    spaceCut = i + 1;
                    char prev = text.charAt(i - 1);
                    if (prev == '.' || prev == '!' || prev == '?' || c == '\n') {
                        sentenceCut = i + 1;
                    }
                }
            }
            int cut = sentenceCut > start ? sentenceCut : (spaceCut > start ? spaceCut : limit);
            parts.add(text.substring(start, cut));
            start = cut;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String sessionId() {
        StringBuilder sid = new StringBuilder(Long.toHexString(System.currentTimeMillis()));
        while (sid.length() < 16) {
            sid.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return sid.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String abbreviate(String value) {
        return value.length() > MAX_ERROR_CHARS ? value.substring(0, MAX_ERROR_CHARS) + "…" : value;
    }
}

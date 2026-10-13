/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.translation;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.translation.TextTranslator.TranslationHttpException;

/**
 * Client of the Google HTML translation API used by the page translation of Chrome (no user key required).
 * The markup of the texts is kept, like with the HTML format of Yandex.
 */
public final class GoogleTranslator {

    private static final String API_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    /** Public key of the Chrome page translation. */
    private static final String API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    /** The service accepts long texts (20 000+ characters), batches only keep the requests small. */
    private static final int MAX_REQUEST_CHARS = 10_000;
    private static final int MAX_TEXTS_PER_REQUEST = 100;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** Rate limits and server errors are usually transient: retry with a growing pause. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    /** An error response can be a whole HTML page, of which only the start is worth logging. */
    private static final int MAX_ERROR_CHARS = 200;

    private GoogleTranslator() {
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

        List<String> result = new ArrayList<>(texts.size());
        int start = 0;
        while (start < texts.size()) {
            int end = start;
            int chars = 0;
            while (end < texts.size() && end - start < MAX_TEXTS_PER_REQUEST) {
                int length = texts.get(end).length();
                if (end > start && chars + length > MAX_REQUEST_CHARS) {
                    break;
                }
                chars += length;
                end++;
            }
            result.addAll(request(texts.subList(start, end), targetLanguage));
            start = end;
        }
        return result;
    }

    private static List<String> request(List<String> texts, String targetLanguage)
            throws IOException, TranslationHttpException {
        // [[[texts], source language, target language], client]
        JSONArray query = new JSONArray()
                .put(new JSONArray(texts))
                .put("auto")
                .put(targetLanguage);
        byte[] body = new JSONArray().put(query).put("wt_lib").toString().getBytes(StandardCharsets.UTF_8);

        for (int attempt = 1; ; attempt++) {
            try {
                return requestOnce(body, texts.size());
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

    private static List<String> requestOnce(byte[] body, int textCount)
            throws IOException, TranslationHttpException {
        HttpURLConnection connection = Requester.openConnection(API_URL);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Content-Type", "application/json+protobuf");
            connection.setRequestProperty("X-Goog-Api-Key", API_KEY);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            }

            int status = connection.getResponseCode();
            if (status != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new TranslationHttpException(status,
                        "Google HTTP status: " + status + " response: " + abbreviate(Requester.parseErrorString(connection)));
            }

            // [[translations], [detected source languages]]
            JSONArray translations = new JSONArray(Requester.parseString(connection)).getJSONArray(0);
            if (translations.length() != textCount) {
                throw new IOException("Google returned " + translations.length() + " texts for " + textCount);
            }
            List<String> result = new ArrayList<>(translations.length());
            for (int i = 0; i < translations.length(); i++) {
                result.add(translations.getString(i));
            }
            return result;
        } catch (JSONException ex) {
            throw new IOException("Invalid Google response", ex);
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

    private static String abbreviate(String value) {
        return value.length() > MAX_ERROR_CHARS ? value.substring(0, MAX_ERROR_CHARS) + "…" : value;
    }
}

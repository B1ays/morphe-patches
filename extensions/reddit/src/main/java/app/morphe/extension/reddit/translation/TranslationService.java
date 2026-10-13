/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.translation;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.translation.TextTranslator.TranslationHttpException;

/**
 * Machine translation services used when Reddit has no translation.
 * Both keep the HTML markup of the texts, which carries the formatting of the rich text.
 */
public enum TranslationService {
    YANDEX("morphe_translation_source_yandex") {
        @Override
        public List<String> translateHtml(List<String> texts, String targetLanguage)
                throws IOException, TranslationHttpException {
            return YandexTranslator.translateHtml(texts, targetLanguage);
        }
    },
    GOOGLE("morphe_translation_source_google") {
        @Override
        public List<String> translateHtml(List<String> texts, String targetLanguage)
                throws IOException, TranslationHttpException {
            return GoogleTranslator.translateHtml(texts, targetLanguage);
        }
    };

    private final String nameKey;

    TranslationService(String nameKey) {
        this.nameKey = nameKey;
    }

    /**
     * Translates HTML fragments into the target language. Always call off the main thread.
     *
     * @return Translations in the same order as the input.
     */
    public abstract List<String> translateHtml(List<String> texts, String targetLanguage)
            throws IOException, TranslationHttpException;

    /**
     * @return Name of the service shown to the user.
     */
    public String displayName() {
        return str(nameKey);
    }

    /**
     * @return The selected service.
     */
    public static TranslationService selected() {
        TranslationService service = fromName(Settings.TRANSLATION_SERVICE.get());
        return service != null ? service : YANDEX;
    }

    @Nullable
    public static TranslationService fromName(@Nullable String name) {
        if (name != null) {
            for (TranslationService service : values()) {
                if (service.name().equalsIgnoreCase(name.trim())) {
                    return service;
                }
            }
        }
        return null;
    }
}

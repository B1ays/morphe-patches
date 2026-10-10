package com.reddit.localization.translations;

import com.reddit.domain.model.PostGallery;
import com.reddit.domain.model.Preview;

public final class TranslatedLink {
    /**
     * @param kindWithId      Post id.
     * @param language        Language of the translation.
     * @param title           Translated title.
     * @param selftextHtml    Translated text as HTML.
     * @param richText        Translated text as rich text JSON.
     * @param contentPreview  Translated content preview.
     * @param selftext        Translated text as markdown.
     * @param thumbnail       Thumbnail of the post.
     */
    public TranslatedLink(String kindWithId, String language, String title, String selftextHtml,
                          String richText, String contentPreview, String selftext, String thumbnail,
                          Preview preview, PostGallery gallery) {
        throw new UnsupportedOperationException("Stub");
    }
}

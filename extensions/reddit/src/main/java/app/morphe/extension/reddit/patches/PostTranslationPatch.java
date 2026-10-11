/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import com.reddit.domain.model.Comment;
import com.reddit.domain.model.Link;
import com.reddit.domain.model.RichTextResponse;
import com.reddit.localization.translations.TranslatedLink;
import com.reddit.localization.translations.comments.OriginalComment;
import com.reddit.postdetail.refactor.events.PostDetailRefreshEvent;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.translation.RichTextTranslator;
import app.morphe.extension.reddit.translation.TranslationService;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.translation.TextTranslator.TranslationHttpException;

/**
 * Manual activation of the Reddit server side machine translation
 * ("MT SEO", the ?tl=xx parameter of post links).
 * <p>
 * - "Translate all posts" on: every post detail screen is opened in MT SEO mode with the target language.
 * - "Translate all posts" off: the translation banner is shown in every post. "Translate" switches the screen
 *   to MT SEO mode and reloads the post in place (or reopens it with the ?tl= link if the screen cannot be switched).
 * - Reddit has a translation only for some post and language pairs. Without it the title and the text of the post
 *   are translated with Yandex or Google (if enabled), otherwise a toast is shown after the "Translate" button.
 * - In MT SEO mode the server returns only the comments it has already translated and translates the rest
 *   on demand: the post is reloaded when no comments or only a few are loaded.
 * <p>
 * Obfuscated app code is used through interfaces added to the app classes and through static bridges,
 * both generated during patching.
 */
@SuppressWarnings("unused")
public final class PostTranslationPatch {

    private static final long BUTTON_REQUEST_TIMEOUT_MS = 60_000;

    /**
     * In MT SEO mode the server translates the comments on demand: the post is reloaded while no comments
     * are loaded. A reload replaces the comments on screen, so a partial load is reloaded only once.
     */
    private static final long[] COMMENTS_RETRY_DELAYS_MS = {1500, 3000, 5000, 8000};
    private static final int COMMENTS_SPARSE_RETRIES = 1;
    private static final long COMMENTS_RETRY_TIMEOUT_MS = 60_000;
    private static final int COMMENTS_EXPECTED_MAX = 50;
    /** Less loaded comments than this part of the number of comments of the post. */
    private static final double COMMENTS_SPARSE_RATIO = 0.25;

    /** Language of the last MT SEO post detail screen. */
    @Nullable
    private static volatile String lastRequestedLanguage;

    /** Posts (kind_id) for which "Show original" was used during this session. */
    private static final Set<String> SHOW_ORIGINAL = Collections.synchronizedSet(new HashSet<>());

    /**
     * Posts translated by a translation service: kind_id -> "SERVICE:title hash".
     * Kept between app launches: translated posts are cached by the app.
     */
    private static final String FALLBACK_TITLES_PREFERENCES = "morphe_translation_fallback_titles";
    /** Titles translated with Yandex before the translation service was selectable: kind_id -> title hash. */
    private static final String LEGACY_YANDEX_TITLES_PREFERENCES = "morphe_translation_yandex_titles";
    private static final String FALLBACK_TITLES_KEY = "titles";
    private static final int FALLBACK_TITLES_MAX = 500;

    @Nullable
    private static LinkedHashMap<String, String> fallbackTitles;

    /**
     * Translation services of the translated posts (kind_id), set when the post is translated,
     * before it reaches the screen. A post translated by Reddit has no entry.
     */
    private static final Map<String, TranslationService> POST_SERVICES = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TranslationService> eldest) {
                    return size() > 200;
                }
            });

    /**
     * Post detail screens (RedditPostDetailViewModel) and their post (kind_id).
     * The post detail pager also loads the posts next to the open post.
     */
    private static final Map<Object, String> SCREEN_POSTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Post (kind_id) of the post detail screen that loaded last. */
    @Nullable
    private static volatile String lastLoadedPostId;

    /** Post detail screen whose translation banner is being composed. Main thread. */
    @Nullable
    private static WeakReference<Object> bannerScreen;

    /** Loaded comments by kind_id, to translate them with the translation service. */
    private static final Map<String, WeakReference<Comment>> COMMENTS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WeakReference<Comment>> eldest) {
                    return size() > 1000;
                }
            });

    /**
     * "Translate" events of the comment menu and the event dispatcher of the comments, by comment id (kind_id).
     */
    private static final Map<String, WeakReference<?>[]> PENDING_COMMENT_EVENTS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WeakReference<?>[]> eldest) {
                    return size() > 20;
                }
            });

    /** Comments (kind_id) whose "Translate" event was sent again with a service translation, and when. */
    private static final Map<String, Long> RESENT_COMMENT_EVENTS = Collections.synchronizedMap(new HashMap<>());
    private static final long COMMENT_EVENT_RESEND_TIMEOUT_MS = 10_000;

    /**
     * The first translation service failure is shown to the user, the next ones are only logged
     * until a translation works again.
     */
    private static volatile boolean reportNextServiceError = true;

    /** Comment of the comment menu that is being opened. */
    @Nullable
    private static volatile WeakReference<Object> overflowMenuComment;

    /** Posts (kind_id) translated in place with the "Translate" action. */
    private static final Set<String> FORCED = Collections.synchronizedSet(new HashSet<>());

    /** Post waiting for the in place translation reload, and its language. */
    @Nullable
    private static volatile String pendingRefreshId;
    @Nullable
    private static volatile String pendingRefreshLanguage;

    /** Post (kind_id) translated with the "Translate" button, and when. */
    @Nullable
    private static volatile String buttonRequestId;
    private static volatile long buttonRequestTime;

    /** Event dispatcher of the post detail screen that is loading a post. */
    @Nullable
    private static volatile WeakReference<EventDispatcher> loadingDispatcher;

    /**
     * Event dispatchers of the post detail screens by post id (kind_id).
     * The post detail pager also loads the posts next to the open post.
     */
    private static final Map<String, WeakReference<EventDispatcher>> POST_DISPATCHERS = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WeakReference<EventDispatcher>> eldest) {
                    return size() > 20;
                }
            });

    /** Comments reloads of the current MT SEO post. Guarded by the class. */
    @Nullable
    private static String retryPostId;
    private static int retryAttempts;
    private static long retryStartTime;
    /** If a reload is scheduled, and if the last load of the comments still needs it. */
    private static boolean retryPending;
    private static boolean retryNeeded;

    /** Service translations of posts: "kind_id|language|SERVICE" -> {title, rich text, html}. */
    private static final Map<String, String[]> FALLBACK_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > 50;
                }
            });

    @Nullable
    private static Field isTranslatedField;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    // region App interfaces

    // Added to obfuscated app classes during patching, with the methods generated in the app classes.

    /**
     * PostDetailRefreshEventHandler: reloads the post and the comments of a post detail screen.
     */
    public interface RefreshHandler {
        /**
         * @return Post of the screen.
         */
        @Nullable
        Link patch_getScreenPost();

        /**
         * Switches the post context of the screen to MT SEO mode.
         *
         * @return If the screen was switched.
         */
        boolean patch_switchToMtSeo(String language);
    }

    /**
     * Event dispatcher of a post detail screen.
     */
    public interface EventDispatcher {
        void patch_dispatchEvent(Object event);

        /**
         * @return Post detail screen (RedditPostDetailViewModel) of the dispatcher.
         */
        @Nullable
        Object patch_getScreen();
    }

    /**
     * CommentLink: post of a comments screen.
     */
    public interface CommentLinkInfo {
        /**
         * @return Post id (kind_id).
         */
        @Nullable
        String patch_getPostId();

        long patch_getNumComments();
    }

    /**
     * RedditTranslationsRepository.
     */
    public interface TranslationsRepository {
        /**
         * Stores a comment translation in the translation cache of the app.
         */
        void patch_putTranslatedComment(TranslationKey key, Object translation);

        /**
         * Stores the original of a translated comment, used by "Show original".
         */
        void patch_putOriginalComment(String commentId, OriginalComment original);
    }

    /**
     * Cache key of a comment translation (comment id, language).
     */
    public interface TranslationKey {
        /**
         * @return Comment id (kind_id).
         */
        @Nullable
        String patch_getCommentId();
    }

    /**
     * "Translate" event of the comment menu.
     */
    public interface TranslateCommentEvent {
        /**
         * @return Comment id (kind_id).
         */
        @Nullable
        String patch_getCommentId();
    }

    // endregion

    // region Static bridges

    // Replaced during patching: they call obfuscated static methods and constructors.

    /**
     * Bridge: merges a translation into a post, the same way as the app does.
     */
    private static Link mergeTranslation(TranslatedLink translatedLink, Link link) {
        throw new IllegalStateException();  // Modified during patching.
    }

    /**
     * Bridge: creates the MT SEO post context of the post detail screen.
     */
    private static Object newMtSeoPostContext(String language) {
        throw new IllegalStateException();  // Modified during patching.
    }

    /**
     * Bridge: creates a TranslatedComment.
     */
    private static Object newTranslatedComment(@Nullable String richText, @Nullable String preview,
                                               @Nullable String markdown, @Nullable String postId) {
        throw new IllegalStateException();  // Modified during patching.
    }

    /**
     * Bridge: receiver of a function reference.
     *
     * @return Post detail screen (RedditPostDetailViewModel) of a post detail context function, or null.
     */
    @Nullable
    private static Object getScreenOfFunction(Object function) {
        throw new IllegalStateException();  // Modified during patching.
    }

    /**
     * Bridge: invokes a Function1.
     */
    private static void invokeFunction(Object function, Object argument) {
        throw new IllegalStateException();  // Modified during patching.
    }

    // endregion

    // region Post detail screen context

    /**
     * Injection point: post detail screen arguments, after "mt_language" is read.
     */
    @Nullable
    public static String getMtLanguage(@Nullable String original, boolean isContinuation, boolean sctReplacement) {
        String language = resolveMtLanguage(original, isContinuation, sctReplacement);
        if (language != null && !language.trim().isEmpty()) {
            lastRequestedLanguage = language.trim();
        }
        return language;
    }

    @Nullable
    private static String resolveMtLanguage(@Nullable String original, boolean isContinuation, boolean sctReplacement) {
        if (original != null && !original.trim().isEmpty()) {
            return original;
        }
        try {
            if (isContinuation || sctReplacement || !Settings.TRANSLATE_ALL_POSTS.get()) {
                return original;
            }
            return getTargetLanguage();
        } catch (Exception ex) {
            Logger.printException(() -> "getMtLanguage failure", ex);
            return original;
        }
    }

    /**
     * Injection point: post detail screen arguments without a post context (post opened from a feed).
     *
     * @return MT SEO post context, or null to use the original context.
     */
    @Nullable
    public static Object getMtSeoPostContext(@Nullable Bundle arguments) {
        try {
            boolean isContinuation = arguments != null && arguments.getBoolean("is_continuation");
            String language = getMtLanguage(null, isContinuation, false);
            if (language == null) {
                return null;
            }
            return newMtSeoPostContext(language);
        } catch (Exception ex) {
            Logger.printException(() -> "getMtSeoPostContext failure", ex);
            return null;
        }
    }

    /**
     * Injection point: MT SEO checks of the post and the comments, replaces Link.isMtSeoDeepLink().
     * Called only when the screen is in MT SEO mode and the post is known.
     */
    public static boolean isMtSeoActive(Link link) {
        String id = link.getKindWithId();
        if (id != null && FORCED.contains(id)) {
            return true;
        }
        if (link.isMtSeoDeepLink()) {
            return true;
        }
        try {
            // A post opened from a feed is preloaded without the MT SEO flag.
            if (!Settings.TRANSLATE_ALL_POSTS.get()) {
                return false;
            }
            if (id != null && SHOW_ORIGINAL.contains(id)) {
                return false;
            }
            return !isPostInLanguage(link, getTargetLanguage());
        } catch (Exception ex) {
            Logger.printException(() -> "isMtSeoActive failure", ex);
            return false;
        }
    }

    // endregion

    // region Translation banner

    /**
     * Injection point: translation banner visibility.
     *
     * @return If the translation banner must be shown regardless of the server flags.
     */
    public static boolean shouldForceTranslateButton(@Nullable Link link, boolean isMtSeo) {
        if (link == null || isMtSeo) {
            return false;
        }
        try {
            // Link.isTranslated() is not checked: a post that was translated once keeps
            // the flag in the app link cache even when it is shown in the original.
            if (link.getPromoted() || isPostInLanguage(link, getTargetLanguage())) {
                return false;
            }
            if (Settings.TRANSLATE_ALL_POSTS.get()) {
                // Posts are translated automatically, the banner is needed only after "Show original".
                String id = link.getKindWithId();
                return id != null && SHOW_ORIGINAL.contains(id);
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "shouldForceTranslateButton failure", ex);
            return false;
        }
    }

    /**
     * Injection point: "Translate" of the banner and the post menu.
     *
     * @param isUserInitiated If the translation was requested by the user, not by the app.
     * @return If the event was handled and the original handler must be skipped.
     */
    public static boolean onTranslateClicked(@Nullable Link link, boolean isUserInitiated,
                                             @Nullable EventDispatcher eventDispatcher) {
        // Link.isTranslated() is not checked: the flag is kept in the app link cache
        // after the post is shown in the original.
        if (link == null || !isUserInitiated) {
            return false;
        }
        try {
            String id = link.getKindWithId();
            if (id != null) {
                SHOW_ORIGINAL.remove(id);
            }
            if (id != null && eventDispatcher != null) {
                // Translated in place: beforeRefresh() switches the screen to MT SEO mode.
                pendingRefreshLanguage = getTargetLanguage();
                pendingRefreshId = id;
                FORCED.add(id);
                buttonRequestId = id;
                buttonRequestTime = System.currentTimeMillis();
                if (dispatchRefresh(eventDispatcher)) {
                    return true;
                }
                pendingRefreshId = null;
                FORCED.remove(id);
            }
            boolean opened = openTranslatedPost(link);
            if (opened) {
                buttonRequestId = id;
                buttonRequestTime = System.currentTimeMillis();
            }
            return opened;
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateClicked failure", ex);
            return false;
        }
    }

    /**
     * Injection point: title of the translation banner.
     *
     * @param isTranslated If the banner shows that the post is translated.
     * @return Title with the source of the translation.
     */
    public static String getTranslationBannerTitle(String title, boolean isTranslated) {
        if (!isTranslated) {
            return title;
        }
        try {
            WeakReference<Object> reference = bannerScreen;
            Object screen = reference != null ? reference.get() : null;
            String postId = screen != null ? SCREEN_POSTS.get(screen) : null;
            if (postId == null) {
                postId = lastLoadedPostId;
            }
            TranslationService service = postId != null ? POST_SERVICES.get(postId) : null;
            String source = service != null
                    ? service.displayName()
                    : str("morphe_translation_source_reddit");
            return str("morphe_translation_banner_title", title, source);
        } catch (Exception ex) {
            Logger.printException(() -> "getTranslationBannerTitle failure", ex);
            return title;
        }
    }

    /**
     * Injection point: the translation banner section of a post detail screen is composed,
     * with the post detail context of the screen.
     */
    public static void setBannerScreen(@Nullable Object postDetailContext) {
        try {
            Object screen = postDetailContext != null ? getScreenOfFunction(postDetailContext) : null;
            bannerScreen = screen != null ? new WeakReference<>(screen) : null;
        } catch (Exception ex) {
            Logger.printException(() -> "setBannerScreen failure", ex);
            bannerScreen = null;
        }
    }

    /**
     * Injection point: the translation banner section is composed.
     */
    public static void clearBannerScreen() {
        bannerScreen = null;
    }

    /**
     * Injection point: "Show original".
     */
    public static void onShowOriginal(@Nullable Link link) {
        if (link == null) {
            return;
        }
        String id = link.getKindWithId();
        if (id != null) {
            FORCED.remove(id);
            SHOW_ORIGINAL.add(id);
        }
    }

    // endregion

    // region Post loading

    /**
     * Injection point: translations of a loaded post, before {@link #onPostLoaded(Link)}.
     */
    public static void setPostEventDispatcher(@Nullable EventDispatcher eventDispatcher) {
        loadingDispatcher = eventDispatcher != null ? new WeakReference<>(eventDispatcher) : null;
    }

    /**
     * Injection point: translations of a loaded post.
     * Shows a hint if the "Translate" button found no translation into the target language.
     */
    public static void onPostLoaded(@Nullable Link link) {
        WeakReference<EventDispatcher> dispatcher = loadingDispatcher;
        loadingDispatcher = null;
        EventDispatcher postDispatcher = dispatcher != null ? dispatcher.get() : null;
        if (link != null && postDispatcher != null) {
            String id = link.getKindWithId();
            if (id != null) {
                POST_DISPATCHERS.put(id, dispatcher);
            }
            if (id != null) {
                lastLoadedPostId = id;
                // The post can come translated from the app cache, or be translated by Reddit later.
                if (link.isTranslated()) {
                    TranslationService service = getTranslationService(link);
                    if (service != null) {
                        POST_SERVICES.put(id, service);
                    } else {
                        POST_SERVICES.remove(id);
                    }
                }
                try {
                    Object screen = postDispatcher.patch_getScreen();
                    if (screen != null) {
                        SCREEN_POSTS.put(screen, id);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Post detail screen not found", ex);
                }
            }
        }
        try {
            if (link == null || !link.isMtSeoDeepLink() || link.isTranslated()) {
                return;
            }
            String id = link.getKindWithId();
            if (id == null || !id.equals(buttonRequestId)
                    || System.currentTimeMillis() - buttonRequestTime > BUTTON_REQUEST_TIMEOUT_MS) {
                return;
            }
            buttonRequestId = null;

            String language = getTargetLanguage();
            Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
            String name = Locale.forLanguageTag(language).getDisplayLanguage(system);
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(language)) {
                name = language;
            }
            Utils.showToastShort(str("morphe_translation_unavailable_toast", name));
        } catch (Exception ex) {
            Logger.printException(() -> "onPostLoaded failure", ex);
        }
    }

    /**
     * Injection point: posts loaded by id.
     * Translates with the translation service the posts that were requested in MT SEO mode but are not translated.
     * Runs on the coroutine thread of the request.
     */
    public static List<?> translateLoadedPosts(@Nullable List<?> links, boolean mtSeo) {
        if (!mtSeo || links == null || links.isEmpty()) {
            return links;
        }
        try {
            if (!Settings.TRANSLATION_FALLBACK.get() || Utils.isCurrentlyOnMainThread()) {
                return links;
            }
            TranslationService service = TranslationService.selected();
            String language = lastRequestedLanguage;
            if (language == null || language.isEmpty()) {
                language = getTargetLanguage();
            }

            List<Object> result = null;
            for (int i = 0, size = links.size(); i < size; i++) {
                if (!(links.get(i) instanceof Link link)) {
                    continue;
                }
                if (link.isTranslated() || link.getPromoted() || isPostInLanguage(link, language)) {
                    continue;
                }
                Link translated = translateWithService(link, language, service);
                if (translated != null) {
                    if (result == null) {
                        result = new ArrayList<>(links);
                    }
                    result.set(i, translated);
                }
            }
            return result != null ? result : links;
        } catch (Exception ex) {
            Logger.printException(() -> "translateLoadedPosts failure", ex);
            return links;
        }
    }

    @Nullable
    private static Link translateWithService(Link link, String language, TranslationService service) {
        String id = link.getKindWithId();
        String key = id + "|" + language + "|" + service.name();
        try {
            String[] translation = FALLBACK_CACHE.get(key);
            if (translation == null) {
                translation = requestServiceTranslation(link, language, service);
                FALLBACK_CACHE.put(key, translation);
            }

            // Media is taken as is by the merge: keep the media of the post.
            TranslatedLink translatedLink = new TranslatedLink(id, language, translation[0], translation[2],
                    translation[1], null, null, link.getThumbnail(), link.getPreview(), link.getGallery());
            Link merged = mergeTranslation(translatedLink, link);

            if (isTranslatedField == null) {
                Field field = Link.class.getDeclaredField("isTranslated");
                field.setAccessible(true);
                isTranslatedField = field;
            }
            isTranslatedField.setBoolean(merged, true);
            rememberServiceTranslation(id, merged.getTitle(), service);
            if (id != null) {
                POST_SERVICES.put(id, service);
            }
            return merged;
        } catch (IOException | TranslationHttpException ex) {
            onServiceError(service, service + " translation failed for " + id, ex);
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> service + " translation failed for " + id, ex);
            return null;
        }
    }

    @Nullable
    private static SharedPreferences getPreferences(String name) {
        Context context = Utils.getContext();
        return context != null ? context.getSharedPreferences(name, Context.MODE_PRIVATE) : null;
    }

    /**
     * @return Posts translated by a translation service, oldest first: kind_id -> "SERVICE:title hash".
     */
    private static synchronized LinkedHashMap<String, String> getFallbackTitles() {
        if (fallbackTitles == null) {
            fallbackTitles = new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > FALLBACK_TITLES_MAX;
                }
            };
            try {
                SharedPreferences legacy = getPreferences(LEGACY_YANDEX_TITLES_PREFERENCES);
                String legacySaved = legacy != null ? legacy.getString(FALLBACK_TITLES_KEY, null) : null;
                if (legacySaved != null) {
                    JSONObject titles = new JSONObject(legacySaved);
                    for (Iterator<String> ids = titles.keys(); ids.hasNext(); ) {
                        String id = ids.next();
                        fallbackTitles.put(id, TranslationService.YANDEX.name() + ":" + titles.getInt(id));
                    }
                }

                SharedPreferences preferences = getPreferences(FALLBACK_TITLES_PREFERENCES);
                String saved = preferences != null ? preferences.getString(FALLBACK_TITLES_KEY, null) : null;
                if (saved != null) {
                    JSONObject titles = new JSONObject(saved);
                    for (Iterator<String> ids = titles.keys(); ids.hasNext(); ) {
                        String id = ids.next();
                        fallbackTitles.put(id, titles.getString(id));
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Translated titles cannot be loaded", ex);
            }
        }
        return fallbackTitles;
    }

    private static synchronized void rememberServiceTranslation(@Nullable String id, @Nullable String title,
                                                                TranslationService service) {
        if (id == null || title == null) {
            return;
        }
        LinkedHashMap<String, String> titles = getFallbackTitles();
        titles.put(id, service.name() + ":" + title.hashCode());
        SharedPreferences preferences = getPreferences(FALLBACK_TITLES_PREFERENCES);
        if (preferences != null) {
            preferences.edit().putString(FALLBACK_TITLES_KEY, new JSONObject(titles).toString()).apply();
        }
    }

    /**
     * @return Translation service of a translated post, or null if the post is translated by Reddit.
     */
    @Nullable
    private static synchronized TranslationService getTranslationService(Link link) {
        try {
            String id = link.getKindWithId();
            String title = link.getTitle();
            String saved = id != null ? getFallbackTitles().get(id) : null;
            if (saved == null || title == null) {
                return null;
            }
            int separator = saved.indexOf(':');
            // The post can be translated by Reddit later.
            if (separator < 0 || !saved.substring(separator + 1).equals(String.valueOf(title.hashCode()))) {
                return null;
            }
            return TranslationService.fromName(saved.substring(0, separator));
        } catch (Exception ex) {
            Logger.printException(() -> "getTranslationService failure", ex);
            return null;
        }
    }

    /**
     * @return {title, rich text, html}, null entries are not translated.
     */
    private static String[] requestServiceTranslation(Link link, String language, TranslationService service)
            throws Exception {
        List<String> fragments = new ArrayList<>();
        String title = link.getTitle();
        boolean hasTitle = title != null && !title.trim().isEmpty();
        if (hasTitle) {
            fragments.add(RichTextTranslator.escape(title));
        }

        RichTextResponse richTextResponse = link.getRtjson();
        String richText = richTextResponse != null ? richTextResponse.getRichTextString() : null;
        RichTextTranslator document = richText != null && !richText.isEmpty()
                ? RichTextTranslator.prepare(richText) : null;
        String html = link.getSelftextHtml();
        boolean translateHtml = richText == null && html != null && !html.trim().isEmpty();

        int documentStart = fragments.size();
        if (document != null) {
            fragments.addAll(document.fragments());
        } else if (translateHtml) {
            fragments.add(html);
        }
        if (fragments.isEmpty()) {
            return new String[3];
        }

        List<String> translated = service.translateHtml(fragments, language);
        onServiceSuccess();
        String[] result = new String[3];
        if (hasTitle) {
            result[0] = RichTextTranslator.parse(translated.get(0)).text;
        }
        if (document != null) {
            result[1] = document.apply(translated.subList(documentStart, translated.size()));
        } else if (translateHtml) {
            result[2] = translated.get(documentStart);
        }
        return result;
    }

    // endregion

    // region Comment menu

    /**
     * Injection point: comment menu, before {@link #isCommentTranslateVisible(boolean, boolean)}.
     */
    public static void setOverflowMenuComment(@Nullable Object comment) {
        overflowMenuComment = comment != null ? new WeakReference<>(comment) : null;
        if (comment instanceof Comment loaded) {
            cacheComment(loaded);
        }
    }

    private static void cacheComment(Comment comment) {
        String id = comment.getKindWithId();
        if (id != null) {
            COMMENTS.put(id, new WeakReference<>(comment));
        }
    }

    /**
     * Injection point: comment menu.
     *
     * @param original              If the app shows the "Translate" action.
     * @param isShowOriginalVisible If the comment is translated and "Show original" is shown.
     * @return If the "Translate" action is shown.
     */
    public static boolean isCommentTranslateVisible(boolean original, boolean isShowOriginalVisible) {
        if (original || isShowOriginalVisible || !Settings.TRANSLATION_COMMENT_MENU.isAvailable()
                || !Settings.TRANSLATION_COMMENT_MENU.get()) {
            return original;
        }
        try {
            WeakReference<Object> reference = overflowMenuComment;
            overflowMenuComment = null;
            Object comment = reference != null ? reference.get() : null;
            if (comment instanceof Comment menuComment) {
                // Translated by Reddit in MT SEO mode. The flag stays after "Show original" of the post,
                // when the comments are shown in the original.
                if (menuComment.isTranslated() && isShowingServerTranslation(menuComment)) {
                    return false;
                }
                String language = menuComment.getLanguageCode();
                if (language != null && !language.isEmpty()
                        && baseLanguage(language).equals(baseLanguage(getTargetLanguage()))) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "isCommentTranslateVisible failure", ex);
            return original;
        }
    }

    /**
     * @return If the comments of the post of a comment are shown with the Reddit translation (MT SEO mode).
     */
    private static boolean isShowingServerTranslation(Comment comment) {
        if (!Settings.TRANSLATE_ALL_POSTS.get()) {
            // Only the post is translated in place, the comments are loaded in the original.
            return false;
        }
        String postId = comment.getLinkKindWithId();
        return postId == null || !SHOW_ORIGINAL.contains(postId);
    }

    /**
     * Injection point: "Translate" event of the comment menu, with the event dispatcher of the comments.
     */
    public static void onTranslateCommentEvent(@Nullable TranslateCommentEvent event, @Nullable Object eventDispatcher) {
        if (event == null || eventDispatcher == null) {
            return;
        }
        try {
            String id = event.patch_getCommentId();
            if (id != null) {
                PENDING_COMMENT_EVENTS.put(id, new WeakReference<?>[]{
                        new WeakReference<>(event), new WeakReference<>(eventDispatcher)});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateCommentEvent failure", ex);
        }
    }

    /**
     * Injection point: translated comment loaded from Reddit ("Translate" in the comment menu).
     * Translates the comment with the translation service if Reddit did not translate it.
     * <p>
     * Called on the main thread: the comment is translated in the background and stored in the
     * translation cache of the app, then the "Translate" event is sent again and the app finds
     * the translation in its cache.
     *
     * @param translationKey Cache key of the translation (comment id, language).
     * @param result         TranslatedComment, or null.
     * @param repository     RedditTranslationsRepository.
     */
    @Nullable
    public static Object translateComment(TranslationKey translationKey, @Nullable Object result,
                                          TranslationsRepository repository) {
        if (result != null) {
            return result;
        }
        try {
            if (!Settings.TRANSLATION_FALLBACK.get()) {
                return null;
            }
            String id = translationKey.patch_getCommentId();
            if (id == null) {
                return null;
            }
            if (!Utils.isCurrentlyOnMainThread()) {
                return translateCommentWithService(id, repository);
            }

            // The event was already sent again: the translation is not in the cache, do not loop.
            Long resent = RESENT_COMMENT_EVENTS.remove(id);
            if (resent != null && System.currentTimeMillis() - resent < COMMENT_EVENT_RESEND_TIMEOUT_MS) {
                Logger.printDebug(() -> "Comment translation not found after the event was sent again: " + id);
                return null;
            }

            WeakReference<?>[] pending = PENDING_COMMENT_EVENTS.remove(id);
            if (pending == null) {
                return null;
            }
            WeakReference<?> event = pending[0];
            WeakReference<?> dispatcher = pending[1];
            Utils.runOnBackgroundThread(() -> {
                Object translation = translateCommentWithService(id, repository);
                if (translation == null) {
                    return;
                }
                repository.patch_putTranslatedComment(translationKey, translation);
                Utils.runOnMainThread(() -> {
                    Object pendingEvent = event.get();
                    Object pendingDispatcher = dispatcher.get();
                    if (pendingEvent != null && pendingDispatcher != null) {
                        RESENT_COMMENT_EVENTS.put(id, System.currentTimeMillis());
                        invokeFunction(pendingDispatcher, pendingEvent);
                    }
                });
            });
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "translateComment failure", ex);
            return null;
        }
    }

    /**
     * @return TranslatedComment, or null if the comment is not loaded or cannot be translated.
     */
    @Nullable
    private static Object translateCommentWithService(String id, TranslationsRepository repository) {
        TranslationService service = TranslationService.selected();
        WeakReference<Comment> reference = COMMENTS.get(id);
        Comment comment = reference != null ? reference.get() : null;
        if (comment == null) {
            return null;
        }
        try {
            CommentText text = CommentText.of(comment);
            if (text == null) {
                return null;
            }
            List<String> translated = service.translateHtml(text.fragments, getTargetLanguage());
            onServiceSuccess();
            Object[] translation = text.translation(translated);
            repository.patch_putOriginalComment(id, (OriginalComment) translation[1]);
            return translation[0];
        } catch (IOException | TranslationHttpException ex) {
            onServiceError(service, service + " translation failed for " + id, ex);
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> service + " translation failed for " + id, ex);
            return null;
        }
    }

    /**
     * Text of a comment sent to the translation service.
     */
    private static final class CommentText {
        final Comment comment;
        @Nullable
        final String richText;
        @Nullable
        final String markdown;
        @Nullable
        final RichTextTranslator document;
        final List<String> fragments;

        private CommentText(Comment comment, @Nullable String richText, @Nullable String markdown,
                            @Nullable RichTextTranslator document, List<String> fragments) {
            this.comment = comment;
            this.richText = richText;
            this.markdown = markdown;
            this.document = document;
            this.fragments = fragments;
        }

        /**
         * @return Text of the comment, or null if the comment has no text.
         */
        @Nullable
        static CommentText of(Comment comment) throws Exception {
            RichTextResponse richTextResponse = comment.getRtjson();
            String richText = richTextResponse != null ? richTextResponse.getRichTextString() : null;
            RichTextTranslator document = richText != null && !richText.isEmpty()
                    ? RichTextTranslator.prepare(richText) : null;
            String markdown = comment.getBody();

            // The rich text and the markdown are the same text: translate only one of them.
            List<String> fragments;
            if (document != null) {
                fragments = document.fragments();
            } else if (markdown != null && !markdown.trim().isEmpty()) {
                fragments = Collections.singletonList(RichTextTranslator.escape(markdown));
            } else {
                return null;
            }
            return fragments.isEmpty() ? null : new CommentText(comment, richText, markdown, document, fragments);
        }

        /**
         * @param translated Translations of the fragments.
         * @return {TranslatedComment, OriginalComment}
         */
        Object[] translation(List<String> translated) throws Exception {
            String translatedRichText = document != null ? document.apply(translated) : null;
            StringBuilder translatedText = new StringBuilder();
            for (String fragment : translated) {
                if (translatedText.length() > 0) {
                    translatedText.append('\n');
                }
                translatedText.append(RichTextTranslator.parse(fragment).text);
            }
            return new Object[]{
                    newTranslatedComment(translatedRichText, translatedText.toString(),
                            translatedText.toString(), comment.getLinkKindWithId()),
                    new OriginalComment(markdown, richText, comment.getBodyPreview(), true)
            };
        }
    }

    /**
     * A network or service failure of the translation service: shown to the user once, then only logged.
     */
    private static void onServiceError(TranslationService service, String message, Exception ex) {
        Logger.printDebug(() -> message, ex);
        if (!reportNextServiceError) {
            return;
        }
        reportNextServiceError = false;
        String reason = ex instanceof TranslationHttpException http
                ? str(http.statusCode == 429
                        ? "morphe_translation_service_error_rate_limit"
                        : "morphe_translation_service_error_status", http.statusCode)
                : str("morphe_translation_service_error_network");
        Utils.showToastShort(str("morphe_translation_service_error_toast", service.displayName(), reason));
    }

    private static void onServiceSuccess() {
        reportNextServiceError = true;
    }

    // endregion

    // region In place translation

    /**
     * Injection point: reload of the post and the comments of a post detail screen.
     * Switches the screen to MT SEO mode if the "Translate" action requested it.
     */
    public static void beforeRefresh(RefreshHandler refreshHandler) {
        String id = pendingRefreshId;
        if (id == null) {
            return;
        }
        Link link = null;
        try {
            link = refreshHandler.patch_getScreenPost();
            if (link == null || !id.equals(link.getKindWithId())) {
                return;
            }
            pendingRefreshId = null;
            String language = pendingRefreshLanguage;
            // Only the post: the comments are translated one by one from the comment menu.
            if (refreshHandler.patch_switchToMtSeo(language)) {
                lastRequestedLanguage = language;
                return;
            }
            Logger.printDebug(() -> "Screen cannot be switched to MT SEO mode");
        } catch (Exception ex) {
            Logger.printException(() -> "beforeRefresh failure", ex);
        }
        // Open the translated post instead.
        FORCED.remove(id);
        if (link != null) {
            openTranslatedPost(link);
        }
    }

    /**
     * Injection point: comments are loaded (a page, more comments or replies).
     * <p>
     * In MT SEO mode the server returns only the comments it has already translated and translates the rest
     * on demand, so the first load is often empty or partial. The post is reloaded, the same way as a manual
     * pull to refresh, while the comments are missing.
     */
    public static void onCommentsLoaded(@Nullable Object loadType, @Nullable Object result,
                                        @Nullable Object context, @Nullable CommentLinkInfo commentLink) {
        try {
            List<?> items = result != null ? getCommentItems(result) : null;
            if (items != null) {
                for (Object item : items) {
                    if (item instanceof Comment comment) {
                        cacheComment(comment);
                    }
                }
            }

            if (items == null || commentLink == null || context == null
                    || !context.getClass().getName().endsWith("CommentScreenContext$MtSeo")) {
                return;
            }
            String postId = commentLink.patch_getPostId();
            if (postId == null || SHOW_ORIGINAL.contains(postId)) {
                return;
            }

            if (loadType != null && "PageLoad".equals(loadType.toString())) {
                retryIncompleteComments(postId, commentLink.patch_getNumComments(), items);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCommentsLoaded failure", ex);
        }
    }

    /**
     * Reloads the post if the first page of comments is empty or incomplete.
     */
    private static void retryIncompleteComments(String postId, long numComments, List<?> items) {
        int loaded = 0;
        for (Object item : items) {
            if (item instanceof Comment) {
                loaded++;
            }
        }

        long expected = Math.min(numComments, COMMENTS_EXPECTED_MAX);
        int retries;
        if (loaded == 0) {
            // Nothing on screen is replaced by the reload.
            retries = numComments > 0 ? COMMENTS_RETRY_DELAYS_MS.length : 0;
        } else if (loaded < expected * COMMENTS_SPARSE_RATIO) {
            retries = COMMENTS_SPARSE_RETRIES;
        } else {
            retries = 0;
        }

        synchronized (PostTranslationPatch.class) {
            long now = System.currentTimeMillis();
            if (!postId.equals(retryPostId) || now - retryStartTime > COMMENTS_RETRY_TIMEOUT_MS) {
                retryPostId = postId;
                retryAttempts = 0;
                retryStartTime = now;
                retryPending = false;
            }
            // The comments can come in several loads: the last load decides.
            retryNeeded = retryAttempts < retries;
            if (!retryNeeded || retryPending) {
                return;
            }
            retryPending = true;
            final int attempt = retryAttempts;
            final int loadedComments = loaded;
            Logger.printDebug(() -> "Comments of " + postId + " reloaded, attempt " + (attempt + 1)
                    + ", loaded " + loadedComments + " of " + numComments);
            scheduleRefresh(postId, COMMENTS_RETRY_DELAYS_MS[retryAttempts++]);
        }
    }

    private static void scheduleRefresh(String postId, long delayMs) {
        Utils.runOnMainThreadDelayed(() -> {
            synchronized (PostTranslationPatch.class) {
                if (!postId.equals(retryPostId)) {
                    return;
                }
                retryPending = false;
                if (!retryNeeded) {
                    return;
                }
            }
            WeakReference<EventDispatcher> reference = POST_DISPATCHERS.get(postId);
            EventDispatcher dispatcher = reference != null ? reference.get() : null;
            if (dispatcher != null) {
                dispatchRefresh(dispatcher);
            }
        }, delayMs);
    }

    /**
     * @return Comments and "load more" items of a comments load result.
     */
    @Nullable
    private static List<?> getCommentItems(Object result) throws Exception {
        for (Field field : result.getClass().getDeclaredFields()) {
            if (field.getType() == ArrayList.class) {
                field.setAccessible(true);
                return (List<?>) field.get(result);
            }
        }
        return null;
    }

    private static boolean dispatchRefresh(EventDispatcher eventDispatcher) {
        try {
            eventDispatcher.patch_dispatchEvent(PostDetailRefreshEvent.INSTANCE);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "dispatchRefresh failure", ex);
            return false;
        }
    }

    private static boolean openTranslatedPost(Link link) {
        Activity activity = Utils.getActivity();
        Context context = activity != null ? activity : Utils.getContext();
        if (context == null) {
            return false;
        }

        String path = link.getPermalink();
        if (path != null && path.startsWith("http")) {
            path = Uri.parse(path).getEncodedPath();
        }
        if (path == null || !path.startsWith("/")) {
            String id = link.getId();
            if (id == null) {
                return false;
            }
            path = "/comments/" + id + "/";
        }

        Uri uri = new Uri.Builder()
                .scheme("https")
                .authority("www.reddit.com")
                .encodedPath(path)
                .appendQueryParameter("tl", getTargetLanguage())
                .build();

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage(context.getPackageName());
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        Utils.runOnMainThread(() -> {
            try {
                context.startActivity(intent);
            } catch (Exception ex) {
                Logger.printException(() -> "startActivity failure", ex);
            }
        });
        return true;
    }

    // endregion

    // region Language

    private static boolean isPostInLanguage(Link link, String language) {
        String postLanguage = link.getLanguageCode();
        if (postLanguage == null || postLanguage.isEmpty()) {
            return false;
        }
        return baseLanguage(postLanguage).equals(baseLanguage(language));
    }

    /**
     * @return ISO 639 code of the translation language, in lower case.
     */
    public static String getTargetLanguage() {
        String language = Settings.TRANSLATION_LANGUAGE.get().trim();
        if (language.isEmpty() || "DEFAULT".equalsIgnoreCase(language)) {
            // The language Reddit itself translates into.
            language = Requester.getAppLocale().getLanguage();
        }
        return normalizeLanguage(language.toLowerCase(Locale.ROOT));
    }

    private static String normalizeLanguage(String language) {
        // Android still reports the deprecated ISO 639 codes.
        return switch (language) {
            case "iw" -> "he";
            case "in" -> "id";
            case "ji" -> "yi";
            default -> language;
        };
    }

    private static String baseLanguage(String language) {
        String lower = language.toLowerCase(Locale.ROOT);
        int separator = lower.indexOf('-');
        if (separator < 0) {
            separator = lower.indexOf('_');
        }
        return separator > 0 ? lower.substring(0, separator) : lower;
    }

    // endregion
}

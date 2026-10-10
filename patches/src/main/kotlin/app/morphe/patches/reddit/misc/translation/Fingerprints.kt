/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.translation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.instanceOf
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal const val TRANSLATION_HANDLERS_PACKAGE =
    "Lcom/reddit/postdetail/refactor/events/handlers/translation/"

internal const val POST_DETAIL_REFRESH_EVENT_HANDLER_CLASS =
    "Lcom/reddit/postdetail/refactor/events/handlers/PostDetailRefreshEventHandler;"

internal const val LINK_CLASS = "Lcom/reddit/domain/model/Link;"

internal const val COMMENT_LINK_CLASS = "Lcom/reddit/comments/models/CommentLink;"

/**
 * Builds the post context (all comments, single comment thread, MT SEO...) from the post detail screen arguments.
 */
internal object PostDetailScreenContextFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        string("com.reddit.arg.context_mvp"),
        string("sct_replacement_enabled"),
        string("mt_language")
    )
)

/**
 * RedditTranslationBannerDelegate.shouldShowTranslationBanner().
 */
internal object TranslationBannerDelegateFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        LINK_CLASS,
        "Z",
        "Lcom/reddit/localization/translations/TranslationState;",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    ),
    filters = listOf(
        instanceOf($$"Lcom/reddit/localization/translations/RedditTranslationBannerDelegate$shouldShowTranslationBanner$1;")
    )
)

internal object TranslationBannerEventHandlerFingerprint : Fingerprint(
    definingClass = TRANSLATION_HANDLERS_PACKAGE + "TranslationBannerEventHandler;",
    name = "handleEvent",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lcom/reddit/postdetail/refactor/events/translation/TranslationBannerEvent;", "L", "L"),
    filters = listOf(
        instanceOf($$"Lcom/reddit/postdetail/refactor/events/translation/TranslationBannerEvent$ViewBanner;")
    )
)

internal object TranslatePostEventHandlerFingerprint : Fingerprint(
    definingClass = TRANSLATION_HANDLERS_PACKAGE + "TranslatePostEventHandler;",
    name = "handleEvent",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lcom/reddit/postdetail/refactor/events/translation/TranslatePostEvent;", "L", "L"),
    filters = listOf(
        methodCall(
            definingClass = TRANSLATION_HANDLERS_PACKAGE + "TranslatePostEventHandler;",
            name = "sendClickEvent"
        )
    )
)

internal object TranslatePostMergeLambdaFingerprint : Fingerprint(
    definingClass = TRANSLATION_HANDLERS_PACKAGE + "TranslatePostEventHandler;",
    name = $$"updateLinkAndCrossPostLinkWithTranslatedLink$lambda$0$0",
    returnType = LINK_CLASS,
    parameters = listOf("Lcom/reddit/localization/translations/TranslatedLink;", LINK_CLASS),
    filters = listOf(
        methodCall(
            parameters = listOf("Lcom/reddit/localization/translations/TranslatedLink;", LINK_CLASS),
            returnType = LINK_CLASS
        )
    )
)

internal object ShowOriginalPostEventHandlerFingerprint : Fingerprint(
    definingClass = TRANSLATION_HANDLERS_PACKAGE + "ShowOriginalPostEventHandler;",
    name = "fetchAndUpdateLinkForMtSeo",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(LINK_CLASS, "L")
)

internal object RefreshPostAndCommentsFingerprint : Fingerprint(
    definingClass = POST_DETAIL_REFRESH_EVENT_HANDLER_CLASS,
    name = "refreshPostAndComments",
    returnType = "Ljava/lang/Object;"
)

/**
 * PostDetailLoadedTranslationDelegate.updateTranslations().
 */
internal object UpdatePostTranslationsFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "L"),
    filters = listOf(
        newInstance($$"Lcom/reddit/postdetail/refactor/translation/PostDetailLoadedTranslationDelegate$updateTranslations$1;"),
        methodCall(definingClass = LINK_CLASS, name = "getPromoted")
    )
)

/**
 * RemoteGqlLinkDataSource: marks the posts loaded by id as MT SEO posts.
 */
internal object MarkMtSeoPostsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/util/List;",
    parameters = listOf("Ljava/util/List;", "Z"),
    custom = { _, classDef ->
        classDef.methods.any { method ->
            method.name == "<init>" && method.parameterTypes.contains(
                "Lcom/reddit/data/model/graphql/GqlPostToLinkDomainModelMapper;"
            )
        }
    }
)

/**
 * RedditCommentsLoader: handles a loaded page of comments.
 */
internal object CommentsLoadedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "L",
        "Lcom/reddit/localization/translations/CommentLoadType;",
        "L",
        "Lcom/reddit/comments/models/CommentScreenContext;",
        "Lcom/reddit/comments/models/CommentLink;",
        "L"
    )
)

/**
 * Creates the CommentLink of a post.
 */
internal object CommentLinkFromLinkFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = COMMENT_LINK_CLASS,
    filters = listOf(
        methodCall(definingClass = LINK_CLASS, name = "getKindWithId"),
        methodCall(definingClass = LINK_CLASS, name = "getNumComments"),
        methodCall(definingClass = COMMENT_LINK_CLASS, name = "<init>")
    ),
    custom = { method, _ ->
        method.parameterTypes.firstOrNull() == LINK_CLASS
    }
)

/**
 * Title of the translation banner.
 */
internal object TranslationBannerTitleFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "translation_banner_title_label_active"),
        resourceLiteral(ResourceType.STRING, "translation_banner_title_label_available")
    )
)

/**
 * Translation banner section of a post detail screen: shows the banner with the post detail context.
 */
internal object PostDetailTranslationBannerFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/postdetail/refactor/ui/composables/PostDetailLazyListSectionsKt$translationBanner$1$1$1;"),
        methodCall(
            parameters = listOf(
                "Z", "Z", "Lcom/reddit/localization/translations/TranslationBannerTrailingActionType;",
                "Lkotlin/jvm/functions/Function1;", "Lkotlin/jvm/functions/Function1;", "L", "L", "I"
            ),
            returnType = "V"
        )
    )
)

/**
 * Builds the comment menu.
 */
internal object CommentOverflowMenuFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/comments/events/handler/OnClickCommentOverflowMenuEventHandler$handle$2;",
    name = "invokeSuspend",
    filters = listOf(
        methodCall(definingClass = "Lcom/reddit/domain/model/Comment;", name = "isTranslatable")
    )
)

internal const val TRANSLATIONS_REPOSITORY_PREFIX =
    "Lcom/reddit/localization/translations/data/RedditTranslationsRepository$"

/**
 * RedditTranslationsRepository.fetchCommentsTranslation().
 */
internal object FetchCommentsTranslationFingerprint : Fingerprint(
    returnType = "Ljava/io/Serializable;",
    filters = listOf(
        newInstance(TRANSLATIONS_REPOSITORY_PREFIX + "fetchCommentsTranslation$1;"),
        methodCall(returnType = "Lcom/reddit/localization/translations/comments/OriginalComment;")
    )
)

/**
 * RedditTranslationsRepository.getTranslatedComment().
 */
internal object GetTranslatedCommentFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;", "Z"),
    filters = listOf(
        instanceOf(TRANSLATIONS_REPOSITORY_PREFIX + "getTranslatedComment$1;")
    )
)

/**
 * OnClickTranslateEventHandler: "Translate" in the comment menu.
 */
internal object TranslateCommentEventHandlerFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        instanceOf("Lcom/reddit/comments/events/handler/translation/OnClickTranslateEventHandler\$handle\$1;")
    )
)

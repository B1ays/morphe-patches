/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.translation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.removeFlags
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

// Modifies the app code found by resolveTranslationAbi().

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/PostTranslationPatch;"

// Interfaces of the extension, added to the app classes.
private const val REFRESH_HANDLER = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$RefreshHandler;"
private const val EVENT_DISPATCHER = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$EventDispatcher;"
private const val COMMENT_LINK_INFO = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$CommentLinkInfo;"
private const val TRANSLATIONS_REPOSITORY = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$TranslationsRepository;"
private const val TRANSLATION_KEY = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$TranslationKey;"
private const val TRANSLATE_COMMENT_EVENT = $$"Lapp/morphe/extension/reddit/patches/PostTranslationPatch$TranslateCommentEvent;"

private const val ORIGINAL_COMMENT = "Lcom/reddit/localization/translations/comments/OriginalComment;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val CALLABLE_REFERENCE = "Lkotlin/jvm/internal/CallableReference;"

/** Receiver of a function reference (p0 for a static method, "this" field for an instance method): register v0. */
private fun boundReceiverSmali(functionRegister: String) = """
    instance-of v1, $functionRegister, $CALLABLE_REFERENCE
    if-eqz v1, :not_reference
    check-cast $functionRegister, $CALLABLE_REFERENCE
    invoke-virtual { $functionRegister }, $CALLABLE_REFERENCE->getBoundReceiver()Ljava/lang/Object;
    move-result-object v0
    return-object v0
    :not_reference
    const/4 v0, 0x0
    return-object v0
"""
private const val CONCURRENT_HASH_MAP_PUT =
    "Ljava/util/concurrent/ConcurrentHashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * A method of an extension interface, generated in an app class.
 *
 * @param registers Registers of the method, including "this" and the parameters.
 */
private class InterfaceMethod(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val registers: Int,
    val smali: String,
)

private fun BytecodePatchContext.implementInterface(
    classType: String,
    interfaceType: String,
    vararg methods: InterfaceMethod,
) = mutableClassDefBy(classType).apply {
    interfaces.add(interfaceType)
    methods.forEach { method -> addMethod(method) }
}

private fun MutableClass.addMethod(method: InterfaceMethod) {
    methods.add(
        ImmutableMethod(
            type,
            method.name,
            method.parameters.map { ImmutableMethodParameter(it, null, null) },
            method.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(method.registers),
        ).toMutable().apply { addInstructionsWithLabels(0, method.smali) }
    )
}

/**
 * Replaces a static bridge stub of the extension with code that calls obfuscated static methods
 * or constructors of the app.
 */
private fun BytecodePatchContext.replaceStaticBridge(name: String, smali: String) {
    mutableClassDefBy(EXTENSION_CLASS).apply {
        val stub = methods.single { it.name == name }
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        bridge.addInstructionsWithLabels(0, smali)
        methods.remove(stub)
        methods.add(bridge)
    }
}

internal fun BytecodePatchContext.installTranslation(abi: TranslationAbi) {
    installScreenContext(abi.screenContext)
    installMtSeoChecks(abi.mtSeoChecks)
    installBanner(abi.banner)
    installPostLoading(abi.posts)
    installInPlaceTranslation(abi.refresh, abi.screenContext)
    installComments(abi.comments)
}

private fun BytecodePatchContext.installScreenContext(context: ScreenContextAbi) = with(context) {
    // Modify the later index first.
    method.addInstructions(
        languageIndex + 1,
        """
            invoke-static { v$languageRegister, v$continuationRegister, v$sctReplacementRegister }, $EXTENSION_CLASS->getMtLanguage(Ljava/lang/String;ZZ)Ljava/lang/String;
            move-result-object v$languageRegister
        """
    )

    method.addInstructionsWithLabels(
        defaultContextIndex,
        """
            invoke-static/range { v$argumentsRegister .. v$argumentsRegister }, $EXTENSION_CLASS->getMtSeoPostContext(Landroid/os/Bundle;)Ljava/lang/Object;
            move-result-object v$contextRegister
            if-eqz v$contextRegister, :original
            check-cast v$contextRegister, $postContextType
            goto :done
            :original
            nop
        """,
        ExternalLabel("done", contextDone)
    )

    replaceStaticBridge(
        "newMtSeoPostContext",
        """
            new-instance v0, $mtSeoPostContextType
            const/4 v1, 0x0
            invoke-direct { v0, v1, p0, v1 }, $mtSeoPostContextType->$MT_SEO_CONTEXT_CONSTRUCTOR
            return-object v0
        """
    )
}

private fun installMtSeoChecks(checks: List<CallSite>) = checks.forEach { check ->
    check.method.replaceInstruction(
        check.index,
        "invoke-static { v${check.registers[0]} }, $EXTENSION_CLASS->isMtSeoActive($LINK_CLASS)Z"
    )
}

private fun BytecodePatchContext.installBanner(banner: BannerAbi) = with(banner) {
    val delegateFree = delegate.registers[0]
    delegate.method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->shouldForceTranslateButton(${LINK_CLASS}Z)Z
            move-result v$delegateFree
            if-eqz v$delegateFree, :original
            sget-object v$delegateFree, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            return-object v$delegateFree
            :original
            nop
        """
    )

    // The forced banner ignores the "banner dismissed" preference.
    val (link, free, handler) = viewBanner.registers
    viewBanner.method.addInstructionsAtControlFlowLabel(
        viewBanner.index,
        """
            iget-object v$free, v$handler, ${mtSeoProviderField.smali()}
            invoke-virtual { v$free }, $mtSeoCheck
            move-result v$free
            invoke-static { v$link, v$free }, $EXTENSION_CLASS->shouldForceTranslateButton(${LINK_CLASS}Z)Z
            move-result v$free
            if-nez v$free, :show
        """,
        ExternalLabel("show", bannerShown)
    )

    // Source of the translation in the banner title.
    val (titleRegister, activeRegister) = title.registers
    title.method.addInstructions(
        title.index + 1,
        """
            invoke-static { v$titleRegister, v$activeRegister }, $EXTENSION_CLASS->getTranslationBannerTitle(Ljava/lang/String;Z)Ljava/lang/String;
            move-result-object v$titleRegister
        """
    )

    // "Translate" of the banner and of the post menu.
    val (event, translateLink, dispatcher, translateFree) = translate.registers
    translate.method.addInstructionsAtControlFlowLabel(
        translate.index,
        """
            invoke-virtual { v$event }, $eventSourceMethod
            move-result-object v$translateFree
            invoke-virtual { v$translateFree }, $userInitiatedMethod
            move-result v$translateFree
            invoke-static { v$translateLink, v$translateFree, v$dispatcher }, $EXTENSION_CLASS->onTranslateClicked(${LINK_CLASS}Z$EVENT_DISPATCHER)Z
            move-result v$translateFree
            if-eqz v$translateFree, :original
            sget-object v$translateFree, ${unitField.smali()}
            return-object v$translateFree
            :original
            nop
        """
    )

    showOriginal.method.addInstructionsAtControlFlowLabel(
        showOriginal.index,
        "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onShowOriginal($LINK_CLASS)V"
    )

    // The title of the banner is composed inside the section: the source of the translation of its post.
    val (contextRegister, sectionFree) = section.registers
    // Modify the later index first.
    section.method.addInstructionsAtControlFlowLabel(
        sectionBannerIndex + 1,
        "invoke-static { }, $EXTENSION_CLASS->clearBannerScreen()V"
    )
    section.method.addInstructions(
        section.index,
        """
            iget-object v$sectionFree, v$contextRegister, ${postDetailContextFunctionField.smali()}
            invoke-static { v$sectionFree }, $EXTENSION_CLASS->setBannerScreen(Ljava/lang/Object;)V
        """
    )

    replaceStaticBridge("getScreenOfFunction", boundReceiverSmali("p0"))
}

private fun BytecodePatchContext.installPostLoading(posts: PostLoadingAbi) = with(posts) {
    val link = loaded.registers[0]
    loaded.method.addInstructionsAtControlFlowLabel(
        loaded.index,
        """
            invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->setPostEventDispatcher($EVENT_DISPATCHER)V
            invoke-static/range { v$link .. v$link }, $EXTENSION_CLASS->onPostLoaded($LINK_CLASS)V
        """
    )

    // Later indices first.
    markMtSeo.sortedByDescending { it.index }.forEach { call ->
        val (postsRegister, flagRegister) = call.registers
        call.method.addInstructionsAtControlFlowLabel(
            call.index,
            """
                invoke-static { v$postsRegister, v$flagRegister }, $EXTENSION_CLASS->translateLoadedPosts(Ljava/util/List;Z)Ljava/util/List;
                move-result-object v$postsRegister
            """
        )
    }

    replaceStaticBridge(
        "mergeTranslation",
        """
            invoke-static { p0, p1 }, $mergeTranslationMethod
            move-result-object v0
            return-object v0
        """
    )
}

private fun BytecodePatchContext.installInPlaceTranslation(refresh: RefreshAbi, context: ScreenContextAbi) = with(refresh) {
    refreshMethod.addInstructions(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->beforeRefresh($REFRESH_HANDLER)V"
    )

    // The post context is switched in place.
    mutableClassDefBy(screenArgsContextField.definingClass).fields
        .single { it.name == screenArgsContextField.name }
        .removeFlags(AccessFlags.FINAL)

    implementInterface(
        POST_DETAIL_REFRESH_EVENT_HANDLER_CLASS,
        REFRESH_HANDLER,
        InterfaceMethod(
            "patch_getScreenPost", listOf(), LINK_CLASS, 2,
            """
                iget-object v0, p0, ${stateProducerField.smali()}
                invoke-static { v0 }, $screenPostGetter
                move-result-object v0
                return-object v0
            """
        ),
        InterfaceMethod(
            "patch_switchToMtSeo", listOf("Ljava/lang/String;"), "Z", 6,
            """
                iget-object v0, p0, ${loadUseCaseField.smali()}
                iget-object v0, v0, ${useCaseScreenArgsField.smali()}
                iget-object v1, v0, ${screenArgsContextField.smali()}
                instance-of v2, v1, ${context.mtSeoPostContextType}
                if-nez v2, :switched
                instance-of v2, v1, ${context.allPostCommentsContextType}
                if-eqz v2, :fail
                check-cast v1, ${context.allPostCommentsContextType}
                iget-object v2, v1, ${allPostCommentsCommentField.smali()}
                new-instance v1, ${context.mtSeoPostContextType}
                const/4 v3, 0x0
                invoke-direct { v1, v3, p1, v2 }, ${context.mtSeoPostContextType}->$MT_SEO_CONTEXT_CONSTRUCTOR
                iput-object v1, v0, ${screenArgsContextField.smali()}
                :switched
                const/4 v0, 0x1
                return v0
                :fail
                const/4 v0, 0x0
                return v0
            """
        ),
    )

    implementInterface(
        eventDispatcherType,
        EVENT_DISPATCHER,
        InterfaceMethod(
            "patch_dispatchEvent", listOf("Ljava/lang/Object;"), "V", 3,
            """
                iget-object v0, p0, ${eventDispatcherFunctionField.smali()}
                invoke-interface { v0, p1 }, $FUNCTION1->invoke(Ljava/lang/Object;)Ljava/lang/Object;
                return-void
            """
        ),
        InterfaceMethod(
            "patch_getScreen", listOf(), "Ljava/lang/Object;", 4,
            """
                iget-object v2, p0, ${eventDispatcherFunctionField.smali()}
            """ + boundReceiverSmali("v2")
        ),
    )
}

private fun BytecodePatchContext.installComments(comments: CommentsAbi) = with(comments) {
    // Yandex translation of a comment that Reddit did not translate.
    val (continuation, result, key, free) = translatedComment.registers
    translatedComment.method.addInstructionsAtControlFlowLabel(
        translatedComment.index,
        """
            iget-object v$free, v$continuation, ${repositoryField.smali()}
            invoke-static { v$key, v$result, v$free }, $EXTENSION_CLASS->translateComment(${TRANSLATION_KEY}Ljava/lang/Object;$TRANSLATIONS_REPOSITORY)Ljava/lang/Object;
            move-result-object v$result
        """
    )

    implementInterface(
        repositoryType,
        TRANSLATIONS_REPOSITORY,
        InterfaceMethod(
            "patch_putTranslatedComment", listOf(TRANSLATION_KEY, "Ljava/lang/Object;"), "V", 4,
            """
                iget-object v0, p0, ${translatedCommentsField.smali()}
                invoke-virtual { v0, p1, p2 }, $CONCURRENT_HASH_MAP_PUT
                return-void
            """
        ),
        InterfaceMethod(
            "patch_putOriginalComment", listOf("Ljava/lang/String;", ORIGINAL_COMMENT), "V", 4,
            """
                iget-object v0, p0, ${originalCommentsField.smali()}
                invoke-virtual { v0, p1, p2 }, $CONCURRENT_HASH_MAP_PUT
                return-void
            """
        ),
    )

    implementInterface(
        translationKeyType,
        TRANSLATION_KEY,
        InterfaceMethod(
            "patch_getCommentId", listOf(), "Ljava/lang/String;", 2,
            """
                iget-object v0, p0, ${translationKeyCommentIdField.smali()}
                return-object v0
            """
        ),
    )

    replaceStaticBridge(
        "newTranslatedComment",
        """
            new-instance v0, $translatedCommentType
            invoke-direct { v0, p0, p1, p2, p3 }, $translatedCommentType-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            return-object v0
        """
    )

    // The "Translate" event of the comment menu is sent again when the Yandex translation is ready.
    translateEventMethod.addInstructions(
        0,
        "invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->onTranslateCommentEvent(${TRANSLATE_COMMENT_EVENT}Ljava/lang/Object;)V"
    )

    implementInterface(
        eventType,
        TRANSLATE_COMMENT_EVENT,
        InterfaceMethod(
            "patch_getCommentId", listOf(), "Ljava/lang/String;", 2,
            """
                iget-object v0, p0, ${eventCommentIdField.smali()}
                return-object v0
            """
        ),
    )

    replaceStaticBridge(
        "invokeFunction",
        """
            check-cast p0, $FUNCTION1
            invoke-interface { p0, p1 }, $FUNCTION1->invoke(Ljava/lang/Object;)Ljava/lang/Object;
            return-void
        """
    )

    // "Translate" in the comment menu.
    val (comment, translateVisible, showOriginalVisible) = menu.registers
    menu.method.addInstructionsAtControlFlowLabel(
        menu.index,
        """
            invoke-static/range { v$comment .. v$comment }, $EXTENSION_CLASS->setOverflowMenuComment(Ljava/lang/Object;)V
            invoke-static/range { v$translateVisible .. v$showOriginalVisible }, $EXTENSION_CLASS->isCommentTranslateVisible(ZZ)Z
            move-result v$translateVisible
        """
    )

    // Comments of an MT SEO post that the server did not translate yet.
    commentsLoadedMethod.addInstructions(
        0,
        "invoke-static/range { p1 .. p4 }, $EXTENSION_CLASS->onCommentsLoaded(" +
                "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;$COMMENT_LINK_INFO)V"
    )

    implementInterface(
        COMMENT_LINK_CLASS,
        COMMENT_LINK_INFO,
        InterfaceMethod(
            "patch_getPostId", listOf(), "Ljava/lang/String;", 2,
            """
                iget-object v0, p0, ${commentLinkPostIdField.smali()}
                return-object v0
            """
        ),
        InterfaceMethod(
            "patch_getNumComments", listOf(), "J", 3,
            """
                iget-wide v0, p0, ${commentLinkNumCommentsField.smali()}
                return-wide v0
            """
        ),
    )
}

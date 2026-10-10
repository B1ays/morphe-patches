/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.translation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.instanceOf
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// The obfuscated app code used by the patch. Resolving only reads the app code, so every anchor is found,
// and checked, before anything is modified.

internal const val MT_SEO_COMMENTS_CONTEXT_CLASS = $$"Lcom/reddit/comments/models/CommentScreenContext$MtSeo;"

/** (context, language, comment) */
internal const val MT_SEO_CONTEXT_CONSTRUCTOR = "<init>(Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;)V"

private val GOTO_OPCODES = listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

internal data class TranslationAbi(
    val screenContext: ScreenContextAbi,
    val mtSeoChecks: List<CallSite>,
    val banner: BannerAbi,
    val posts: PostLoadingAbi,
    val refresh: RefreshAbi,
    val comments: CommentsAbi,
)

/** An instruction of a method, and its registers. */
internal data class CallSite(
    val method: MutableMethod,
    val index: Int,
    val registers: List<Int> = emptyList(),
)

/** Builds the post context of the post detail screen from its arguments. */
internal data class ScreenContextAbi(
    val method: MutableMethod,
    /** Result of the "mt_language" argument read. */
    val languageIndex: Int,
    val languageRegister: Int,
    val continuationRegister: Int,
    val sctReplacementRegister: Int,
    /** Default post context of a post opened from a feed (no context bundle). */
    val defaultContextIndex: Int,
    val contextRegister: Int,
    val argumentsRegister: Int,
    val contextDone: Instruction,
    val postContextType: String,
    val mtSeoPostContextType: String,
    val allPostCommentsContextType: String,
)

internal data class BannerAbi(
    val delegate: CallSite,
    /** "Banner dismissed" check of ViewBanner: link, free, handler registers. */
    val viewBanner: CallSite,
    val mtSeoProviderField: FieldReference,
    val mtSeoCheck: MethodReference,
    val bannerShown: Instruction,
    /** Title string: title, active registers. */
    val title: CallSite,
    /** "Translate" of the banner and the post menu: event, link, dispatcher, free registers. */
    val translate: CallSite,
    val eventSourceMethod: MethodReference,
    val userInitiatedMethod: MethodReference,
    val unitField: FieldReference,
    val eventDispatcherType: String,
    val showOriginal: CallSite,
    /** Banner section of a post detail screen: post detail context read (context, free registers). */
    val section: CallSite,
    /** The banner composable call of the section. */
    val sectionBannerIndex: Int,
    val postDetailContextFunctionField: FieldReference,
)

internal data class PostLoadingAbi(
    /** Translations of a loaded post: link register. */
    val loaded: CallSite,
    /** Posts loaded by id, before they are marked as MT SEO posts: posts, flag registers. */
    val markMtSeo: List<CallSite>,
    val mergeTranslationMethod: MethodReference,
)

internal data class RefreshAbi(
    val refreshMethod: MutableMethod,
    val eventDispatcherType: String,
    val eventDispatcherFunctionField: FieldReference,
    val stateProducerField: FieldReference,
    val screenPostGetter: MethodReference,
    val loadUseCaseField: FieldReference,
    val useCaseScreenArgsField: FieldReference,
    val screenArgsContextField: FieldReference,
    val allPostCommentsCommentField: FieldReference,
)

internal data class CommentsAbi(
    val repositoryType: String,
    val originalCommentsField: FieldReference,
    val translatedCommentsField: FieldReference,
    val translatedCommentType: String,
    /** Result of getTranslatedComment: continuation, result, key, free registers. */
    val translatedComment: CallSite,
    val repositoryField: FieldReference,
    val translationKeyType: String,
    val translationKeyCommentIdField: FieldReference,
    val eventType: String,
    val eventCommentIdField: FieldReference,
    val translateEventMethod: MutableMethod,
    /** Comment menu: comment, translate visible, show original visible registers. */
    val menu: CallSite,
    val commentsLoadedMethod: MutableMethod,
    val commentLinkPostIdField: FieldReference,
    val commentLinkNumCommentsField: FieldReference,
)

internal fun FieldReference.smali() = "$definingClass->$name:$type"

internal fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Registers of the non range invoke instructions of the hooks. */
internal fun requireLowRegisters(vararg registers: Int) {
    if (registers.any { it > 15 }) {
        throw PatchException("Registers out of range: ${registers.joinToString()}")
    }
}

private fun MutableMethod.methodCallIndex(startIndex: Int, filter: (MethodReference) -> Boolean) =
    indexOfFirstInstructionOrThrow(startIndex) {
        val reference = getReference<MethodReference>()
        reference != null && filter(reference)
    }

private fun MethodReference.parameters() = parameterTypes.map { it.toString() }

internal fun BytecodePatchContext.resolveTranslationAbi(): TranslationAbi {
    val screenContext = resolveScreenContext()
    val (postMtSeoCheck, mtSeoChecks) = resolveMtSeoChecks(screenContext)
    val banner = resolveBanner()
    return TranslationAbi(
        screenContext = screenContext,
        mtSeoChecks = mtSeoChecks,
        banner = banner,
        posts = resolvePostLoading(),
        refresh = resolveRefresh(screenContext, postMtSeoCheck, banner.eventDispatcherType),
        comments = resolveComments(),
    )
}

private fun BytecodePatchContext.resolveScreenContext() = PostDetailScreenContextFingerprint.matchSingle().method.run {
    val contextBundleKeyIndex = indexOfFirstStringInstructionOrThrow("com.reddit.arg.context_mvp")
    val getContextBundleIndex = methodCallIndex(contextBundleKeyIndex) { it.name == "getBundle" }
    val contextBundleRegister = getInstruction<OneRegisterInstruction>(getContextBundleIndex + 1).registerA

    /** @return Index and register of the result of a context bundle read. */
    fun bundleRead(key: String, methodName: String): Pair<Int, Int> {
        val keyIndex = indexOfFirstStringInstructionOrThrow(key)
        val keyRegister = getInstruction<OneRegisterInstruction>(keyIndex).registerA
        val callIndex = indexOfFirstInstructionOrThrow(keyIndex) {
            val argumentRegister = when (this) {
                is FiveRegisterInstruction -> registerD
                is RegisterRangeInstruction -> startRegister + 1
                else -> -1
            }
            getReference<MethodReference>()?.name == methodName && argumentRegister == keyRegister
        }
        val resultIndex = callIndex + 1
        return resultIndex to getInstruction<OneRegisterInstruction>(resultIndex).registerA
    }

    val (languageIndex, languageRegister) = bundleRead("mt_language", "getString")
    val (continuationIndex, continuationRegister) = bundleRead("is_continuation", "getBoolean")
    val (sctReplacementIndex, sctReplacementRegister) = bundleRead("sct_replacement_enabled", "getBoolean")
    if (continuationIndex > languageIndex || sctReplacementIndex > languageIndex) {
        throw PatchException("Unexpected order of the post detail screen arguments")
    }

    val mtSeoPostContextType = getInstruction<ReferenceInstruction>(
        methodCallIndex(languageIndex) {
            "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" ==
                    MT_SEO_CONTEXT_CONSTRUCTOR
        }
    ).getReference<MethodReference>()!!.definingClass

    val defaultContextIndex = indexOfFirstInstructionOrThrow(getContextBundleIndex, Opcode.NEW_INSTANCE)
    if (getInstruction(defaultContextIndex - 1).opcode != Opcode.IF_NEZ ||
        getInstruction<OneRegisterInstruction>(defaultContextIndex - 1).registerA != contextBundleRegister
    ) {
        throw PatchException("Unexpected post context without a context bundle")
    }

    ScreenContextAbi(
        method = this,
        languageIndex = languageIndex,
        languageRegister = languageRegister,
        continuationRegister = continuationRegister,
        sctReplacementRegister = sctReplacementRegister,
        defaultContextIndex = defaultContextIndex,
        contextRegister = getInstruction<OneRegisterInstruction>(defaultContextIndex).registerA,
        argumentsRegister = getInstruction<FiveRegisterInstruction>(getContextBundleIndex).registerC,
        contextDone = getInstruction(indexOfFirstInstructionOrThrow(defaultContextIndex) { opcode in GOTO_OPCODES }),
        postContextType = classDefBy(mtSeoPostContextType).superclass!!,
        mtSeoPostContextType = mtSeoPostContextType,
        allPostCommentsContextType = getInstruction<ReferenceInstruction>(defaultContextIndex)
            .getReference<TypeReference>()!!.type,
    ).also {
        requireLowRegisters(it.languageRegister, it.continuationRegister, it.sctReplacementRegister)
    }
}

/**
 * @return The post MT SEO check, and the replaced Link.isMtSeoDeepLink() calls of the post and the comments checks.
 */
private fun BytecodePatchContext.resolveMtSeoChecks(screenContext: ScreenContextAbi): Pair<MutableMethod, List<CallSite>> {
    fun mtSeoCheck(contextType: String) = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "Z",
        parameters = listOf(),
        filters = listOf(
            instanceOf(contextType),
            methodCall(definingClass = LINK_CLASS, name = "isMtSeoDeepLink")
        )
    ).matchSingle()

    val checks = listOf(mtSeoCheck(screenContext.mtSeoPostContextType), mtSeoCheck(MT_SEO_COMMENTS_CONTEXT_CLASS))
    return checks.first().method to checks.map { match ->
        val index = match.instructionMatches.last().index
        CallSite(match.method, index, listOf(match.method.getInstruction<FiveRegisterInstruction>(index).registerC))
    }
}

private fun BytecodePatchContext.resolveBanner(): BannerAbi {
    val delegate = TranslationBannerDelegateFingerprint.matchSingle().method

    val viewBanner = TranslationBannerEventHandlerFingerprint.matchSingle().let { match ->
        match.method.run {
            val viewBannerIndex = match.instructionMatches.first().index
            val isTranslatedIndex = methodCallIndex(viewBannerIndex) {
                it.definingClass == LINK_CLASS && it.name == "isTranslated"
            }
            val mtSeoCheckIndex = indexOfFirstInstructionReversedOrThrow(isTranslatedIndex) {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_VIRTUAL && reference?.returnType == "Z" &&
                        reference.parameterTypes.isEmpty() && reference.definingClass != LINK_CLASS
            }
            val bannerShownIndex = indexOfFirstInstructionOrThrow(isTranslatedIndex) { opcode in GOTO_OPCODES }
            val preferenceIndex = indexOfFirstInstructionOrThrow(bannerShownIndex) {
                opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.name == "translationBannerPreferenceDelegate"
            }
            val preference = getInstruction<TwoRegisterInstruction>(preferenceIndex)
            val registers = listOf(
                getInstruction<FiveRegisterInstruction>(isTranslatedIndex).registerC,
                preference.registerA,
                preference.registerB,
            )
            requireLowRegisters(*registers.toIntArray())

            Triple(
                CallSite(this, preferenceIndex, registers),
                getInstruction<ReferenceInstruction>(mtSeoCheckIndex).getReference<MethodReference>()!! to
                        getInstruction<ReferenceInstruction>(
                            indexOfFirstInstructionReversedOrThrow(mtSeoCheckIndex, Opcode.IGET_OBJECT)
                        ).getReference<FieldReference>()!!,
                getInstruction(bannerShownIndex)
            )
        }
    }

    val title = TranslationBannerTitleFingerprint.matchSingle().let { match ->
        match.method.run {
            val activeTitleIndex = match.instructionMatches.first().index
            val activeRegister = getInstruction<OneRegisterInstruction>(
                indexOfFirstInstructionReversedOrThrow(activeTitleIndex, Opcode.IF_EQZ)
            ).registerA
            val titleIndex = methodCallIndex(activeTitleIndex) { it.returnType == "Ljava/lang/String;" } + 1
            val titleRegister = getInstruction<OneRegisterInstruction>(titleIndex).registerA
            requireLowRegisters(titleRegister, activeRegister)
            CallSite(this, titleIndex, listOf(titleRegister, activeRegister))
        }
    }

    lateinit var eventSourceMethod: MethodReference
    lateinit var userInitiatedMethod: MethodReference
    lateinit var unitField: FieldReference
    val translate = TranslatePostEventHandlerFingerprint.matchSingle().let { match ->
        match.method.run {
            val index = match.instructionMatches.first().index
            val sendClickEvent = getInstruction<FiveRegisterInstruction>(index)
            // p2: event dispatcher.
            val dispatcherRegister = implementation!!.registerCount - 2
            val registers = listOf(
                sendClickEvent.registerD,
                sendClickEvent.registerE,
                dispatcherRegister,
                findFreeRegister(index, sendClickEvent.registerD, sendClickEvent.registerE, dispatcherRegister),
            )
            requireLowRegisters(*registers.toIntArray())

            eventSourceMethod = getInstruction<ReferenceInstruction>(
                methodCallIndex(index) { it.name == "getTranslationPostEventSource" }
            ).getReference<MethodReference>()!!
            userInitiatedMethod = getInstruction<ReferenceInstruction>(
                methodCallIndex(index) { it.name == "isUserInitiatedPostTranslate" }
            ).getReference<MethodReference>()!!
            unitField = instructions.firstNotNullOf { instruction ->
                instruction.getReference<FieldReference>()?.takeIf {
                    instruction.opcode == Opcode.SGET_OBJECT && it.type == "Lkotlin/Unit;"
                }
            }
            CallSite(this, index, registers)
        }
    }

    val showOriginal = ShowOriginalPostEventHandlerFingerprint.matchSingle().method.run {
        val mtSeoProviderIndex = indexOfFirstInstructionOrThrow {
            opcode == Opcode.IGET_OBJECT && getReference<FieldReference>()?.name == "mtSeoProvider"
        }
        val mtSeoCheckIndex = indexOfFirstInstructionOrThrow(mtSeoProviderIndex, Opcode.IF_NEZ)
        CallSite(this, (getInstruction(mtSeoCheckIndex) as BuilderOffsetInstruction).target.location.index)
    }

    // The post of the banner: the section reads the post detail context, a function of the screen.
    lateinit var postDetailContextFunctionField: FieldReference
    var sectionBannerIndex = -1
    val section = PostDetailTranslationBannerFingerprint.matchSingle().let { match ->
        match.method.run {
            sectionBannerIndex = match.instructionMatches.last().index
            val contextIndex = indexOfFirstInstructionOrThrow {
                val field = getReference<FieldReference>()
                opcode == Opcode.IGET_OBJECT && field?.definingClass == definingClass &&
                        classDefByOrNull(field.type)?.fields?.singleOrNull()?.type == "Lkotlin/jvm/functions/Function1;"
            }
            if (contextIndex > sectionBannerIndex) {
                throw PatchException("Post detail context is read after the banner")
            }
            postDetailContextFunctionField = classDefBy(
                getInstruction<ReferenceInstruction>(contextIndex).getReference<FieldReference>()!!.type
            ).fields.single()
            val contextRegister = getInstruction<TwoRegisterInstruction>(contextIndex).registerA
            val registers = listOf(contextRegister, findFreeRegister(contextIndex + 1, contextRegister))
            requireLowRegisters(*registers.toIntArray())
            CallSite(this, contextIndex + 1, registers)
        }
    }

    return BannerAbi(
        delegate = CallSite(delegate, 0, listOf(delegate.findFreeRegister(0))),
        viewBanner = viewBanner.first,
        mtSeoCheck = viewBanner.second.first,
        mtSeoProviderField = viewBanner.second.second,
        bannerShown = viewBanner.third,
        title = title,
        translate = translate,
        eventSourceMethod = eventSourceMethod,
        userInitiatedMethod = userInitiatedMethod,
        unitField = unitField,
        eventDispatcherType = translate.method.parameterTypes[1].toString(),
        showOriginal = showOriginal,
        section = section,
        sectionBannerIndex = sectionBannerIndex,
        postDetailContextFunctionField = postDetailContextFunctionField,
    )
}

private fun BytecodePatchContext.resolvePostLoading(): PostLoadingAbi {
    val loaded = UpdatePostTranslationsFingerprint.matchSingle().let { match ->
        val index = match.instructionMatches.last().index
        CallSite(match.method, index, listOf(match.method.getInstruction<FiveRegisterInstruction>(index).registerC))
    }

    val markMtSeo = MarkMtSeoPostsFingerprint.matchSingle().let { match ->
        val markMethod = match.originalMethod

        fun MethodReference.isMarkMethod() = definingClass == markMethod.definingClass &&
                name == markMethod.name && parameterTypes == markMethod.parameterTypes &&
                returnType == markMethod.returnType

        // Posts loaded by id (post detail screen).
        val loadById = match.classDef.methods.single { method ->
            method.parameterTypes.getOrNull(1) == "Ljava/lang/String;" &&
                    method.implementation?.instructions?.any {
                        it.getReference<MethodReference>()?.isMarkMethod() == true
                    } == true
        }
        loadById.instructions.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()!!.isMarkMethod()
        }.map { (index, _) ->
            // Before the posts are marked: the result register can be the register of the flag.
            val call = loadById.getInstruction<FiveRegisterInstruction>(index)
            requireLowRegisters(call.registerC, call.registerD)
            CallSite(loadById, index, listOf(call.registerC, call.registerD))
        }
    }

    return PostLoadingAbi(
        loaded = loaded,
        markMtSeo = markMtSeo,
        mergeTranslationMethod = TranslatePostMergeLambdaFingerprint.matchSingle().instructionMatches.first()
            .getInstruction<ReferenceInstruction>().getReference<MethodReference>()!!,
    )
}

private fun BytecodePatchContext.resolveRefresh(
    screenContext: ScreenContextAbi,
    postMtSeoCheck: MutableMethod,
    eventDispatcherType: String,
): RefreshAbi {
    val screenArgsContextField = postMtSeoCheck.instructions.firstNotNullOf { instruction ->
        instruction.getReference<FieldReference>()?.takeIf {
            instruction.opcode == Opcode.IGET_OBJECT && it.type == screenContext.postContextType
        }
    }
    val screenPostGetter = postMtSeoCheck.instructions.firstNotNullOf { instruction ->
        instruction.getReference<MethodReference>()?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == LINK_CLASS
        }
    }

    val refreshHandlerClass = classDefBy(POST_DETAIL_REFRESH_EVENT_HANDLER_CLASS)
    fun refreshHandlerField(name: String) = refreshHandlerClass.fields.single { it.name == name }

    val stateProducerField = refreshHandlerField("postDetailStateProducer")
    if (screenPostGetter.parameterTypes.singleOrNull() != stateProducerField.type) {
        throw PatchException("Unexpected post getter: $screenPostGetter")
    }
    val loadUseCaseField = refreshHandlerField("postDetailLoadUseCase")

    return RefreshAbi(
        refreshMethod = RefreshPostAndCommentsFingerprint.matchSingle().method,
        eventDispatcherType = eventDispatcherType,
        eventDispatcherFunctionField = classDefBy(eventDispatcherType).fields.single {
            it.type == "Lkotlin/jvm/functions/Function1;"
        },
        stateProducerField = stateProducerField,
        screenPostGetter = screenPostGetter,
        loadUseCaseField = loadUseCaseField,
        useCaseScreenArgsField = classDefBy(loadUseCaseField.type).fields.single {
            it.type == screenArgsContextField.definingClass
        },
        screenArgsContextField = screenArgsContextField,
        allPostCommentsCommentField = classDefBy(screenContext.allPostCommentsContextType).fields.single {
            it.type == "Ljava/lang/String;"
        },
    )
}

private fun BytecodePatchContext.resolveComments(): CommentsAbi {
    // Originals of the translated comments, for "Show original".
    val originalCommentsField = FetchCommentsTranslationFingerprint.matchSingle().let { match ->
        match.method.run {
            getInstruction<ReferenceInstruction>(
                indexOfFirstInstructionOrThrow(match.instructionMatches.last().index) {
                    opcode == Opcode.IGET_OBJECT &&
                            getReference<FieldReference>()?.type == "Ljava/util/concurrent/ConcurrentHashMap;"
                }
            ).getReference<FieldReference>()!!
        }
    }

    // A single comment ("Translate" in the comment menu).
    val getTranslatedComment = GetTranslatedCommentFingerprint.matchSingle()
    val continuationType = getTranslatedComment.instructionMatches.first().instruction
        .getReference<TypeReference>()!!.type
    lateinit var translatedCommentType: String
    lateinit var translationKeyType: String
    lateinit var translatedCommentsField: FieldReference
    val translatedComment = getTranslatedComment.method.run {
        val fetchIndex = methodCallIndex(0) {
            it.definingClass == definingClass && it.returnType == "Ljava/lang/Object;" &&
                    it.parameterTypes.firstOrNull() == "Ljava/lang/String;"
        }
        val continuationRegister = getInstruction<TwoRegisterInstruction>(
            indexOfFirstInstructionReversedOrThrow(fetchIndex) {
                opcode == Opcode.IPUT_OBJECT && getReference<FieldReference>()?.definingClass == continuationType
            }
        ).registerB
        // Result of the request (TranslatedComment), after the direct and the resumed paths join.
        val resultIndex = indexOfFirstInstructionOrThrow(fetchIndex, Opcode.CHECK_CAST)
        translatedCommentType = getInstruction<ReferenceInstruction>(resultIndex).getReference<TypeReference>()!!.type
        val resultRegister = getInstruction<OneRegisterInstruction>(resultIndex).registerA
        // The cache key (comment id, language): the comment id is not kept when the request is resumed.
        val keyRegister = getInstruction<FiveRegisterInstruction>(methodCallIndex(resultIndex) { it.name == "put" })
            .registerD
        translationKeyType = getInstruction<ReferenceInstruction>(
            methodCallIndex(0) {
                it.name == "<init>" && it.definingClass != continuationType &&
                        it.parameters() == listOf("Ljava/lang/String;", "Ljava/lang/String;")
            }
        ).getReference<MethodReference>()!!.definingClass
        translatedCommentsField = getInstruction<ReferenceInstruction>(
            indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.type == "Ljava/util/concurrent/ConcurrentHashMap;"
            }
        ).getReference<FieldReference>()!!

        val registers = listOf(
            continuationRegister,
            resultRegister,
            keyRegister,
            findFreeRegister(resultIndex, continuationRegister, resultRegister, keyRegister),
        )
        requireLowRegisters(*registers.toIntArray())
        CallSite(this, resultIndex, registers)
    }
    if (originalCommentsField.definingClass != translatedCommentsField.definingClass) {
        throw PatchException("Comment translations are not stored in the same repository")
    }

    val translationKeyCommentIdField = classDefBy(translationKeyType).let { keyClass ->
        val constructor = keyClass.methods.single { method ->
            method.name == "<init>" && method.parameterTypes.map { it.toString() } ==
                    listOf("Ljava/lang/String;", "Ljava/lang/String;")
        }
        // First parameter register: p1.
        val commentIdRegister = constructor.implementation!!.registerCount - 2
        constructor.implementation!!.instructions.firstNotNullOf { instruction ->
            instruction.getReference<FieldReference>()?.takeIf {
                instruction.opcode == Opcode.IPUT_OBJECT &&
                        (instruction as TwoRegisterInstruction).registerA == commentIdRegister
            }
        }
    }

    // "Translate" event of the comment menu.
    val translateEventHandler = TranslateCommentEventHandlerFingerprint.matchSingle()
    val eventType = translateEventHandler.originalMethod.parameterTypes.first().toString()
    val (commentLinkPostIdField, commentLinkNumCommentsField) = resolveCommentLinkFields()

    return CommentsAbi(
        repositoryType = originalCommentsField.definingClass,
        originalCommentsField = originalCommentsField,
        translatedCommentsField = translatedCommentsField,
        translatedCommentType = translatedCommentType,
        translatedComment = translatedComment,
        repositoryField = classDefBy(continuationType).fields.single { it.name == "this$0" },
        translationKeyType = translationKeyType,
        translationKeyCommentIdField = translationKeyCommentIdField,
        eventType = eventType,
        eventCommentIdField = classDefBy(eventType).fields.single { it.type == "Ljava/lang/String;" },
        translateEventMethod = translateEventHandler.classDef.methods.single { method ->
            method.parameterTypes.getOrNull(1) == "Lkotlin/jvm/functions/Function1;"
        },
        menu = resolveCommentMenu(),
        commentsLoadedMethod = CommentsLoadedFingerprint.matchSingle().method,
        commentLinkPostIdField = commentLinkPostIdField,
        commentLinkNumCommentsField = commentLinkNumCommentsField,
    )
}

private fun BytecodePatchContext.resolveCommentMenu() = CommentOverflowMenuFingerprint.matchSingle().method.run {
    val translatableIndex = indexOfFirstInstructionOrThrow {
        getReference<MethodReference>()?.name == "isTranslatable"
    }
    // isTranslateVisible = isTranslatable(...) && !isTranslated
    val translateCheckIndex = methodCallIndex(translatableIndex) {
        it.returnType == "Z" && it.parameters() == listOf("Ljava/lang/String;", "Z", "Z")
    }
    val translateVisibleIndex = indexOfFirstInstructionOrThrow(translateCheckIndex) {
        (opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16) && (this as NarrowLiteralInstruction).narrowLiteral == 1
    }
    val translateVisibleRegister = getInstruction<OneRegisterInstruction>(translateVisibleIndex).registerA
    // isShowOriginalVisible = isTranslated
    val showOriginalCheckIndex = indexOfFirstInstructionOrThrow(translateVisibleIndex, Opcode.INVOKE_STATIC)
    val showOriginalRegister = getInstruction<OneRegisterInstruction>(showOriginalCheckIndex + 1).registerA
    if (showOriginalRegister != translateVisibleRegister + 1) {
        throw PatchException("Unexpected comment menu registers")
    }

    val menuParamsIndex = indexOfFirstInstructionOrThrow(showOriginalCheckIndex, Opcode.NEW_INSTANCE)
    val commentRegister = getInstruction(
        indexOfFirstInstructionReversedOrThrow(menuParamsIndex) {
            getReference<MethodReference>()?.name == "isTranslatable"
        }
    ).let { instruction ->
        if (instruction is RegisterRangeInstruction) instruction.startRegister
        else (instruction as FiveRegisterInstruction).registerC
    }
    CallSite(this, menuParamsIndex, listOf(commentRegister, translateVisibleRegister, showOriginalRegister))
}

/**
 * @return Post id (kind_id) and number of comments fields of a CommentLink, found from the CommentLink of a Link.
 */
private fun BytecodePatchContext.resolveCommentLinkFields() = CommentLinkFromLinkFingerprint.matchSingle().let { match ->
    val constructorCall = match.method.getInstruction<RegisterRangeInstruction>(match.instructionMatches.last().index)
    val constructor = constructorCall.getReference<MethodReference>()!!
    val constructorMethod = classDefBy(COMMENT_LINK_CLASS).methods.single { method ->
        method.name == "<init>" && method.parameterTypes == constructor.parameterTypes
    }
    val constructorRegisters = constructorMethod.implementation!!.registerCount

    /**
     * @return CommentLink field set from the value of a Link getter.
     */
    fun commentLinkField(getterIndex: Int): FieldReference {
        val valueRegister = match.method.getInstruction<OneRegisterInstruction>(getterIndex + 1).registerA
        // Register offset of the value in the constructor arguments, after "this".
        val argumentOffset = valueRegister - constructorCall.startRegister - 1
        if (argumentOffset < 0 || valueRegister >= constructorCall.startRegister + constructorCall.registerCount) {
            throw PatchException("Value is not a CommentLink constructor argument")
        }
        // Parameter registers are the last registers of the constructor, "this" is the first of them.
        val parameterRegister = constructorRegisters - constructorCall.registerCount + 1 + argumentOffset

        // The value can be moved to another register before it is stored.
        val valueRegisters = mutableSetOf(parameterRegister)
        constructorMethod.implementation!!.instructions.forEach { instruction ->
            if (instruction !is TwoRegisterInstruction) return@forEach
            val opcodeName = instruction.opcode.name.lowercase()
            if (opcodeName.startsWith("move")) {
                if (instruction.registerB in valueRegisters) {
                    valueRegisters += instruction.registerA
                } else {
                    valueRegisters -= instruction.registerA
                }
            } else if (opcodeName.startsWith("iput") && instruction.registerA in valueRegisters) {
                return instruction.getReference<FieldReference>()!!
            }
        }
        throw PatchException("CommentLink field not found")
    }

    commentLinkField(match.instructionMatches[0].index) to commentLinkField(match.instructionMatches[1].index)
}

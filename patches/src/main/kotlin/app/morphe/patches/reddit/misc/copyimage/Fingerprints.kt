/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.copyimage

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val ICON_TYPE_CLASS =
    "Lcom/reddit/fullbleedplayer/composables/IconType;"

private const val ACTION_MENU_ITEM_CLASS =
    "Lcom/reddit/fullbleedplayer/ui/ActionMenuItemUiState;"

/**
 * Builds the overflow menu items of the full bleed media viewer.
 * Used both by the overflow bottom sheet and by the inline overflow menu.
 */
internal object ActionMenuItemsBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "Ljava/lang/String;", "Z"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = ICON_TYPE_CLASS,
            name = "Link"
        ),
        newInstance(ACTION_MENU_ITEM_CLASS),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = ICON_TYPE_CLASS,
            name = "Download"
        ),
        methodCall(
            definingClass = "Lkotlin/collections/builders/ListBuilder;",
            name = "build",
            returnType = "Ljava/util/List;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Download media event handler, where the url of the selected image of an image post is resolved:
 *
 * ```
 * if (mediaPage instanceof ImagePage) {
 *     Image image = (Image) CollectionsKt.getOrNull(mediaPage.selectedIndex, mediaPage.images);
 *     String url = image.url;
 * ```
 */
internal object DownloadMediaImageUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        opcode(Opcode.INSTANCE_OF),
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(3)),
        // Images.
        opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        // Selected index.
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Lkotlin/collections/CollectionsKt;",
            returnType = "Ljava/lang/Object;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        // Image url.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(3)
        )
    ),
    custom = { _, classDef ->
        classDef.type.startsWith("Lcom/reddit/fullbleedplayer/data/events/")
    }
)

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.copyimage

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CopyImagePatch;"

private const val EXTENSION_ICON_TYPE_CLASS =
    "Lapp/morphe/extension/reddit/patches/CopyImagePatch\$CopyImageIconType;"

@Suppress("unused")
val copyImagePatch = bytecodePatch(
    name = "Copy image",
    description = "Adds an option to show a button in the menu of the fullscreen image viewer that copies the image to the clipboard."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        val extensionClass = mutableClassDefBy(EXTENSION_CLASS)

        // region Resolve the url of the currently selected image.

        DownloadMediaImageUrlFingerprint.let {
            it.method.apply {
                fun reference(matchIndex: Int) =
                    getInstruction<ReferenceInstruction>(it.instructionMatches[matchIndex].index).reference

                val imagePageType = (reference(0) as TypeReference).type
                val imagesField = reference(2) as FieldReference
                val selectedIndexField = reference(3) as FieldReference
                val imageType = (reference(6) as TypeReference).type
                val urlField = reference(7) as FieldReference

                // Replace the placeholder method with the real implementation.
                val getImageUrl = extensionClass.methods.first { method ->
                    method.name == "getImageUrl"
                }
                extensionClass.methods.remove(getImageUrl)
                extensionClass.methods.add(
                    ImmutableMethod(
                        EXTENSION_CLASS,
                        getImageUrl.name,
                        listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                        "Ljava/lang/String;",
                        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                        null,
                        null,
                        MutableMethodImplementation(4)
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                instance-of v0, p0, $imagePageType
                                if-eqz v0, :not_found
                                check-cast p0, $imagePageType
                                iget-object v0, p0, $imagesField
                                iget v1, p0, $selectedIndexField
                                if-ltz v1, :not_found
                                invoke-interface { v0 }, Ljava/util/List;->size()I
                                move-result v2
                                if-ge v1, v2, :not_found
                                invoke-interface { v0, v1 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                                move-result-object v0
                                instance-of v1, v0, $imageType
                                if-eqz v1, :not_found
                                check-cast v0, $imageType
                                iget-object v0, v0, $urlField
                                return-object v0
                                :not_found
                                const/4 v0, 0x0
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        // endregion

        // region Add a toIcon implementation to the extension icon type.

        val toIconMethod = classDefBy(ICON_TYPE_CLASS).methods.first { method ->
            method.name == "toIcon" && AccessFlags.ABSTRACT.isSet(method.accessFlags)
        }
        val composerType = toIconMethod.parameterTypes[0].toString()
        val iconType = toIconMethod.returnType

        // The icon is a data class of the icon size, drawable resource and an unknown boolean.
        val iconClass = classDefBy(iconType)
        fun iconField(type: String) = iconClass.fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == type
        } ?: throw PatchException("Could not find icon field of type: $type")
        val sizeField = iconField("J")
        val resourceField = iconField("I")
        val booleanField = iconField("Z")

        val iconConstructor = iconClass.methods.single { method -> method.name == "<init>" }
        val constructorRegisters = iconConstructor.parameterTypes.joinToString(", ") { type ->
            when (type.toString()) {
                "J" -> "v2, v3"
                "I" -> "v1"
                "Z" -> "v4"
                else -> throw PatchException("Unexpected icon constructor parameter: $type")
            }
        }
        val constructorParameters = iconConstructor.parameterTypes.joinToString("")

        mutableClassDefBy(EXTENSION_ICON_TYPE_CLASS).methods.add(
            ImmutableMethod(
                EXTENSION_ICON_TYPE_CLASS,
                toIconMethod.name,
                toIconMethod.parameters,
                iconType,
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(9)
            ).toMutable().apply {
                // Use the download icon with the style of the current theme, and swap the drawable.
                addInstructions(
                    0,
                    """
                        sget-object v0, $ICON_TYPE_CLASS->Download:$ICON_TYPE_CLASS
                        invoke-virtual { v0, p1, p2 }, $ICON_TYPE_CLASS->toIcon(${composerType}I)$iconType
                        move-result-object v0
                        iget v1, v0, $resourceField
                        invoke-static { v1 }, $EXTENSION_CLASS->getCopyImageIconResource(I)I
                        move-result v1
                        iget-wide v2, v0, $sizeField
                        iget-boolean v4, v0, $booleanField
                        new-instance v5, $iconType
                        invoke-direct { v5, $constructorRegisters }, $iconType-><init>($constructorParameters)V
                        return-object v5
                    """
                )
            }
        )

        // endregion

        // region Add the menu item.

        ActionMenuItemsBuilderFingerprint.let {
            it.method.apply {
                // The method uses more than 16 registers, so the range variant of invoke is required.
                val index = it.instructionMatches[4].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->addCopyImageMenuItem(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )

                addInstructions(
                    0,
                    "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->setMenuMediaPage(Ljava/lang/Object;)V"
                )
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

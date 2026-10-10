/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.translation

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

/**
 * Connects the Reddit machine translation (MT SEO mode of the post detail screen) and the Yandex fallback
 * to the extension. The obfuscated app code is found in [resolveTranslationAbi] before anything is modified,
 * then [installTranslation] adds the hooks, the interfaces used by the extension and the static bridges.
 */
@Suppress("unused")
val translatePostsPatch = bytecodePatch(
    name = "Translate posts",
    description = "Adds options to translate posts and comments with the Reddit machine translation, " +
            "without opening a translated link. Optionally translates posts and comments with Yandex " +
            "when Reddit has no translation."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        installTranslation(resolveTranslationAbi())

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

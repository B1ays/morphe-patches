/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import app.morphe.extension.reddit.patches.PostTranslationPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SortedListPreference;

@SuppressWarnings("deprecation")
public class TranslationPreferenceCategory extends ConditionalPreferenceCategory {
    public TranslationPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_translation_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return PostTranslationPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(switchPreference(context, Settings.TRANSLATE_ALL_POSTS));
        addPreference(languagePreference(context, Settings.TRANSLATION_LANGUAGE));
        addPreference(switchPreference(context, Settings.TRANSLATION_YANDEX_FALLBACK));
        addPreference(switchPreference(context, Settings.TRANSLATION_COMMENT_MENU));
    }

    /**
     * Language list shared by the Morphe patches. The summary shows the selected language.
     */
    private static SortedListPreference languagePreference(Context context, StringSetting setting) {
        SortedListPreference preference = new SortedListPreference(context);
        preference.setKey(setting.key);
        preference.setTitle(str(setting.key + "_title"));
        preference.setDialogTitle(str(setting.key + "_title"));
        preference.setEntries(ResourceUtils.getStringArray("morphe_language_entries"));
        preference.setEntryValues(ResourceUtils.getStringArray("morphe_language_entry_values"));
        // Keep "App language" first.
        preference.sortEntryAndValues(1);
        preference.setValue(setting.get());
        return preference;
    }

    private static SwitchPreference switchPreference(Context context, BooleanSetting setting) {
        SwitchPreference preference = new SwitchPreference(context);
        preference.setKey(setting.key);
        preference.setTitle(str(setting.key + "_title"));
        preference.setSummaryOn(str(setting.key + "_summary_on"));
        preference.setSummaryOff(str(setting.key + "_summary_off"));
        preference.setChecked(setting.get());
        return preference;
    }
}

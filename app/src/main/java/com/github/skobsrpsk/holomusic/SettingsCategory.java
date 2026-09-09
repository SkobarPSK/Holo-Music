package com.github.skobsrpsk.holomusic;

/** Разделы главного экрана настроек. */
public enum SettingsCategory {
    INTERFACE(R.string.settings_category_interface, SettingsInterfaceActivity.class),
    LIBRARY(R.string.settings_category_library, SettingsLibraryActivity.class),
    PLAYBACK(R.string.settings_category_playback, SettingsPlaybackActivity.class),
    ABOUT(R.string.settings_category_about, SettingsAboutActivity.class);

    public final int titleRes;
    public final Class<?> activityClass;

    SettingsCategory(int titleRes, Class<?> activityClass) {
        this.titleRes = titleRes;
        this.activityClass = activityClass;
    }
}

package com.github.skobsrpsk.holomusic;

/**
 * Разделы бокового меню. Порядок объявления здесь используется только как
 * порядок "по умолчанию" — реальный порядок и видимость хранятся в SortPrefs
 * и настраиваются на экране настроек.
 */
public enum DrawerSection {
    SEARCH("search", R.string.section_search),
    ALL_TRACKS("all_tracks", R.string.section_all_tracks),
    PLAYLISTS("playlists", R.string.section_playlists),
    QUEUE("queue", R.string.section_queue),
    ARTISTS("artists", R.string.section_artists),
    ALBUMS("albums", R.string.section_albums);

    public final String key;
    public final int titleRes;

    DrawerSection(String key, int titleRes) {
        this.key = key;
        this.titleRes = titleRes;
    }

    public static DrawerSection byKey(String key) {
        for (DrawerSection s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}

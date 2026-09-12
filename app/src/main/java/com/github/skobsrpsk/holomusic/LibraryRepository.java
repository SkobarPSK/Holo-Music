package com.github.skobsrpsk.holomusic;

import android.content.Context;

import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.List;

/**
 * Точка входа для чтения библиотеки треков: если кэш (LibraryCache) уже
 * заполнен — читаем из него (быстро, без обращения к MediaStore). Если
 * кэш пуст (первый запуск) — делаем полный скан и сразу его заполняем.
 * Принудительное обновление — только через rescan(), вызываемый вручную
 * кнопкой в настройках; никакого фонового отслеживания изменений.
 */
public class LibraryRepository {

    public static List<Song> getSongs(Context context, int sortMode) {
        LibraryCache cache = new LibraryCache(context);
        List<Song> songs;
        if (cache.isEmpty()) {
            songs = fullScanAndCache(context, cache);
        } else {
            songs = cache.getAll();
        }
        MediaScanner.sortSongs(songs, sortMode);
        return songs;
    }

    /** Принудительный пересбор библиотеки — вызывается кнопкой в настройках. */
    public static List<Song> rescan(Context context) {
        LibraryCache cache = new LibraryCache(context);
        return fullScanAndCache(context, cache);
    }

    private static List<Song> fullScanAndCache(Context context, LibraryCache cache) {
        List<Song> songs = MediaScanner.getAllSongs(context, MediaScanner.SORT_TITLE);
        songs = MediaScanner.filterByTypeAndDuration(songs,
                SortPrefs.getExcludedExtensions(context), SortPrefs.getMinDurationSeconds(context));
        for (Song s : songs) {
            MediaScanner.resolveMissingTags(s);
            s.isBroken = MediaScanner.isLikelyBroken(s.path);
        }
        cache.replaceAll(songs);
        return songs;
    }
}

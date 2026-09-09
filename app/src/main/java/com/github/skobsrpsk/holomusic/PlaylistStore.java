package com.github.skobsrpsk.holomusic;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.skobsrpsk.holomusic.model.Playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Простое хранилище пользовательских плейлистов в SharedPreferences —
 * без БД, по образцу SortPrefs. Имена плейлистов держим в отдельном
 * StringSet, треки каждого плейлиста — в своём ключе "songs_of_<name>".
 */
public class PlaylistStore {

    private static final String PREFS = "holo_music_playlists";
    private static final String KEY_NAMES = "playlist_names";
    private static final String SONGS_PREFIX = "songs_of_";

    public static List<String> getPlaylistNames(Context context) {
        Set<String> names = prefs(context).getStringSet(KEY_NAMES, new HashSet<String>());
        List<String> result = new ArrayList<>(names);
        Collections.sort(result, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareToIgnoreCase(b);
            }
        });
        return result;
    }

    public static boolean createPlaylist(Context context, String name) {
        if (name == null || name.trim().isEmpty()) return false;
        Set<String> names = new HashSet<>(prefs(context).getStringSet(KEY_NAMES, new HashSet<String>()));
        if (names.contains(name)) return false;
        names.add(name);
        prefs(context).edit().putStringSet(KEY_NAMES, names).apply();
        return true;
    }

    public static void deletePlaylist(Context context, String name) {
        Set<String> names = new HashSet<>(prefs(context).getStringSet(KEY_NAMES, new HashSet<String>()));
        names.remove(name);
        prefs(context).edit()
                .putStringSet(KEY_NAMES, names)
                .remove(SONGS_PREFIX + name)
                .apply();
    }

    public static Playlist getPlaylist(Context context, String name) {
        Playlist playlist = new Playlist(name);
        String raw = prefs(context).getString(SONGS_PREFIX + name, "");
        for (String part : raw.split(";")) {
            if (!part.trim().isEmpty()) {
                try {
                    playlist.songIds.add(Long.parseLong(part));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return playlist;
    }

    public static void addSongToPlaylist(Context context, String playlistName, long songId) {
        Playlist playlist = getPlaylist(context, playlistName);
        if (!playlist.songIds.contains(songId)) {
            playlist.songIds.add(songId);
            savePlaylistSongs(context, playlistName, playlist.songIds);
        }
    }

    public static void removeSongFromPlaylist(Context context, String playlistName, long songId) {
        Playlist playlist = getPlaylist(context, playlistName);
        playlist.songIds.remove(songId);
        savePlaylistSongs(context, playlistName, playlist.songIds);
    }

    private static void savePlaylistSongs(Context context, String playlistName, List<Long> songIds) {
        StringBuilder sb = new StringBuilder();
        for (Long id : songIds) {
            sb.append(id).append(";");
        }
        prefs(context).edit().putString(SONGS_PREFIX + playlistName, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

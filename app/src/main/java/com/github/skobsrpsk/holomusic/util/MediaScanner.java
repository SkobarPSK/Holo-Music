package com.github.skobsrpsk.holomusic.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.provider.MediaStore;

import com.github.skobsrpsk.holomusic.model.Album;
import com.github.skobsrpsk.holomusic.model.Artist;
import com.github.skobsrpsk.holomusic.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * Простой сканер медиатеки через MediaStore. Без внешних библиотек,
 * без кэширования — читаем напрямую при каждом обращении к экрану.
 * Для больших библиотек можно добавить кэш в SharedPreferences/SQLite позже.
 */
public class MediaScanner {

    public static final int SORT_TITLE = 0;
    public static final int SORT_ARTIST = 1;
    public static final int SORT_ALBUM = 2;

    /**
     * Проверка разрешения на чтение аудио. Раньше запрос к MediaStore
     * выполнялся сразу в onCreate, не дожидаясь ответа на системный диалог
     * разрешения — на большинстве прошивок запрос без разрешения просто
     * возвращал пустой курсор, но как минимум на Android 9 у части устройств
     * это оборачивается SecurityException и приложение падает мгновенно при
     * первом запуске. Теперь каждый метод, дергающий MediaStore, сначала
     * проверяет разрешение сам и возвращает пустой список вместо падения —
     * это работает как страховка независимо от того, в каком порядке экраны
     * вызывают загрузку данных.
     */
    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // до Android 6 runtime-разрешения не нужны
        }
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Сортировка уже готового списка (для данных из LibraryCache — SQL ORDER BY там не применялся). */
    public static void sortSongs(List<Song> songs, int sortMode) {
        java.util.Comparator<Song> comparator;
        switch (sortMode) {
            case SORT_ARTIST:
                comparator = new java.util.Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        int c = nullSafeCompare(a.artist, b.artist);
                        return c != 0 ? c : nullSafeCompare(a.title, b.title);
                    }
                };
                break;
            case SORT_ALBUM:
                comparator = new java.util.Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        int c = nullSafeCompare(a.album, b.album);
                        return c != 0 ? c : nullSafeCompare(a.title, b.title);
                    }
                };
                break;
            default:
                comparator = new java.util.Comparator<Song>() {
                    @Override
                    public int compare(Song a, Song b) {
                        return nullSafeCompare(a.title, b.title);
                    }
                };
        }
        java.util.Collections.sort(songs, comparator);
    }

    private static int nullSafeCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareToIgnoreCase(b);
    }

    public static List<Song> getAllSongs(Context context, int sortMode) {
        if (!hasPermission(context)) return new ArrayList<>();
        List<Song> songs = new ArrayList<>();

        String sortOrder;
        switch (sortMode) {
            case SORT_ARTIST:
                sortOrder = MediaStore.Audio.Media.ARTIST + " ASC, " + MediaStore.Audio.Media.TITLE + " ASC";
                break;
            case SORT_ALBUM:
                sortOrder = MediaStore.Audio.Media.ALBUM + " ASC, " + MediaStore.Audio.Media.TITLE + " ASC";
                break;
            default:
                sortOrder = MediaStore.Audio.Media.TITLE + " ASC";
        }

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder);

        if (cursor != null) {
            try {
                int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int artistIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    songs.add(new Song(
                            cursor.getLong(idCol),
                            cursor.getString(titleCol),
                            cursor.getString(artistCol),
                            cursor.getLong(artistIdCol),
                            cursor.getString(albumCol),
                            cursor.getLong(albumIdCol),
                            cursor.getString(dataCol),
                            cursor.getLong(durationCol)
                    ));
                }
            } finally {
                cursor.close();
            }
        }

        return songs;
    }

    /**
     * Список альбомов строится группировкой уже отфильтрованных треков
     * (getAllSongs() учитывает только IS_MUSIC != 0), а не отдельным запросом
     * к MediaStore.Audio.Albums. Та таблица — общая агрегация по всей
     * медиатеке устройства и может содержать "альбомы", в которых нет ни
     * одного реального музыкального трека (голосовые заметки, WhatsApp-аудио
     * и т.п. группируются в свои psuedo-альбомы). Группировка по уже
     * отфильтрованным трекам гарантирует, что в списке будут только альбомы
     * с реальной музыкой.
     */
    public static List<Album> getAlbumsFromSongs(List<Song> songs) {
        java.util.LinkedHashMap<Long, Album> byId = new java.util.LinkedHashMap<>();
        for (Song s : songs) {
            Album album = byId.get(s.albumId);
            if (album == null) {
                album = new Album(s.albumId, s.album, s.artist, 0);
                byId.put(s.albumId, album);
            }
            album.songCount++;
        }
        List<Album> result = new ArrayList<>(byId.values());
        java.util.Collections.sort(result, new java.util.Comparator<Album>() {
            @Override
            public int compare(Album a, Album b) {
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
        return result;
    }

    /**
     * Список артистов строится группировкой уже отфильтрованных треков —
     * той же логикой, что и getAlbumsFromSongs(). Раньше здесь был отдельный
     * запрос к MediaStore.Audio.Artists, который (а) не учитывал выбранные
     * в настройках папки библиотеки — раздел "Исполнители" показывал артистов
     * из треков вне ограниченных папок, хотя "Все треки" их уже не показывал;
     * и (б) как и с альбомами, мог включать артистов из не-музыкальных
     * файлов, не проходящих IS_MUSIC. Группировка по уже отфильтрованному
     * списку решает обе проблемы разом.
     */
    // Разделители для "составных" тегов исполнителя: запятая, точка с
    // запятой, "feat."/"ft."/"featuring" (без учёта регистра). Амперсанд
    // намеренно НЕ разделитель — он часто часть настоящего имени дуэта/
    // группы ("Simon & Garfunkel", "Hall & Oates"), а не признака "тут
    // несколько исполнителей через тег", в отличие от запятой и feat./ft.
    // \b вокруг feat/ft не даёт зацепить середину обычных слов вроде "Feathers".
    private static final java.util.regex.Pattern ARTIST_SPLIT_PATTERN = java.util.regex.Pattern.compile(
            "\\s*(?:,|;|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b)\\s*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Первый исполнитель из тега вида "Artist A, Artist B" или
     * "Artist A feat. Artist B" — по договорённости для раздела Artists
     * не пытаемся разобраться, кто тут "главный", просто берём первого
     * по порядку, чтобы не плодить отдельную строку на каждую комбинацию.
     */
    public static String primaryArtistName(String rawArtist) {
        if (rawArtist == null) return "";
        String[] parts = ARTIST_SPLIT_PATTERN.split(rawArtist, 2);
        return parts[0].trim();
    }

    public static List<Artist> getArtistsFromSongs(List<Song> songs) {
        java.util.LinkedHashMap<String, Artist> byName = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<Long>> albumsByArtist = new java.util.HashMap<>();

        for (Song s : songs) {
            String primaryName = primaryArtistName(s.artist);
            String key = primaryName.toLowerCase(java.util.Locale.ROOT);
            Artist artist = byName.get(key);
            if (artist == null) {
                artist = new Artist(s.artistId, primaryName, 0, 0);
                byName.put(key, artist);
                albumsByArtist.put(key, new java.util.HashSet<Long>());
            }
            artist.songCount++;
            albumsByArtist.get(key).add(s.albumId);
        }

        List<Artist> result = new ArrayList<>();
        for (java.util.Map.Entry<String, Artist> entry : byName.entrySet()) {
            Artist a = entry.getValue();
            a.albumCount = albumsByArtist.get(entry.getKey()).size();
            result.add(a);
        }

        java.util.Collections.sort(result, new java.util.Comparator<Artist>() {
            @Override
            public int compare(Artist a, Artist b) {
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
        return result;
    }

    /**
     * Треки "объединённого" исполнителя из раздела Artists — совпадение
     * идёт по primaryArtistName(), а не по artistId, потому что несколько
     * разных MediaStore artist_id ("Artist A" и "Artist A, Artist B")
     * теперь схлопнуты в одну строку и должны открываться вместе.
     */
    public static List<Song> getSongsForArtistName(Context context, String primaryName) {
        List<Song> all = getAllSongs(context, SORT_ALBUM);
        List<Song> result = new ArrayList<>();
        for (Song s : all) {
            if (primaryArtistName(s.artist).equalsIgnoreCase(primaryName)) {
                result.add(s);
            }
        }
        return result;
    }

    /** Один трек по его MediaStore id — нужен для разрешения id в плейлистах. */
    public static Song getSongById(Context context, long songId) {
        List<Song> result = querySongsBy(context, MediaStore.Audio.Media._ID + "=?",
                new String[]{String.valueOf(songId)}, null);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Трек по пути к файлу — нужен, чтобы после правки тегов и
     * MediaScannerConnection.scanFile() забрать у MediaStore уже
     * актуальные значения (включая, возможно, новый artist_id/album_id —
     * они назначаются MediaStore по строке тега, а не нами).
     */
    public static Song getSongByPath(Context context, String path) {
        if (path == null) return null;
        List<Song> result = querySongsBy(context, MediaStore.Audio.Media.DATA + "=?",
                new String[]{path}, null);
        return result.isEmpty() ? null : result.get(0);
    }

    /** Треки конкретного альбома — запрос напрямую по album_id, без промежуточной фильтрации. */
    public static List<Song> getSongsForAlbum(Context context, long albumId) {
        return querySongsBy(context, MediaStore.Audio.Media.ALBUM_ID + "=?",
                new String[]{String.valueOf(albumId)},
                MediaStore.Audio.Media.TITLE + " ASC");
    }

    /**
     * Треки конкретного артиста — запрос напрямую по artist_id (не по имени!).
     * Раньше фильтрация шла по строковому совпадению artist.equals(s.artist),
     * а счётчик треков брался из MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
     * который группирует по artist_id. Из-за этого если у части треков имя
     * артиста отличалось написанием (регистр, лишний пробел, "feat." и т.п.),
     * они физически принадлежали тому же artist_id, но не проходили строковое
     * сравнение — и терялись из списка, хотя счётчик их учитывал. Запрос по
     * artist_id полностью убирает это расхождение.
     */
    public static List<Song> getSongsForArtist(Context context, long artistId) {
        return querySongsBy(context, MediaStore.Audio.Media.ARTIST_ID + "=?",
                new String[]{String.valueOf(artistId)},
                MediaStore.Audio.Media.ALBUM + " ASC, " + MediaStore.Audio.Media.TITLE + " ASC");
    }

    private static List<Song> querySongsBy(Context context, String extraSelection, String[] selectionArgs, String sortOrder) {
        List<Song> songs = new ArrayList<>();
        if (!hasPermission(context)) return songs;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + extraSelection;

        Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder);

        if (cursor != null) {
            try {
                int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int artistIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    songs.add(new Song(
                            cursor.getLong(idCol),
                            cursor.getString(titleCol),
                            cursor.getString(artistCol),
                            cursor.getLong(artistIdCol),
                            cursor.getString(albumCol),
                            cursor.getLong(albumIdCol),
                            cursor.getString(dataCol),
                            cursor.getLong(durationCol)
                    ));
                }
            } finally {
                cursor.close();
            }
        }

        return songs;
    }

    /**
     * Оставляет только треки, чей путь лежит внутри одной из выбранных папок.
     * Если список папок пуст — ограничения нет, возвращается исходный список.
     */
    public static List<Song> filterByFolders(List<Song> songs, List<String> folders) {
        if (folders == null || folders.isEmpty()) return songs;
        List<Song> result = new ArrayList<>();
        for (Song s : songs) {
            if (s.path == null) continue;
            for (String folder : folders) {
                if (s.path.startsWith(folder)) {
                    result.add(s);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Фильтр по типу файла и минимальной длине — применяется на этапе
     * скана, до попадания в кэш, поэтому исключённые файлы не сканируются
     * никак, а не просто прячутся в интерфейсе.
     */
    public static List<Song> filterByTypeAndDuration(List<Song> songs, java.util.Set<String> excludedExtensions, int minDurationSeconds) {
        if ((excludedExtensions == null || excludedExtensions.isEmpty()) && minDurationSeconds <= 0) {
            return songs;
        }
        List<Song> result = new ArrayList<>();
        long minDurationMs = minDurationSeconds * 1000L;
        for (Song s : songs) {
            if (minDurationMs > 0 && s.duration < minDurationMs) continue;
            if (excludedExtensions != null && !excludedExtensions.isEmpty()) {
                String ext = extensionOf(s.path);
                if (ext != null && excludedExtensions.contains(ext)) continue;
            }
            result.add(s);
        }
        return result;
    }

    private static String extensionOf(String path) {
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        return path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * MediaStore иногда не может разобрать теги (нестандартная кодировка,
     * ID3v2.4 и т.п.) и подставляет "<unknown>". В этом случае пробуем
     * дочитать артиста/альбом/название напрямую из файла через
     * MediaMetadataRetriever — тот же путь, которым пользуются сторонние
     * плееры, парсящие теги сами. Вызывать только вне UI-потока: открытие
     * файла и разбор тегов занимает заметное время на больших библиотеках.
     */
    public static void resolveMissingTags(Song song) {
        if (!isUnknown(song.artist) && !isUnknown(song.album) && !isEmpty(song.title)) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(song.path);
            if (isUnknown(song.artist)) {
                String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (!isEmpty(artist)) song.artist = artist;
            }
            if (isUnknown(song.album)) {
                String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                if (!isEmpty(album)) song.album = album;
            }
            if (isEmpty(song.title)) {
                String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (!isEmpty(title)) song.title = title;
            }
        } catch (Exception ignored) {
            // Повреждённый файл или неподдерживаемый формат — оставляем как есть.
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isUnknown(String value) {
        return isEmpty(value) || value.equalsIgnoreCase("<unknown>") || value.equalsIgnoreCase("unknown");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    // Меньше этого размера реальный трек физически не уместится — типичный
    // случай: файл от оборванной закачки (например, ВК-загрузчиком),
    // который MediaStore всё равно проиндексировал по расширению.
    private static final long MIN_PLAUSIBLE_FILE_SIZE = 8 * 1024;

    /**
     * Грубая, но дешёвая эвристика "этот файл, похоже, не проиграется" —
     * не запускает воспроизведение, просто проверяет физический размер и
     * (для mp3) — что сразу после тега действительно начинается настоящий
     * MP3-фрейм (сигнатура синхронизации MPEG: 0xFF + три старших бита
     * следующего байта). Ловит самый частый жизненный случай — оборванные
     * закачки, у которых теги на месте, а аудио-данных нет или почти нет,
     * хотя MediaStore всё равно показывает файл как обычный трек.
     * Вызывать только вне UI-потока — открывает файл на чтение.
     */
    public static boolean isLikelyBroken(String path) {
        if (path == null) return false;
        java.io.File file = new java.io.File(path);
        long len = file.length();
        if (len < MIN_PLAUSIBLE_FILE_SIZE) return true;
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) {
            return false; // проверка сигнатуры имеет смысл только для mp3
        }

        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(file, "r");
            byte[] header = new byte[10];
            long audioStart = 0;
            if (raf.read(header) == 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                audioStart = 10L + Mp3TagIO.synchsafeToInt(header, 6);
            }
            if (audioStart + 2 > len) return true; // тег "съедает" весь файл целиком
            raf.seek(audioStart);
            byte[] sync = new byte[2];
            raf.readFully(sync);
            return !((sync[0] & 0xFF) == 0xFF && (sync[1] & 0xE0) == 0xE0);
        } catch (java.io.IOException e) {
            return true; // не смогли прочитать файл — тоже подозрительно
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }
}

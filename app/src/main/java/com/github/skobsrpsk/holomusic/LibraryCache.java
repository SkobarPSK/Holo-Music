package com.github.skobsrpsk.holomusic;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.github.skobsrpsk.holomusic.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * Простой кэш библиотеки на SQLite (голый SQLiteOpenHelper, без Room и
 * прочих библиотек). Раньше каждый заход на "Все треки"/"Альбомы"/
 * "Исполнители" заново дёргал MediaStore и — для треков с "unknown" тегами —
 * ещё и MediaMetadataRetriever по каждому файлу. На небольшой библиотеке
 * незаметно, на нескольких тысячах треков уже ощутимая задержка при каждом
 * открытии раздела. Кэш хранит уже отсканированный и дотянутый по тегам
 * список — читается почти мгновенно, обновляется только по явному запросу
 * пользователя ("Пересканировать библиотеку" в настройках), без фонового
 * отслеживания изменений (ContentObserver и т.п.) — сознательно просто.
 */
public class LibraryCache extends SQLiteOpenHelper {

    private static final String DB_NAME = "holo_music_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "songs";

    public LibraryCache(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY," +
                "title TEXT," +
                "artist TEXT," +
                "artist_id INTEGER," +
                "album TEXT," +
                "album_id INTEGER," +
                "path TEXT," +
                "duration INTEGER" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public boolean isEmpty() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return !c.moveToFirst() || c.getInt(0) == 0;
        } finally {
            c.close();
        }
    }

    /** Полная замена содержимого кэша — вызывается после свежего скана MediaStore. */
    public void replaceAll(List<Song> songs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, null, null);
            for (Song s : songs) {
                ContentValues cv = new ContentValues();
                cv.put("id", s.id);
                cv.put("title", s.title);
                cv.put("artist", s.artist);
                cv.put("artist_id", s.artistId);
                cv.put("album", s.album);
                cv.put("album_id", s.albumId);
                cv.put("path", s.path);
                cv.put("duration", s.duration);
                db.insert(TABLE, null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Song> getAll() {
        List<Song> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, null);
        if (c != null) {
            try {
                int idCol = c.getColumnIndex("id");
                int titleCol = c.getColumnIndex("title");
                int artistCol = c.getColumnIndex("artist");
                int artistIdCol = c.getColumnIndex("artist_id");
                int albumCol = c.getColumnIndex("album");
                int albumIdCol = c.getColumnIndex("album_id");
                int pathCol = c.getColumnIndex("path");
                int durationCol = c.getColumnIndex("duration");

                while (c.moveToNext()) {
                    result.add(new Song(
                            c.getLong(idCol),
                            c.getString(titleCol),
                            c.getString(artistCol),
                            c.getLong(artistIdCol),
                            c.getString(albumCol),
                            c.getLong(albumIdCol),
                            c.getString(pathCol),
                            c.getLong(durationCol)
                    ));
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    /**
     * Точечное обновление одной строки — используется после редактирования
     * тегов трека, чтобы не гонять полный replaceAll() по всей библиотеке
     * ради одного файла. id — PRIMARY KEY, поэтому это просто перезаписывает
     * существующую строку (или создаёт её, если её почему-то не было).
     */
    public void updateSong(Song s) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", s.id);
        cv.put("title", s.title);
        cv.put("artist", s.artist);
        cv.put("artist_id", s.artistId);
        cv.put("album", s.album);
        cv.put("album_id", s.albumId);
        cv.put("path", s.path);
        cv.put("duration", s.duration);
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }
}

package com.github.skobsrpsk.holomusic.model;

/**
 * Простая модель трека. Никакой лишней архитектуры — обычный POJO.
 */
public class Song {

    public long id;
    public String title;
    public String artist;
    public long artistId;
    public String album;
    public long albumId;
    public String path;
    public long duration; // в миллисекундах
    public boolean favorite;

    public Song(long id, String title, String artist, long artistId, String album, long albumId, String path, long duration) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.artistId = artistId;
        this.album = album;
        this.albumId = albumId;
        this.path = path;
        this.duration = duration;
    }
}

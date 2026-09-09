package com.github.skobsrpsk.holomusic.model;

public class Album {
    public long id;
    public String name;
    public String artist;
    public int songCount;

    public Album(long id, String name, String artist, int songCount) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.songCount = songCount;
    }
}

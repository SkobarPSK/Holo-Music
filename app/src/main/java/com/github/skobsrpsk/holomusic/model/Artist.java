package com.github.skobsrpsk.holomusic.model;

public class Artist {
    public long id;
    public String name;
    public int songCount;
    public int albumCount;

    public Artist(long id, String name, int songCount, int albumCount) {
        this.id = id;
        this.name = name;
        this.songCount = songCount;
        this.albumCount = albumCount;
    }
}

package com.github.skobsrpsk.holomusic.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {
    public String name;
    public List<Long> songIds = new ArrayList<>();

    public Playlist(String name) {
        this.name = name;
    }
}

package com.github.skobsrpsk.holomusic;

/**
 * Реализуют экраны/фрагменты со списком треков, которым нужно подсвечивать
 * текущий играющий трек (треугольник + акцентный цвет в SongAdapter).
 */
public interface NowPlayingAware {
    void onNowPlayingChanged(long currentSongId);
}

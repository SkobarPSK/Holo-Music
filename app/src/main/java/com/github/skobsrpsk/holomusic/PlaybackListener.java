package com.github.skobsrpsk.holomusic;

/**
 * Простой колбэк для UI (NowPlayingActivity), чтобы получать обновления
 * о состоянии воспроизведения без брокеров/шины событий — минимум архитектуры.
 */
public interface PlaybackListener {
    void onTrackChanged();
    void onPlaybackStateChanged(boolean isPlaying);
    void onProgress(int positionMs, int durationMs);
}

package com.github.skobsrpsk.holomusic;

import android.app.Fragment;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.SongAdapter;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка MUSICS — плоский список всех треков на устройстве.
 * Загрузка идёт в фоне: чтение MediaStore + докачка отсутствующих тегов
 * через MediaMetadataRetriever может занять заметное время на большой
 * библиотеке, поэтому UI-поток не блокируем.
 */
public class MusicsFragment extends Fragment implements NowPlayingAware, Searchable {

    private ListView listView;
    private TextView emptyText;
    private SongAdapter adapter;
    private final List<Song> songs = new ArrayList<>();
    private final List<Song> allSongs = new ArrayList<>();
    private LoadTask activeTask;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_song_list, container, false);
        listView = root.findViewById(R.id.list_view);
        emptyText = root.findViewById(R.id.empty_text);

        adapter = new SongAdapter(getActivity(), songs);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlayerService.playQueue(getActivity(), songs, position);
                NowPlayingActivity.start(getActivity());
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                SongActions.showMenu(getActivity(), songs.get(position), new SongActions.Callback() {
                    @Override
                    public void onSongDeleted() {
                        reload();
                    }
                });
                return true;
            }
        });

        reload();
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (activeTask != null) {
            activeTask.cancel(true);
        }
    }

    public void reload() {
        if (getActivity() == null) return;
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        emptyText.setText(R.string.loading_library);
        emptyText.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);

        activeTask = new LoadTask();
        activeTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Простой локальный фильтр по названию/артисту/альбому для SearchView. */
    @Override
    public void filter(String query) {
        songs.clear();
        if (query == null || query.trim().isEmpty()) {
            songs.addAll(allSongs);
        } else {
            // Ищем только по названию трека — это то, что показано крупным
            // шрифтом в строке списка. Раньше искало ещё и по исполнителю/
            // альбому одновременно, из-за чего результаты было сложно
            // предсказать.
            String q = query.toLowerCase();
            for (Song s : allSongs) {
                if (s.title != null && s.title.toLowerCase().contains(q)) {
                    songs.add(s);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        emptyText.setText(R.string.empty_library);
        emptyText.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onNowPlayingChanged(long currentSongId) {
        if (adapter != null) adapter.setCurrentlyPlayingId(currentSongId);
    }

    private class LoadTask extends AsyncTask<Void, Void, List<Song>> {
        @Override
        protected List<Song> doInBackground(Void... voids) {
            if (getActivity() == null) return new ArrayList<>();

            List<Song> loaded = LibraryRepository.getSongs(getActivity(), SortPrefs.getSortMode(getActivity()));
            loaded = MediaScanner.filterByFolders(loaded, SortPrefs.getLibraryFolders(getActivity()));

            for (Song s : loaded) {
                if (isCancelled()) break;
                s.favorite = SortPrefs.isFavorite(getActivity(), s.id);
            }
            return loaded;
        }

        @Override
        protected void onPostExecute(List<Song> result) {
            if (getActivity() == null) return;
            allSongs.clear();
            allSongs.addAll(result);
            songs.clear();
            songs.addAll(allSongs);
            adapter.notifyDataSetChanged();
            updateEmptyState();
            if (getActivity() instanceof BaseActivity) {
                BaseActivity base = (BaseActivity) getActivity();
                if (base.serviceBound) {
                    Song current = base.playerService.getCurrentSong();
                    adapter.setCurrentlyPlayingId(current != null ? current.id : -1);
                }
            }
        }
    }
}

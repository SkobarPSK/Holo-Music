package com.github.skobsrpsk.holomusic;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.adapter.SongAdapter;
import com.github.skobsrpsk.holomusic.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел "Очередь воспроизведения" — показывает текущую очередь сервиса.
 * Тап по треку сразу переключает на него (playAtIndex), не создавая новую очередь.
 */
public class QueueFragment extends Fragment implements NowPlayingAware {

    private final List<Song> queue = new ArrayList<>();
    private final List<Integer> orderIndices = new ArrayList<>();
    private SongAdapter adapter;
    private TextView emptyText;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_song_list, container, false);
        ListView listView = root.findViewById(R.id.list_view);
        emptyText = root.findViewById(R.id.empty_text);
        emptyText.setText(R.string.queue_empty);

        adapter = new SongAdapter(getActivity(), queue);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (getActivity() instanceof BaseActivity) {
                    BaseActivity base = (BaseActivity) getActivity();
                    if (base.serviceBound && position < orderIndices.size()) {
                        // position — это место в отображаемом (уже упорядоченном
                        // под shuffle) списке, а playAtIndex() ждёт настоящий
                        // индекс в очереди сервиса — берём его из orderIndices.
                        base.playerService.playAtIndex(orderIndices.get(position));
                        NowPlayingActivity.start(getActivity());
                    }
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                SongActions.showMenu(getActivity(), queue.get(position), new SongActions.Callback() {
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
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onNowPlayingChanged(long currentSongId) {
        // reload() уже читает текущий id из сервиса и проставляет метку —
        // отдельный вызов adapter.setCurrentlyPlayingId() тут не нужен.
        reload();
    }

    public void reload() {
        queue.clear();
        orderIndices.clear();
        long currentId = -1;
        if (getActivity() instanceof BaseActivity) {
            BaseActivity base = (BaseActivity) getActivity();
            if (base.serviceBound) {
                orderIndices.addAll(base.playerService.getQueueOrderIndices());
                queue.addAll(base.playerService.getQueueSnapshot());
                Song current = base.playerService.getCurrentSong();
                if (current != null) currentId = current.id;
            }
        }
        if (adapter != null) {
            adapter.setCurrentlyPlayingId(currentId);
            adapter.notifyDataSetChanged();
        }
        if (emptyText != null) emptyText.setVisibility(queue.isEmpty() ? View.VISIBLE : View.GONE);
    }
}

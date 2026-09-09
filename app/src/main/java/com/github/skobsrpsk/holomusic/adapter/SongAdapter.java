package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.R;
import com.github.skobsrpsk.holomusic.SortPrefs;
import com.github.skobsrpsk.holomusic.model.Song;
import com.github.skobsrpsk.holomusic.util.AlbumArtLoader;

import java.util.List;

public class SongAdapter extends ArrayAdapter<Song> {

    private static final int THUMBNAIL_SIZE_PX = 120; // с запасом под 40dp на любой плотности

    private final LayoutInflater inflater;
    private long currentlyPlayingId = -1;

    public SongAdapter(Context context, List<Song> songs) {
        super(context, 0, songs);
        inflater = LayoutInflater.from(context);
    }

    /** Помечает трек как играющий сейчас — треугольник + акцентный цвет названия. */
    public void setCurrentlyPlayingId(long songId) {
        if (this.currentlyPlayingId != songId) {
            this.currentlyPlayingId = songId;
            notifyDataSetChanged();
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_two_line, parent, false);
            holder = new ViewHolder();
            holder.marker = convertView.findViewById(R.id.text_now_playing_marker);
            holder.thumbnail = convertView.findViewById(R.id.image_thumbnail);
            holder.title = convertView.findViewById(R.id.text_title);
            holder.subtitle = convertView.findViewById(R.id.text_subtitle);
            holder.favorite = convertView.findViewById(R.id.text_favorite);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Song song = getItem(position);
        if (song != null) {
            holder.title.setText(song.title);
            holder.subtitle.setText(song.artist + " — " + song.album);
            holder.favorite.setText(song.favorite ? "\u2605" : "");

            boolean isPlaying = song.id == currentlyPlayingId;
            holder.marker.setVisibility(isPlaying ? View.VISIBLE : View.INVISIBLE);
            int color = getContext().getResources().getColor(isPlaying ? R.color.holo_blue : R.color.text_primary);
            holder.title.setTextColor(color);

            if (SortPrefs.isShowThumbnailsInLists(getContext())) {
                holder.thumbnail.setVisibility(View.VISIBLE);
                AlbumArtLoader.loadInto(song.id, song.path, holder.thumbnail, THUMBNAIL_SIZE_PX);
            } else {
                holder.thumbnail.setVisibility(View.GONE);
            }
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView marker;
        ImageView thumbnail;
        TextView title;
        TextView subtitle;
        TextView favorite;
    }
}

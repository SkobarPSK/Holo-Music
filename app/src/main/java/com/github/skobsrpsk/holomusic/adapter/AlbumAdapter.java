package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.R;
import com.github.skobsrpsk.holomusic.model.Album;

import java.util.List;

public class AlbumAdapter extends ArrayAdapter<Album> {

    private final LayoutInflater inflater;

    public AlbumAdapter(Context context, List<Album> albums) {
        super(context, 0, albums);
        inflater = LayoutInflater.from(context);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_two_line, parent, false);
            holder = new ViewHolder();
            holder.title = convertView.findViewById(R.id.text_title);
            holder.subtitle = convertView.findViewById(R.id.text_subtitle);
            convertView.findViewById(R.id.text_favorite).setVisibility(View.GONE);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Album album = getItem(position);
        if (album != null) {
            holder.title.setText(album.name);
            holder.subtitle.setText(album.artist + " • " + album.songCount + " треков");
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView title;
        TextView subtitle;
    }
}

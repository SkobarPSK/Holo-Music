package com.github.skobsrpsk.holomusic.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.github.skobsrpsk.holomusic.R;
import com.github.skobsrpsk.holomusic.model.Artist;

import java.util.List;

public class ArtistAdapter extends ArrayAdapter<Artist> {

    private final LayoutInflater inflater;

    public ArtistAdapter(Context context, List<Artist> artists) {
        super(context, 0, artists);
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

        Artist artist = getItem(position);
        if (artist != null) {
            holder.title.setText(artist.name);
            holder.subtitle.setText(artist.albumCount + " альбомов • " + artist.songCount + " треков");
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView title;
        TextView subtitle;
    }
}

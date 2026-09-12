package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Главный экран настроек — просто список разделов (Интерфейс / Библиотека /
 * Воспроизведение / О приложении), тап открывает соответствующий подэкран.
 * Раньше это был один длинный скролл со всеми настройками сразу — стало
 * заметно длиннее по мере добавления новых пунктов, разбил по разделам.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_categories);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings);
        }

        ListView listView = findViewById(R.id.list_settings_categories);
        final SettingsCategory[] categories = SettingsCategory.values();

        ArrayAdapter<SettingsCategory> adapter = new ArrayAdapter<SettingsCategory>(this, 0, categories) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view;
                if (convertView == null) {
                    view = (TextView) LayoutInflater.from(getContext()).inflate(R.layout.list_item_drawer, parent, false);
                } else {
                    view = (TextView) convertView;
                }
                view.setText(categories[position].titleRes);
                return view;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startActivity(new Intent(SettingsActivity.this, categories[position].activityClass));
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

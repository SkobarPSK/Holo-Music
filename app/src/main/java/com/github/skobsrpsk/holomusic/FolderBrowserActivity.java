package com.github.skobsrpsk.holomusic;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Простой файловый браузер по файловой системе — без Storage Access
 * Framework, обычный обход File. Позволяет выбрать одну папку, чтобы
 * ограничить ею сканирование библиотеки. Возвращает выбранный путь через
 * setResult(RESULT_OK, ...) с ключом EXTRA_SELECTED_PATH.
 *
 * SD-карта монтируется как отдельный корень (например /storage/XXXX-XXXX),
 * а не как подпапка внутренней памяти — обычной навигацией "вверх" от
 * /storage/emulated/0 до неё не добраться (сам /storage чаще всего не
 * читается напрямую). Поэтому корни хранилищ определяются отдельно через
 * getExternalFilesDirs() и предлагаются как быстрые переходы сверху.
 */
public class FolderBrowserActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.github.skobsrpsk.holomusic.util.LocaleHelper.wrap(newBase));
    }


    public static final String EXTRA_SELECTED_PATH = "selected_path";

    private File currentDir;
    private TextView textPath;
    private ListView listView;
    private final List<File> subDirs = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_browser);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.add_folder);
        }

        textPath = findViewById(R.id.text_current_path);
        listView = findViewById(R.id.list_folders);
        Button selectButton = findViewById(R.id.btn_select_folder);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listView.setAdapter(adapter);

        setupStorageRoots();

        File start = Environment.getExternalStorageDirectory();
        currentDir = (start != null && start.isDirectory()) ? start : new File("/storage/emulated/0");
        refreshList();

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                currentDir = subDirs.get(position);
                refreshList();
            }
        });

        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithSelection(currentDir.getAbsolutePath());
            }
        });

        requestAllFilesAccessIfNeeded();
    }

    /**
     * На Android 11+ (API 30) requestLegacyExternalStorage больше не
     * действует вообще — Google отключил этот обходной путь полностью,
     * независимо от targetSdk. Обычный File-доступ за пределами публичных
     * медиа-папок (и тем более SD-карта) виден только с отдельным
     * разрешением "Доступ ко всем файлам", которое выдаётся через системный
     * экран настроек, а не обычный requestPermissions(). Без него список
     * подпапок на карте памяти будет просто пустым или недоступным.
     */
    private void requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.all_files_access_title)
                    .setMessage(R.string.all_files_access_message)
                    .setCancelable(true)
                    .setPositiveButton(R.string.open_settings, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                // На некоторых прошивках экран для конкретного приложения отсутствует — открываем общий.
                                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    /**
     * Определяет корни доступных хранилищ (внутренняя память + SD-карта,
     * если есть). Раньше полагался только на getExternalFilesDirs() — на
     * части прошивок (особенно кастомные MIUI/EMUI и т.п.) этот API
     * возвращает только внутреннюю память, даже если SD-карта физически
     * вставлена и видна через MediaStore. Теперь дополнительно напрямую
     * сканируем /storage и берём оттуда любые читаемые тома, которых не было
     * среди уже найденных — так шансы найти карту заметно выше вне
     * зависимости от прошивки.
     */
    private void setupStorageRoots() {
        LinearLayout row = findViewById(R.id.storage_roots_row);
        List<File> roots = new ArrayList<>();

        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            roots.add(primary);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir == null) continue;
                    File root = stripToVolumeRoot(dir);
                    if (root != null && root.isDirectory() && !containsPath(roots, root)) {
                        roots.add(root);
                    }
                }
            }
        }

        // Резервный проход: сканируем /storage напрямую и добавляем любые
        // читаемые тома, которые не всплыли через getExternalFilesDirs().
        File storageDir = new File("/storage");
        File[] children = storageDir.listFiles();
        if (children != null) {
            for (File f : children) {
                String name = f.getName();
                if (f.isDirectory() && !"emulated".equals(name) && !"self".equals(name)
                        && !containsPath(roots, f)) {
                    roots.add(f);
                }
            }
        }

        if (roots.size() <= 1) {
            // Только один том — отдельные кнопки не нужны, обычной навигации достаточно.
            row.setVisibility(View.GONE);
            return;
        }

        for (int i = 0; i < roots.size(); i++) {
            final File root = roots.get(i);
            String label = i == 0 ? getString(R.string.internal_storage)
                    : i == 1 ? getString(R.string.sd_card)
                    : getString(R.string.storage_n, i + 1);

            TextView button = new TextView(this);
            button.setText(label);
            button.setTextColor(getResources().getColor(R.color.holo_blue));
            button.setTextSize(13f);
            button.setPadding(24, 16, 24, 16);
            button.setBackgroundResource(R.drawable.list_selector_holo);
            button.setGravity(Gravity.CENTER);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentDir = root;
                    refreshList();
                }
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.rightMargin = 16;
            row.addView(button, params);
        }
    }

    /** "/storage/XXXX-XXXX/Android/data/pkg/files" -> "/storage/XXXX-XXXX" */
    private File stripToVolumeRoot(File appSpecificDir) {
        String path = appSpecificDir.getAbsolutePath();
        int idx = path.indexOf("/Android/data/");
        if (idx <= 0) return null;
        return new File(path.substring(0, idx));
    }

    private boolean containsPath(List<File> list, File file) {
        for (File f : list) {
            if (f.getAbsolutePath().equals(file.getAbsolutePath())) return true;
        }
        return false;
    }

    private void refreshList() {
        textPath.setText(currentDir.getAbsolutePath());
        subDirs.clear();

        File[] children = currentDir.listFiles();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            for (File f : children) {
                if (f.isDirectory() && !f.isHidden()) {
                    dirs.add(f);
                }
            }
            Collections.sort(dirs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            subDirs.addAll(dirs);
        }

        adapter.clear();
        for (File f : subDirs) {
            adapter.add(f.getName());
        }
        adapter.notifyDataSetChanged();
    }

    private void finishWithSelection(String path) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SELECTED_PATH, path);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            currentDir = parent;
            refreshList();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

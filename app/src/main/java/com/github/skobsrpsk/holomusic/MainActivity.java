package com.github.skobsrpsk.holomusic;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SearchView;

import com.github.skobsrpsk.holomusic.adapter.DrawerSectionAdapter;
import com.github.skobsrpsk.holomusic.util.MediaScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран с боковым меню (DrawerContainer) вместо вкладок ActionBar.
 * Разделы: поиск, все треки, плейлисты, очередь, исполнители, альбомы —
 * порядок и видимость настраиваются в SettingsActivity.
 */
public class MainActivity extends BaseActivity {

    private static final int REQUEST_READ_AUDIO = 100;

    private DrawerContainer drawerContainer;
    private DrawerSectionAdapter drawerAdapter;
    private DrawerSection currentSection;

    private MenuItem searchMenuItem;
    private boolean pendingSearchFocus = false;
    private boolean justCreated = false;
    private boolean returningFromSettings = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setBaseContentView(R.layout.activity_main);
        requestAudioPermissionIfNeeded();

        drawerContainer = findViewById(R.id.drawer_container);
        ListView drawerList = findViewById(R.id.list_drawer_sections);

        drawerContainer.setDrawerListener(new DrawerContainer.DrawerListener() {
            @Override
            public void onDrawerOpened() {
                // Мини-плеер раньше оставался виден поверх задвинутого
                // контента, пока меню открыто — прячем его на время,
                // пока панель раскрыта.
                setMiniPlayerHiddenByDrawer(true);
            }

            @Override
            public void onDrawerClosed() {
                setMiniPlayerHiddenByDrawer(false);
            }
        });

        List<DrawerSection> visibleSections = buildVisibleSectionList();
        drawerAdapter = new DrawerSectionAdapter(this, visibleSections);
        drawerList.setAdapter(drawerAdapter);

        drawerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id) {
                DrawerSection section = drawerAdapter.getItem(position);
                drawerContainer.close();
                if (section != null) showSection(section);
            }
        });

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getActionBar().setHomeAsUpIndicator(R.drawable.ic_drawer_menu);
            }
            // На API < 21 setHomeAsUpIndicator(int) недоступен — останется
            // обычная стрелка "назад" вместо гамбургера, чисто косметическая
            // разница на очень старых устройствах.
        }

        justCreated = true;
        showSection(SortPrefs.getDefaultScreen(this));
    }

    @Override
    protected void onNowPlayingChanged(long currentSongId) {
        Fragment frag = getFragmentManager().findFragmentById(R.id.main_content_frame);
        if (frag instanceof NowPlayingAware) {
            ((NowPlayingAware) frag).onNowPlayingChanged(currentSongId);
        }
    }

    private List<DrawerSection> buildVisibleSectionList() {
        List<DrawerSection> order = SortPrefs.getDrawerOrder(this);
        java.util.Set<DrawerSection> hidden = SortPrefs.getHiddenSections(this);
        List<DrawerSection> visible = new ArrayList<>();
        for (DrawerSection s : order) {
            if (!hidden.contains(s)) visible.add(s);
        }
        return visible;
    }

    private void showSection(DrawerSection section) {
        currentSection = section;
        drawerAdapter.setSelected(section);

        Fragment fragment;
        switch (section) {
            case PLAYLISTS:
                fragment = new PlaylistsFragment();
                break;
            case QUEUE:
                fragment = new QueueFragment();
                break;
            case ARTISTS:
                fragment = new ArtistsFragment();
                break;
            case ALBUMS:
                fragment = new AlbumsFragment();
                break;
            case SEARCH:
            case ALL_TRACKS:
            default:
                fragment = new MusicsFragment();
                break;
        }

        getFragmentManager().beginTransaction()
                .replace(R.id.main_content_frame, fragment)
                .commitAllowingStateLoss();

        if (getActionBar() != null) {
            getActionBar().setTitle(section == DrawerSection.SEARCH
                    ? getString(R.string.section_all_tracks)
                    : getString(section.titleRes));
        }

        if (section == DrawerSection.SEARCH) {
            pendingSearchFocus = true;
            tryFocusSearch();
        }

        invalidateOptionsMenu();
    }

    private void tryFocusSearch() {
        if (pendingSearchFocus && searchMenuItem != null) {
            searchMenuItem.expandActionView();
            pendingSearchFocus = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (justCreated) {
            // Сразу после onCreate() showSection() уже отработал —
            // не нужно грузить раздел второй раз подряд.
            justCreated = false;
            return;
        }
        if (!returningFromSettings) {
            // Раньше здесь безусловно пересобирался текущий фрагмент при
            // КАЖДОМ возврате в приложение — в том числе просто после
            // сворачивания и разворачивания (переключение на другое
            // приложение и обратно), хотя ничего не менялось. Список
            // ненадолго показывал "треки не найдены" и перезагружался
            // заново, хотя воспроизведение и не думало прерываться.
            // Теперь список остаётся как есть, если пользователь не ходил
            // в настройки — там и правда могли поменяться папки/сортировка/
            // состав меню.
            return;
        }
        returningFromSettings = false;

        List<DrawerSection> visible = buildVisibleSectionList();
        drawerAdapter.clear();
        drawerAdapter.addAll(visible);
        if (!visible.contains(currentSection)) {
            currentSection = visible.isEmpty() ? DrawerSection.ALL_TRACKS : visible.get(0);
        }
        showSection(currentSection);
    }

    @Override
    public void onBackPressed() {
        if (drawerContainer != null && drawerContainer.handleBackPress()) {
            return;
        }
        super.onBackPressed();
    }

    private void requestAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final String permission = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                if (SortPrefs.isFirstRun(this)) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.permission_rationale_title)
                            .setMessage(R.string.permission_rationale_message)
                            .setCancelable(false)
                            .setPositiveButton(R.string.permission_rationale_continue, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    SortPrefs.markFirstRunDone(MainActivity.this);
                                    requestPermissions(new String[]{permission}, REQUEST_READ_AUDIO);
                                }
                            })
                            .show();
                } else {
                    requestPermissions(new String[]{permission}, REQUEST_READ_AUDIO);
                }
            } else {
                SortPrefs.markFirstRunDone(this);
            }
        } else {
            SortPrefs.markFirstRunDone(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && currentSection != null) {
            showSection(currentSection);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        searchMenuItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchMenuItem.getActionView();
        if (searchView != null) {
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    Fragment frag = getFragmentManager().findFragmentById(R.id.main_content_frame);
                    if (frag instanceof MusicsFragment) {
                        ((MusicsFragment) frag).filter(newText);
                    }
                    return true;
                }
            });
        }

        // Пункт сортировки полезен только на "Все треки"/"Поиск".
        boolean showSort = currentSection == DrawerSection.ALL_TRACKS || currentSection == DrawerSection.SEARCH;
        menu.findItem(R.id.action_sort).setVisible(showSort);

        tryFocusSearch();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            drawerContainer.toggle();
            return true;
        } else if (id == R.id.action_sort_title) {
            SortPrefs.setSortMode(this, MediaScanner.SORT_TITLE);
            showSection(currentSection);
            return true;
        } else if (id == R.id.action_sort_artist) {
            SortPrefs.setSortMode(this, MediaScanner.SORT_ARTIST);
            showSection(currentSection);
            return true;
        } else if (id == R.id.action_sort_album) {
            SortPrefs.setSortMode(this, MediaScanner.SORT_ALBUM);
            showSection(currentSection);
            return true;
        } else if (id == R.id.action_settings) {
            returningFromSettings = true;
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

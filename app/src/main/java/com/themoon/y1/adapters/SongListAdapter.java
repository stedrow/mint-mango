package com.themoon.y1.adapters;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.util.List;

public class SongListAdapter extends BaseAdapter {
    private List<SongItem> items;

    public SongListAdapter(List<SongItem> items) {
        this.items = items;
    }

    @Override
    public int getCount() { return items.size(); }

    @Override
    public Object getItem(int position) { return items.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final Button btn;

        if (convertView == null) {
            btn = MainActivity.instance.createListButton("");
            btn.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT));
        } else {
            btn = (Button) convertView;
        }

        final SongItem song = items.get(position);

        // 🚀 [들여쓰기 정렬] 음악 모드와 오디오북 모드에 맞춰 알맞은 접두사 아이콘 부여
        String prefixIcon = MainActivity.instance.isAudiobookLibraryMode ? "🎧 " : "🎵 ";
        btn.setText(prefixIcon + song.title);

        // 🚀 [버그 1의 핵심 해결책] 포커스가 이동할 때 프로그레스 바가 강제로 지워지는 현상을 원천 차단합니다.
        if (MainActivity.instance.isAudiobookLibraryMode) {
            int pos = MainActivity.instance.prefs.getInt("book_pos_" + song.file.getAbsolutePath(), 0);
            int dur = MainActivity.instance.prefs.getInt("book_dur_" + song.file.getAbsolutePath(), 0);

            if (pos > 0 && dur > 0) {
                // 이어듣기 기록이 있는 오디오북 파일은 프로그레스 전용 포커스 유지 엔진으로 연결!
                MainActivity.instance.setupAudiobookProgress(btn, pos, dur);
            } else {
                // 재생 기록이 없는 순수 파일은 기본 포커스 리스너 할당
                applyDefaultFocusListener(btn, song.title);
            }
        } else {
            // 오디오북 모드가 아닌 일반 음악 모드일 때도 순정 포커스 리스너 할당
            applyDefaultFocusListener(btn, song.title);
        }

        // 짧은 클릭 리스너 (STATE_PLAYER로 화면 전환 및 재생)
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.instance.clickFeedback();
                MainActivity.instance.currentIndex = position;
                MainActivity.instance.currentPlaylist.clear();
                MainActivity.instance.currentPlaylist.addAll(MainActivity.instance.virtualSongList);
                com.themoon.y1.managers.AudioPlayerManager.getInstance().prepareMusicTrack(MainActivity.instance.currentIndex);
                MainActivity.instance.changeScreen(3); // 3: STATE_PLAYER
            }
        });

        // 롱 클릭 리스너 (즐겨찾기 해제 / M3U 플레이리스트 추가 등 통합 분기)
        btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                MainActivity.instance.clickFeedback();
                MainActivity.instance.isLongPressConsumed = true;

                if (MainActivity.instance.currentBrowserMode == 5) { // BROWSER_FAVORITES
                    MainActivity.instance.showRemoveFromFavoritesDialog(song.file);
                } else if (MainActivity.instance.currentBrowserMode == 7) { // BROWSER_M3U_SONGS
                    MainActivity.instance.showRemoveFromPlaylistDialog(song.file);
                } else {
                    MainActivity.instance.showAddToPlaylistDialog(song.file);
                }
                return true;
            }
        });

        return btn;
    }

    // 🚀 [안전장치] 단색 배경 버튼들의 원활한 휠 이동 처리를 돕는 기본 리스너 팩토리
    private void applyDefaultFocusListener(final Button btn, final String title) {
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    MainActivity.instance.showFastScrollLetter(title);
                } else {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                }
            }
        });
        // 리스트뷰 아이템이 재사용될 때 남아있던 무지개 빛깔 프로그레스 배경 찌꺼기를 깨끗하게 소독합니다.
        btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
    }
}
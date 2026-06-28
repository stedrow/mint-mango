package com.themoon.y1.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;

import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.List;

public class CategoryListAdapter extends BaseAdapter {
    private List<String> items;
    private String type;

    // 🚀 스크롤 할 때 버벅거리지 않도록 이미지를 기억해두는 '메모리 캐시 금고'입니다!
    private static LruCache<String, Drawable> coverCache;

    public CategoryListAdapter(List<String> items, String type) {
        this.items = items;
        this.type = type;

        if (coverCache == null) {
            coverCache = new LruCache<>(50); // 최대 50개의 앨범 아트를 메모리에 안전하게 기억
        }
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

        final String name = items.get(position);

        // 🚀 [핵심 개조] 앨범 모드일 때만 왼쪽 아이콘(앨범 아트 썸네일)을 세팅합니다!
        if (type.equals("ALBUM")) {
            btn.setText(name); // 💿 이모티콘을 없애고 순수 앨범 이름만 넣습니다.

            // 1. 메모리 금고에 이미 불러온 그림이 있는지 확인!
            Drawable leftDrawable = coverCache.get(name);

            // 2. 금고에 그림이 없다면? 직접 찾아서 그립니다.
            if (leftDrawable == null) {
                String firstTrackPath = null;

                // 이 앨범에 속한 '첫 번째 노래'의 주소를 찾아냅니다.
                for (SongItem song : MainActivity.customLibrary) {
                    if (song.album.equals(name)) {
                        firstTrackPath = song.file.getAbsolutePath();
                        break;
                    }
                }

                // 3. 인터넷 캐시 이미지 경로를 먼저 확인합니다.
                String artPath = "";
                if (firstTrackPath != null && MainActivity.instance.prefs != null) {
                    artPath = MainActivity.instance.prefs.getString("album_art_" + firstTrackPath, "");
                }

                int size = (int) (40 * MainActivity.instance.getResources().getDisplayMetrics().density);
                Bitmap bmp = null;

                // [시도 1] 인터넷 캐시 이미지(Y1_Covers)가 있으면 불러오기
                if (!artPath.isEmpty() && new File(artPath).exists()) {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        bmp = BitmapFactory.decodeFile(artPath, opts);
                    } catch (Exception e) {}
                }

                // 🚀 [시도 2 - 신규 추가!!] 캐시가 없다면, 파일 내부에 묻혀있는 그림(Embedded)을 뜯어옵니다!
                if (bmp == null && firstTrackPath != null) {
                    String ext = firstTrackPath.toLowerCase();
                    boolean isFlac = ext.endsWith(".flac");

                    // 💡 기기를 터뜨리는 FLAC 파일은 스캔 금지! MP3, M4A 등만 안전하게 뜯어봅니다.
                    if (!isFlac) {
                        android.media.MediaMetadataRetriever mmr = null;
                        java.io.FileInputStream fis = null;
                        try {
                            mmr = new android.media.MediaMetadataRetriever();
                            fis = new java.io.FileInputStream(firstTrackPath);
                            mmr.setDataSource(fis.getFD());
                            byte[] embeddedPic = mmr.getEmbeddedPicture();

                            if (embeddedPic != null && embeddedPic.length > 0) {
                                BitmapFactory.Options opts = new BitmapFactory.Options();
                                opts.inSampleSize = 4; // 썸네일 크기이므로 화질을 낮춰서 메모리 절약
                                bmp = BitmapFactory.decodeByteArray(embeddedPic, 0, embeddedPic.length, opts);
                            }
                        } catch (Exception e) {
                        } finally {
                            try { if (fis != null) fis.close(); } catch (Exception e) {}
                            try { if (mmr != null) mmr.release(); } catch (Exception e) {}
                        }
                    }
                }

                // [최후의 보루] 그래도 사진이 없으면 기본 앨범 이미지를 씁니다.
                if (bmp == null) {
                    bmp = BitmapFactory.decodeResource(MainActivity.instance.getResources(), R.drawable.default_album);
                }

                // 4. 찾은 그림을 40dp 사이즈로 예쁘게 잘라서 왼쪽 아이콘으로 조립!
                if (bmp != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
                    leftDrawable = new BitmapDrawable(MainActivity.instance.getResources(), scaled);
                    leftDrawable.setBounds(0, 0, size, size);
                    coverCache.put(name, leftDrawable); // 다음번 로딩을 위해 캐시에 쏙 넣어둡니다.
                }
            }

            // 버튼 왼쪽에 이미지를 세팅하고, 글자와의 간격을 15dp 띄워줍니다.
            btn.setCompoundDrawables(leftDrawable, null, null, null);
            btn.setCompoundDrawablePadding((int)(15 * MainActivity.instance.getResources().getDisplayMetrics().density));

        } else {
            // 👤 가수(ARTIST) 모드일 때는 기존처럼 텍스트 이모티콘만 씁니다!
            btn.setText("👤 " + name);
            btn.setCompoundDrawables(null, null, null, null); // 그림 비우기
        }

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    MainActivity.instance.showFastScrollLetter(name);
                } else {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                }
            }
        });

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.instance.clickFeedback();
                MainActivity.instance.virtualQueryType = type;
                MainActivity.instance.virtualQueryValue = name;
                MainActivity.instance.currentBrowserMode = MainActivity.BROWSER_VIRTUAL_SONGS;
                MainActivity.instance.buildVirtualSongs();
            }
        });

        return btn;
    }
}
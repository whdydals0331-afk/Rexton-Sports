package com.rexton.carmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 차량모드 메인 화면. 가로 고정 · 전체화면 · 화면 켜짐 유지.
 * Smart View 로 차 화면(약 1024x600)에 그대로 미러링되므로 글자·버튼을 크게 만든다.
 *
 * 레이아웃은 XML 대신 코드로 만든다 (리소스 ID 가 필요 없어서 첫 빌드 실패 위험이 적다).
 * 글자 크기는 sp 가 아니라 dp 로 준다: 휴대폰 "글자 크기"를 키워 둔 경우에도 칸이 넘치지 않게.
 */
public class MainActivity extends Activity {

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0BEC5;
    private static final int BTN_BG = 0xFF1C2630;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clockView;
    private TextView dateView;
    private LinearLayout root;
    private int basePad;

    /** 칸별 화면 요소. Category.ALL 과 같은 순서. */
    private final List<TileViews> tiles = new ArrayList<>();

    private static final class TileViews {
        Category cat;
        ImageView icon;
        TextView appLabel;
    }

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            // 다음 분이 시작되는 순간에 맞춰 다시 그린다
            handler.postDelayed(this, 60_000L - (now % 60_000L) + 50L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(buildUi());

        if (!Apps.prefs(this).getBoolean("help_shown", false)) {
            Apps.prefs(this).edit().putBoolean("help_shown", true).apply();
            showHelp();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        refreshTiles(); // 그 사이에 앱을 설치/삭제했을 수 있으므로 매번 다시 확인
        handler.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi(); // 대화상자가 닫히면 상태바/내비바를 다시 숨긴다
    }

    // ───────────────────────── 화면 구성 ─────────────────────────

    private View buildUi() {
        basePad = dp(12);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(basePad, basePad, basePad, basePad);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                applyInsets(insets);
                return insets;
            }
        });

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tileRow = new LinearLayout(this);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);
        for (Category cat : Category.ALL) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            tileRow.addView(buildTile(cat), lp);
        }
        root.addView(tileRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottomRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return root;
    }

    /** 펀치홀(카메라 구멍)과, 혹시 보이는 시스템 바를 피해 여백을 준다. Android 15 는 앱을 화면 끝까지 그린다. */
    @SuppressWarnings("deprecation")
    private void applyInsets(WindowInsets insets) {
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets in = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = in.left;
            t = in.top;
            r = in.right;
            b = in.bottom;
        } else {
            l = insets.getSystemWindowInsetLeft();
            t = insets.getSystemWindowInsetTop();
            r = insets.getSystemWindowInsetRight();
            b = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = insets.getDisplayCutout();
                if (dc != null) {
                    l = Math.max(l, dc.getSafeInsetLeft());
                    t = Math.max(t, dc.getSafeInsetTop());
                    r = Math.max(r, dc.getSafeInsetRight());
                    b = Math.max(b, dc.getSafeInsetBottom());
                }
            }
        }
        root.setPadding(basePad + l, basePad + t, basePad + r, basePad + b);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);

        clockView = text("00:00", 52, TEXT, true);
        bar.addView(clockView);

        dateView = text("", 22, TEXT_DIM, false);
        dateView.setPadding(dp(16), 0, 0, 0);
        bar.addView(dateView);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView help = button("?", BTN_BG);
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(56), dp(52));
        hlp.setMargins(0, 0, dp(10), 0);
        bar.addView(help, hlp);

        TextView smart = button("📺 Smart View 연결", 0xFF0D47A1);
        smart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmartView.open(MainActivity.this);
            }
        });
        bar.addView(smart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        return bar;
    }

    private View buildTile(final Category cat) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(pressable(cat.color, dp(20)));
        tile.setClickable(true);
        tile.setFocusable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView header = text(cat.emoji + " " + cat.title, 30, TEXT, true);
        header.setGravity(Gravity.CENTER);
        content.addView(header);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(72), dp(72));
        ilp.setMargins(0, dp(12), 0, dp(8));
        content.addView(icon, ilp);

        TextView appLabel = text("", 20, TEXT_DIM, false);
        appLabel.setGravity(Gravity.CENTER);
        appLabel.setMaxLines(2);
        appLabel.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(appLabel);

        tile.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 오른쪽 위 작은 톱니: 이 칸의 앱 바꾸기 (길게 누르기와 같음)
        TextView gear = text("⚙", 26, TEXT_DIM, false);
        gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChooser(cat);
            }
        });
        tile.addView(gear, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.END));

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = Apps.resolve(MainActivity.this, cat);
                if (pkg == null) {
                    showChooser(cat);
                } else {
                    Apps.launch(MainActivity.this, pkg);
                }
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showChooser(cat);
                return true;
            }
        });

        TileViews tv = new TileViews();
        tv.cat = cat;
        tv.icon = icon;
        tv.appLabel = appLabel;
        tiles.add(tv);
        return tile;
    }

    private View buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), 0);

        TextView phone = button("📞 전화", BTN_BG);
        phone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_DIAL);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Apps.toast(MainActivity.this, "전화 앱을 열 수 없습니다");
                }
            }
        });
        row.addView(phone, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));

        TextView exit = button("🏠 나가기", BTN_BG);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        row.addView(exit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    // ───────────────────────── 동작 ─────────────────────────

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.KOREAN).format(now));
        dateView.setText(new SimpleDateFormat("M월 d일 E요일", Locale.KOREAN).format(now));
    }

    private void refreshTiles() {
        PackageManager pm = getPackageManager();
        for (TileViews tv : tiles) {
            String pkg = Apps.resolve(this, tv.cat);
            if (pkg == null) {
                tv.icon.setImageDrawable(null);
                tv.icon.setVisibility(View.GONE);
                tv.appLabel.setText("앱 없음 — 눌러서 고르기");
            } else {
                Drawable d = Apps.loadIcon(pm, pkg);
                tv.icon.setImageDrawable(d);
                tv.icon.setVisibility(d == null ? View.GONE : View.VISIBLE);
                tv.appLabel.setText(Apps.label(pm, pkg, pkg));
            }
        }
    }

    /** 이 칸에 쓸 앱 고르기: 설치된 후보 + "기타 앱 고르기". */
    private void showChooser(final Category cat) {
        final List<Apps.Entry> list = Apps.installedCandidates(this, cat);
        final String current = Apps.resolve(this, cat);
        final AppListAdapter adapter = new AppListAdapter(list, current, "➕ 기타 앱 고르기");
        new AlertDialog.Builder(this)
                .setTitle(cat.emoji + " " + cat.title + " — 앱 고르기")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which >= list.size()) {
                            showAllApps(cat);
                        } else {
                            choose(cat, list.get(which));
                        }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** 홈 화면에 아이콘이 있는 모든 앱에서 고르기. */
    private void showAllApps(final Category cat) {
        final List<Apps.Entry> list = Apps.allLaunchable(this);
        if (list.isEmpty()) {
            Apps.toast(this, "앱 목록을 읽을 수 없습니다");
            return;
        }
        final String current = Apps.resolve(this, cat);
        new AlertDialog.Builder(this)
                .setTitle(cat.emoji + " " + cat.title + " — 모든 앱")
                .setAdapter(new AppListAdapter(list, current, null), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which >= 0 && which < list.size()) choose(cat, list.get(which));
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void choose(Category cat, Apps.Entry e) {
        Apps.save(this, cat, e.pkg);
        refreshTiles();
        Apps.toast(this, cat.title + " → " + e.label);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("차에서 쓰는 법")
                .setMessage("1) 차에서 N90 의 미러링 앱을 켭니다.\n\n"
                        + "2) 이 앱의 \"Smart View 연결\"을 누르고 " + SmartView.CAR_NAME + " 를 고릅니다.\n"
                        + "    (안 되면: 빠른 설정을 화면 위에서 두 번 내려 Smart View → " + SmartView.CAR_NAME + ")\n\n"
                        + "3) 휴대폰을 가로로 두면 차 화면에 크게 나옵니다.\n\n"
                        + "4) 조작은 휴대폰에서 합니다. (차 화면 터치는 안 됩니다)\n\n"
                        + "칸을 길게 누르거나 ⚙ 를 누르면 그 칸에서 열 앱을 바꿀 수 있습니다.")
                .setPositiveButton("확인", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    return;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Exception ignored) {
            // 전체화면 실패는 치명적이지 않다
        }
    }

    // ───────────────────────── 작은 도우미 ─────────────────────────

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, int sizeDp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setSingleLine(false);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, int bg) {
        TextView b = text(s, 22, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setBackground(pressable(bg, dp(14)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** 둥근 사각형 + 누를 때 물결 효과. */
    private static Drawable pressable(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, mask);
    }

    /** 대화상자용 앱 목록 (아이콘 + 이름, 현재 선택은 ✓). extraLabel 이 있으면 맨 아래 한 줄 추가. */
    private final class AppListAdapter extends BaseAdapter {
        private final List<Apps.Entry> items;
        private final String current;
        private final String extraLabel;

        AppListAdapter(List<Apps.Entry> items, String current, String extraLabel) {
            this.items = items;
            this.current = current;
            this.extraLabel = extraLabel;
        }

        @Override
        public int getCount() {
            return items.size() + (extraLabel == null ? 0 : 1);
        }

        @Override
        public Object getItem(int position) {
            return position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                row.setMinimumHeight(dp(64));
                ImageView iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                TextView tv = text("", 22, TEXT, false);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(16), 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            ImageView iv = (ImageView) row.getChildAt(0);
            TextView tv = (TextView) row.getChildAt(1);
            if (position < items.size()) {
                Apps.Entry e = items.get(position);
                iv.setVisibility(View.VISIBLE);
                iv.setImageDrawable(e.icon(getPackageManager()));
                boolean selected = e.pkg.equals(current);
                tv.setText(selected ? "✓ " + e.label : e.label);
                tv.setTextColor(selected ? 0xFF4FC3F7 : TEXT);
            } else {
                iv.setImageDrawable(null);
                iv.setVisibility(View.INVISIBLE);
                tv.setText(extraLabel);
                tv.setTextColor(0xFF4FC3F7);
            }
            return row;
        }
    }
}

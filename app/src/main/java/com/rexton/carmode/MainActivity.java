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
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
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

    // ───── 출발 모드 상태 ─────
    private static final int DEP_IDLE = 0;      // 아무것도 안 함 (상태 줄 숨김)
    private static final int DEP_WAITING = 1;   // Smart View 창을 열었고, 사용자가 돌아오기를 기다리는 중
    private static final int DEP_RUNNING = 2;   // 음악/네비 단계를 예약해 둔 상태
    private static final int DEP_DONE = 3;      // 끝 (상태 줄에 결과만 표시)

    /**
     * 앱을 오래 뒤에 두었다가 아이콘으로 다시 열면 "새로 연 것"으로 본다.
     * (안드로이드가 이 화면을 메모리에 남겨 두거나 저장 상태로 되살리면 onCreate 가 "처음"이 아니기 때문)
     * 이보다 짧으면 운전 중에 잠깐 돌아온 것으로 보고 아무것도 자동으로 하지 않는다.
     */
    private static final long REARM_AFTER_MS = 60L * 60L * 1000L;
    /** Smart View 에 간 뒤 이보다 늦게 돌아오면 음악·네비를 자동으로 잇지 않는다 (버튼만 남김). */
    private static final long WAIT_MAX_MS = 15L * 60L * 1000L;
    /** 돌아온 직후 화면이 자리 잡을 시간을 조금 준다. */
    private static final long RESUME_SETTLE_MS = 700L;

    private int depState = DEP_IDLE;
    /** 이번에 앱을 연 동안 자동 출발을 이미 처리했는가 (켜짐/꺼짐과 상관없이 "판단을 끝냈다"). */
    private boolean depHandled;
    /** Smart View 를 기다리는 중에 이 화면을 실제로 떠났는가 (돌아오면 다음 단계로). */
    private boolean depLeftForSmartView;
    /** Smart View 를 기다리다 화면을 떠난 시각 (System.currentTimeMillis). */
    private long depLeftAt;
    /** 화면이 뒤로 간 시각 (System.currentTimeMillis). 0 = 아직 없음. */
    private long stoppedAt;
    /** 처음 설치 후 사용법 창을 띄웠으면, 그 창을 닫을 때 자동 출발을 시작한다. */
    private boolean depDeferredToHelp;

    private LinearLayout statusRow;
    private TextView statusText;
    private TextView statusGo;
    private TextView statusCancel;
    private TextView autoToggle;

    private final Runnable stepMusic = new Runnable() {
        @Override
        public void run() {
            doMusicStep();
        }
    };
    private final Runnable stepCheck = new Runnable() {
        @Override
        public void run() {
            doCheckStep();
        }
    };
    private final Runnable stepPlay = new Runnable() {
        @Override
        public void run() {
            doPlayStep();
        }
    };
    private final Runnable stepNav = new Runnable() {
        @Override
        public void run() {
            doNavStep();
        }
    };

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
        restoreDepartureState(savedInstanceState);

        if (!Apps.prefs(this).getBoolean("help_shown", false)) {
            Apps.prefs(this).edit().putBoolean("help_shown", true).apply();
            // 처음 한 번은 사용법부터 읽게 하고, 창을 닫으면 그때 자동 출발을 판단한다
            if (!depHandled) depDeferredToHelp = true;
            showHelp(new Runnable() {
                @Override
                public void run() {
                    if (depDeferredToHelp) {
                        depDeferredToHelp = false;
                        maybeAutoStart();
                    }
                }
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 이 화면이 메모리에 남아 있는 상태에서 홈 화면 아이콘을 다시 누른 경우.
        // 오래(REARM_AFTER_MS 이상) 뒤에 있었으면 "새로 연 것"으로 보고 다시 자동 출발을 허용한다.
        // 최근 앱 목록으로 돌아오는 것은 onNewIntent 가 오지 않으므로 여기 해당하지 않는다.
        // 1시간 넘게 뒤에 있었다면 Smart View 를 기다리다 그대로 둔 상태(WAITING)도 낡은 것이므로 함께 지운다.
        if (isLauncherIntent(intent)
                && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt >= REARM_AFTER_MS) {
            cancelPending();
            depLeftForSmartView = false;
            depLeftAt = 0L;
            depHandled = false;
            setDepState(DEP_IDLE, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        refreshTiles(); // 그 사이에 앱을 설치/삭제했을 수 있으므로 매번 다시 확인
        refreshAutoToggle();
        handler.removeCallbacks(clockTick);
        clockTick.run();

        if (!depHandled) {
            if (!depDeferredToHelp) maybeAutoStart();
        } else if (depState == DEP_WAITING && depLeftForSmartView) {
            // Smart View 창(또는 빠른 설정 등)에 갔다가 돌아왔다 → 음악·네비로
            depLeftForSmartView = false;
            if (System.currentTimeMillis() - depLeftAt < WAIT_MAX_MS) {
                runMediaSteps(RESUME_SETTLE_MS);
            }
            // 너무 늦게 돌아왔으면 상태 줄의 "음악·네비 시작 ▶" 버튼만 남겨 둔다
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        // depLeftAt == 0: 한 번 떠났다 돌아온 뒤(늦게 돌아와 버튼만 남긴 경우 포함)에는 다시 자동으로 잇지 않는다
        if (depState == DEP_WAITING && !depLeftForSmartView && depLeftAt == 0L) {
            depLeftForSmartView = true;
            depLeftAt = System.currentTimeMillis();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = System.currentTimeMillis();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); // 시계 + 예약된 출발 단계 모두
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("dep_handled", depHandled || depDeferredToHelp);
        out.putInt("dep_state", depState);
        out.putBoolean("dep_left", depLeftForSmartView);
        out.putLong("dep_left_at", depLeftAt);
        out.putLong("dep_saved_at", System.currentTimeMillis());
    }

    /** 화면이 다시 만들어졌을 때(회전·프로세스 복원) 같은 "앱 열기"에서 출발이 두 번 돌지 않게 한다. */
    private void restoreDepartureState(Bundle in) {
        if (in == null) return; // 새로 연 것
        long savedAt = in.getLong("dep_saved_at", 0L);
        if (savedAt > 0 && System.currentTimeMillis() - savedAt >= REARM_AFTER_MS) {
            // 아주 오래전에 저장된 상태(예: 전날 뒤에 둔 앱을 아이콘으로 다시 연 경우) → 새로 연 것으로 본다
            return;
        }
        depHandled = in.getBoolean("dep_handled", false);
        int st = in.getInt("dep_state", DEP_IDLE);
        depLeftForSmartView = in.getBoolean("dep_left", false);
        depLeftAt = in.getLong("dep_left_at", 0L);
        if (st == DEP_WAITING) {
            showWaiting();
        } else if (st == DEP_RUNNING) {
            // 예약해 둔 단계는 화면과 함께 사라졌다. 다시 실행하지 않는다.
            setDepState(DEP_IDLE, null);
        }
    }

    private static boolean isLauncherIntent(Intent i) {
        return i != null && Intent.ACTION_MAIN.equals(i.getAction())
                && i.getCategories() != null && i.getCategories().contains(Intent.CATEGORY_LAUNCHER);
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

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        slp.setMargins(0, dp(6), 0, 0);
        root.addView(buildStatusRow(), slp);

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

        // 날짜는 남는 폭만 쓴다 (출발 모드 버튼이 들어가서 폭이 모자라면 날짜가 줄어든다)
        dateView = text("", 22, TEXT_DIM, false);
        dateView.setPadding(dp(16), 0, dp(8), 0);
        dateView.setSingleLine(true);
        dateView.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(dateView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoToggle = button("", BTN_BG);
        autoToggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        autoToggle.setPadding(dp(14), 0, dp(14), 0);
        autoToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !Departure.autoOn(MainActivity.this);
                Departure.setAutoOn(MainActivity.this, on);
                refreshAutoToggle();
                Apps.toast(MainActivity.this, on
                        ? "출발 모드 켜짐 — 다음에 앱을 열 때부터 자동으로 진행합니다"
                        : "출발 모드 꺼짐 — 필요하면 아래 \"🚗 출발\" 을 누르세요");
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        alp.setMargins(0, 0, dp(6), 0);
        bar.addView(autoToggle, alp);

        TextView depGear = button("⚙", BTN_BG);
        depGear.setPadding(0, 0, 0, 0);
        depGear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDepartureSettings();
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(52), dp(52));
        glp.setMargins(0, 0, dp(10), 0);
        bar.addView(depGear, glp);

        TextView help = button("?", BTN_BG);
        help.setPadding(0, 0, 0, 0);
        help.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp(null);
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(52), dp(52));
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
                cancelDeparture(); // 직접 칸을 누르면 사용자가 알아서 하는 것으로 보고 남은 단계를 멈춘다
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
                cancelDeparture();
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

        row.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));

        // 출발 모드가 꺼져 있어도 지금 바로 출발 순서를 돌린다
        TextView go = button("🚗 출발", 0xFF1B5E20);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDeparture(true);
            }
        });
        row.addView(go, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        row.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));

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

    /** 출발 순서 진행 상황 한 줄 + "음악·네비 시작 ▶" + "취소". 평소에는 숨김. */
    private View buildStatusRow() {
        statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(16), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF15202B);
        bg.setCornerRadius(dp(14));
        statusRow.setBackground(bg);

        statusText = text("", 22, TEXT, true);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusGo = button("음악·네비 시작 ▶", 0xFF1B5E20);
        statusGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (depState == DEP_WAITING) runMediaSteps(0L);
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        glp.setMargins(dp(8), 0, 0, 0);
        statusRow.addView(statusGo, glp);

        statusCancel = button("취소", BTN_BG);
        statusCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean wasActive = depState == DEP_WAITING || depState == DEP_RUNNING;
                cancelDeparture();
                if (wasActive) Apps.toast(MainActivity.this, "출발 순서를 멈췄습니다");
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        clp.setMargins(dp(8), 0, 0, 0);
        statusRow.addView(statusCancel, clp);

        statusRow.setVisibility(View.GONE);
        return statusRow;
    }

    // ───────────────────────── 출발 모드 ─────────────────────────

    /** 앱을 연 직후 한 번만: 출발 모드가 켜져 있으면 순서를 시작한다. */
    private void maybeAutoStart() {
        if (depHandled) return;
        depHandled = true;
        if (Departure.autoOn(this)) startDeparture(false);
    }

    /**
     * 출발 순서 시작. 사용자가 앱을 열었을 때(maybeAutoStart) 또는 "🚗 출발"을 눌렀을 때만 불린다.
     * @param manual "🚗 출발" 버튼으로 시작했으면 true (켜진 단계가 없을 때 안내용)
     */
    private void startDeparture(boolean manual) {
        cancelPending();
        depHandled = true;
        depLeftForSmartView = false;
        depLeftAt = 0L;
        boolean sv = Departure.smartViewOn(this);
        boolean media = Departure.musicOn(this) || Departure.navOn(this);
        if (!sv && !media) {
            setDepState(DEP_IDLE, null);
            if (manual) Apps.toast(this, "켜진 단계가 없습니다. 위쪽 ⚙ 에서 단계를 켜세요");
            return;
        }
        if (sv) {
            if (media) {
                showWaiting();
            } else {
                setDepState(DEP_DONE, "목록에서 " + SmartView.CAR_NAME + " 를 고르세요");
            }
            SmartView.open(this); // 기기 고르는 창 → 돌아오면 onResume 에서 다음 단계
        } else {
            runMediaSteps(0L);
        }
    }

    private void showWaiting() {
        setDepState(DEP_WAITING, "Smart View 연결 후 이 화면으로 돌아오세요");
    }

    /** 음악 → (재생 키) → 네비. 켜진 단계만. */
    private void runMediaSteps(long firstDelay) {
        cancelPending();
        depLeftForSmartView = false;
        if (Departure.musicOn(this)) {
            setDepState(DEP_RUNNING, "음악 재생 준비 중…");
            handler.postDelayed(stepMusic, firstDelay);
        } else if (Departure.navOn(this)) {
            setDepState(DEP_RUNNING, "네비 실행 준비 중…");
            handler.postDelayed(stepNav, firstDelay);
        } else {
            setDepState(DEP_DONE, "출발 준비 끝");
        }
    }

    /*
     * 음악 단계는 두 갈래다.
     * 1) 먼저 이 앱을 앞에 둔 채로 재생 키만 보낸다. 마지막으로 듣던 음악 앱이 뒤에서 이어서 재생되면
     *    이 앱이 계속 앞에 있으므로 네비도 막힘 없이 열 수 있다.
     * 2) 재생이 안 됐으면 그때 음악 앱을 열고 재생 키를 보낸다. 이 경우 네비는 이 앱이 뒤에 있을 때
     *    여는 것이라 시스템이 막을 수 있다 (안 뜨면 🧭 칸 안내).
     */
    private void doMusicStep() {
        String pkg = Apps.resolve(this, Category.MUSIC);
        if (pkg == null) {
            Apps.toast(this, "음악 앱이 없습니다 — 🎵 음악 칸에서 앱을 고르세요");
            afterMusic(0L);
            return;
        }
        setDepState(DEP_RUNNING, "음악 이어 재생 시도 중…");
        Departure.sendPlayKey(this);
        handler.postDelayed(stepCheck, 1500L);
    }

    private void doCheckStep() {
        if (isMusicPlaying()) {
            setDepState(DEP_RUNNING, "음악 재생 중…");
            afterMusic(Departure.navSec(this) * 1000L);
            return;
        }
        String pkg = Apps.resolve(this, Category.MUSIC);
        setDepState(DEP_RUNNING, "음악 앱 여는 중…");
        if (pkg != null && Apps.launch(this, pkg)) {
            // 음악 앱이 뜨고 재생 준비를 할 시간을 준 뒤 재생 키
            handler.postDelayed(stepPlay, Departure.playSec(this) * 1000L);
        } else {
            afterMusic(0L);
        }
    }

    private boolean isMusicPlaying() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }

    private void doPlayStep() {
        if (!Departure.sendPlayKey(this)) Apps.toast(this, "재생 키를 보내지 못했습니다");
        afterMusic(Departure.navSec(this) * 1000L);
    }

    private void afterMusic(long navDelay) {
        if (Departure.navOn(this)) {
            setDepState(DEP_RUNNING, "네비 실행 준비 중…");
            handler.postDelayed(stepNav, navDelay);
        } else {
            setDepState(DEP_DONE, "출발 준비 끝");
        }
    }

    private void doNavStep() {
        String pkg = Apps.resolve(this, Category.NAV);
        if (pkg == null) {
            Apps.toast(this, "네비 앱이 없습니다 — 🧭 네비 칸에서 앱을 고르세요");
            setDepState(DEP_DONE, "네비 앱 없음");
            return;
        }
        Apps.launch(this, pkg);
        // 이 앱이 뒤에 있을 때 여는 것이라 기종·버전에 따라 시스템이 막을 수 있다 (예외 없이 조용히).
        setDepState(DEP_DONE, "출발 준비 끝 — 네비가 안 떴으면 🧭 네비 칸을 누르세요");
    }

    /** 예약된 단계만 지운다 (시계는 그대로). */
    private void cancelPending() {
        handler.removeCallbacks(stepMusic);
        handler.removeCallbacks(stepCheck);
        handler.removeCallbacks(stepPlay);
        handler.removeCallbacks(stepNav);
    }

    /** 남은 단계를 멈추고 상태 줄을 숨긴다. 진행 중이 아니어도 안전. */
    private void cancelDeparture() {
        cancelPending();
        depLeftForSmartView = false;
        depLeftAt = 0L;
        depDeferredToHelp = false;
        depHandled = true;
        setDepState(DEP_IDLE, null);
    }

    private void setDepState(int st, String msg) {
        depState = st;
        if (statusRow == null) return;
        if (st == DEP_IDLE) {
            statusRow.setVisibility(View.GONE);
            return;
        }
        statusRow.setVisibility(View.VISIBLE);
        statusText.setText(msg != null ? "🚗 " + msg : "");
        boolean mediaLeft = Departure.musicOn(this) || Departure.navOn(this);
        statusGo.setVisibility(st == DEP_WAITING && mediaLeft ? View.VISIBLE : View.GONE);
        statusCancel.setText(st == DEP_DONE ? "닫기" : "취소");
    }

    private void refreshAutoToggle() {
        if (autoToggle == null) return;
        boolean on = Departure.autoOn(this);
        autoToggle.setText(on ? "🚗 출발 모드: 켜짐" : "🚗 출발 모드: 꺼짐");
        autoToggle.setBackground(pressable(on ? 0xFF1B5E20 : BTN_BG, dp(14)));
        autoToggle.setTextColor(on ? TEXT : TEXT_DIM);
    }

    /** 출발 순서 설정: 단계 3개 켜고 끄기 + 지연 시간. */
    private void showDepartureSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(4));

        final CheckBox cbSv = checkBox("📺 Smart View 기기 고르는 창 열기", Departure.smartViewOn(this));
        final CheckBox cbMusic = checkBox("🎵 음악 앱 열고 자동 재생", Departure.musicOn(this));
        final CheckBox cbNav = checkBox("🧭 네비 앱 자동 실행 (마지막에 앞으로)", Departure.navOn(this));
        box.addView(cbSv);
        box.addView(cbMusic);
        box.addView(cbNav);

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER);
        pickers.setPadding(0, dp(12), 0, 0);
        final NumberPicker npPlay = picker(Departure.PLAY_SEC_MIN, Departure.PLAY_SEC_MAX, Departure.playSec(this));
        final NumberPicker npNav = picker(Departure.NAV_SEC_MIN, Departure.NAV_SEC_MAX, Departure.navSec(this));
        pickers.addView(labeled("음악 실행 → 재생 키 (초)", npPlay),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pickers.addView(labeled("재생 키 → 네비 (초)", npNav),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(pickers);

        TextView note = text("출발 순서는 앱을 직접 열었을 때(출발 모드 켜짐)나 \"🚗 출발\" 을 눌렀을 때만 시작합니다.\n"
                + "지연 시간은 합쳐서 10초 안쪽으로 묶여 있습니다 (그보다 늦으면 시스템이 네비 실행을 막을 수 있음).",
                16, TEXT_DIM, false);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("🚗 출발 순서 설정")
                .setView(scroll)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Departure.save(MainActivity.this, cbSv.isChecked(), cbMusic.isChecked(), cbNav.isChecked(),
                                npPlay.getValue(), npNav.getValue());
                        Apps.toast(MainActivity.this, "출발 순서 설정을 저장했습니다");
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        cb.setChecked(checked);
        cb.setMinHeight(dp(52));
        return cb;
    }

    private NumberPicker picker(int min, int max, int value) {
        NumberPicker np = new NumberPicker(this);
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(value);
        np.setWrapSelectorWheel(false);
        np.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); // 키보드가 뜨지 않게
        return np;
    }

    private View labeled(String label, View v) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(label, 16, TEXT_DIM, false);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        col.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(120)));
        return col;
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

    /** @param onClosed 창이 닫힌 뒤 할 일 (없으면 null) */
    private void showHelp(final Runnable onClosed) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("차에서 쓰는 법")
                .setMessage("1) 차에서 N90 의 미러링 앱을 켭니다.\n\n"
                        + "2) 이 앱을 엽니다. 출발 모드가 켜져 있으면 Smart View 기기 고르는 창이 자동으로 뜹니다.\n"
                        + "    " + SmartView.CAR_NAME + " 를 고르고 이 화면으로 돌아오세요.\n"
                        + "    (창이 안 뜨면: 빠른 설정을 화면 위에서 두 번 내려 Smart View → " + SmartView.CAR_NAME + ")\n\n"
                        + "3) 돌아오면 음악 앱이 열려 재생되고, 몇 초 뒤 네비가 앞으로 나옵니다.\n"
                        + "    돌아오지 않았다면 위의 \"음악·네비 시작 ▶\" 을 누르세요. 멈추려면 \"취소\".\n\n"
                        + "4) 휴대폰을 가로로 두면 차 화면에 크게 나옵니다. 조작은 휴대폰에서 합니다.\n\n"
                        + "출발 순서는 이 앱을 직접 열었을 때나 \"🚗 출발\" 을 눌렀을 때만 시작합니다. "
                        + "블루투스 연결 등으로 저절로 시작하지 않습니다. 운전 중 잠깐 돌아올 때는 다시 실행하지 않습니다.\n\n"
                        + "위쪽 \"출발 모드\" 버튼으로 켜고 끄고, 옆의 ⚙ 로 단계와 시간을 바꿉니다.\n"
                        + "칸을 길게 누르거나 칸의 ⚙ 를 누르면 그 칸에서 열 앱을 바꿀 수 있습니다.")
                .setPositiveButton("확인", null)
                .create();
        if (onClosed != null) {
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    onClosed.run();
                }
            });
        }
        dlg.show();
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

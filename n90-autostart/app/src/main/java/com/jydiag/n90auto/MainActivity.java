package com.jydiag.n90auto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * 설정 + 상태 + 이벤트 기록 화면. 화면은 코드로 만든다 (XML 레이아웃 없음).
 * 가로 화면(헤드유닛 1024x600)에서는 왼쪽: 설정, 오른쪽: 기록 2단으로 배치한다.
 */
public class MainActivity extends Activity {

    private static final int MAX_SHOWN_LOG = 200;
    private static final int C_ON = 0xFF2E7D32;
    private static final int C_OFF = 0xFF616161;
    private static final int C_NOTE = 0xFF263238;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView uptimeView;
    private TextView statusView;
    private TextView targetView;
    private Button toggleBtn;
    private Button delayBtn;
    private Button homeBtn;
    private Button homeDelayBtn;
    private Button retryBtn;
    private Button retryDelayBtn;
    private TextView logView;
    private TextView logHeader;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateUptime();
            handler.postDelayed(this, 5000);
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    // 수신기가 기록/설정을 바꾸면 화면을 갱신한다 (강한 참조로 들고 있어야 함)
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    handler.removeCallbacks(refreshRunnable);
                    handler.postDelayed(refreshRunnable, 150);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            // 이 줄이 기록에 있으면 "한 번도 안 연 상태(stopped)"가 풀린 것이다
            Store.log(this, "앱 화면 열림 (이제부터 시스템 신호를 받을 수 있음)");
        }
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener);
        refresh();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(refreshRunnable);
    }

    // ------------------------------------------------------------------ UI 만들기

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        b.setMinHeight(dp(64));
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        la.rightMargin = dp(4);
        r.addView(a, la);
        r.addView(b, lb);
        return r;
    }

    private View buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density;
        boolean twoColumns = dm.widthPixels > dm.heightPixels && wDp >= 640;

        // ---- 왼쪽(설정) 열
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(12), dp(8), dp(12), dp(12));

        left.addView(text("N90 자동실행", 24, true));
        uptimeView = text("", 20, true);
        left.addView(uptimeView);
        TextView uptimeNote = text("시동을 껐다 켠 직후 이 값이 크면(수십 분 이상) 헤드유닛이 재부팅이 아니라 "
                + "잠들었다 깨어난 것입니다.", 14, false);
        left.addView(uptimeNote);

        TextView notice = text("※ 처음 설치한 뒤에는 이 화면을 한 번 열어야 합니다. 안드로이드 3.1 이상은 한 번도 "
                + "열지 않은 앱에 부팅 신호를 보내지 않습니다. 설정 > 앱에서 이 앱을 '강제 중지'하면 "
                + "다시 그 상태가 되어 자동 실행이 안 되니, 그때는 이 화면을 다시 한 번 열어 주세요.", 15, false);
        notice.setBackgroundColor(0xFFFFF3C4);
        notice.setTextColor(C_NOTE);
        notice.setPadding(dp(10), dp(8), dp(10), dp(8));
        left.addView(notice);

        statusView = text("", 22, true);
        statusView.setPadding(dp(10), dp(10), dp(10), dp(10));
        statusView.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        left.addView(statusView, slp);

        toggleBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleEnabled();
            }
        });
        left.addView(toggleBtn);

        left.addView(text("실행할 앱", 18, true));
        targetView = text("", 17, false);
        targetView.setBackgroundColor(0x22FFFFFF);
        targetView.setPadding(dp(10), dp(8), dp(10), dp(8));
        left.addView(targetView);
        left.addView(row(
                button("앱 고르기", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, PickerActivity.class));
                    }
                }),
                button("지금 실행 테스트", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testLaunchNow();
                    }
                })));

        left.addView(text("설정", 18, true));
        delayBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSeconds("신호 후 실행까지 기다릴 시간", Store.K_DELAY, Store.DELAY_CHOICES, Store.delaySec(MainActivity.this));
            }
        });
        left.addView(delayBtn);

        homeBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                flip(Store.K_HOME, Store.homeEnabled(MainActivity.this));
            }
        });
        homeDelayBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSeconds("실행 후 홈으로 갈 때까지", Store.K_HOME_DELAY, Store.HOME_DELAY_CHOICES,
                        Store.homeDelaySec(MainActivity.this));
            }
        });
        left.addView(row(homeBtn, homeDelayBtn));

        retryBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                flip(Store.K_RETRY, Store.retryEnabled(MainActivity.this));
            }
        });
        retryDelayBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSeconds("첫 실행 후 한 번 더 실행할 때까지", Store.K_RETRY_DELAY, Store.RETRY_DELAY_CHOICES,
                        Store.retryDelaySec(MainActivity.this));
            }
        });
        left.addView(row(retryBtn, retryDelayBtn));
        left.addView(text("순서: 신호 → (지연) → 실행 → (한 번 더 실행) → (홈으로). "
                + "'홈으로'와 '한 번 더'는 그 순간 다른 화면을 보고 있어도 동작하니 필요할 때만 켜세요.", 14, false));

        left.addView(row(
                button("순서 테스트", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testSequence();
                    }
                }),
                button("예약 취소", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Launcher.cancelAll(MainActivity.this);
                        Store.log(MainActivity.this, "사용자가 대기 중 예약을 취소함");
                        toast("대기 중 예약을 취소했습니다");
                    }
                })));
        left.addView(text("'순서 테스트'는 자동 실행이 꺼져 있어도 위 설정대로 한 번 예약합니다 "
                + "(시동 신호 없이 실행·홈 이동 동작만 확인).", 14, false));

        // ---- 기록 영역
        LinearLayout logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setPadding(dp(12), dp(8), dp(12), dp(12));
        logHeader = text("이벤트 기록", 18, true);
        logBox.addView(logHeader);
        logBox.addView(row(
                button("기록 지우기", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearLog();
                    }
                }),
                button("공유", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        shareLog();
                    }
                })));
        logView = text("", 14, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);

        if (twoColumns) {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            ScrollView ls = new ScrollView(this);
            ls.addView(left);
            root.addView(ls, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            ScrollView rs = new ScrollView(this);
            rs.addView(logView);
            logBox.addView(rs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(logBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
            return root;
        }
        LinearLayout all = new LinearLayout(this);
        all.setOrientation(LinearLayout.VERTICAL);
        all.addView(left);
        logBox.addView(logView);
        all.addView(logBox);
        ScrollView sv = new ScrollView(this);
        sv.addView(all);
        return sv;
    }

    // ------------------------------------------------------------------ 상태 표시

    private void updateUptime() {
        if (uptimeView == null) {
            return;
        }
        long ms = SystemClock.elapsedRealtime();
        long min = ms / 60000L;
        String s;
        if (min >= 60) {
            s = String.format(Locale.US, "부팅 후 경과: %d분 (%d시간 %d분)", min, min / 60, min % 60);
        } else {
            s = String.format(Locale.US, "부팅 후 경과: %d분 %d초", min, (ms / 1000L) % 60);
        }
        uptimeView.setText(s);
    }

    private void refresh() {
        updateUptime();
        boolean on = Store.enabled(this);
        boolean hasTarget = Store.targetPkg(this) != null;
        statusView.setText(on ? "자동 실행: 켜짐" : (hasTarget ? "자동 실행: 꺼짐" : "자동 실행: 꺼짐 (먼저 앱을 고르세요)"));
        statusView.setBackgroundColor(on ? C_ON : C_OFF);
        toggleBtn.setText(on ? "자동 실행 끄기" : "자동 실행 켜기");
        targetView.setText(Store.targetDescription(this));

        delayBtn.setText("신호 후 실행까지: " + Store.delaySec(this) + "초");
        boolean home = Store.homeEnabled(this);
        homeBtn.setText("실행 후 홈으로 돌아가기: " + (home ? "켜짐" : "꺼짐"));
        homeDelayBtn.setText("홈으로: 실행 " + Store.homeDelaySec(this) + "초 후");
        homeDelayBtn.setEnabled(home);
        boolean retry = Store.retryEnabled(this);
        retryBtn.setText("한 번 더 실행: " + (retry ? "켜짐" : "꺼짐"));
        retryDelayBtn.setText("한 번 더: " + Store.retryDelaySec(this) + "초 후");
        retryDelayBtn.setEnabled(retry);

        List<String> lines = Store.logLines(this);
        logHeader.setText("이벤트 기록 (" + lines.size() + "개, 최신이 위)");
        if (lines.isEmpty()) {
            logView.setText("(기록 없음)\n\n시동을 껐다 켠 뒤 여기에 어떤 신호가 찍히는지 보세요. "
                    + "아무것도 안 찍히면 N90 이 이 앱이 듣는 신호를 보내지 않는 것입니다.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = lines.size() - 1; i >= 0 && shown < MAX_SHOWN_LOG; i--, shown++) {
            sb.append(lines.get(i)).append("\n\n");
        }
        logView.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 동작

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void toggleEnabled() {
        boolean on = Store.enabled(this);
        if (on) {
            Store.prefs(this).edit().putBoolean(Store.K_ENABLED, false).commit();
            // 끄는 즉시 대기 중 예약도 모두 취소
            Launcher.cancelAll(this);
            Store.log(this, "자동 실행 끔 (대기 중 예약 취소)");
            toast("자동 실행을 껐습니다");
        } else {
            if (Store.targetPkg(this) == null) {
                toast("먼저 '앱 고르기'로 실행할 앱을 선택하세요");
                return;
            }
            Store.prefs(this).edit().putBoolean(Store.K_ENABLED, true).commit();
            Store.log(this, "자동 실행 켬 (대상: " + Store.targetPkg(this) + ")");
            toast("자동 실행을 켰습니다");
        }
        refresh();
    }

    private void flip(String key, boolean current) {
        Store.prefs(this).edit().putBoolean(key, !current).commit();
        refresh();
    }

    private void chooseSeconds(String title, final String key, final int[] choices, int current) {
        String[] labels = new String[choices.length];
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] + "초";
            if (choices[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Store.prefs(MainActivity.this).edit().putInt(key, choices[which]).commit();
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void testLaunchNow() {
        if (Store.targetPkg(this) == null) {
            toast("먼저 '앱 고르기'로 실행할 앱을 선택하세요");
            return;
        }
        boolean ok = Launcher.launchTarget(this, "지금 실행 테스트");
        if (!ok) {
            toast("실행 실패 — 기록을 확인하세요");
            refresh();
        }
    }

    private void testSequence() {
        if (Store.targetPkg(this) == null) {
            toast("먼저 '앱 고르기'로 실행할 앱을 선택하세요");
            return;
        }
        String plan = Launcher.scheduleSequence(this, "수동", true);
        Store.log(this, "순서 테스트 예약: " + plan);
        toast("예약했습니다: " + plan);
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("기록 지우기")
                .setMessage("이벤트 기록을 모두 지울까요? (설정은 그대로입니다)")
                .setPositiveButton("지우기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Store.clearLog(MainActivity.this);
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void shareLog() {
        Context c = this;
        StringBuilder sb = new StringBuilder();
        sb.append("[N90 자동실행 기록] ").append(Store.now()).append('\n');
        sb.append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("부팅 후 경과: ").append(SystemClock.elapsedRealtime() / 60000L).append("분\n");
        sb.append("자동 실행: ").append(Store.enabled(c) ? "켜짐" : "꺼짐").append('\n');
        sb.append("대상: ").append(Store.targetDescription(c).replace('\n', ' ')).append('\n');
        sb.append("지연 ").append(Store.delaySec(c)).append("초, 홈으로 ")
                .append(Store.homeEnabled(c) ? Store.homeDelaySec(c) + "초" : "꺼짐")
                .append(", 한 번 더 ").append(Store.retryEnabled(c) ? Store.retryDelaySec(c) + "초" : "꺼짐").append('\n');
        sb.append("----- 기록 (오래된 것 → 최근) -----\n");
        for (String l : Store.logLines(c)) {
            sb.append(l).append('\n');
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "N90 자동실행 기록");
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        try {
            startActivity(Intent.createChooser(send, "기록 공유"));
        } catch (Throwable t) {
            toast("공유할 앱이 없습니다: " + t.getClass().getSimpleName());
        }
    }
}

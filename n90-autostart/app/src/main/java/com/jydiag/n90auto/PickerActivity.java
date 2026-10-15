package com.jydiag.n90auto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 실행할 앱 고르기.
 * 1단계: 실행 가능한 앱 목록 (미러링으로 보이는 앱을 위에 ★ 표시). "모든 앱 보기"로 아이콘 없는 앱까지.
 * 2단계(고급): 앱을 누르고 "화면 직접 고르기" → 그 앱의 exported 액티비티 목록에서 하나 선택.
 * 이 앱 자신은 목록에서 뺀다 (자기 자신을 실행하지 않음).
 */
public class PickerActivity extends Activity {

    static final String[] KEYWORDS = {
            "mirror", "miracast", "smartview", "wfd", "wifidisplay", "easyconn", "carlife", "carbit",
            "phonelink", "autolink", "projection", "sink", "미러", "스마트폰", "smartphone", "cast", "link", "jy"
    };

    static boolean looksLikeMirroring(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p.toLowerCase(Locale.US)).append(' ');
            }
        }
        String s = sb.toString();
        for (String k : KEYWORDS) {
            if (s.indexOf(k) >= 0) {
                return true;
            }
        }
        return false;
    }

    static final class Entry {
        String pkg;
        String cls;          // 액티비티 모드에서만
        String label;
        String sub;
        boolean candidate;
        boolean hasLauncher;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Entry> items = new ArrayList<Entry>();
    private Adapter adapter;
    private TextView header;
    private Button modeBtn;

    private boolean showAll;
    private String activityModePkg;  // null 이면 앱 목록 모드
    private String activityModeLabel;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        header = new TextView(this);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeBtn = new Button(this);
        modeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        modeBtn.setMinHeight(dp(60));
        modeBtn.setAllCaps(false);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activityModePkg != null) {
                    activityModePkg = null;
                } else {
                    showAll = !showAll;
                }
                load();
            }
        });
        top.addView(modeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    onPick(items.get(position));
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    @Override
    public void onBackPressed() {
        if (activityModePkg != null) {
            activityModePkg = null;
            load();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ 목록 읽기 (백그라운드)

    private void load() {
        final int gen = ++generation;
        final boolean all = showAll;
        final String actPkg = activityModePkg;
        items.clear();
        adapter.notifyDataSetChanged();
        if (actPkg != null) {
            header.setText("읽는 중… (" + actPkg + ")");
            modeBtn.setText("← 앱 목록으로");
        } else {
            header.setText("읽는 중…");
            modeBtn.setText(all ? "아이콘 있는 앱만" : "모든 앱 보기");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Entry> result = new ArrayList<Entry>();
                String err = null;
                try {
                    if (actPkg != null) {
                        loadActivities(actPkg, result);
                    } else {
                        loadApps(all, result);
                    }
                } catch (Throwable t) {
                    err = t.toString();
                }
                final String error = err;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isFinishing()) {
                            return;
                        }
                        items.clear();
                        items.addAll(result);
                        adapter.notifyDataSetChanged();
                        int cand = 0;
                        for (Entry e : result) {
                            if (e.candidate) {
                                cand++;
                            }
                        }
                        String h;
                        if (actPkg != null) {
                            h = (activityModeLabel != null ? activityModeLabel + " — " : "")
                                    + "외부에서 열 수 있는 화면 " + result.size() + "개";
                        } else {
                            h = (all ? "모든 앱 " : "실행 가능한 앱 ") + result.size() + "개 (★ 미러링 후보 " + cand + "개)";
                        }
                        if (error != null) {
                            h += " / 오류: " + error;
                        }
                        header.setText(h);
                    }
                });
            }
        }, "picker-load").start();
    }

    private void loadApps(boolean all, List<Entry> out) {
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        HashSet<String> launchable = new HashSet<String>();
        HashSet<String> seen = new HashSet<String>();

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                launchable.add(pkg);
                if (pkg.equals(self) || !seen.add(pkg)) {
                    continue;
                }
                Entry e = new Entry();
                e.pkg = pkg;
                e.label = safeLabel(ri.loadLabel(pm), pkg);
                e.hasLauncher = true;
                e.sub = pkg;
                e.candidate = looksLikeMirroring(pkg, e.label, ri.activityInfo.name);
                out.add(e);
            }
        }
        if (all) {
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            if (pkgs != null) {
                for (PackageInfo pi : pkgs) {
                    String pkg = pi.packageName;
                    if (pkg == null || pkg.equals(self) || !seen.add(pkg)) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.pkg = pkg;
                    CharSequence l = null;
                    try {
                        if (pi.applicationInfo != null) {
                            l = pi.applicationInfo.loadLabel(pm);
                        }
                    } catch (Throwable ignored) {
                        // 라벨 없으면 패키지명
                    }
                    e.label = safeLabel(l, pkg);
                    e.hasLauncher = launchable.contains(pkg);
                    e.sub = pkg + (e.hasLauncher ? "" : "  (아이콘 없음 — 화면 직접 고르기 필요)");
                    e.candidate = looksLikeMirroring(pkg, e.label);
                    out.add(e);
                }
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.candidate != b.candidate) {
                    return a.candidate ? -1 : 1;
                }
                if (a.hasLauncher != b.hasLauncher) {
                    return a.hasLauncher ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private void loadActivities(String pkg, List<Entry> out) throws PackageManager.NameNotFoundException {
        PackageManager pm = getPackageManager();
        PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
        if (pi.activities == null) {
            return;
        }
        for (ActivityInfo ai : pi.activities) {
            if (!ai.exported || !ai.enabled) {
                continue;
            }
            Entry e = new Entry();
            e.pkg = pkg;
            e.cls = ai.name;
            CharSequence l = null;
            try {
                l = ai.loadLabel(pm);
            } catch (Throwable ignored) {
                // 라벨 없으면 클래스명
            }
            e.label = safeLabel(l, shortName(ai.name));
            e.sub = ai.name;
            e.hasLauncher = true;
            e.candidate = looksLikeMirroring(ai.name, e.label);
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.candidate != b.candidate) {
                    return a.candidate ? -1 : 1;
                }
                return a.sub.compareToIgnoreCase(b.sub);
            }
        });
    }

    private static String shortName(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    private static String safeLabel(CharSequence l, String fallback) {
        if (l == null) {
            return fallback;
        }
        String s = l.toString().trim();
        return s.length() == 0 ? fallback : s;
    }

    // ------------------------------------------------------------------ 선택

    private void onPick(final Entry e) {
        if (e.cls != null) {
            new AlertDialog.Builder(this)
                    .setTitle("이 화면을 실행 대상으로?")
                    .setMessage(e.label + "\n" + e.pkg + "\n" + e.cls)
                    .setPositiveButton("선택", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            save(e.pkg, e.cls, activityModeLabel != null ? activityModeLabel + " / " + e.label : e.label);
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        boolean canDefault;
        try {
            canDefault = getPackageManager().getLaunchIntentForPackage(e.pkg) != null;
        } catch (Throwable t) {
            canDefault = false;
        }
        final boolean def = canDefault;
        final String[] opts = def
                ? new String[]{"이 앱 선택 (기본 실행 화면)", "화면(액티비티) 직접 고르기 — 고급"}
                : new String[]{"화면(액티비티) 직접 고르기 — 고급"};
        new AlertDialog.Builder(this)
                .setTitle(e.label + "\n" + e.pkg)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (def && which == 0) {
                            save(e.pkg, null, e.label);
                        } else {
                            activityModePkg = e.pkg;
                            activityModeLabel = e.label;
                            load();
                        }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void save(String pkg, String cls, String label) {
        if (pkg.equals(getPackageName())) {
            Toast.makeText(this, "이 앱 자신은 고를 수 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        Store.prefs(this).edit()
                .putString(Store.K_PKG, pkg)
                .putString(Store.K_CLS, cls)
                .putString(Store.K_LABEL, label)
                .commit();
        Store.log(this, "실행 대상 선택: " + pkg + (cls != null ? " / " + cls : " (기본 실행 화면)"));
        Toast.makeText(this, "선택했습니다: " + label, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ------------------------------------------------------------------ 목록 어댑터

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t;
            if (convertView instanceof TextView) {
                t = (TextView) convertView;
            } else {
                t = new TextView(PickerActivity.this);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setMinHeight(dp(64));
                t.setPadding(dp(8), dp(10), dp(8), dp(10));
            }
            Entry e = items.get(position);
            t.setText((e.candidate ? "★ " : "") + e.label + "\n" + e.sub);
            t.setTypeface(Typeface.DEFAULT, e.candidate ? Typeface.BOLD : Typeface.NORMAL);
            return t;
        }
    }
}

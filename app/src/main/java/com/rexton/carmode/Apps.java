package com.rexton.carmode;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 설치된 앱 찾기 / 칸별 선택 저장 / 앱 실행. */
final class Apps {
    private Apps() {}

    private static final String PREFS = "carmode";

    /** 목록에 보여줄 앱 한 개. 아이콘은 필요할 때 읽는다(전체 앱 목록이 길 수 있어서). */
    static final class Entry {
        final String pkg;
        final String label;
        private Drawable icon;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        Drawable icon(PackageManager pm) {
            if (icon == null) icon = loadIcon(pm, pkg);
            return icon;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isLaunchable(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 이 칸에서 실제로 쓸 앱. 저장된 선택이 지워졌으면 설치된 첫 후보, 그것도 없으면 null. */
    static String resolve(Context c, Category cat) {
        PackageManager pm = c.getPackageManager();
        String saved = prefs(c).getString("pkg_" + cat.key, null);
        if (isLaunchable(pm, saved)) return saved;
        for (String[] cand : cat.candidates) {
            if (isLaunchable(pm, cand[0])) return cand[0];
        }
        return null;
    }

    static void save(Context c, Category cat, String pkg) {
        prefs(c).edit().putString("pkg_" + cat.key, pkg).apply();
    }

    /** 설치된 후보만 (후보 목록 순서 유지). */
    static List<Entry> installedCandidates(Context c, Category cat) {
        PackageManager pm = c.getPackageManager();
        List<Entry> out = new ArrayList<>();
        for (String[] cand : cat.candidates) {
            if (isLaunchable(pm, cand[0])) out.add(new Entry(cand[0], label(pm, cand[0], cand[1])));
        }
        return out;
    }

    /** 홈 화면에 아이콘이 있는 앱 전부 (이 앱 자신 제외), 이름순. */
    static List<Entry> allLaunchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentActivities(main, 0);
        } catch (Exception e) {
            infos = new ArrayList<>();
        }
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            // ResolveInfo 에 이미 들어 있는 ApplicationInfo 를 써서 앱마다 시스템 호출을 한 번씩 줄인다
            String l = null;
            try {
                if (ri.activityInfo.applicationInfo != null) {
                    CharSequence cs = pm.getApplicationLabel(ri.activityInfo.applicationInfo);
                    if (cs != null && cs.length() > 0) l = cs.toString();
                }
            } catch (Exception ignored) {
            }
            out.add(new Entry(pkg, l != null ? l : label(pm, pkg, pkg)));
        }
        final Collator col = Collator.getInstance(Locale.KOREAN);
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return col.compare(a.label, b.label);
            }
        });
        return out;
    }

    static String label(PackageManager pm, String pkg, String fallback) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static Drawable loadIcon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** 앱 실행. 실패하면 토스트를 띄우고 false. */
    static boolean launch(Activity a, String pkg) {
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                toast(a, "앱을 실행할 수 없습니다 (설치돼 있지 않음)");
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            toast(a, "앱을 찾을 수 없습니다");
        } catch (Exception e) {
            toast(a, "앱 실행 실패: " + e.getClass().getSimpleName());
        }
        return false;
    }

    static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }
}

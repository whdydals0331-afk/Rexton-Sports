package com.jydiag.n90auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 설정값과 이벤트 기록을 SharedPreferences 한 파일에 보관한다.
 * 수신기(메인 스레드)와 화면이 같은 프로세스에서 쓰므로 정적 락으로 감싼다.
 * 수신기는 onReceive 가 끝나면 프로세스가 죽을 수 있어서 apply() 대신 commit() 을 쓴다.
 */
final class Store {

    static final String PREFS = "n90auto";

    static final String K_PKG = "target_pkg";
    static final String K_CLS = "target_cls";      // null 이면 패키지의 기본 실행 화면
    static final String K_LABEL = "target_label";
    static final String K_ENABLED = "enabled";
    static final String K_DELAY = "delay_s";
    static final String K_HOME = "home_enabled";
    static final String K_HOME_DELAY = "home_delay_s";
    static final String K_RETRY = "retry_enabled";
    static final String K_RETRY_DELAY = "retry_delay_s";
    static final String K_LAST_SCHED_ELAPSED = "last_sched_elapsed";
    static final String K_LAST_SCHED_BOOTID = "last_sched_bootid";
    static final String K_LAST_SCHED_BOOTWALL = "last_sched_bootwall";
    static final String K_LAST_SCHED_SLEPT = "last_sched_slept";   // 예약 시점의 (elapsedRealtime - uptimeMillis)
    static final String K_LOG = "log";

    static final int DEFAULT_DELAY = 8;
    static final int DEFAULT_HOME_DELAY = 10;
    static final int DEFAULT_RETRY_DELAY = 20;

    static final int[] DELAY_CHOICES = {3, 5, 8, 15, 30};
    static final int[] HOME_DELAY_CHOICES = {5, 10, 20, 30};
    static final int[] RETRY_DELAY_CHOICES = {10, 20, 30, 60};

    static final long COOLDOWN_MS = 120000L;
    /** USER_PRESENT / POWER_CONNECTED 같은 "약한" 신호용: 마지막 예약 후 이만큼 지났거나 */
    static final long WEAK_MIN_GAP_MS = 30L * 60000L;
    /** ...마지막 예약 후 기기가 이만큼 이상 잠들었다(딥슬립) 깨어났을 때만 실행 신호로 쓴다. */
    static final long WEAK_MIN_SLEEP_MS = 60000L;
    static final int MAX_LOG_ENTRIES = 200;
    private static final int MAX_LINE_CHARS = 400;

    static final Object LOCK = new Object();

    private Store() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String targetPkg(Context c) {
        String p = prefs(c).getString(K_PKG, null);
        return (p == null || p.length() == 0) ? null : p;
    }

    static String targetCls(Context c) {
        String s = prefs(c).getString(K_CLS, null);
        return (s == null || s.length() == 0) ? null : s;
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }

    static int delaySec(Context c) {
        return prefs(c).getInt(K_DELAY, DEFAULT_DELAY);
    }

    static boolean homeEnabled(Context c) {
        return prefs(c).getBoolean(K_HOME, false);
    }

    static int homeDelaySec(Context c) {
        return prefs(c).getInt(K_HOME_DELAY, DEFAULT_HOME_DELAY);
    }

    static boolean retryEnabled(Context c) {
        return prefs(c).getBoolean(K_RETRY, false);
    }

    static int retryDelaySec(Context c) {
        return prefs(c).getInt(K_RETRY_DELAY, DEFAULT_RETRY_DELAY);
    }

    static String targetDescription(Context c) {
        String pkg = targetPkg(c);
        if (pkg == null) {
            return "(선택 안 됨)";
        }
        String label = prefs(c).getString(K_LABEL, "");
        String cls = targetCls(c);
        StringBuilder sb = new StringBuilder();
        if (label != null && label.length() > 0) {
            sb.append(label).append("\n");
        }
        sb.append(pkg);
        if (cls != null) {
            sb.append("\n화면: ").append(cls);
        } else {
            sb.append("\n화면: 기본 실행 화면");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 부팅 구분 / 쿨다운

    /** 리눅스 커널이 부팅마다 새로 만드는 ID. 4.4 에서는 권한 없이 읽힌다. 실패하면 null. */
    static String readBootId() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            String s = r.readLine();
            if (s != null) {
                s = s.trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 없거나 못 읽으면 시계 기반 추정으로 대신한다
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                    // 무시
                }
            }
        }
        return null;
    }

    /** 벽시계 기준 부팅 시각 추정 (시계가 틀리면 부정확하므로 보조 수단). */
    static long bootWallEstimate() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /**
     * 같은 부팅 안에서 마지막 예약 후 120초가 안 지났으면 남은 ms, 아니면 0.
     * elapsedRealtime 은 재부팅하면 0 부터 다시 세므로, 부팅이 바뀌었는지 먼저 확인한다.
     * (확인 안 하면 "지난 부팅 40초에 예약 → 이번 부팅 45초에 신호" 를 쿨다운으로 오판한다)
     */
    static long cooldownRemainingMs(Context c, long nowElapsed, String bootId) {
        SharedPreferences p = prefs(c);
        long last = p.getLong(K_LAST_SCHED_ELAPSED, -1L);
        if (last < 0 || last > nowElapsed || !sameBootAsLast(p, bootId)) {
            return 0;
        }
        long passed = nowElapsed - last;
        return passed < COOLDOWN_MS ? COOLDOWN_MS - passed : 0;
    }

    private static boolean sameBootAsLast(SharedPreferences p, String bootId) {
        String lastBoot = p.getString(K_LAST_SCHED_BOOTID, null);
        if (bootId != null && lastBoot != null) {
            return bootId.equals(lastBoot);
        }
        long lastWall = p.getLong(K_LAST_SCHED_BOOTWALL, Long.MIN_VALUE);
        return lastWall != Long.MIN_VALUE && Math.abs(bootWallEstimate() - lastWall) < 60000L;
    }

    /** 부팅 후 기기가 잠들어(딥슬립) 있던 누적 시간. 잠들면 uptime 은 멈추고 elapsedRealtime 은 계속 간다. */
    static long sleptMs(long nowElapsed) {
        return nowElapsed - SystemClock.uptimeMillis();
    }

    /**
     * "약한" 신호(USER_PRESENT, ACTION_POWER_CONNECTED)는 운전 중에도 화면을 껐다 켜면 다시 올 수 있다.
     * 그때마다 미러링 앱이 내비 위로 뜨지 않도록, 같은 부팅 안에서는
     * 마지막 예약 후 30분이 지났거나 그 사이 기기가 1분 이상 잠들었던 경우(시동 꺼짐→켜짐 추정)에만 허용한다.
     * @return 허용하면 null, 막으면 기록용 이유
     */
    static String weakTriggerBlockReason(Context c, long nowElapsed, String bootId) {
        SharedPreferences p = prefs(c);
        long last = p.getLong(K_LAST_SCHED_ELAPSED, -1L);
        if (last < 0 || last > nowElapsed || !sameBootAsLast(p, bootId)) {
            return null;
        }
        long passed = nowElapsed - last;
        if (passed >= WEAK_MIN_GAP_MS) {
            return null;
        }
        long lastSlept = p.getLong(K_LAST_SCHED_SLEPT, -1L);
        long sleptSince = lastSlept >= 0 ? sleptMs(nowElapsed) - lastSlept : -1L;
        if (sleptSince >= WEAK_MIN_SLEEP_MS) {
            return null;
        }
        return "마지막 예약 후 " + (passed / 60000L) + "분, 그 사이 잠든 시간 "
                + (sleptSince >= 0 ? (sleptSince / 1000L) + "초" : "모름")
                + " (화면켜짐/전원 신호는 30분 경과 또는 1분 이상 잠든 뒤에만 실행)";
    }

    static void markScheduled(Context c, long nowElapsed, String bootId) {
        synchronized (LOCK) {
            prefs(c).edit()
                    .putLong(K_LAST_SCHED_ELAPSED, nowElapsed)
                    .putString(K_LAST_SCHED_BOOTID, bootId)
                    .putLong(K_LAST_SCHED_BOOTWALL, bootWallEstimate())
                    .putLong(K_LAST_SCHED_SLEPT, sleptMs(nowElapsed))
                    .commit();
        }
    }

    // ---------------------------------------------------------------- 이벤트 기록

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    static String fmtSec(long ms) {
        return String.format(Locale.US, "%.1fs", ms / 1000.0);
    }

    /** 한 줄 기록을 덧붙인다. 앞에 시각/부팅 후 경과/uptime 을 자동으로 붙인다. 최근 200줄만 유지. */
    static void log(Context c, String msg) {
        String line = now()
                + " | 부팅후 " + fmtSec(SystemClock.elapsedRealtime())
                + " (uptime " + fmtSec(SystemClock.uptimeMillis()) + ")"
                + " | " + msg;
        line = line.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS) + "…";
        }
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            List<String> lines = splitLines(p.getString(K_LOG, ""));
            lines.add(line);
            int from = Math.max(0, lines.size() - MAX_LOG_ENTRIES);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            p.edit().putString(K_LOG, sb.toString()).commit();
        }
    }

    /** 오래된 것 → 최근 순서. */
    static List<String> logLines(Context c) {
        synchronized (LOCK) {
            return splitLines(prefs(c).getString(K_LOG, ""));
        }
    }

    static void clearLog(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().remove(K_LOG).commit();
        }
    }

    private static List<String> splitLines(String s) {
        ArrayList<String> out = new ArrayList<String>();
        if (s == null || s.length() == 0) {
            return out;
        }
        for (String l : s.split("\n")) {
            if (l.length() > 0) {
                out.add(l);
            }
        }
        return out;
    }
}

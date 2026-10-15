package com.jydiag.n90auto;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/**
 * 실행/홈 이동/예약/예약 취소. 화면의 "지금 실행 테스트"와 자동 실행이 같은 launchTarget() 을 쓴다.
 * 상주 서비스 없이 AlarmManager 로 예약하므로 수신기가 끝나도 예약은 살아 있다
 * (단, 재부팅하면 예약은 사라진다 — 그때는 새 부팅 신호가 다시 예약한다).
 */
final class Launcher {

    static final String ACTION_LAUNCH = "com.jydiag.n90auto.LAUNCH";
    static final String ACTION_RETRY = "com.jydiag.n90auto.RETRY";
    static final String ACTION_GO_HOME = "com.jydiag.n90auto.GO_HOME";
    static final String EXTRA_TEST = "test";
    static final String EXTRA_TRIGGER = "trigger";

    private static final int RC_LAUNCH = 101;
    private static final int RC_RETRY = 102;
    private static final int RC_GO_HOME = 103;

    private Launcher() {
    }

    private static PendingIntent stepIntent(Context c, String action, int rc, boolean test, String trigger) {
        Intent i = new Intent(c, StepReceiver.class);
        i.setAction(action);
        i.putExtra(EXTRA_TEST, test);
        i.putExtra(EXTRA_TRIGGER, trigger);
        // FLAG_UPDATE_CURRENT: 같은 action+requestCode 면 같은 PendingIntent 이므로
        // 신호가 여러 번 와도 알람이 쌓이지 않고 마지막 예약으로 바뀐다.
        return PendingIntent.getBroadcast(c.getApplicationContext(), rc, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        // targetSdk 19 에서는 set() 이 부정확(묶음 처리)해질 수 있어서 setExact (API 19 에 있음) 를 쓴다.
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    /**
     * 실행 → (한 번 더 실행) → (홈으로) 순서를 예약한다. 기존 예약은 먼저 모두 지운다.
     * @return 사람이 읽을 예약 요약
     */
    static String scheduleSequence(Context c, String trigger, boolean test) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        cancelAlarms(c, am);
        long now = SystemClock.elapsedRealtime();
        int delay = Store.delaySec(c);
        long launchAt = now + delay * 1000L;
        setAlarm(am, launchAt, stepIntent(c, ACTION_LAUNCH, RC_LAUNCH, test, trigger));
        StringBuilder sb = new StringBuilder();
        sb.append("실행 ").append(delay).append("초 후");

        long base = launchAt;
        int offset = delay;
        if (Store.retryEnabled(c)) {
            int r = Store.retryDelaySec(c);
            base = launchAt + r * 1000L;
            offset += r;
            setAlarm(am, base, stepIntent(c, ACTION_RETRY, RC_RETRY, test, trigger));
            sb.append(", 한 번 더 실행 ").append(offset).append("초 후");
        }
        if (Store.homeEnabled(c)) {
            int h = Store.homeDelaySec(c);
            offset += h;
            setAlarm(am, base + h * 1000L, stepIntent(c, ACTION_GO_HOME, RC_GO_HOME, test, trigger));
            sb.append(", 홈으로 ").append(offset).append("초 후");
        }
        return sb.toString();
    }

    private static void cancelAlarms(Context c, AlarmManager am) {
        PendingIntent[] all = {
                stepIntent(c, ACTION_LAUNCH, RC_LAUNCH, false, null),
                stepIntent(c, ACTION_RETRY, RC_RETRY, false, null),
                stepIntent(c, ACTION_GO_HOME, RC_GO_HOME, false, null),
        };
        for (PendingIntent pi : all) {
            try {
                am.cancel(pi);
                pi.cancel();
            } catch (Throwable ignored) {
                // 이미 없으면 무시
            }
        }
    }

    /** 대기 중인 예약(실행/한 번 더/홈)을 모두 취소한다. */
    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        cancelAlarms(c, am);
    }

    /**
     * 고른 앱을 실행한다. 결과를 기록에 남기고 성공 여부를 돌려준다.
     * @param source 기록에 남길 이유 (예: "자동 실행(BOOT_COMPLETED)", "지금 실행 테스트")
     */
    static boolean launchTarget(Context c, String source) {
        String pkg = Store.targetPkg(c);
        String cls = Store.targetCls(c);
        if (pkg == null) {
            Store.log(c, source + " → 실패: 실행할 앱이 선택되지 않음");
            return false;
        }
        if (pkg.equals(c.getPackageName())) {
            Store.log(c, source + " → 거부: 자기 자신은 실행하지 않음");
            return false;
        }
        PackageManager pm = c.getPackageManager();
        try {
            pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Store.log(c, source + " → 실패: 앱이 설치되어 있지 않음 (" + pkg + ")");
            return false;
        } catch (Throwable t) {
            Store.log(c, source + " → 실패: 패키지 확인 오류 " + t);
            return false;
        }

        Intent intent;
        if (cls != null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(pkg, cls));
        } else {
            try {
                intent = pm.getLaunchIntentForPackage(pkg);
            } catch (Throwable t) {
                intent = null;
            }
            if (intent == null) {
                Store.log(c, source + " → 실패: " + pkg + " 에 기본 실행 화면이 없음 (화면을 직접 골라 주세요)");
                return false;
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        String what = intent.getComponent() != null ? intent.getComponent().flattenToShortString() : pkg;
        try {
            c.startActivity(intent);
            Store.log(c, source + " → 실행 요청 성공: " + what);
            return true;
        } catch (Throwable t) {
            Store.log(c, source + " → 실행 실패: " + what + " / " + t);
            return false;
        }
    }

    /** 홈(런처) 화면으로 이동. 사용자가 미러링 앱을 연 뒤 다른 화면으로 나가는 동작을 흉내 낸다. */
    static boolean goHome(Context c, String source) {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(home);
            Store.log(c, source + " → 홈으로 이동 요청 성공");
            return true;
        } catch (Throwable t) {
            Store.log(c, source + " → 홈으로 이동 실패: " + t);
            return false;
        }
    }
}

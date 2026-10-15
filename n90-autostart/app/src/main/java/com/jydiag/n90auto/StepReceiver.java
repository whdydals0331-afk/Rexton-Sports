package com.jydiag.n90auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager 가 예약 시각에 깨우는 내부 수신기 (exported="false").
 * 실행 직전에 자동 실행이 여전히 켜져 있는지 다시 확인한다 (끄면 예약도 취소되지만 이중 안전장치).
 * "순서 테스트"로 예약된 것(test=true)은 자동 실행이 꺼져 있어도 진행한다.
 */
public class StepReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            String action = intent != null ? intent.getAction() : null;
            boolean test = intent != null && intent.getBooleanExtra(Launcher.EXTRA_TEST, false);
            String trigger = intent != null ? intent.getStringExtra(Launcher.EXTRA_TRIGGER) : null;
            String src = (test ? "순서 테스트" : "자동") + "(" + (trigger != null ? trigger : "?") + ")";

            if (!test && !Store.enabled(c)) {
                Store.log(c, src + " " + action + " → 건너뜀: 자동 실행 꺼짐");
                return;
            }
            if (Launcher.ACTION_LAUNCH.equals(action)) {
                Launcher.launchTarget(c, src + " 실행");
            } else if (Launcher.ACTION_RETRY.equals(action)) {
                Launcher.launchTarget(c, src + " 한 번 더 실행");
            } else if (Launcher.ACTION_GO_HOME.equals(action)) {
                Launcher.goHome(c, src);
            } else {
                Store.log(c, "알 수 없는 내부 신호: " + action);
            }
        } catch (Throwable t) {
            try {
                Store.log(c, "내부 수신기 오류: " + t);
            } catch (Throwable ignored) {
                // 무시
            }
        }
    }
}

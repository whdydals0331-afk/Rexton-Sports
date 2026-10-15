package com.jydiag.n90auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * 시스템 신호 수신기. 받은 신호는 전부 기록한다 (N90 이 시동 때 무엇을 보내는지 알아내는 게 핵심).
 * 조건(자동 실행 켜짐 + 대상 선택됨 + 쿨다운 지남)이 맞으면 AlarmManager 로 실행 순서를 예약하고 바로 끝난다.
 * 여기서 직접 startActivity 하거나 오래 기다리지 않는다.
 */
public class TriggerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            handle(context, intent);
        } catch (Throwable t) {
            try {
                Store.log(context, "수신기 오류: " + t);
            } catch (Throwable ignored) {
                // 기록조차 실패하면 조용히 끝낸다 (기기를 방해하지 않는 게 우선)
            }
        }
    }

    private void handle(Context c, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            action = "(action 없음)";
        }
        String extras = describeExtras(intent);
        String head = "신호 " + action + (extras.length() > 0 ? " " + extras : "");

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 업데이트 직후 실행되면 혼란스러우므로 기록만 한다.
            Store.log(c, head + " → 기록만 (앱 업데이트는 실행 신호로 쓰지 않음)");
            return;
        }
        if (!Store.enabled(c)) {
            Store.log(c, head + " → 건너뜀: 자동 실행 꺼짐");
            return;
        }
        if (Store.targetPkg(c) == null) {
            Store.log(c, head + " → 건너뜀: 실행할 앱 미선택");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String bootId = Store.readBootId();
        long remain = Store.cooldownRemainingMs(c, now, bootId);
        if (remain > 0) {
            Store.log(c, head + " → 건너뜀: 쿨다운 (" + (remain / 1000) + "초 남음)");
            return;
        }
        if (isWeak(action)) {
            String why = Store.weakTriggerBlockReason(c, now, bootId);
            if (why != null) {
                Store.log(c, head + " → 건너뜀: " + why);
                return;
            }
        }
        Store.markScheduled(c, now, bootId);
        String plan = Launcher.scheduleSequence(c, shortAction(action), false);
        Store.log(c, head + " → 예약함: " + plan);
    }

    /** 운전 중 화면 끄고 켜기 등으로 반복될 수 있는 신호. */
    static boolean isWeak(String action) {
        return Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_POWER_CONNECTED.equals(action);
    }

    static String shortAction(String action) {
        int dot = action.lastIndexOf('.');
        return dot >= 0 && dot < action.length() - 1 ? action.substring(dot + 1) : action;
    }

    /** 알 수 없는 헤드유닛 신호의 단서가 될 수 있어서 extras 키/값을 짧게 남긴다. */
    private static String describeExtras(Intent intent) {
        if (intent == null) {
            return "";
        }
        try {
            Bundle b = intent.getExtras();
            if (b == null || b.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("{");
            for (String k : b.keySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                Object v = b.get(k);
                sb.append(k).append('=').append(String.valueOf(v));
                if (sb.length() > 160) {
                    break;
                }
            }
            sb.append('}');
            String s = sb.toString();
            return s.length() > 180 ? s.substring(0, 180) + "…}" : s;
        } catch (Throwable t) {
            return "{extras 읽기 실패}";
        }
    }
}

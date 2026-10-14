package com.rexton.carmode;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * "출발 모드" 설정 값과 작은 동작(재생 키 보내기).
 * 순서 진행(어느 단계에서 기다리는지, 취소)은 화면과 붙어 있어서 MainActivity 가 맡는다.
 *
 * 출발 순서는 사용자가 앱을 직접 열었거나 "🚗 출발"을 눌렀을 때만 시작한다.
 * 블루투스 연결·부팅·알림 같은 다른 계기로는 절대 시작하지 않는다 (리시버·서비스 없음).
 */
final class Departure {
    private Departure() {}

    private static final String K_AUTO = "dep_auto";
    private static final String K_SV = "dep_sv";
    private static final String K_MUSIC = "dep_music";
    private static final String K_NAV = "dep_nav";
    private static final String K_PLAY_SEC = "dep_play_sec";
    private static final String K_NAV_SEC = "dep_nav_sec";

    /*
     * 지연 시간 범위. 음악 앱을 연 순간부터 네비를 여는 순간까지(재생 키 + 네비 지연)를 10초 안으로 묶는다.
     * 안드로이드 10+ 는 뒤로 물러난 앱이 다른 앱 화면을 띄우는 것을 막는데, 방금 화면을 띄운 앱에는
     * 약 10초의 유예가 있다 (API 35 프레임워크 코드의 "within 10000ms grace period").
     * 네비는 이 앱이 이미 뒤로 물러난 뒤에 열기 때문에 그 유예 안에 끝내야 한다.
     */
    static final int PLAY_SEC_MIN = 1, PLAY_SEC_MAX = 6, PLAY_SEC_DEF = 3;
    static final int NAV_SEC_MIN = 1, NAV_SEC_MAX = 3, NAV_SEC_DEF = 2;

    private static SharedPreferences p(Context c) {
        return Apps.prefs(c);
    }

    static boolean autoOn(Context c) {
        return p(c).getBoolean(K_AUTO, true);
    }

    static void setAutoOn(Context c, boolean on) {
        p(c).edit().putBoolean(K_AUTO, on).apply();
    }

    static boolean smartViewOn(Context c) {
        return p(c).getBoolean(K_SV, true);
    }

    static boolean musicOn(Context c) {
        return p(c).getBoolean(K_MUSIC, true);
    }

    static boolean navOn(Context c) {
        return p(c).getBoolean(K_NAV, true);
    }

    static int playSec(Context c) {
        return clamp(p(c).getInt(K_PLAY_SEC, PLAY_SEC_DEF), PLAY_SEC_MIN, PLAY_SEC_MAX);
    }

    static int navSec(Context c) {
        return clamp(p(c).getInt(K_NAV_SEC, NAV_SEC_DEF), NAV_SEC_MIN, NAV_SEC_MAX);
    }

    static void save(Context c, boolean sv, boolean music, boolean nav, int playSec, int navSec) {
        p(c).edit()
                .putBoolean(K_SV, sv)
                .putBoolean(K_MUSIC, music)
                .putBoolean(K_NAV, nav)
                .putInt(K_PLAY_SEC, clamp(playSec, PLAY_SEC_MIN, PLAY_SEC_MAX))
                .putInt(K_NAV_SEC, clamp(navSec, NAV_SEC_MIN, NAV_SEC_MAX))
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 미디어 "재생" 키(누름+뗌)를 시스템에 보낸다. 권한이 필요 없다.
     * 키는 지금 활성인 미디어 세션(없으면 마지막으로 재생하던 앱)으로 간다.
     * 실제로 재생이 시작되는지는 음악 앱이 이 키를 어떻게 처리하느냐에 달려 있다.
     */
    static boolean sendPlayKey(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

package com.rexton.carmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;

import java.util.Locale;

/**
 * 삼성 Smart View(화면 미러링) 열기 — 최선 노력.
 * 삼성은 다른 앱이 미러링을 조용히 시작하는 공개 API 를 주지 않는다. 그래서 "기기 고르는 창"까지만
 * 띄우고, 거기서 JY_G_135c 를 누르는 건 사용자가 한다. 위에서부터 시도하고 처음 성공한 데서 멈춘다.
 */
final class SmartView {
    private SmartView() {}

    static final String PKG = "com.samsung.android.smartmirroring";
    /** 차 화면(JY커스텀 N90)이 Smart View 목록에 뜨는 이름. */
    static final String CAR_NAME = "JY_G_135c";

    static void open(Activity a) {
        // 1) Smart View 앱 자체의 실행 인텐트. 삼성 설정에서 "앱스 화면에 Smart View 아이콘 표시"를
        //    켜 둔 경우에만 런처 액티비티가 있는 것으로 알고 있음 (미확인).
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(PKG);
            if (i != null && start(a, i)) {
                hint(a);
                return;
            }
        } catch (Exception ignored) {
        }

        // 2) 빠른 설정의 Smart View 버튼이 여는 것으로 알려진 액티비티 (미확인 — 외부 공개 여부·이름 모두
        //    One UI 버전에 따라 다를 수 있다. 없거나 막혀 있으면 예외 → 다음 단계).
        Intent capture = new Intent();
        capture.setClassName(PKG, PKG + ".CaptureActivity");
        if (start(a, capture)) {
            hint(a);
            return;
        }

        // 3) 안드로이드 표준 "전송(Cast)" 설정 화면. 삼성에서는 무엇이 열리는지 기종마다 다르다.
        if (start(a, new Intent(Settings.ACTION_CAST_SETTINGS))) {
            Apps.toast(a, "안 보이면: 빠른 설정(화면 위에서 두 번 내리기) → Smart View → " + CAR_NAME);
            return;
        }

        // 4) 직접 안내.
        showManualHelp(a);
    }

    private static boolean start(Activity a, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Exception e) { // ActivityNotFoundException, SecurityException(비공개 액티비티) 등
            return false;
        }
    }

    private static void hint(Activity a) {
        Apps.toast(a, "목록에서 " + CAR_NAME + " 를 고르세요");
    }

    /*
     * ───── 미러링 감지 (화면 방향 "📱 자동" 용) ─────
     *
     * 미확인(UNVERIFIED): 삼성 Smart View 로 미러링하는 동안 안드로이드가 다른 앱에게 "추가 디스플레이"를
     * 보여 주는지는 S23+ 실기기에서 확인하지 않았다. 표준 안드로이드의 Wi-Fi 디스플레이(Miracast)는
     * FLAG_PRESENTATION 이 붙은 추가 디스플레이로 보이지만, 삼성이 자체 방식으로 미러링하면 아무것도
     * 안 보일 수 있다. 그래서 감지가 안 되는 경우를 위해 "📱 세로 / 📱 가로" 수동 모드를 둔다.
     *
     * 판단 기준 (하나라도 맞으면 미러링 중으로 본다):
     *  1) DISPLAY_CATEGORY_PRESENTATION 으로 돌려받은, 기본 화면이 아닌 유효한 디스플레이
     *  2) 기본 화면이 아닌 유효한 디스플레이 중 FLAG_PRESENTATION 이 있는 것
     *  3) 기본 화면이 아닌 유효한 디스플레이 중 이름에 Wi-Fi / Smart View / Miracast / wfd 가 들어간 것
     * 이름이 없는 기타 가상 디스플레이(화면 녹화 등)까지 미러링으로 보면 오작동이 잦을 것 같아 넣지 않았다.
     * 디스플레이 이름은 기록(로그)하지 않는다. 필요한 권한 없음 (DisplayManager 는 API 17+).
     */
    private static final String[] MIRROR_NAME_HINTS = {
            "wi-fi", "wifi", "smart view", "smartview", "miracast", "wfd"
    };

    /** 지금 휴대폰 화면을 다른 화면(차 헤드유닛 등)으로 미러링 중인 것 같은가. 실패하면 false. */
    static boolean isMirroring(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return false;
            Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (pres != null) {
                for (Display d : pres) {
                    if (isExtra(d)) return true;
                }
            }
            Display[] all = dm.getDisplays();
            if (all != null) {
                for (Display d : all) {
                    if (!isExtra(d)) continue;
                    if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) return true;
                    if (nameLooksMirrored(d.getName())) return true;
                }
            }
        } catch (Exception ignored) {
            // 감지 실패 = 미러링 아님으로 본다 (수동 모드로 해결 가능)
        }
        return false;
    }

    private static boolean isExtra(Display d) {
        return d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid();
    }

    private static boolean nameLooksMirrored(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String h : MIRROR_NAME_HINTS) {
            if (n.contains(h)) return true;
        }
        return false;
    }

    static void showManualHelp(Activity a) {
        try {
            new AlertDialog.Builder(a)
                    .setTitle("Smart View 직접 켜기")
                    .setMessage("빠른 설정(화면 위에서 두 번 내리기) → Smart View → " + CAR_NAME
                            + "\n\n차에서 N90 의 미러링 앱이 켜져 있어야 목록에 나타납니다.")
                    .setPositiveButton("확인", null)
                    .show();
        } catch (Exception e) {
            Apps.toast(a, "빠른 설정(화면 위에서 두 번 내리기) → Smart View → " + CAR_NAME);
        }
    }
}

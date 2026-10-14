package com.rexton.carmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;

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

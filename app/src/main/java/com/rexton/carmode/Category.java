package com.rexton.carmode;

/**
 * 화면의 큰 칸 하나(네비 / 음악 / 유튜브).
 * candidates 는 {패키지명, 사람이 읽는 이름} 쌍. 앞에 있을수록 기본값 우선순위가 높다.
 */
final class Category {
    final String key;
    final String emoji;
    final String title;
    final int color;
    final String[][] candidates;

    Category(String key, String emoji, String title, int color, String[][] candidates) {
        this.key = key;
        this.emoji = emoji;
        this.title = title;
        this.color = color;
        this.candidates = candidates;
    }

    /*
     * 패키지명 확인 상태:
     *  - 표시 없음 = Play 스토어 주소(play.google.com/store/apps/details?id=...)로 알려진 이름
     *  - "미확인"   = 기억에 의존한 이름. 틀려도 그 앱이 목록에 안 뜰 뿐 다른 동작에는 영향 없음
     *    (어떤 앱이든 "기타 앱 고르기"로 직접 고를 수 있다)
     *  같은 목록이 AndroidManifest.xml 의 <queries> 에도 있어야 한다.
     */
    static final Category NAV = new Category("nav", "🧭", "네비", 0xFF143A5C, new String[][]{
            {"com.skt.tmap.ku", "T맵"},
            {"com.locnall.KimGiSa", "카카오내비"},
            {"com.nhn.android.nmap", "네이버 지도"},
            {"net.daum.android.map", "카카오맵"},
            {"com.google.android.apps.maps", "구글 지도"},
            {"kr.mappers.AtlanSmart", "아틀란"}, // 미확인
    });

    static final Category MUSIC = new Category("music", "🎵", "음악", 0xFF3E1F5C, new String[][]{
            {"com.iloen.melon", "멜론"},
            {"com.google.android.apps.youtube.music", "YouTube Music"},
            {"com.ktmusic.geniemusic", "지니"},
            {"com.spotify.music", "Spotify"},
            {"skplanet.musicmate", "FLO"},
            {"com.neowiz.android.bugs", "벅스"},
            {"com.sec.android.app.music", "삼성 뮤직"},
    });

    static final Category YOUTUBE = new Category("youtube", "▶", "유튜브", 0xFF5C1717, new String[][]{
            {"com.google.android.youtube", "YouTube"},
            {"app.revanced.android.youtube", "YouTube ReVanced"}, // ReVanced 패치본 기본 패키지명 (미확인)
    });

    static final Category[] ALL = {NAV, MUSIC, YOUTUBE};
}

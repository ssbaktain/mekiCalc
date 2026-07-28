package mekicalc.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 임시: 발송 없이 buildNoticeMessage(=NoticeKakaoRenderer) 출력을 미리보기. 유형별 대표 + 최신 몇 건. */
public final class KakaoRenderCheck {
    public static void main(String[] a) throws Exception {
        NoticeStore st = new NoticeStore(Path.of("data"));
        List<NoticeStore.Notice> all = st.all();
        all.sort((x, y) -> Long.compare(y.postedAt, x.postedAt));
        List<NoticeStore.Notice> pick = new ArrayList<>();
        Set<String> seenType = new LinkedHashSet<>();
        // 유형별 최신 1건씩(preview/patchnote/event/issue/devnote/coupon/etc) + 쿠폰 보유 1건
        for (NoticeStore.Notice n : all) {
            if (n.data == null) continue;
            String t = n.data.noticeType == null ? "etc" : n.data.noticeType;
            if (seenType.add(t)) pick.add(n);
        }
        for (NoticeStore.Notice n : all) {
            if (n.data != null && !n.data.coupons.isEmpty() && !pick.contains(n)) { pick.add(n); break; }
        }
        for (NoticeStore.Notice n : pick) {
            System.out.println("═══════════ [" + n.category + " · 말머리:" + n.headline + " · type:" + n.data.noticeType + "]");
            System.out.println(NoticeKakaoRenderer.render(n.data, n.sourceUrl));
            System.out.println();
        }
        System.out.println("(총 " + pick.size() + "건 미리보기 · 발송 안 함)");
    }
}

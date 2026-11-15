package com.smbox.service;

import com.smbox.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlagEngineTest {
    static final double[] FLAG = {12.05, 11.90, 12.10, 12.25, 12.35, 12.20};

    static List<DailyBar> shape(double[] flag, long flagVol) {
        List<DailyBar> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 10, 1);
        int k = 0;
        for (int i = 0; i < 30; i++) out.add(bar(d.plusDays(k++), 10.5, 10.45, 10.55, 1000));
        out.add(bar(d.plusDays(k++), 11.445, 10.5, 11.5, 3000));
        out.add(bar(d.plusDays(k++), 12.36, 11.4, 12.94, 3000));
        for (double c : flag) out.add(bar(d.plusDays(k++), c, c - 0.15, c + 0.15, flagVol));
        return out;
    }

    static DailyBar bar(LocalDate d, double c, double lo, double hi, long v) {
        return DailyBar.builder().code("000819").tradeDate(d).open(c).close(c).high(hi).low(lo).volume(v).amount(c * v * 100).build();
    }

    static List<DailyBar> withBreak(double close, long vol) {
        List<DailyBar> b = shape(FLAG, 1200);
        b.add(bar(b.get(b.size() - 1).getTradeDate().plusDays(1), close, 12.1, close, vol));
        return b;
    }

    @Test
    void 截图形状_旗面中() {
        FlagEngine.Result r = FlagEngine.evaluate(shape(FLAG, 1200), FlagEngine.DEFAULT_MIN_POLE);
        assertEquals(FlagEngine.FORMING, r.state);
        assertEquals(6, r.flagDays);
        assertEquals(18.29, FlagEngine.pct(r.pole), 0.01);      // 12.36 / 10.45
        assertTrue(r.flagRange <= 0.08);
        assertTrue(r.volShrink < 1);
    }

    @Test
    void 截图形状_涨停突破() {
        FlagEngine.Result r = FlagEngine.evaluate(withBreak(12.82, 3000), FlagEngine.DEFAULT_MIN_POLE);
        assertEquals(FlagEngine.BREAKOUT, r.state);
        assertEquals(6, r.flagDays);
        assertEquals(2.5, r.breakVol, 1e-9);
    }

    @Test
    void 突破条件不够() {
        assertNotEquals(FlagEngine.BREAKOUT, FlagEngine.evaluate(withBreak(12.82, 1500), FlagEngine.DEFAULT_MIN_POLE).state, "量不够 1.5 倍");
        assertNotEquals(FlagEngine.BREAKOUT, FlagEngine.evaluate(withBreak(12.33, 3000), FlagEngine.DEFAULT_MIN_POLE).state, "没过杆顶");
    }

    @Test
    void 旗面不合格() {
        assertEquals(FlagEngine.NONE, FlagEngine.evaluate(shape(FLAG, 3500), FlagEngine.DEFAULT_MIN_POLE).state, "没缩量");
        assertEquals(FlagEngine.NONE, FlagEngine.evaluate(shape(new double[]{12.0, 11.2, 11.6, 12.1, 12.3, 12.2}, 1200),
                FlagEngine.DEFAULT_MIN_POLE).state, "旗面太宽（区间 >8%）");
        assertEquals(FlagEngine.NONE, FlagEngine.evaluate(shape(new double[]{11.6, 11.5, 11.4, 11.4, 11.5, 11.3}, 1200),
                FlagEngine.DEFAULT_MIN_POLE).state, "回吐超过旗杆一半");
        assertEquals(FlagEngine.NONE, FlagEngine.evaluate(shape(FLAG, 1200), 0.25).state, "旗杆不到 25%");
        assertEquals(FlagEngine.NONE, FlagEngine.evaluate(shape(new double[]{12.2, 12.3}, 1200), FlagEngine.DEFAULT_MIN_POLE).state, "旗面不足 4 天");
    }

    @Test
    void 不偷看未来() {
        List<DailyBar> b = shape(FLAG, 1200);
        int t = b.size() - 1;
        FlagEngine.Result a = FlagEngine.evaluate(b, FlagEngine.DEFAULT_MIN_POLE);
        for (int i = 1; i <= 5; i++) b.add(bar(b.get(b.size() - 1).getTradeDate().plusDays(1), 5, 4.9, 5.1, 99999));
        FlagEngine.Result c = FlagEngine.evaluate(b, t, FlagEngine.DEFAULT_MIN_POLE);
        assertEquals(a.state, c.state);
        assertEquals(a.pole, c.pole, 1e-12);
    }
}

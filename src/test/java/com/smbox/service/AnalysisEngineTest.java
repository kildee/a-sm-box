package com.smbox.service;

import com.smbox.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisEngineTest {
    private static DailyBar mk(double c, double v, LocalDate d) {
        return DailyBar.builder()
                .code("TEST").tradeDate(d)
                .open(c * 0.995).close(c).high(c * 1.012).low(c * 0.988)
                .volume(Math.max(1000, Math.round(v))).amount(0)
                .build();
    }

    private static List<DailyBar> syntheticBars() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        double p = 40;
        for (int i = 0; i < 60; i++) { p *= 0.9915; bars.add(mk(p, 80000 - i * 300, d = d.plusDays(1))); }
        for (int i = 0; i < 20; i++) {
            double b = 24 + Math.sin(i / 3.0) * 0.6 - (i == 12 ? 0.7 : 0);
            bars.add(mk(b, 40000, d = d.plusDays(1)));
        }
        p = 24;
        for (int i = 0; i < 50; i++) { p *= 1.005; bars.add(mk(p, 45000 + i * 900, d = d.plusDays(1))); }
        return bars;
    }

    @Test
    void scoreStockMatchesJs() {
        AnalysisEngine.ScoreResult r = AnalysisEngine.scoreStock(syntheticBars());
        assertEquals(30, r.score);
        assertEquals("D档", r.level);
        assertEquals(2, r.tier);
        assertEquals(40, AnalysisEngine.scoreStock(syntheticBars(), 90d).score);
        assertEquals(25, AnalysisEngine.scoreStock(syntheticBars(), 40d).score);
    }

    @Test
    void factCodesMatchJs() {
        assertEquals(java.util.Arrays.asList("MA_BULL", "ABOVE_MA20", "POS_HIGH", "CHAND_ABOVE"),
                AnalysisEngine.scoreStock(syntheticBars()).factCodes());
        assertEquals(java.util.Arrays.asList("MA_BULL", "ABOVE_MA20", "POS_HIGH", "CHAND_ABOVE", "RPS_TOP"),
                AnalysisEngine.scoreStock(syntheticBars(), 90d).factCodes());
        assertEquals(java.util.Arrays.asList("MA_BULL", "ABOVE_MA20", "POS_HIGH", "CHAND_ABOVE", "RPS_WEAK"),
                AnalysisEngine.scoreStock(syntheticBars(), 40d).factCodes());
        AnalysisEngine.ScoreResult veto = AnalysisEngine.scoreStock(syntheticBars().subList(0, 60));
        assertEquals(java.util.Arrays.asList("VETO_BEAR_BELOW_MA20", "POS"), veto.factCodes());
        assertEquals(8, veto.score);
        AnalysisEngine.ScoreResult bk = AnalysisEngine.scoreStock(breakoutBars());
        assertEquals(java.util.Arrays.asList("MA_BULL", "ABOVE_MA20", "GOLDEN_RECENT", "VOL_UP", "BK_NEWHIGH", "POS_HIGH", "CHAND_ABOVE"), bk.factCodes());
        assertEquals(61, bk.score);
        AnalysisEngine.ScoreResult ox = AnalysisEngine.scoreStock(overextBars());
        assertEquals(java.util.Arrays.asList("MA_BULL", "ABOVE_MA20", "POS_HIGH", "CHAND_ABOVE", "OVEREXT"), ox.factCodes());
        assertEquals(22, ox.score);
        // 数据不足只有一个码
        assertEquals(java.util.Collections.singletonList("DATA_SHORT"), AnalysisEngine.scoreStock(syntheticBars().subList(0, 20)).factCodes());
    }


    private static List<DailyBar> breakoutBars() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i <= 66; i++) { double c = 10 + 5.0 * ((double) i / 66.0); bars.add(mk(c, 30000, d = d.plusDays(1))); }
        for (int i = 67; i <= 96; i++) bars.add(mk(14.8, 30000, d = d.plusDays(1)));
        bars.add(mk(14.9, 30000, d = d.plusDays(1)));
        bars.add(mk(14.95, 30000, d = d.plusDays(1)));
        bars.add(mk(16.0, 90000, d = d.plusDays(1)));
        return bars;
    }

    @Test
    void detectBreakoutAndScoreMatchesJs() {
        List<DailyBar> bars = breakoutBars();
        assertEquals("新高突破", AnalysisEngine.detectBreakout(bars));
        assertEquals(61, AnalysisEngine.scoreStock(bars).score);
    }

    private static List<DailyBar> overextBars() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        double p = 10;
        for (int i = 0; i < 90; i++) { p *= 1.002; bars.add(mk(p, 30000, d = d.plusDays(1))); }
        for (int i = 0; i < 8; i++) { p *= 1.055; bars.add(mk(p, 60000 + i * 4000, d = d.plusDays(1))); }
        return bars;
    }

    @Test
    void overextPenaltyMatchesJs() {
        List<DailyBar> bars = overextBars();
        AnalysisEngine.ExpertResult e = AnalysisEngine.expert(bars);
        assertTrue(e.ok);
        assertTrue(e.overext, "该合成K线应判为短线透支");
        assertEquals(22, AnalysisEngine.scoreStock(bars).score);
    }

    private static List<DailyBar> distributionBars() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        double p = 10;
        for (int i = 0; i < 80; i++) { p *= 1.012; bars.add(mk(p, 30000, d = d.plusDays(1))); }
        double last = bars.get(bars.size() - 1).getClose();
        bars.add(DailyBar.builder().code("TEST").tradeDate(d.plusDays(1))
                .open(last * 1.005).close(last * 0.995).high(last * 1.06).low(last * 0.99)
                .volume(120000).amount(0).build());
        return bars;
    }


    @Test
    void chandelierMatchesJs() {
        List<DailyBar> bars = syntheticBars();
        assertEquals(29.0617, AnalysisEngine.chandelierAt(bars, bars.size() - 1), 0.001);
    }

    @Test
    void expertMatchesJs() {
        AnalysisEngine.ExpertResult e = AnalysisEngine.expert(syntheticBars());
        assertTrue(e.ok);
        assertEquals("UPTREND", e.regime);
        assertEquals(68, e.score);
        assertEquals("REVERSAL_LIKELY", e.verdict);
        assertFalse(e.overext);
        assertNotNull(e.r1);
        assertEquals(31.17, e.r1, 0.02);
        assertEquals(30.12, e.s1, 0.02);
        assertEquals(22.57, e.hardStop, 0.02);
        assertNotNull(e.chandelier);
        assertEquals(29.0617, e.chandelier, 0.001);
    }

    @Test
    void shortHistoryRejected() {
        AnalysisEngine.ExpertResult e = AnalysisEngine.expert(syntheticBars().subList(0, 50));
        assertFalse(e.ok);
        AnalysisEngine.ScoreResult s = AnalysisEngine.scoreStock(syntheticBars().subList(0, 20));
        assertEquals("数据不足", s.level);
    }

    private static List<DailyBar> synthShaped(java.util.function.IntToDoubleFunction priceAt) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 130; i++) {
            double p = priceAt.applyAsDouble(i);
            double c = Math.round(p * 1000) / 1000.0;
            bars.add(DailyBar.builder().code("TEST").tradeDate(d.plusDays(i))
                    .open(Math.round(c * 0.997 * 1000) / 1000.0)
                    .high(Math.round(c * 1.010 * 1000) / 1000.0)
                    .low(Math.round(c * 0.990 * 1000) / 1000.0)
                    .close(c).volume(100000).amount(c * 100000 * 100).build());
        }
        return bars;
    }

    @Test
    void regimeRangeUpMatchesJs() {
        double[] p1 = {10};
        List<DailyBar> stair = synthShaped(i -> p1[0] *= ((i + 9) % 20 < 8) ? 1.020 : 0.991);
        AnalysisEngine.ExpertResult r1 = AnalysisEngine.expert(stair);
        assertTrue(r1.ok);
        assertEquals("RANGE_UP", r1.regime, "台阶式上涨应判震荡上升（JS 实测同）");

        double[] p2 = {10};
        List<DailyBar> smooth = synthShaped(i -> p2[0] *= 1.006);
        AnalysisEngine.ExpertResult r2 = AnalysisEngine.expert(smooth);
        assertTrue(r2.ok);
        assertEquals("UPTREND", r2.regime, "平滑上涨仍应判上升趋势");

        List<DailyBar> flat = synthShaped(i -> 10 + Math.sin(i / 5.0) * 0.4);
        AnalysisEngine.ExpertResult r3 = AnalysisEngine.expert(flat);
        assertTrue(r3.ok);
        assertNotEquals("RANGE_UP", r3.regime, "无重心抬升的横盘不该判震荡上升");
        assertEquals("REBOUND", r3.regime, "JS 实测该合成判 REBOUND");
    }

    private static DailyBar cb(double o, double h, double l, double c, long v, LocalDate d) {
        java.util.function.DoubleUnaryOperator r3 = x -> Math.round(x * 1000) / 1000.0;
        return DailyBar.builder().code("TEST").tradeDate(d)
                .open(r3.applyAsDouble(o)).high(r3.applyAsDouble(h))
                .low(r3.applyAsDouble(l)).close(r3.applyAsDouble(c))
                .volume(v).amount(0).build();
    }


    // ===== 平台突破距今天数 =====

    private static List<DailyBar> boxBreakoutBars() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 95; i++) bars.add(mk(10.0, 30000, d = d.plusDays(1)));
        bars.add(mk(11.5, 90000, d = d.plusDays(1)));          // index 95：放量3倍破箱顶
        for (int i = 96; i < 100; i++) bars.add(mk(11.4, 30000, d = d.plusDays(1)));
        return bars;
    }

    private static List<AnalysisEngine.Sig> twoBuysEndingB2() {
        List<AnalysisEngine.Sig> chan = new ArrayList<>();
        chan.add(new AnalysisEngine.Sig(85, "buy", "B1", false));
        chan.add(new AnalysisEngine.Sig(90, "buy", "B2", false));
        return chan;
    }

    @Test
    void 平台突破天数_突破日固定可回看() {
        assertEquals(Integer.valueOf(4),
                AnalysisEngine.platformBreakoutDaysAgo(boxBreakoutBars(), 15, twoBuysEndingB2()));
        assertNull(AnalysisEngine.platformBreakoutDaysAgo(boxBreakoutBars(), 3, twoBuysEndingB2()));
        assertNull(AnalysisEngine.platformBreakoutDaysAgo(boxBreakoutBars(), 15, new ArrayList<>()));
        List<AnalysisEngine.Sig> endB1 = new ArrayList<>();
        endB1.add(new AnalysisEngine.Sig(85, "buy", "B2", false));
        endB1.add(new AnalysisEngine.Sig(90, "buy", "B1", false));
        assertNull(AnalysisEngine.platformBreakoutDaysAgo(boxBreakoutBars(), 15, endB1));
    }

    @Test
    void 平台突破天数_无突破与数据不足返回null() {
        assertNull(AnalysisEngine.platformBreakoutDaysAgo(syntheticBars(), 15));
        assertNull(AnalysisEngine.platformBreakoutDaysAgo(syntheticBars().subList(0, 50), 15));   // <70根
    }
}

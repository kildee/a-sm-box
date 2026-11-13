package com.smbox.service;

import com.smbox.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BottomTurnEngineTest {
    static List<DailyBar> shape(double bottomVol, double recentVol, double[] tailCloses) {
        List<DailyBar> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 130; i++) {
            double c;
            long v = 1000;
            if (i < 80) c = 10.0;
            else if (i <= 123) c = 10.0 - (10.0 - 8.0) * (i - 79) / 44.0;
            else c = tailCloses[i - 124];
            double low = i == 123 ? 7.9 : (i < 80 ? 9.8 : (i > 123 ? c - 0.05 : c - 0.1));
            double high = i < 80 ? 10.2 : c + 0.05;
            if (i >= 113 && i <= 122) v = (long) bottomVol;
            if (i >= 127) v = (long) recentVol;
            out.add(DailyBar.builder().code("600001").tradeDate(d.plusDays(i))
                    .open(c).close(c).high(high).low(low).volume(v).amount(c * v * 100).build());
        }
        return out;
    }

    static final double[] UP = {8.05, 8.1, 8.2, 8.35, 8.5, 8.65};

    static BottomTurnEngine.Result eval(List<DailyBar> bars, Boolean ox) {
        return BottomTurnEngine.evaluate(bars, ox, BottomTurnEngine.DEFAULT);
    }

    static void onlyFails(BottomTurnEngine.Result r, int cond) {
        assertTrue(r.evaluable, r.skip);
        for (int i = 0; i < 7; i++) assertEquals(i != cond, r.cond[i], "C" + (i + 1));
        assertFalse(r.hit);
    }

    @Test
    void 标准形态全部命中_字段口径() {
        BottomTurnEngine.Result r = eval(shape(1000, 2000, UP), false);
        assertTrue(r.evaluable);
        assertTrue(r.hit, java.util.Arrays.toString(r.cond));
        assertEquals(6, r.daysSince);
        assertEquals(22.55, BottomTurnEngine.pctOf(r.drawdown), 0.01);
        assertEquals(9.49, BottomTurnEngine.pctOf(r.rebound), 0.01);
        assertEquals(2.0, r.volRatio, 1e-9);
        assertEquals(0.33, BottomTurnEngine.round2(r.pos), 1e-9);
        assertEquals(true, r.toMap().get("hit"));
    }

    @Test
    void C1_回撤不够() {
        List<DailyBar> b = shape(1000, 2000, new double[]{9.05, 9.1, 9.2, 9.35, 9.5, 9.6});
        for (int i = 80; i <= 123; i++) {
            DailyBar x = b.get(i);
            double c = 10.0 - (10.0 - 9.0) * (i - 79) / 44.0;
            x.setClose(c); x.setOpen(c); x.setHigh(c + 0.05); x.setLow(i == 123 ? 8.9 : c - 0.1);
        }
        BottomTurnEngine.Result r = eval(b, false);
        assertFalse(r.cond[0]);
        assertFalse(r.hit);
    }

    @Test
    void C2_底部太近或太远() {
        List<DailyBar> b = shape(1000, 2000, UP);
        b.get(127).setLow(7.85);   // 新低挪到 2 天前
        BottomTurnEngine.Result r = eval(b, false);
        assertEquals(2, r.daysSince);
        assertFalse(r.cond[1]);
        assertFalse(r.hit);
    }

    @Test
    void C3_反弹太多或连涨不够() {
        BottomTurnEngine.Result big = eval(shape(1000, 2000, new double[]{8.3, 8.6, 8.9, 9.2, 9.5, 9.6}), false);
        assertFalse(big.cond[2]);
        assertFalse(big.hit);
        BottomTurnEngine.Result r = eval(shape(1000, 2000, new double[]{8.05, 8.3, 8.6, 8.55, 8.5, 8.65}), false);
        assertEquals(1, r.upDays);
        assertFalse(r.cond[2]);
        assertFalse(r.hit);
    }

    @Test
    void C4_没放量() {
        onlyFails(eval(shape(1000, 1200, UP), false), 3);   // 1.2 < 1.3
    }

    @Test
    void C5_没站上均线() {
        BottomTurnEngine.Result r = eval(shape(1000, 2000, new double[]{8.05, 8.4, 8.6, 8.45, 8.55, 8.42}), false);
        assertFalse(r.cond[4]);
        assertFalse(r.hit);
    }

    @Test
    void C6_位置太高() {
        List<DailyBar> b = shape(1000, 2000, new double[]{8.7, 8.9, 9.1, 9.3, 9.5, 9.75});
        for (int i = 80; i <= 123; i++) {
            DailyBar x = b.get(i);
            double c = 10.0 - (10.0 - 8.6) * (i - 79) / 44.0;
            x.setClose(c); x.setOpen(c); x.setHigh(c + 0.05); x.setLow(i == 123 ? 8.5 : c - 0.1);
        }
        BottomTurnEngine.Result r = eval(b, false);
        assertEquals(0.74, BottomTurnEngine.round2(r.pos), 0.01);
        onlyFails(r, 5);
    }

    @Test
    void C7_透支或算不出都不命中() {
        onlyFails(eval(shape(1000, 2000, UP), true), 6);
        onlyFails(eval(shape(1000, 2000, UP), null), 6);
    }

    @Test
    void 数据不足_不可算() {
        List<DailyBar> b = shape(1000, 2000, UP).subList(20, 130);   // 110 根 < 120
        BottomTurnEngine.Result r = eval(b, false);
        assertFalse(r.evaluable);
        assertFalse(r.hit);
        assertEquals(false, r.toMap().get("evaluable"));
    }

    @Test
    void 指定t只看到t为止_不偷看未来() {
        List<DailyBar> b = shape(1000, 2000, UP);
        BottomTurnEngine.Result whole = eval(b, false);
        List<DailyBar> longer = new ArrayList<>(b);
        LocalDate d = b.get(129).getTradeDate();
        for (int i = 1; i <= 15; i++) {
            longer.add(DailyBar.builder().code("600001").tradeDate(d.plusDays(i)).open(5).close(5).high(5.1).low(4.9).volume(9000).build());
        }
        BottomTurnEngine.Result atT = BottomTurnEngine.evaluate(longer, 129, false, BottomTurnEngine.DEFAULT);
        assertEquals(whole.hit, atT.hit);
        assertEquals(whole.rebound, atT.rebound, 1e-12);
        assertEquals(whole.volRatio, atT.volRatio, 1e-12);
    }

    @Test
    void 主板口径() {
        assertTrue(BottomTurnEngine.isMainBoard("600001"));
        assertTrue(BottomTurnEngine.isMainBoard("002594"));
        assertFalse(BottomTurnEngine.isMainBoard("300750"));
        assertFalse(BottomTurnEngine.isMainBoard("688001"));
        assertFalse(BottomTurnEngine.isMainBoard("920001"));
    }

    @Test
    void 参数副本互不影响() {
        BottomTurnEngine.Params p = BottomTurnEngine.DEFAULT.copy();
        p.minVolRatio = 2.5;
        assertEquals(1.3, BottomTurnEngine.DEFAULT.minVolRatio, 1e-12);
        assertFalse(BottomTurnEngine.evaluate(shape(1000, 2000, UP), false, p).cond[3]);
    }
}

package com.smbox.service;

import com.smbox.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OversoldEngineTest {
    static List<DailyBar> shape() {
        List<DailyBar> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 130; i++) {
            double c = i < 100 ? 10 : 10 - 0.06 * (i - 99);
            out.add(DailyBar.builder().code("600001").tradeDate(d.plusDays(i)).open(c + 0.03).close(c)
                    .high(c + 0.05).low(c - 0.05).volume(500000).amount(5e8).build());
        }
        return out;
    }

    @Test
    void 标准形态命中() {
        OversoldEngine.Result r = OversoldEngine.evaluate(shape(), false);
        assertTrue(r.evaluable);
        assertTrue(r.hit, java.util.Arrays.toString(r.cond));
        assertEquals(-12.77, OversoldEngine.pct(r.ret20), 0.01);
        assertTrue(r.distMa20 < -0.055);
        assertTrue(r.ret5 < 0);
    }

    @Test
    void 每个条件单独破坏() {
        assertFalse(OversoldEngine.evaluate(shape(), true).cond[4], "透支");
        assertFalse(OversoldEngine.evaluate(shape(), null).hit, "透支算不出也不命中");
        List<DailyBar> thin = shape();
        for (DailyBar b : thin) b.setAmount(1e8);
        OversoldEngine.Result r = OversoldEngine.evaluate(thin, false);
        assertFalse(r.cond[5]);
        assertFalse(r.hit);
        List<DailyBar> wild = shape();
        for (int i = 115; i < 130; i++) { DailyBar b = wild.get(i); b.setHigh(b.getClose() * 1.06); b.setLow(b.getClose() * 0.94); }
        r = OversoldEngine.evaluate(wild, false);
        assertFalse(r.cond[3], "ATR 过热");
        assertTrue(r.cond[0] && r.cond[1]);
        List<DailyBar> pop = shape();
        pop.get(129).setClose(pop.get(124).getClose() * 1.06);   // 5 日已冲高 6%
        assertFalse(OversoldEngine.evaluate(pop, false).cond[2]);
        List<DailyBar> mild = shape();
        for (int i = 100; i < 130; i++) mild.get(i).setClose(10 - 0.02 * (i - 99));   // 只跌 4%
        r = OversoldEngine.evaluate(mild, false);
        assertFalse(r.cond[0]);
        assertFalse(r.hit);
        assertFalse(OversoldEngine.evaluate(shape().subList(20, 130), false).evaluable, "不足 121 根");
    }

    @Test
    void 不偷看未来() {
        List<DailyBar> b = new ArrayList<>(shape());
        LocalDate d = b.get(129).getTradeDate();
        for (int i = 1; i <= 10; i++) b.add(DailyBar.builder().code("600001").tradeDate(d.plusDays(i)).open(20).close(20).high(21).low(19).volume(1).amount(1).build());
        OversoldEngine.Result a = OversoldEngine.evaluate(b, 129, false), w = OversoldEngine.evaluate(shape(), false);
        assertEquals(w.hit, a.hit);
        assertEquals(w.ret20, a.ret20, 1e-12);
        assertEquals(w.atrPct, a.atrPct, 1e-12);
    }
}

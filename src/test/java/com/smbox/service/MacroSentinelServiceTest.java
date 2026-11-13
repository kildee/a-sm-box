package com.smbox.service;

import com.smbox.model.Quote;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MacroSentinelServiceTest {
    private static Quote q(String secid, double pct) {
        return Quote.builder().code(secid).name(secid).changePct(pct).price(100).build();
    }

    private static Map<String, Quote> quotes(Object... pairs) {
        Map<String, Quote> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], q((String) pairs[i], (Double) pairs[i + 1]));
        }
        return m;
    }

    @Test
    void 单指标过高危线_直接判高() {
        Map<String, Object> r = MacroSentinelService.evaluate(quotes(
                "100.NDX", -2.5, "100.SPX", -0.3, "100.DJIA", -0.2,
                "100.UDI", 0.1, "133.USDCNH", 0.05, "104.CN00Y", -0.4));
        assertEquals("高", r.get("level"));
        assertTrue(((List<?>) r.get("triggers")).toString().contains("纳斯达克"));
    }

    @Test
    void 两个中危叠加_升级为高() {
        Map<String, Object> r = MacroSentinelService.evaluate(quotes(
                "100.NDX", -0.8, "100.SPX", -1.4, "100.DJIA", -0.5,
                "100.UDI", 0.2, "133.USDCNH", 0.6, "104.CN00Y", -0.9));
        assertEquals("高", r.get("level"));
    }

    @Test
    void 单个中危_判中() {
        Map<String, Object> r = MacroSentinelService.evaluate(quotes(
                "100.NDX", -1.3, "100.SPX", -0.9, "100.DJIA", -0.5,
                "100.UDI", 0.2, "133.USDCNH", 0.1, "104.CN00Y", -0.8));
        assertEquals("中", r.get("level"));
    }

    @Test
    void 方向语义_美元大跌人民币升值不是风险() {
        Map<String, Object> r = MacroSentinelService.evaluate(quotes(
                "100.NDX", 0.5, "100.SPX", 0.3, "100.DJIA", 0.2,
                "100.UDI", -1.8, "133.USDCNH", -0.9, "104.CN00Y", 0.6));
        assertEquals("低", r.get("level"));
        assertTrue(((List<?>) r.get("triggers")).isEmpty());
    }

    @Test
    void 缺数不算风险_但要如实标注() {
        Map<String, Object> r = MacroSentinelService.evaluate(quotes(
                "100.NDX", -0.2, "100.SPX", 0.1, "100.DJIA", 0.0));
        assertEquals("低", r.get("level"));
        List<?> missing = (List<?>) r.get("missing");
        assertEquals(3, missing.size());
        assertTrue(missing.toString().contains("A50期指"));
    }
}

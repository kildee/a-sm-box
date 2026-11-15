package com.smbox.service;

import com.smbox.model.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimStrategyCTest {
    @Test
    void 三条卖出规则与边界() {
        // 买入当天（第1天）无论涨跌都不卖
        assertNull(SimService.cExitReason(12.0, 10.0, 1));
        assertNull(SimService.cExitReason(8.0, 10.0, 1));
        assertTrue(SimService.cExitReason(11.5, 10.0, 2).contains("止盈"));
        assertNull(SimService.cExitReason(11.49, 10.0, 2));
        assertTrue(SimService.cExitReason(9.0, 10.0, 5).contains("止损"));
        assertNull(SimService.cExitReason(9.01, 10.0, 5));
        assertNull(SimService.cExitReason(10.3, 10.0, 9));
        assertTrue(SimService.cExitReason(10.3, 10.0, 10).contains("第10个交易日到期"));
        assertTrue(SimService.cExitReason(9.5, 10.0, 12).contains("到期"), "停牌顺延超过10天的，复牌当天卖");
        assertTrue(SimService.cExitReason(11.6, 10.0, 10).contains("止盈"));
    }

    @Test
    void 持有天数按该股K线计_买入当天算1_停牌日不计() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 10, 12);   // 周一
        for (int i = 0; i < 12; i++) {
            if (d.getDayOfWeek().getValue() >= 6) d = d.plusDays(8 - d.getDayOfWeek().getValue());
            if (!d.equals(LocalDate.of(2026, 10, 15))) {   // 10-15 停牌
                bars.add(DailyBar.builder().code("T").tradeDate(d).open(10).high(10).low(10).close(10).volume(1).amount(0).build());
            }
            d = d.plusDays(1);
        }
        LocalDate opened = LocalDate.of(2026, 10, 13);
        assertEquals(1, SimService.heldTradingDays(bars, opened, opened), "买入当天=第1天");
        assertEquals(2, SimService.heldTradingDays(bars, opened, LocalDate.of(2026, 10, 14)));
        assertEquals(3, SimService.heldTradingDays(bars, opened, LocalDate.of(2026, 10, 16)), "10-15 停牌不计");
        assertEquals(5, SimService.heldTradingDays(bars, opened, LocalDate.of(2026, 10, 20)), "周末不计");
    }
}

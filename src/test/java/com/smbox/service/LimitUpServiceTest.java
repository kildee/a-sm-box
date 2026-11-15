package com.smbox.service;

import com.smbox.datasource.EastmoneyClient.PoolItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LimitUpServiceTest {
    // ---- 分档规则：顺序即优先级 ----

    @Test
    void 冰点_涨停稀少或跌停反超() {
        assertEquals("冰点", LimitUpService.evaluate(15, 3, 20, 3, 1.0)[0]);   // 涨停<20
        assertEquals("冰点", LimitUpService.evaluate(30, 35, 20, 3, 1.0)[0]);  // 跌停≥涨停
        assertEquals("冰点", LimitUpService.evaluate(10, 2, 60, 2, -3.0)[0]);
    }

    @Test
    void 退潮_炸板过半或昨涨停大面() {
        assertEquals("退潮", LimitUpService.evaluate(60, 5, 55, 4, 1.0)[0]);   // 炸板率≥50
        assertEquals("退潮", LimitUpService.evaluate(60, 5, 20, 4, -2.5)[0]);  // 昨溢价≤-2
    }

    @Test
    void 亢奋与回暖() {
        assertEquals("亢奋", LimitUpService.evaluate(90, 3, 25, 6, 2.0)[0]);
        assertEquals("回暖", LimitUpService.evaluate(90, 3, 25, 4, 2.0)[0]);
        assertEquals("回暖", LimitUpService.evaluate(55, 5, 35, 3, 0.5)[0]);
    }

    @Test
    void 分歧_默认档() {
        assertEquals("分歧", LimitUpService.evaluate(35, 8, 45, 3, 0.5)[0]);
    }

    @Test
    void 缺数语义_昨溢价拉不到时不触发退潮也不挡回暖() {
        assertEquals("回暖", LimitUpService.evaluate(60, 5, 20, 3, null)[0]);
        // 但炸板率≥50 的退潮判定不受缺数影响
        assertEquals("退潮", LimitUpService.evaluate(60, 5, 55, 3, null)[0]);
    }

    // ---- zdp 尺度探测 ----

    private static List<PoolItem> pool(double... zdps) {
        List<PoolItem> out = new ArrayList<>();
        for (double z : zdps) {
            PoolItem p = new PoolItem();
            p.zdp = z;
            out.add(p);
        }
        return out;
    }

    @Test
    void 尺度探测_原值与百倍值都能认出() {
        assertEquals(1, LimitUpService.detectScale(pool(10.02, 9.98, 20.01, 10.0)), 0.001);
        assertEquals(100, LimitUpService.detectScale(pool(1002, 998, 2001, 1000)), 0.001);
        assertEquals(1, LimitUpService.detectScale(pool()), 0.001);
    }
}

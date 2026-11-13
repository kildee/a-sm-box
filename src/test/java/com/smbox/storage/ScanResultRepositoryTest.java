package com.smbox.storage;

import com.smbox.service.BottomTurnEngine;
import com.smbox.service.FlagEngine;
import com.smbox.service.OversoldEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanResultRepositoryTest {
    private EmbeddedDatabase db;
    private ScanResultRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("scanrepotest;MODE=MySQL;DATABASE_TO_UPPER=FALSE").addScript("schema.sql").build();
        repo = new ScanResultRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() { if (db != null) db.shutdown(); }

    private static Map<String, Object> item(String code, String name, int score, int tier,
                                            Object bLabel, Integer bDaysAgo, Object xLabel, Integer xDays,
                                            Integer bCount, String regime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("name", name); m.put("score", score); m.put("tier", tier);
        m.put("bLabel", bLabel); m.put("bDaysAgo", bDaysAgo); m.put("xLabel", xLabel); m.put("xDays", xDays);
        m.put("bCount", bCount); m.put("regime", regime);
        m.put("vRatio", 1.6);
        return m;
    }

    private List<ScanResultRepository.Row> rows() {
        Map<String, Object> body = new HashMap<>();
        body.put("items", Arrays.asList(
                item("600001", "甲", 90, 5, "B2", 1, "金叉", 0, 3, "UPTREND"),
                item("600002", "乙", 80, 5, "B3", 6, "金叉", 8, 2, "RANGE_UP"),
                item("600003", "丙", 60, 4, "B1", 3, "死叉", 2, 1, "RANGE"),
                item("600004", "丁", 40, 3, null, null, null, null, null, "DOWNTREND"),
                item("600005", "戊", -1, 0, "X9", 1, "B", 1, null, null)));
        return ScanResultRepository.parseRows(body, mapper);
    }

    private List<String> codes(ScanResultRepository.Filter f) {
        List<String> out = new ArrayList<>();
        for (String p : repo.findPage("g", f, 0, 50)) {
            int i = p.indexOf("\"code\":\"");
            out.add(p.substring(i + 8, p.indexOf('"', i + 8)));
        }
        return out;
    }

    private static ScanResultRepository.Filter f() { return new ScanResultRepository.Filter(); }

    @Test
    void 不带条件按分数倒排返回全部() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        assertEquals(Arrays.asList("600001", "600002", "600003", "600004", "600005"), codes(f()));
        assertEquals(Arrays.asList("600001", "600002", "600003", "600004", "600005"), codes(null));
    }

    @Test
    void B点_级别与天数() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.bLabel = "B2";
        assertEquals(Collections.singletonList("600001"), codes(x));
        x = f(); x.bDays = 3;
        assertEquals(Arrays.asList("600001", "600003"), codes(x), "只给天数=任一级别在 N 天内；没有B点的不命中");
        x = f(); x.bLabel = "B3"; x.bDays = 3;
        assertTrue(codes(x).isEmpty(), "B3 在 6 天前，不在 3 天内");
        x.bDays = 6;
        assertEquals(Collections.singletonList("600002"), codes(x));
    }

    @Test
    void 均线交叉_类型与天数_0表示今天() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.xLabel = "金叉";
        assertEquals(Arrays.asList("600001", "600002"), codes(x));
        x.xDays = 0;
        assertEquals(Collections.singletonList("600001"), codes(x));
        x = f(); x.xLabel = "死叉"; x.xDays = 5;
        assertEquals(Collections.singletonList("600003"), codes(x));
        x = f(); x.xDays = 3;
        assertEquals(Arrays.asList("600001", "600003"), codes(x), "只给天数=任一交叉在 N 天内");
    }

    @Test
    void 脏值不进筛选列() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.bDays = 60;
        assertFalse(codes(x).contains("600005"), "bLabel=X9 不认");
        x = f(); x.xDays = 60;
        assertFalse(codes(x).contains("600005"), "xLabel=B 不认");
        x = f(); x.bLabel = "DROP TABLE"; x.xLabel = "乱填";
        assertEquals(5, codes(x).size(), "筛选参数是脏值时当没传");
    }

    @Test
    void 连续买点是下限_没算出来的不命中() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.bCount = 2;
        assertEquals(Arrays.asList("600001", "600002"), codes(x));
        x.bCount = 1;
        assertEquals(Arrays.asList("600001", "600002", "600003"), codes(x));
        x.bCount = 4;
        assertTrue(codes(x).isEmpty());
    }

    @Test
    void 趋势_任一上升合并两档() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.regime = "UPTREND";
        assertEquals(Collections.singletonList("600001"), codes(x));
        x.regime = "UP_ANY";
        assertEquals(Arrays.asList("600001", "600002"), codes(x));
        x.regime = "DOWNTREND";
        assertEquals(Collections.singletonList("600004"), codes(x));
    }

    @Test
    void 条件之间是AND_搜索按名称或代码() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        ScanResultRepository.Filter x = f();
        x.bDays = 10; x.xLabel = "金叉"; x.xDays = 10; x.bCount = 2; x.regime = "UP_ANY";
        assertEquals(Arrays.asList("600001", "600002"), codes(x));
        x.bLabel = "B3";
        assertEquals(Collections.singletonList("600002"), codes(x));
        x.regime = "RANGE";
        assertTrue(codes(x).isEmpty());

        ScanResultRepository.Filter k = f();
        k.kw = "乙";
        assertEquals(Collections.singletonList("600002"), codes(k));
        k.kw = "60000";
        assertEquals(5, codes(k).size());
    }

    @Test
    void 分页_统计_导出与列表同口径() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        assertEquals(2, repo.findPage("g", f(), 0, 2).size());
        assertEquals(1, repo.findPage("g", f(), 4, 2).size());

        Map<String, Object> all = repo.stats("g", f());
        assertEquals(5, ((Number) all.get("total")).intValue());
        assertEquals(2, ((Number) all.get("strong")).intValue());
        assertEquals(1, ((Number) all.get("build")).intValue());

        ScanResultRepository.Filter x = f();
        x.regime = "UP_ANY";
        assertEquals(2, ((Number) repo.stats("g", x).get("total")).intValue());
        List<Map<String, Object>> exp = repo.exportCodeName("g", x);
        assertEquals(2, exp.size());
        assertEquals("600001", exp.get(0).get("code"));
        assertEquals("甲", exp.get(0).get("name"));
    }

    @Test
    void 分组之间互不影响_重扫先清再写() {
        repo.upsertBatch("g", rows(), LocalDateTime.now());
        repo.upsertBatch("other", rows().subList(0, 1), LocalDateTime.now());
        assertEquals(1, codes2("other").size());
        repo.deleteGroup("g");
        assertTrue(codes(f()).isEmpty());
        assertEquals(1, codes2("other").size(), "删一个分组的结果不动别的分组");
    }

    private List<String> codes2(String group) { return repo.findPage(group, f(), 0, 50); }

    @Test
    void payload原样落库_三组标签只从徽章接口出来() {
        List<ScanResultRepository.Row> rows = rows();
        String before = rows.get(0).payload;

        BottomTurnEngine.Result bt = new BottomTurnEngine.Result();
        bt.evaluable = true; bt.hit = true;
        bt.rebound = 0.0951; bt.daysSince = 6; bt.volRatio = 1.876; bt.pos = 0.3333; bt.drawdown = 0.2255;
        rows.get(0).bt = bt;
        BottomTurnEngine.Result miss = new BottomTurnEngine.Result();
        miss.evaluable = true; miss.hit = false;
        rows.get(1).bt = miss;

        OversoldEngine.Result os = new OversoldEngine.Result();
        os.evaluable = true; os.hit = true; os.ret20 = -0.1234; os.distMa20 = -0.0712;
        rows.get(1).os = os;

        FlagEngine.Result fl = new FlagEngine.Result();
        fl.state = "BREAKOUT"; fl.pole = 0.1829; fl.flagDays = 6;
        rows.get(2).flag = fl;

        repo.upsertBatch("g", rows, LocalDateTime.now());

        assertEquals(before, repo.findPage("g", f(), 0, 1).get(0), "payload 逐字节不变");
        assertTrue(before.contains("\"vRatio\":1.6"), "非筛选字段留在 payload 里");
        assertFalse(before.contains("bt_") || before.contains("oversold") || before.contains("flag"), "标签不进 payload");

        List<String> all = Arrays.asList("600001", "600002", "600003", "600004");
        Map<String, Map<String, Object>> b = repo.bottomTurnOf("g", all);
        assertEquals(Collections.singleton("600001"), b.keySet(), "只回命中的，没命中的 600002 不出现");
        assertEquals(9.51, ((Number) b.get("600001").get("rebound")).doubleValue(), 1e-9);
        assertEquals(6, ((Number) b.get("600001").get("daysSince")).intValue());
        assertEquals(22.55, ((Number) b.get("600001").get("drawdown")).doubleValue(), 1e-9);

        Map<String, Map<String, Object>> o = repo.oversoldOf("g", all);
        assertEquals(Collections.singleton("600002"), o.keySet());
        assertEquals(-12.34, ((Number) o.get("600002").get("ret20")).doubleValue(), 1e-9);
        assertEquals(-7.12, ((Number) o.get("600002").get("distMa20")).doubleValue(), 1e-9);

        Map<String, Map<String, Object>> g = repo.flagOf("g", all);
        assertEquals(Collections.singleton("600003"), g.keySet());
        assertEquals("BREAKOUT", g.get("600003").get("state"));
        assertEquals(18.29, ((Number) g.get("600003").get("pole")).doubleValue(), 1e-9);
        assertEquals(6, ((Number) g.get("600003").get("days")).intValue());
    }
}

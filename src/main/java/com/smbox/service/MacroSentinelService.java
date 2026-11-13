package com.smbox.service;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.model.Quote;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MacroSentinelService {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private final EastmoneyClient eastmoney;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    static final class Sentinel {
        final String secid, name;
        final boolean dirUp;
        final double mid, high;
        Sentinel(String secid, String name, boolean dirUp, double mid, double high) {
            this.secid = secid; this.name = name; this.dirUp = dirUp; this.mid = mid; this.high = high;
        }
    }

    static final List<Sentinel> SENTINELS = java.util.Arrays.asList(
            new Sentinel("100.NDX", "纳斯达克", false, -1.2, -2.0),
            new Sentinel("100.SPX", "标普500", false, -1.2, -2.0),
            new Sentinel("100.DJIA", "道琼斯", false, -1.2, -2.0),
            new Sentinel("100.UDI", "美元指数", true, 0.8, 1.5),
            new Sentinel("133.USDCNH", "离岸人民币", true, 0.5, 1.0),
            new Sentinel("104.CN00Y", "A50期指", false, -1.5, -2.5)
    );

    public synchronized Map<String, Object> run() {
        List<String> secids = new ArrayList<>();
        for (Sentinel s : SENTINELS) secids.add(s.secid);
        List<Quote> quotes = eastmoney.fetchGlobalQuotes(secids);
        Map<String, Quote> bySecid = new LinkedHashMap<>();
        for (Quote q : quotes) bySecid.put(q.getCode(), q);

        Map<String, Object> result = evaluate(bySecid);
        LocalDateTime now = LocalDateTime.now(SH);
        result.put("checkedAt", now.toString());

        try {
            String payload = mapper.writeValueAsString(result);
            jdbc.update("MERGE INTO macro_sentinel(trade_date, level, payload, created_at) KEY(trade_date) VALUES (?,?,?,?)",
                    java.sql.Date.valueOf(now.toLocalDate()), String.valueOf(result.get("level")),
                    payload, Timestamp.valueOf(now));
            jdbc.update("DELETE FROM macro_sentinel WHERE trade_date < ?",
                    java.sql.Date.valueOf(now.toLocalDate().minusDays(120)));
        } catch (Exception e) {
            log.warn("盘前哨兵落库失败（不影响本次结果）: {}", e.getMessage());
        }

        String level = String.valueOf(result.get("level"));
        log.info("盘前哨兵：等级={}，触发 {} 条，缺数 {} 个", level,
                ((List<?>) result.get("triggers")).size(), ((List<?>) result.get("missing")).size());
        return result;
    }

    static Map<String, Object> evaluate(Map<String, Quote> bySecid) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> triggers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int midCount = 0;
        boolean anyHigh = false;

        for (Sentinel s : SENTINELS) {
            Quote q = bySecid.get(s.secid);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("secid", s.secid);
            it.put("name", s.name);
            if (q == null) {
                it.put("ok", false);
                items.add(it);
                missing.add(s.name);
                continue;
            }
            double pct = q.getChangePct();
            it.put("ok", true);
            it.put("pct", pct);
            it.put("price", q.getPrice());
            String hit = null;
            if (s.dirUp) {
                if (pct >= s.high) { hit = "高"; }
                else if (pct >= s.mid) { hit = "中"; }
            } else {
                if (pct <= s.high) { hit = "高"; }
                else if (pct <= s.mid) { hit = "中"; }
            }
            it.put("hit", hit);
            if ("高".equals(hit)) { anyHigh = true; triggers.add(s.name + " " + fmtPct(pct) + "（高危）"); }
            else if ("中".equals(hit)) { midCount++; triggers.add(s.name + " " + fmtPct(pct)); }
            items.add(it);
        }

        String level = anyHigh || midCount >= 2 ? "高" : midCount == 1 ? "中" : "低";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("items", items);
        out.put("triggers", triggers);
        out.put("missing", missing);
        out.put("advice", advice(level));
        return out;
    }

    private static String advice(String level) {
        switch (level) {
            case "高": return "隔夜外围明显走弱。";
            case "中": return "隔夜外围偏弱。";
            default:   return "隔夜外围平稳。";
        }
    }

    private static String fmtPct(double v) {
        return (v > 0 ? "+" : "") + String.format("%.2f", v) + "%";
    }

    public Map<String, Object> latest() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trade_date, level, payload FROM macro_sentinel ORDER BY trade_date DESC LIMIT 1");
            if (rows.isEmpty()) return java.util.Collections.emptyMap();
            Map<String, Object> row = rows.get(0);
            Map<String, Object> out = mapper.readValue(String.valueOf(row.get("payload")), Map.class);
            out.put("tradeDate", String.valueOf(row.get("trade_date")));
            return out;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }
}

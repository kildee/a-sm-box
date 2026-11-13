package com.smbox.service;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.datasource.EastmoneyClient.LimitUpPools;
import com.smbox.datasource.EastmoneyClient.PoolItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitUpService {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private final EastmoneyClient eastmoney;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public synchronized Map<String, Object> run() {
        return runEastmoney(LocalDate.now(SH), "东财四池");
    }

    private Map<String, Object> runEastmoney(LocalDate today, String source) {
        LimitUpPools pools = eastmoney.fetchLimitUpPools(today);
        Map<String, Object> out = new LinkedHashMap<>();
        if (pools.zt.isEmpty() && pools.zb.isEmpty() && pools.dt.isEmpty()) {
            out.put("ok", false);
            out.put("message", "涨停池无数据（非交易日或数据未出），未存档");
            return out;
        }
        double scale = detectScale(pools.zt);
        int zt = pools.zt.size(), zb = pools.zb.size(), dt = pools.dt.size();
        double zbRate = (zt + zb) == 0 ? 0 : zb * 100.0 / (zt + zb);
        int maxLbc = 0, lb2 = 0;
        for (PoolItem p : pools.zt) {
            maxLbc = Math.max(maxLbc, p.lbc);
            if (p.lbc >= 2) lb2++;
        }
        Double yPrem = null, yRed = null;
        if (!pools.yzt.isEmpty()) {
            double sum = 0; int n = 0, red = 0;
            for (PoolItem p : pools.yzt) {
                if (Double.isNaN(p.zdp)) continue;
                double v = p.zdp / scale;
                sum += v; n++;
                if (v > 0) red++;
            }
            if (n > 0) { yPrem = sum / n; yRed = red * 100.0 / n; }
        }
        String[] lv = evaluate(zt, dt, zbRate, maxLbc, yPrem);
        List<PoolItem> byLbc = new ArrayList<>(pools.zt);
        byLbc.sort(Comparator.comparingInt((PoolItem p) -> p.lbc).reversed());
        List<Map<String, Object>> ladder = new ArrayList<>();
        for (int i = 0; i < Math.min(5, byLbc.size()); i++) {
            if (byLbc.get(i).lbc < 2) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", byLbc.get(i).name);
            m.put("lbc", byLbc.get(i).lbc);
            ladder.add(m);
        }
        Map<String, Integer> indCount = new LinkedHashMap<>();
        for (PoolItem p : pools.zt) {
            if (p.industry == null || p.industry.isEmpty()) continue;
            indCount.merge(p.industry, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> inds = new ArrayList<>(indCount.entrySet());
        inds.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> topInds = new ArrayList<>();
        for (int i = 0; i < Math.min(3, inds.size()); i++) topInds.add(inds.get(i).getKey() + "×" + inds.get(i).getValue());
        fill(out, today, lv, zt, dt, zb, zbRate, maxLbc, lb2, yPrem, yRed, ladder, topInds,
                yPrem == null ? "昨日涨停池缺数（溢价维度未参与评级）" : null, source);
        persist(out, today, lv, zt, dt, zb, zbRate, maxLbc, lb2, yPrem, yRed);
        log.info("涨停情绪：{} 涨停{} 跌停{} 炸板率{}% 高度{} 昨溢价{}", lv[0], zt, dt,
                round1(zbRate), maxLbc, yPrem == null ? "缺" : round1(yPrem));
        return out;
    }

    // ===== 公共 =====

    private void fill(Map<String, Object> out, LocalDate date, String[] lv, int zt, int dt, int zb, double zbRate,
                      int maxLbc, int lb2, Double yPrem, Double yRed, List<Map<String, Object>> ladder,
                      List<String> topInds, String missing, String source) {
        out.put("ok", true);
        out.put("tradeDate", date.toString());
        out.put("level", lv[0]);
        out.put("note", lv[1]);
        out.put("ztCount", zt);
        out.put("dtCount", dt);
        out.put("zbCount", zb);
        out.put("zbRate", round1(zbRate));
        out.put("maxLbc", maxLbc);
        out.put("lb2Count", lb2);
        out.put("yPrem", yPrem == null ? null : round1(yPrem));
        out.put("yRedRate", yRed == null ? null : round1(yRed));
        out.put("ladder", ladder);
        out.put("topIndustries", topInds);
        out.put("source", source);
        if (missing != null) out.put("missing", missing);
        boolean isToday = date.equals(LocalDate.now(SH));
        out.put("timing", isToday && LocalDateTime.now(SH).getHour() < 15 ? "盘中快照，收盘后会变" : "收盘定型数据");
    }

    private void persist(Map<String, Object> out, LocalDate date, String[] lv, int zt, int dt, int zb, double zbRate,
                         int maxLbc, int lb2, Double yPrem, Double yRed) {
        try {
            String payload = mapper.writeValueAsString(out);
            jdbc.update("MERGE INTO limitup_emotion(trade_date, level, zt_count, dt_count, zb_count, " +
                            "zb_rate, max_lbc, lb2_count, y_prem, y_red_rate, payload, created_at) " +
                            "KEY(trade_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Date.valueOf(date), lv[0], zt, dt, zb, round1(zbRate), maxLbc, lb2,
                    yPrem == null ? null : round1(yPrem), yRed == null ? null : round1(yRed),
                    payload, Timestamp.valueOf(LocalDateTime.now(SH)));
            jdbc.update("DELETE FROM limitup_emotion WHERE trade_date < ?",
                    java.sql.Date.valueOf(LocalDate.now(SH).minusDays(120)));
        } catch (Exception e) {
            log.warn("涨停情绪落库失败（不影响本次结果）: {}", e.getMessage());
        }
    }

    static String[] evaluate(int zt, int dt, double zbRatePct, int maxLbc, Double yPrem) {
        if (zt < 20 || dt >= Math.max(1, zt)) {
            return new String[]{"冰点", "涨停稀少或跌停不少于涨停"};
        }
        if (zbRatePct >= 50 || (yPrem != null && yPrem <= -2)) {
            return new String[]{"退潮", "炸板过半或昨日涨停今日明显走弱"};
        }
        if (zt >= 80 && zbRatePct < 30 && maxLbc >= 5) {
            return new String[]{"亢奋", "涨停多、封板牢、连板高度足"};
        }
        if (zt >= 50 && zbRatePct < 40 && (yPrem == null || yPrem > 0)) {
            return new String[]{"回暖", "涨停数量与封板质量都不错"};
        }
        return new String[]{"分歧", "多空拉锯"};
    }

    static double detectScale(List<PoolItem> ztPool) {
        List<Double> vals = new ArrayList<>();
        for (PoolItem p : ztPool) if (!Double.isNaN(p.zdp)) vals.add(Math.abs(p.zdp));
        if (vals.isEmpty()) return 1;
        vals.sort(Double::compareTo);
        double median = vals.get(vals.size() / 2);
        return median > 50 ? 100 : 1;
    }

    public Map<String, Object> latest(int recentDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trade_date, level, zt_count, dt_count, zb_rate, max_lbc, lb2_count, y_prem, payload " +
                            "FROM limitup_emotion ORDER BY trade_date DESC LIMIT ?", recentDays);
            if (rows.isEmpty()) return out;
            Map<String, Object> latest = mapper.readValue(
                    String.valueOf(rows.get(0).get("payload")), Map.class);
            out.put("latest", latest);
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", String.valueOf(r.get("trade_date")));
                m.put("level", r.get("level"));
                m.put("zt", r.get("zt_count"));
                m.put("maxLbc", r.get("max_lbc"));
                recent.add(m);
            }
            out.put("recent", recent);
        } catch (Exception e) {
            log.warn("读涨停情绪失败: {}", e.getMessage());
        }
        return out;
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}

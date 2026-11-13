package com.smbox.service;

import com.smbox.model.DailyBar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OversoldEngine {
    private OversoldEngine() { }

    public static final double MAX_RET20 = -0.085;
    public static final double MAX_DIST_MA20 = -0.055;
    public static final double MAX_RET5 = 0.051;
    public static final double MAX_ATR_PCT = 0.066;
    public static final double MIN_AMOUNT20 = 3e8;    // 元
    public static final int MIN_BARS = 121;

    public static final class Result {
        public boolean evaluable;
        public String skip;
        public boolean hit;
        public double ret20, distMa20, ret5, atrPct, amount20;
        public final boolean[] cond = new boolean[6];

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hit", hit);
            if (!evaluable) { m.put("evaluable", false); m.put("skip", skip); return m; }
            m.put("ret20", pct(ret20));
            m.put("distMa20", pct(distMa20));
            m.put("ret5", pct(ret5));
            m.put("atrPct", pct(atrPct));
            m.put("amount20Yi", Math.round(amount20 / 1e6) / 100.0);
            Map<String, Object> c = new LinkedHashMap<>();
            String[] n = {"20日跌≥8.5%", "距MA20≤−5.5%", "5日涨幅≤5.1%(未冲高)", "ATR≤6.6%(不过热)", "不透支", "20日均额≥3亿"};
            for (int i = 0; i < n.length; i++) c.put(n[i], cond[i]);
            m.put("conditions", c);
            return m;
        }
    }

    static Result skip(String why) {
        Result r = new Result();
        r.skip = why;
        return r;
    }

    public static Result evaluate(List<DailyBar> bars, Boolean ox) {
        return evaluate(bars, bars == null ? -1 : bars.size() - 1, ox);
    }

    public static Result evaluate(List<DailyBar> bars, int t, Boolean ox) {
        if (bars == null || t < 0 || t >= bars.size()) return skip("无日K");
        if (t + 1 < MIN_BARS) return skip("日K不足" + MIN_BARS + "根");
        Result r = new Result();
        r.evaluable = true;
        double c = bars.get(t).getClose();
        double c20 = bars.get(t - 20).getClose(), c5 = bars.get(t - 5).getClose();
        if (!(c > 0 && c20 > 0 && c5 > 0)) return skip("价格异常");
        r.ret20 = c / c20 - 1;
        r.ret5 = c / c5 - 1;
        r.distMa20 = c / BottomTurnEngine.ma(bars, t, 20) - 1;
        double tr = 0, amt = 0;
        for (int i = t - 13; i <= t; i++) {
            DailyBar x = bars.get(i), p = bars.get(i - 1);
            tr += Math.max(x.getHigh() - x.getLow(), Math.max(Math.abs(x.getHigh() - p.getClose()), Math.abs(x.getLow() - p.getClose())));
        }
        r.atrPct = (tr / 14) / c;
        for (int i = t - 19; i <= t; i++) amt += bars.get(i).getAmount();
        r.amount20 = amt / 20;
        r.cond[0] = r.ret20 <= MAX_RET20 + 1e-12;
        r.cond[1] = r.distMa20 <= MAX_DIST_MA20 + 1e-12;
        r.cond[2] = r.ret5 <= MAX_RET5 + 1e-12;
        r.cond[3] = r.atrPct <= MAX_ATR_PCT + 1e-12;
        r.cond[4] = Boolean.FALSE.equals(ox);
        r.cond[5] = r.amount20 >= MIN_AMOUNT20;
        boolean all = true;
        for (boolean b : r.cond) all &= b;
        r.hit = all;
        return r;
    }

    static double pct(double v) { return Math.round(v * 10000) / 100.0; }
}

package com.smbox.service;

import com.smbox.model.DailyBar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BottomTurnEngine {
    private BottomTurnEngine() { }

    public static final class Params {
        public int window = 60;              // 回看窗口 N
        public double minDrawdown = 0.15;    // C1
        public int minDaysSince = 3;         // C2
        public int maxDaysSince = 8;
        public double minRebound = 0.05;     // C3
        public double maxRebound = 0.20;
        public int minUpDays = 2;            //     近 3 天收涨天数下限
        public double minVolRatio = 1.3;     // C4
        public int preBottomVolDays = 10;    //     底部前均量的天数
        public double maxPos = 0.5;          // C6
        public int minBars = 120;            // 上市不足 120 个交易日不算

        public static Params defaults() { return new Params(); }

        public Params copy() {
            Params p = new Params();
            p.window = window; p.minDrawdown = minDrawdown; p.minDaysSince = minDaysSince; p.maxDaysSince = maxDaysSince;
            p.minRebound = minRebound; p.maxRebound = maxRebound; p.minUpDays = minUpDays; p.minVolRatio = minVolRatio;
            p.preBottomVolDays = preBottomVolDays; p.maxPos = maxPos; p.minBars = minBars;
            return p;
        }
    }

    public static final Params DEFAULT = Params.defaults();

    public static final class Result {
        public boolean evaluable;
        public String skip;          // 不可算的原因
        public boolean hit;
        public double drawdown;
        public int daysSince;        // C2 最低点距今交易日
        public double rebound;       // C3 距低点反弹（小数）
        public int upDays;           //     近 3 天收涨天数
        public double volRatio;
        public double pos;           // C6 60 日区间位置 0~1
        public final boolean[] cond = new boolean[7];   // C1..C7

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!evaluable) {
                m.put("hit", false);
                m.put("evaluable", false);
                m.put("skip", skip);
                return m;
            }
            m.put("hit", hit);
            m.put("rebound", pct(rebound));
            m.put("daysSince", daysSince);
            m.put("volRatio", r2(volRatio));
            m.put("pos", r2(pos));
            m.put("drawdown", pct(drawdown));
            Map<String, Object> c = new LinkedHashMap<>();
            String[] names = {"C1先跌过", "C2底部刚出现", "C3已涨未追高", "C4放量", "C5站上短均线", "C6仍在低位", "C7不透支"};
            for (int i = 0; i < 7; i++) c.put(names[i], cond[i]);
            m.put("conditions", c);
            return m;
        }
    }

    static Result skip(String why) {
        Result r = new Result();
        r.evaluable = false;
        r.skip = why;
        return r;
    }

    public static boolean isMainBoard(String code) {
        return code != null && code.matches("^(600|601|603|605|000|001|002|003)\\d{3}$");
    }

    public static Result evaluate(List<DailyBar> bars, Boolean ox, Params p) {
        return evaluate(bars, bars == null ? -1 : bars.size() - 1, ox, p);
    }

    public static Result evaluate(List<DailyBar> bars, int t, Boolean ox, Params p) {
        if (bars == null || t < 0 || t >= bars.size()) return skip("无日K");
        if (t + 1 < p.minBars) return skip("日K不足" + p.minBars + "根（次新或缓存不够）");
        int from = t - p.window + 1;
        if (from < 0) return skip("日K不足" + p.window + "根");

        double low60 = Double.MAX_VALUE, high60 = -Double.MAX_VALUE;
        int lIdx = from;
        for (int i = from; i <= t; i++) {
            DailyBar b = bars.get(i);
            if (b.getLow() <= low60) { low60 = b.getLow(); lIdx = i; }
            if (b.getHigh() > high60) high60 = b.getHigh();
        }
        if (!(low60 > 0)) return skip("价格异常");
        if (lIdx - p.preBottomVolDays < 0) return skip("底部前不足" + p.preBottomVolDays + "根，量比不可算");

        Result r = new Result();
        r.evaluable = true;

        // C1 先跌过
        double highPre = -Double.MAX_VALUE;
        for (int i = from; i <= lIdx; i++) highPre = Math.max(highPre, bars.get(i).getHigh());
        r.drawdown = highPre > 0 ? (highPre - low60) / highPre : 0;
        r.cond[0] = r.drawdown >= p.minDrawdown - 1e-12;

        // C2 底部刚出现
        r.daysSince = t - lIdx;
        r.cond[1] = r.daysSince >= p.minDaysSince && r.daysSince <= p.maxDaysSince;

        // C3 已涨 2~3 天且未追高
        double close = bars.get(t).getClose();
        r.rebound = close / low60 - 1;
        int up = 0;
        for (int i = t - 2; i <= t; i++) {
            if (i >= 1 && bars.get(i).getClose() > bars.get(i - 1).getClose()) up++;
        }
        r.upDays = up;
        r.cond[2] = r.rebound >= p.minRebound - 1e-12 && r.rebound <= p.maxRebound + 1e-12 && up >= p.minUpDays;

        // C4 放量
        double recent = 0;
        for (int i = t - 2; i <= t; i++) recent += bars.get(i).getVolume();
        recent /= 3.0;
        double pre = 0;
        for (int i = lIdx - p.preBottomVolDays; i <= lIdx - 1; i++) pre += bars.get(i).getVolume();
        pre /= p.preBottomVolDays;
        r.volRatio = pre > 0 ? recent / pre : 0;
        r.cond[3] = pre > 0 && r.volRatio >= p.minVolRatio - 1e-12;

        double ma5 = ma(bars, t, 5), ma10 = ma(bars, t, 10), ma5Prev = ma(bars, t - 3, 5);
        r.cond[4] = close > ma5 && close > ma10 && ma5 > ma5Prev;

        // C6 仍在低位
        double range = high60 - low60;
        r.pos = range > 0 ? (close - low60) / range : 0;
        r.cond[5] = r.pos <= p.maxPos + 1e-12;

        // C7 不透支
        r.cond[6] = Boolean.FALSE.equals(ox);

        boolean all = true;
        for (boolean c : r.cond) all &= c;
        r.hit = all;
        return r;
    }

    static double ma(List<DailyBar> bars, int t, int n) {
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) s += bars.get(i).getClose();
        return s / n;
    }

    static double pct(double v) { return Math.round(v * 10000) / 100.0; }
    static double r2(double v) { return Math.round(v * 100) / 100.0; }
    public static double pctOf(double v) { return pct(v); }
    public static double round2(double v) { return r2(v); }
}

package com.smbox.service;

import com.smbox.model.DailyBar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlagEngine {
    private FlagEngine() { }

    public static final String FORMING = "FORMING", BREAKOUT = "BREAKOUT", NONE = "NONE";

    public static final int MIN_FLAG = 4, MAX_FLAG = 10;
    public static final int POLE_LOOKBACK = 5;
    public static final double DEFAULT_MIN_POLE = 0.15;
    public static final double MAX_FLAG_RANGE = 0.08;
    public static final double MAX_RETRACE = 0.50;
    public static final double MAX_FLAG_OVER_TOP = 0.03;
    public static final double BREAK_MIN_GAIN = 0.03;
    public static final double BREAK_MIN_VOL = 1.5;

    public static final class Result {
        public String state = NONE;
        public double pole;        // 旗杆涨幅（小数）
        public int poleTopAgo;     // 杆顶距今交易日
        public int flagDays;       // 旗面根数
        public double flagRange;   // 旗面收盘区间 / 杆顶收盘
        public double retrace;     // 旗面最低收盘回吐了旗杆的比例
        public double volShrink;   // 旗面均量 / 旗杆均量
        public double breakVol;

        public boolean hit() { return !NONE.equals(state); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            if (NONE.equals(state)) return m;
            m.put("polePct", pct(pole));
            m.put("flagDays", flagDays);
            m.put("flagRangePct", pct(flagRange));
            m.put("retracePct", pct(retrace));
            m.put("volShrink", Math.round(volShrink * 100) / 100.0);
            if (BREAKOUT.equals(state)) m.put("breakVol", Math.round(breakVol * 100) / 100.0);
            return m;
        }
    }

    public static Result evaluate(List<DailyBar> bars, double minPole) {
        return evaluate(bars, bars == null ? -1 : bars.size() - 1, minPole);
    }

    public static Result evaluate(List<DailyBar> bars, int t, double minPole) {
        Result none = new Result();
        if (bars == null || t < MAX_FLAG + POLE_LOOKBACK + 2 || t >= bars.size()) return none;
        // 先看今天是不是突破：前一根必须是合格旗面
        Result prev = forming(bars, t - 1, minPole);
        if (prev != null) {
            int p = t - 1 - prev.flagDays;
            double topClose = -1, flagVol = 0;
            for (int i = p; i <= t - 1; i++) topClose = Math.max(topClose, bars.get(i).getClose());
            for (int i = p + 1; i <= t - 1; i++) flagVol += bars.get(i).getVolume();
            flagVol /= prev.flagDays;
            DailyBar d = bars.get(t), y = bars.get(t - 1);
            double gain = y.getClose() > 0 ? d.getClose() / y.getClose() - 1 : 0;
            double bv = flagVol > 0 ? d.getVolume() / flagVol : 0;
            if (d.getClose() > topClose && gain >= BREAK_MIN_GAIN - 1e-12 && bv >= BREAK_MIN_VOL - 1e-12) {
                prev.state = BREAKOUT;
                prev.breakVol = bv;
                prev.poleTopAgo = t - p;
                return prev;
            }
        }
        Result f = forming(bars, t, minPole);
        return f != null ? f : none;
    }

    static Result forming(List<DailyBar> b, int t, double minPole) {
        if (t < MAX_FLAG + POLE_LOOKBACK + 1) return null;
        int p = -1;
        double top = -1;
        for (int i = t - MAX_FLAG; i <= t - MIN_FLAG; i++) {
            if (b.get(i).getClose() >= top) { top = b.get(i).getClose(); p = i; }
        }
        if (p < POLE_LOOKBACK || !(top > 0)) return null;
        int n = t - p;
        double poleLow = Double.MAX_VALUE;
        for (int i = p - POLE_LOOKBACK; i <= p; i++) poleLow = Math.min(poleLow, b.get(i).getLow());
        if (!(poleLow > 0)) return null;
        double pole = top / poleLow - 1;
        if (pole < minPole - 1e-12) return null;
        // 旗面
        double fmax = -1, fmin = Double.MAX_VALUE, fvol = 0;
        for (int i = p + 1; i <= t; i++) {
            double c = b.get(i).getClose();
            fmax = Math.max(fmax, c);
            fmin = Math.min(fmin, c);
            fvol += b.get(i).getVolume();
        }
        fvol /= n;
        double range = (fmax - fmin) / top;
        if (range > MAX_FLAG_RANGE + 1e-12) return null;
        if (fmax > top * (1 + MAX_FLAG_OVER_TOP) + 1e-9) return null;
        double retrace = (top - fmin) / (top - poleLow);
        if (retrace > MAX_RETRACE + 1e-12) return null;
        int lowIdx = p;
        for (int i = p - POLE_LOOKBACK; i <= p; i++) if (b.get(i).getLow() <= poleLow) lowIdx = i;
        double pvol = 0;
        int pn = 0;
        for (int i = Math.max(lowIdx + 1, p - POLE_LOOKBACK + 1); i <= p; i++) { pvol += b.get(i).getVolume(); pn++; }
        if (pn == 0) { pvol = b.get(p).getVolume(); pn = 1; }
        pvol /= pn;
        if (!(pvol > 0) || fvol >= pvol) return null;
        Result r = new Result();
        r.state = FORMING;
        r.pole = pole;
        r.poleTopAgo = n;
        r.flagDays = n;
        r.flagRange = range;
        r.retrace = Math.max(0, retrace);
        r.volShrink = fvol / pvol;
        return r;
    }

    static double pct(double v) { return Math.round(v * 10000) / 100.0; }
}

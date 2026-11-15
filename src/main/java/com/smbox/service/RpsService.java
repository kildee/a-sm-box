package com.smbox.service;

import com.smbox.model.DailyBar;
import com.smbox.model.WatchItem;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.RpsRepository;
import com.smbox.storage.WatchItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RpsService {
    private final WatchItemRepository watchItemRepository;
    private final DailyBarRepository dailyBarRepository;
    private final RpsRepository rpsRepository;

    public synchronized void computeAndStore() {
        long t0 = System.currentTimeMillis();
        Map<String, double[]> retsByCode = new HashMap<>();
        Map<String, LocalDate> latestByCode = new HashMap<>();
        LocalDate globalMax = null;
        Set<String> seen = new HashSet<>();
        for (WatchItem it : watchItemRepository.findAll()) {
            String code = it.getCode();
            if (code == null || code.startsWith("920") || !seen.add(code)) continue;
            List<DailyBar> bars = dailyBarRepository.findRecent(code, 121);   // DESC
            if (bars.size() < 51) continue;
            double c0 = bars.get(0).getClose();
            if (c0 <= 0) continue;
            double ret50 = c0 / bars.get(50).getClose() - 1;
            double ret120 = bars.size() >= 121 ? c0 / bars.get(120).getClose() - 1 : Double.NaN;
            retsByCode.put(code, new double[]{ret50, ret120});
            LocalDate d = bars.get(0).getTradeDate();
            latestByCode.put(code, d);
            if (globalMax == null || d.isAfter(globalMax)) globalMax = d;
        }
        if (globalMax == null) { log.warn("RPS 计算：无可用数据"); return; }

        // 只对最新日期与全市场一致的票排名
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, LocalDate> e : latestByCode.entrySet()) {
            if (globalMax.equals(e.getValue())) fresh.add(e.getKey());
        }
        Map<String, Double> rps50 = percentile(fresh, retsByCode, 0);
        Map<String, Double> rps120 = percentile(fresh, retsByCode, 1);

        List<RpsRepository.Row> rows = new ArrayList<>(fresh.size());
        for (String code : fresh) {
            rows.add(new RpsRepository.Row(code, rps50.get(code), rps120.get(code)));
        }
        rpsRepository.replaceAll(globalMax, rows);
        log.info("RPS 计算完成：trade_date={} 共 {} 只（rps120 覆盖 {} 只），耗时 {}s",
                globalMax, rows.size(), rps120.size(), (System.currentTimeMillis() - t0) / 1000);
    }

    private static Map<String, Double> percentile(List<String> codes, Map<String, double[]> rets, int idx) {
        List<String> valid = new ArrayList<>();
        for (String c : codes) if (!Double.isNaN(rets.get(c)[idx])) valid.add(c);
        valid.sort((a, b) -> Double.compare(rets.get(a)[idx], rets.get(b)[idx]));
        Map<String, Double> out = new HashMap<>();
        int n = valid.size();
        for (int i = 0; i < n; i++) {
            out.put(valid.get(i), n <= 1 ? 50d : Math.round(i * 10000d / (n - 1)) / 100d);
        }
        return out;
    }
}

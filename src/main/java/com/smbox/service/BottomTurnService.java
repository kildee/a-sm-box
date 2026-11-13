package com.smbox.service;

import com.smbox.model.DailyBar;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BottomTurnService {
    static final int BARS = 130;

    private final DailyBarRepository dailyBarRepository;
    private final JdbcTemplate jdbc;

    public void annotateScan(List<ScanResultRepository.Row> rows) {
        LocalDate latest = latestTradeDate();
        for (ScanResultRepository.Row r : rows) {
            if (!inScope(r)) continue;
            try {
                List<DailyBar> bars = dailyBarRepository.findRecent(r.code, BARS);
                Collections.reverse(bars);   // 库里 DESC
                if (bars.isEmpty()) continue;
                if (latest != null && bars.get(bars.size() - 1).getTradeDate().isBefore(latest)) {
                    r.bt = BottomTurnEngine.skip("停牌或日K未更新");
                    r.os = OversoldEngine.skip("停牌或日K未更新");
                    continue;
                }
                r.bt = BottomTurnEngine.evaluate(bars, r.ox, BottomTurnEngine.DEFAULT);
                r.os = OversoldEngine.evaluate(bars, r.ox);
                r.flag = FlagEngine.evaluate(bars, FlagEngine.DEFAULT_MIN_POLE);
            } catch (Exception e) {
                log.debug("底部反转 {} 计算失败: {}", r.code, e.getMessage());
            }
        }
    }

    static boolean inScope(ScanResultRepository.Row r) {
        if (r == null || r.score < 0) return false;
        if (!BottomTurnEngine.isMainBoard(r.code)) return false;
        return r.name == null || !r.name.toUpperCase().contains("ST");
    }

    private LocalDate latestTradeDate() {
        try {
            java.sql.Date d = jdbc.queryForObject("SELECT MAX(trade_date) FROM daily_bar", java.sql.Date.class);
            return d == null ? null : d.toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }
}

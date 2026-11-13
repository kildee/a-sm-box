package com.smbox.schedule;

import com.smbox.service.LimitUpService;
import com.smbox.service.MacroSentinelService;
import com.smbox.service.MarketService;
import com.smbox.service.SimService;
import com.smbox.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "smbox", name = "schedule-enabled", havingValue = "true", matchIfMissing = true)
public class DailyJobs {
    private final MarketService marketService;
    private final SimService simService;
    private final WatchlistService watchlistService;
    private final MacroSentinelService macroSentinelService;
    private final LimitUpService limitUpService;

    @Scheduled(cron = "0 35 8 * * MON-FRI")
    public void morningMacroSentinel() {
        log.info("定时任务触发: 盘前哨兵");
        try { macroSentinelService.run(); }
        catch (Exception e) { log.error("盘前哨兵失败", e); }
    }

    @Scheduled(cron = "0 5 15 * * MON-FRI")
    public void runMarketPulse() {
        log.info("定时任务触发: 大盘研判");
        try { marketService.compute(); }
        catch (Exception e) { log.error("大盘研判失败", e); }
    }

    @Scheduled(cron = "0 5 16 * * MON-FRI")
    public void warmWatchlistKlines() {
        if (watchlistService.manuallyWarmedTodayAfter(16)) {
            log.info("定时任务跳过: 今日 16 点后已手动刷新过自选 K 线");
            return;
        }
        log.info("定时任务触发: 自选分组 K 线预热");
        try { watchlistService.warmKlineCache(130); }
        catch (Exception e) { log.error("自选 K 线预热失败", e); }
    }

    @Scheduled(cron = "0 15 16 * * MON-FRI")
    public void archiveLimitUpEmotion() {
        log.info("定时任务触发: 涨停情绪存档");
        try { limitUpService.run(); }
        catch (Exception e) { log.error("涨停情绪存档失败", e); }
    }

    @Scheduled(cron = "0 30 19 * * MON-FRI")
    public void runSimDaily() {
        log.info("定时任务触发: 模拟盘结算");
        try { simService.runDailyAuto(); }
        catch (Exception e) { log.error("模拟盘结算失败", e); }
    }
}

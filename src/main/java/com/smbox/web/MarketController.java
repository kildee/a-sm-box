package com.smbox.web;

import com.smbox.model.MarketPulse;
import com.smbox.service.LimitUpService;
import com.smbox.service.MacroSentinelService;
import com.smbox.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MarketController {
    private final MarketService marketService;
    private final LimitUpService limitUpService;
    private final MacroSentinelService macroSentinelService;

    @GetMapping("/market/pulse")
    public MarketPulse marketPulse() {
        if (TradingClock.isTradingNow()) return marketService.compute();
        MarketPulse cached = marketService.last();
        return cached != null ? cached : marketService.compute();
    }

    @PostMapping("/market/scan")
    public MarketPulse marketScan() {
        return marketService.compute();
    }

    @GetMapping("/market/last")
    public Map<String, Object> marketLast() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("pulse", marketService.last());   // 可能为 null（从未归档）
        return resp;
    }

    @GetMapping("/market/limitup")
    public Map<String, Object> limitUpLatest() {
        return limitUpService.latest(10);
    }

    @PostMapping("/market/limitup/run")
    public Map<String, Object> limitUpRun() {
        return limitUpService.run();
    }

    @GetMapping("/macro/sentinel")
    public Map<String, Object> sentinelLatest() {
        return macroSentinelService.latest();
    }

    @PostMapping("/macro/sentinel/run")
    public Map<String, Object> sentinelRun() {
        return macroSentinelService.run();
    }
}

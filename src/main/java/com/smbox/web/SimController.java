package com.smbox.web;

import com.smbox.service.SimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimController {
    private final SimService simService;

    private static String stratOf(String s) {
        if ("B".equalsIgnoreCase(s)) return SimService.STRAT_B;
        if ("C".equalsIgnoreCase(s)) return SimService.STRAT_C;
        return SimService.STRAT_A;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "A") String strategy) {
        return simService.overview(stratOf(strategy));
    }

    @GetMapping("/closed-trades")
    public Map<String, Object> closedTrades(@RequestParam(defaultValue = "A") String strategy,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "20") int limit) {
        return simService.closedTrades(stratOf(strategy), Math.max(0, offset), Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(defaultValue = "A") String strategy,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "20") int limit) {
        return simService.orders(stratOf(strategy), Math.max(0, offset), Math.max(1, Math.min(limit, 100)));
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "all") String strategy) {
        new Thread(() -> {
            try {
                if ("all".equalsIgnoreCase(strategy)) simService.runManual(SimService.ALL_STRATS);
                else simService.runManual(stratOf(strategy));
            } catch (Exception e) { log.error("模拟盘手动结算失败", e); }
        }, "sim-manual-run").start();
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("message", "已触发结算，约几十秒；完成后页面会显示每个策略做了什么");
        return resp;
    }

    @GetMapping("/run-result")
    public Map<String, Object> runResult() {
        return simService.lastManualRun();
    }

    @PostMapping("/close")
    public Map<String, Object> close(@RequestParam(defaultValue = "A") String strategy,
                                     @RequestParam String code) {
        return simService.closePosition(stratOf(strategy), code);
    }

    @PostMapping("/manual")
    public Map<String, Object> manual(@RequestParam(defaultValue = "A") String strategy,
                                      @RequestBody Map<String, Object> body) {
        String side = String.valueOf(body.get("side"));
        String code = String.valueOf(body.get("code"));
        int qty = body.get("qty") instanceof Number ? ((Number) body.get("qty")).intValue() : 0;
        double price = body.get("price") instanceof Number ? ((Number) body.get("price")).doubleValue() : 0;
        return simService.manualTrade(stratOf(strategy), side, code, qty, price);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "A") String strategy) {
        simService.reset(stratOf(strategy));
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        return resp;
    }
}

package com.smbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketPulse {
    private LocalDate tradeDate;
    /** 0-100 大盘温度（五维加权） */
    private double temperature;
    private String advice;
    /** 一句话总结 */
    private String summary;
    /** 是否触发一票降档（风控强制防守） */
    private boolean forcedDefensive;

    // ===== 五维分 =====
    private double trendScore;
    private double breadthScore;
    private double capitalScore;
    private double volumeScore;
    private double momentumScore;

    // ===== 市场宽度原始数据 =====
    private int upCount;
    private int downCount;
    private int flatCount;
    private int limitUp;
    private int limitDown;
    private double avgChangePct;
    /** 两市总成交额（元） */
    private double totalAmount;

    // ===== 大盘资金 =====
    private double mainNetInflowToday;
    /** 近 5 日主力资金净流入合计（元） */
    private double mainNetInflow5d;

    /** 指数卡片 */
    private List<IndexCard> indices;
    /** 加减分理由 */
    private List<Reason> reasons;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexCard {
        private String name;
        private String code;
        private double close;
        private double changePct;
        private boolean aboveMa20;
        private boolean aboveMa60;
        /** MA20 > MA60 多头排列 */
        private boolean bullish;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reason {
        private String kind;
        private String text;
    }
}

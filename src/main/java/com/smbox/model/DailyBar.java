package com.smbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBar {
    private String code;
    private LocalDate tradeDate;
    private double open;
    private double close;
    private double high;
    private double low;
    /** 成交量（手） */
    private long volume;
    /** 成交额（元） */
    private double amount;
}

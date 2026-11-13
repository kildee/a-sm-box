package com.smbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Quote {
    /** 6 位股票代码 */
    private String code;
    /** 名称 */
    private String name;
    private String exchange;
    /** 最新价 */
    private double price;
    /** 涨跌幅（%） */
    private double changePct;
    /** 成交量（手） */
    private long volume;
    /** 成交额（元） */
    private double amount;

    public String secid() {
        switch (exchange) {
            case "sh": return "1." + code;
            case "sz": return "0." + code;
            case "bj": return "0." + code;
            default:   return code.startsWith("6") ? "1." + code : "0." + code;
        }
    }
}

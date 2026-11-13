
CREATE TABLE IF NOT EXISTS daily_bar (
    code        VARCHAR(16)  NOT NULL,
    trade_date  DATE         NOT NULL,
    open        DECIMAL(12,3),
    close       DECIMAL(12,3),
    high        DECIMAL(12,3),
    low         DECIMAL(12,3),
    volume      BIGINT,        -- 单位：手
    amount      DECIMAL(20,2), -- 单位：元
    PRIMARY KEY (code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_bar_date ON daily_bar(trade_date);

CREATE TABLE IF NOT EXISTS watch_group (
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (name)
);
MERGE INTO watch_group(name) KEY(name) VALUES ('全部');

CREATE TABLE IF NOT EXISTS watch_item (
    group_name  VARCHAR(64)  NOT NULL,
    code        VARCHAR(16)  NOT NULL,
    name        VARCHAR(32),
    secid       VARCHAR(24),
    sort_order  INT,
    PRIMARY KEY (group_name, code)
);

CREATE INDEX IF NOT EXISTS idx_watch_group ON watch_item(group_name, sort_order);

CREATE TABLE IF NOT EXISTS scan_result (
    group_name  VARCHAR(64) NOT NULL,
    code        VARCHAR(16) NOT NULL,
    name        VARCHAR(32),           -- 冗余列：服务端分页的搜索用
    score       DOUBLE,
    tier        INT,
    b_label     VARCHAR(4),            -- 最近背驰买点 B1/B2/B3
    b_days      INT,                   -- 距今交易日数
    x_label     VARCHAR(4),            -- 最近一次均线交叉 金叉/死叉
    x_days      INT,                   -- 距今交易日数
    b_count     INT,                   -- 近15根背驰买点个数
    regime      VARCHAR(12),           -- 趋势状态
    bottom_turn   BOOLEAN,
    bt_rebound    DECIMAL(8,2),        -- 自低点反弹 %
    bt_days_since INT,                 -- 低点距今交易日数
    bt_vol_ratio  DECIMAL(8,2),        -- 放量倍数
    bt_pos        DECIMAL(6,2),        -- 60日区间位置 0~1
    bt_drawdown   DECIMAL(8,2),        -- 前期跌幅 %
    oversold      BOOLEAN,             -- 超跌回归（OversoldEngine）
    os_ret20      DECIMAL(8,2),        -- 20 日涨幅 %
    os_dma20      DECIMAL(8,2),        -- 收盘距 MA20 %
    flag_state    VARCHAR(8),
    flag_pole     DECIMAL(8,2),        -- 旗杆涨幅 %
    flag_days     INT,                 -- 旗面天数
    payload     CLOB,
    scanned_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (group_name, code)
);

ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS b_label VARCHAR(4);
ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS b_days INT;
ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS x_label VARCHAR(4);
ALTER TABLE scan_result ADD COLUMN IF NOT EXISTS x_days INT;

CREATE INDEX IF NOT EXISTS idx_scan_result_group_score ON scan_result(group_name, score);

CREATE TABLE IF NOT EXISTS meta_kv (
    k VARCHAR(64)  NOT NULL,
    v VARCHAR(65536),
    PRIMARY KEY (k)
);

CREATE TABLE IF NOT EXISTS rps (
    code       VARCHAR(16) NOT NULL,
    trade_date DATE        NOT NULL,
    rps50      DECIMAL(6,2),          -- 近50日涨幅百分位 0-100
    rps120     DECIMAL(6,2),          -- 近120日涨幅百分位 0-100
    PRIMARY KEY (code)
);

-- ===== 模拟盘 =====
CREATE TABLE IF NOT EXISTS sim_account (
    strategy     VARCHAR(8)    NOT NULL,
    initial_cash DECIMAL(14,2) NOT NULL,
    cash         DECIMAL(14,2) NOT NULL,
    started_at   TIMESTAMP     NOT NULL,
    PRIMARY KEY (strategy)
);

CREATE TABLE IF NOT EXISTS sim_position (
    strategy  VARCHAR(8)    NOT NULL,
    code      VARCHAR(16)   NOT NULL,
    name      VARCHAR(32),
    qty       INT           NOT NULL,
    avg_cost  DECIMAL(12,3) NOT NULL,
    opened_at TIMESTAMP     NOT NULL,
    PRIMARY KEY (strategy, code)
);

CREATE TABLE IF NOT EXISTS sim_order (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    strategy      VARCHAR(8)    NOT NULL,
    trade_date    DATE          NOT NULL,
    code          VARCHAR(16)   NOT NULL,
    name          VARCHAR(32),
    side          VARCHAR(8)    NOT NULL,
    qty           INT,
    budget        DECIMAL(14,2),
    price         DECIMAL(12,3),
    amount        DECIMAL(14,2),
    fee           DECIMAL(10,2),
    reason        VARCHAR(200),
    entry_pattern VARCHAR(16),
    status        VARCHAR(12)   NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    filled_at     TIMESTAMP,
    alt_price     DECIMAL(12,3),
    alt_note      VARCHAR(64),
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sim_order_status ON sim_order(strategy, status);
CREATE INDEX IF NOT EXISTS idx_sim_order_date ON sim_order(strategy, trade_date);

CREATE TABLE IF NOT EXISTS sim_snapshot (
    strategy       VARCHAR(8)    NOT NULL,
    trade_date     DATE          NOT NULL,
    total_value    DECIMAL(14,2) NOT NULL,
    cash           DECIMAL(14,2) NOT NULL,
    position_value DECIMAL(14,2) NOT NULL,
    total_pnl_pct  DECIMAL(8,3),
    hs300_close    DECIMAL(12,3),
    payload        CLOB,
    PRIMARY KEY (strategy, trade_date)
);

-- ===== 大盘研判 =====
CREATE TABLE IF NOT EXISTS market_snapshot (
    trade_date  DATE      NOT NULL,
    payload     CLOB,                  -- MarketPulse JSON
    computed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (trade_date)
);

CREATE TABLE IF NOT EXISTS macro_sentinel (
    trade_date DATE        NOT NULL,
    level      VARCHAR(8),              -- 高 / 中 / 低
    payload    CLOB,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (trade_date)
);

CREATE TABLE IF NOT EXISTS limitup_emotion (
    trade_date DATE        NOT NULL,
    level      VARCHAR(8),             -- 亢奋 / 回暖 / 分歧 / 退潮 / 冰点
    zt_count   INT,                    -- 涨停家数
    dt_count   INT,                    -- 跌停家数
    zb_count   INT,                    -- 炸板家数（开板后未回封）
    zb_rate    DECIMAL(6,2),
    max_lbc    INT,                    -- 连板高度（最高连板数）
    lb2_count  INT,                    -- 二板及以上家数
    y_prem     DECIMAL(8,2),
    y_red_rate DECIMAL(6,2),           -- 昨日涨停今日红盘比例 %
    payload    CLOB,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (trade_date)
);

-- ===== 交易复盘日记 =====
CREATE TABLE IF NOT EXISTS trade_journal (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    code        VARCHAR(16)   NOT NULL,
    name        VARCHAR(32),
    buy_date    DATE          NOT NULL,
    buy_price   DECIMAL(12,3) NOT NULL,
    qty         INT           NOT NULL,
    buy_reason  VARCHAR(300),                -- 买入理由/计划
    sell_date   DATE,
    sell_price  DECIMAL(12,3),
    sell_reason VARCHAR(300),
    status      VARCHAR(8)    NOT NULL,       -- OPEN / CLOSED
    pnl_amt     DECIMAL(14,2),               -- 平仓实现盈亏(含费)
    pnl_pct     DECIMAL(8,3),
    hold_days   INT,                          -- 持有自然日
    fee         DECIMAL(10,2),                -- 双边佣金+印花税
    tags        VARCHAR(200),                 -- 逗号分隔：追高/恐慌割/纪律执行…
    note        VARCHAR(500),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_tj_status ON trade_journal(status);

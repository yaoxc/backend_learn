package com.bizzan.bitrade.entity;

/**
 * 交易对“发布/撮合输出模式”。
 *
 * 主要用途：
 * - 被撮合引擎 `CoinTrader` 读取，用来决定某些撮合/推送策略（例如是否启用分摊撮合逻辑等）。
 * - 由交易对配置（`ExchangeCoin.publishType`）下发到撮合侧，做到“按交易对”差异化行为。
 */
public enum ExchangeCoinPublishType {
	/** 未知/默认值：配置缺失或历史数据兼容场景。 */
	UNKNOW,
	/** 不启用特殊发布模式：走常规撮合/推送逻辑。 */
	NONE,
	/** 抢购模式：用于特定业务形态（如抢购/活动盘）的撮合或推送策略开关。 */
	QIANGGOU,
	/** 分摊模式：成交按比例分摊（撮合侧对应 `processMatchByFENTAN` 分支）。 */
	FENTAN
}

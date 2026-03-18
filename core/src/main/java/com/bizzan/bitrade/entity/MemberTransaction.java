package com.bizzan.bitrade.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;

import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.entity.converter.TransactionTypeAttributeConverter;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 会员交易记录，包括充值、提现、转账、下单冻结、归集冻结等。
 * 通过 refType + refId 关联具体业务，便于按业务查询与平账。
 */
@Entity
@Data
public class MemberTransaction {

    // ===================== 已有 / 资金指令常用 =====================
    /** 业务类型：币币订单（下单冻结、成交、退款等），refId=订单号 */
    public static final String REF_TYPE_ORDER = "ORDER";
    /** 业务类型：提现，refId=提现记录 ID */
    public static final String REF_TYPE_WITHDRAW = "WITHDRAW";
    /** 业务类型：归集/sweep，refId=归集批次或相关 ID */
    public static final String REF_TYPE_SWEEP = "SWEEP";
    /** 业务类型：充值/存款（通用），refId=充值记录 ID */
    public static final String REF_TYPE_DEPOSIT = "DEPOSIT";
    /** 业务类型：冷热再平衡，refId=再平衡批次或相关 ID */
    public static final String REF_TYPE_REBALANCE = "REBALANCE";

    // ===================== 补充：与 TransactionType 场景对齐 =====================
    /** 业务类型：链上充值（扫链入账），refId=充值/区块相关 ID */
    public static final String REF_TYPE_DEPOSIT_CHAIN = "DEPOSIT_CHAIN";
    /** 业务类型：C2C 充值，refId=订单或划拨记录 ID */
    public static final String REF_TYPE_DEPOSIT_C2C = "DEPOSIT_C2C";
    /** 业务类型：人工充值，refId=工单或操作记录 ID */
    public static final String REF_TYPE_ADMIN_RECHARGE = "ADMIN_RECHARGE";
    /** 业务类型：转账，refId=转账记录 ID */
    public static final String REF_TYPE_TRANSFER = "TRANSFER";
    /** 业务类型：法币/C2C 订单，refId=广告单或订单 ID */
    public static final String REF_TYPE_OTC = "OTC";
    /** 业务类型：CTC 订单，refId=订单 ID */
    public static final String REF_TYPE_CTC = "CTC";
    /** 业务类型：红包，refId=红包或领取明细 ID */
    public static final String REF_TYPE_RED_PACKET = "RED_PACKET";
    /** 业务类型：活动/营销（奖励、推广、分红、投票、活动兑换等），refId=活动或发放记录 ID */
    public static final String REF_TYPE_ACTIVITY = "ACTIVITY";
    /** 业务类型：配对，refId=配对记录 ID */
    public static final String REF_TYPE_MATCH = "MATCH";

    @Excel(name = "交易记录编号", orderNum = "1", width = 25)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Long id;
    @Excel(name = "会员id", orderNum = "2", width = 25)
    private Long memberId;
    /**
     * 交易金额
     */
    @Excel(name = "交易金额", orderNum = "3", width = 25)
    @Column(columnDefinition = "decimal(26,16) comment '充币金额'")
    private BigDecimal amount;

    /**
     * 创建时间
     */
    @Excel(name = "创建时间", orderNum = "4", width = 25)
    @CreationTimestamp
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    /**
     * 交易类型
     * 
     * 这两行的意思是：MemberTransaction.type 这个字段在数据库里按“枚举序号”存储，并且导出 Excel 时把它当作“交易类型”列。
     * @Excel(...)：来自 EasyPOI，用于 Excel 导出/导入的字段映射。这里表示导出时列名叫“交易类型”，列顺序是第 5 列，列宽 25。
     * @Enumerated(EnumType.ORDINAL)：JPA 枚举映射方式。表示把 TransactionType 存到数据库时，不存字符串（如 EXCHANGE_FREEZE），而是存整数序号（ordinal）。
     *     例如存成 17（具体取决于 TransactionType 的 ordinal/显式编号实现）。
     */
    @Excel(name = "交易类型", orderNum = "5", width = 25)
    @Convert(converter = TransactionTypeAttributeConverter.class)
    private TransactionType type;

    /**
     * 交易类型字符串（稳定口径）：用于避免 EnumType.ORDINAL 带来的“数字含义随枚举顺序变化”的问题。
     * 建议存 TransactionType.name()（如 EXCHANGE_FREEZE），必要时可用于对账/审计展示与回填。
     */
    @Column(name = "type_str", length = 64)
    private String typeStr;
    /**
     * 币种名称，如 BTC
     */
    private String symbol;
    /**
     * address 只表示链上/账户地址（充值、提现、转账地址等），不再用来存业务 ID。
     */
    private String address;

    /**
     * 业务类型：用于关联具体业务，便于按业务查询与平账。
     * 建议取值见本类 REF_TYPE_* 常量：ORDER/WITHDRAW/SWEEP/DEPOSIT/DEPOSIT_CHAIN/DEPOSIT_C2C/ADMIN_RECHARGE/REBALANCE/TRANSFER/OTC/CTC/RED_PACKET/ACTIVITY/MATCH。
     */
    @Column(length = 32)
    private String refType;
    /**
     * 业务主键：对应业务表的主键或业务单号（如 orderId、withdrawRecordId、sweepBatchId、depositId）。
     * 与 refType 一起唯一关联到具体业务记录，避免复用 address 等字段。
     */
    @Column(length = 64)
    private String refId;

    /**
     * 成交 ID（tradeId），用于把成交相关流水与具体成交绑定。
     * 注意：下单冻结阶段还没有 tradeId，此字段通常为空；成交/退款/手续费等阶段可写入。
     */
    @Column(name = "trade_id", length = 64)
    private String tradeId;

    /**
     * 交易手续费
     * 提现和转账才有手续费，充值没有;如果是法币交易，只收发布广告的那一方的手续费
     */
    @Column(precision = 26,scale = 16)
    private BigDecimal fee = BigDecimal.ZERO ;

    /**
     * 标识位，特殊情况会用到，默认为0
     */
    @Column(nullable=false,columnDefinition="int default 0")
    private int flag = 0;
    /**
     * 实收手续费
     */
    private String realFee ;
    /**
     * 折扣手续费
     */
    private String discountFee ;

    @PrePersist
    @PreUpdate
    public void fillDerivedFields() {
        if (this.type != null) {
            // 稳定字符串口径：不依赖 ordinal 序号
            this.typeStr = this.type.name();
        }
    }
}

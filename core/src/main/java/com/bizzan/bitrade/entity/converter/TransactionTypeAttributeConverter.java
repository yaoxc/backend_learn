package com.bizzan.bitrade.entity.converter;

import com.bizzan.bitrade.constant.TransactionType;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * 将 TransactionType 以“稳定 code”持久化到数据库（int）。
 *
 * 为什么不用 @Enumerated(EnumType.ORDINAL)：
 * - ORDINAL 存的是 Java 枚举声明顺序（0,1,2...），一旦调整枚举顺序/插入新值，历史数据含义会漂移。
 *
 * 本转换器存的是 TransactionType.getOrdinal()（项目内显式定义的稳定编号），并用 valueOfOrdinal 反解。
 */
@Converter(autoApply = false)
public class TransactionTypeAttributeConverter implements AttributeConverter<TransactionType, Integer> {

    @Override
    public Integer convertToDatabaseColumn(TransactionType attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getOrdinal();
    }

    @Override
    public TransactionType convertToEntityAttribute(Integer dbData) {
        if (dbData == null) {
            return null;
        }
        return TransactionType.valueOfOrdinal(dbData);
    }
}


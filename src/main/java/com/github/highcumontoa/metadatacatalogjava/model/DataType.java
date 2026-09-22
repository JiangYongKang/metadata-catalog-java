package com.github.highcumontoa.metadatacatalogjava.model;

/** 统一的规范字段类型，各来源的原始类型通过 TypeDictionary 归一到本枚举。 */
public enum DataType {
    INTEGER,
    BIGINT,
    DECIMAL,
    DOUBLE,
    STRING,
    BOOLEAN,
    TIMESTAMP
}

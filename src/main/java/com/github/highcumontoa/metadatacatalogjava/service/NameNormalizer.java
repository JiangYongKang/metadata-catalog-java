package com.github.highcumontoa.metadatacatalogjava.service;

import java.util.Locale;

/**
 * 统一的对象名归一规则：所有写入口（登记、字段变更、血缘登记、废弃、移除）
 * 与读路径都必须使用本类识别实体与字段。
 *
 * <p>规则：去掉首尾空白，并按 {@link Locale#ROOT} 转为小写。
 * 因此仅大小写或首尾空白不同的名字（如 "ODS.User " 与 "ods.user"）
 * 会被当成同一个对象，不会各自建一份、也不会各算一套血缘。</p>
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    /** 归一实体限定名 / 字段名；null 与空白输入原样返回（由调用方按 INVALID_PAYLOAD / MISSING_FIELD_NAME 处理）。 */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}

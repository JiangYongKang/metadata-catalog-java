package com.github.highcumontoa.metadatacatalogjava.util;

import java.util.Locale;

/**
 * 所有写入口共用的对象名归一规则。
 *
 * <p>实体限定名（qualifiedName）与字段名（fieldName）一律按同一套规则识别对象：</p>
 * <ol>
 *   <li>去除首尾空白；</li>
 *   <li>按 {@link Locale#ROOT} 做大小写折叠（toLowerCase）。</li>
 * </ol>
 * 因此 {@code "ODS.User" / " ods.user " / "ods.user"} 必须被当作同一个实体，
 * {@code "ID" / " id " / "id"} 必须被当作同一个字段——不会各自建一份记录、
 * 也不会各算一套血缘。null 或纯空白名称归一为 {@code null}（由调用方按必填项拒绝）。
 */
public final class Names {

    private Names() {
    }

    /** 实体名 / 字段名 -&gt; 规范键：去首尾空白并按 ROOT Locale 小写化；空白输入返回 null。 */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}

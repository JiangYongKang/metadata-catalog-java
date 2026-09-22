package com.github.highcumontoa.metadatacatalogjava.dto;

import java.util.List;

/**
 * 某一来源对一个实体的登记声明。
 * source 为来源标识，qualifiedName 为实体归一标识，
 * kind 为 TABLE/VIEW/STREAM，fields 为该来源声称的字段列表。
 */
public record RegistrationRequest(
        String source,
        String qualifiedName,
        String kind,
        List<FieldClaim> fields
) {
}

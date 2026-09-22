package com.github.highcumontoa.metadatacatalogjava.dto;

/** 血缘登记请求：upstream -> downstream。 */
public record LineageRequest(String source, String upstream, String downstream) {
}

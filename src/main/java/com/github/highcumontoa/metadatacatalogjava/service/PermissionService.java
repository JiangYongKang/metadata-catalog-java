package com.github.highcumontoa.metadatacatalogjava.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地权限服务：记录“实体 -> 允许写操作的操作者白名单”。
 * 规则：一旦为某实体显式授予过权限，则只有白名单内操作者与超级操作者 admin 可写；
 * 从未授权的实体默认所有人可写。数据全部保存在内存中，可本地复现。
 */
@Component
public class PermissionService {

    public static final String ADMIN = "admin";

    private final ConcurrentHashMap<String, Set<String>> grants = new ConcurrentHashMap<>();

    /** 为操作者授予某实体的写权限；首次授权后实体即进入白名单（其余操作者只读）模式。 */
    public void grant(String qualifiedName, String operator) {
        grants.computeIfAbsent(NameNormalizer.normalize(qualifiedName),
                k -> ConcurrentHashMap.newKeySet()).add(operator);
    }

    /** 判断操作者能否写某实体；admin 始终放行。实体名按统一规则归一后匹配授权。 */
    public boolean canWrite(String qualifiedName, String operator) {
        if (ADMIN.equals(operator)) {
            return true;
        }
        Set<String> allowed = grants.get(NameNormalizer.normalize(qualifiedName));
        // 未显式授权的实体默认开放写；进入白名单模式后严格校验。
        return allowed == null || allowed.contains(operator);
    }
}

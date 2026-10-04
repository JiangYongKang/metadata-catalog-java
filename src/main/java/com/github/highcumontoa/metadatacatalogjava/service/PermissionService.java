package com.github.highcumontoa.metadatacatalogjava.service;

import com.github.highcumontoa.metadatacatalogjava.util.Names;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地权限服务：记录“实体 -> 允许写操作的操作者白名单”。
 * 规则：一旦为某实体显式授予过权限，则只有白名单内操作者与超级操作者 admin 可写；
 * 从未授权的实体默认所有人可写。实体名按 {@link Names#canonical(String)} 归一；
 * 操作者名去首尾空白并大小写不敏感匹配，大小写/空白差异不会绕过白名单。
 * 数据全部保存在内存中，可本地复现。
 */
@Component
public class PermissionService {

    public static final String ADMIN = "admin";

    private final ConcurrentHashMap<String, Set<String>> grants = new ConcurrentHashMap<>();

    /** 为操作者授予某实体的写权限；首次授权后实体即进入白名单模式。操作者键大小写不敏感。 */
    public void grant(String qualifiedName, String operator) {
        String qn = Names.canonical(qualifiedName);
        String op = operatorKey(operator);
        grants.computeIfAbsent(qn, k -> ConcurrentHashMap.newKeySet()).add(op);
    }

    /** 判断操作者能否写某实体；admin 始终放行。实体名归一、操作者去空白且大小写不敏感。 */
    public boolean canWrite(String qualifiedName, String operator) {
        String op = operatorKey(operator);
        if (ADMIN.equals(op)) {
            return true;
        }
        Set<String> allowed = grants.get(Names.canonical(qualifiedName));
        // 未显式授权的实体默认开放写；进入白名单模式后严格校验。
        return allowed == null || allowed.contains(op);
    }

    private static String operatorKey(String operator) {
        if (operator == null) {
            return null;
        }
        String trimmed = operator.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}

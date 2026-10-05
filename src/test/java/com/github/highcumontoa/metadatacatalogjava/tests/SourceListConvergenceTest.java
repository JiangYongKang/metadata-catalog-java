package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationResult;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.EntityKind;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.service.PermissionService;
import com.github.highcumontoa.metadatacatalogjava.service.RegistrationService;
import com.github.highcumontoa.metadatacatalogjava.service.TypeArbitrator;
import com.github.highcumontoa.metadatacatalogjava.service.TypeDictionary;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体来源清单与每个来源去向的收敛一致性测试（本次缺陷修复的回归保障）。
 *
 * <p>被测缺陷：多个来源对同一实体同一字段声明不同类型时，实体级来源清单
 * 随提交先后顺序 / 并发调度忽多忽少，与实际参与合并的来源对不上。</p>
 *
 * <p>修复后的收敛规则：实体来源清单是派生值（实体级声明来源 ∪ 各字段当前生效来源），
 * 被裁决取代 / 被拒绝的来源不留在清单中，其去向从字段级 rejectedClaims 拒绝台账查询。
 * 以下用例把同一组声明打乱成多种顺序、并叠加多轮并发混合提交，断言实体种类、
 * 字段规范定义、生效与被拒来源去向、实体来源清单在各轮之间完全一致。</p>
 *
 * 每次输入、判定依据与各用例结果均通过日志打印（服务层 [REGISTER] 前缀 + 用例日志），
 * 全部本地完成，不依赖外部服务。
 */
class SourceListConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(SourceListConvergenceTest.class);

    private static RegistrationService newService(LocalMetadataStore store) {
        return new RegistrationService(store, new TypeDictionary(), new PermissionService(), new TypeArbitrator());
    }

    /**
     * 目录最终状态的确定性快照：实体种类、实体来源清单、每个字段的规范类型 /
     * 生效来源 / 拒绝台账。字段名与来源名均排序，与落库先后无关。
     */
    private static String snapshot(LocalMetadataStore store, String qn) {
        EntityDef e = store.getEntity(qn);
        StringBuilder sb = new StringBuilder();
        sb.append("kind=").append(e.getKind());
        sb.append(" | entitySources=").append(e.getSources());
        for (Map.Entry<String, FieldDef> entry : new TreeMap<>(e.getFields()).entrySet()) {
            FieldDef f = entry.getValue();
            sb.append(" | field ").append(entry.getKey())
                    .append(": type=").append(f.getCanonicalType())
                    .append(", effective=").append(new TreeSet<>(f.getSources().keySet()))
                    .append(", rejected=").append(new TreeSet<>(f.getRejectedClaims().keySet()));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("乱序收敛：同一组冲突+相同+书写差异声明打乱 12 种顺序，实体来源清单与每个来源去向完全一致")
    void shuffledOrdersConvergeOnEntitySourceListAndDispositions() {
        // 声明集合（每个来源一个字段批次，保证批次原子性下集合本身可收敛）：
        // v 字段四种类型互相冲突（DECIMAL 优先级最高）；id 字段两个来源类型别名归一后
        // 相同（int / integer -> INTEGER）；实体名与字段名带大小写 / 首尾空白变体。
        record Claim(String source, String qn, String fieldName, String rawType) {
            RegistrationRequest toRequest() {
                return new RegistrationRequest(source, qn, "TABLE",
                        List.of(new FieldClaim(fieldName, rawType)));
            }
        }
        List<Claim> claims = List.of(
                new Claim("src-decimal", "db.src", "v", "decimal"),
                new Claim("src-double", "DB.SRC", " V ", "double"),
                new Claim("src-string", " db.Src ", "v ", "string"),
                new Claim("src-bool", "Db.SRC ", "V", "boolean"),
                new Claim("src-id-1", "db.src", " ID ", "int"),
                new Claim("src-id-2", "DB.SRC", "Id", "integer"));
        List<String> rejectedSources = List.of("src-double", "src-string", "src-bool");

        String baseline = null;
        for (int round = 0; round < 12; round++) {
            LocalMetadataStore store = new LocalMetadataStore();
            RegistrationService reg = newService(store);
            List<Claim> shuffled = new ArrayList<>(claims);
            Collections.shuffle(shuffled, new Random(round * 97L + 13));
            log.info("[乱序] 第{}轮提交顺序={}", round,
                    shuffled.stream().map(c -> c.source() + "(" + c.fieldName().trim() + ":" + c.rawType() + ")")
                            .toList());
            for (Claim c : shuffled) {
                RegistrationResult r = reg.register(c.toRequest());
                log.info("[乱序] 第{}轮 {} -> decision={}, reason={}", round, c.source(), r.decision(), r.reason());
            }
            String snap = snapshot(store, "db.src");
            log.info("[乱序] 第{}轮最终快照：{}", round, snap);
            if (baseline == null) {
                baseline = snap;
            } else {
                assertEquals(baseline, snap, "第" + round + "轮：最终状态必须与第0轮完全一致，与提交顺序无关");
            }

            // ---- 显式断言收敛后的预期内容 ----
            EntityDef e = store.getEntity("db.src");
            assertEquals(EntityKind.TABLE, e.getKind(), "第" + round + "轮：实体种类一致");
            FieldDef v = e.getFields().get("v");
            assertEquals(DataType.DECIMAL, v.getCanonicalType(),
                    "第" + round + "轮：v 收敛到固定优先级最高的 DECIMAL");
            assertEquals(Set.of("src-decimal"), v.getSources().keySet(),
                    "第" + round + "轮：v 只有最高优先级来源生效");
            assertEquals(Set.copyOf(rejectedSources), v.getRejectedClaims().keySet(),
                    "第" + round + "轮：v 落败来源全部在拒绝台账（去向可查询）");
            FieldDef id = e.getFields().get("id");
            assertEquals(DataType.INTEGER, id.getCanonicalType(),
                    "第" + round + "轮：id 的类型别名归一到 INTEGER");
            assertEquals(Set.of("src-id-1", "src-id-2"), id.getSources().keySet(),
                    "第" + round + "轮：id 两个相同类型来源都生效合并");
            // 实体来源清单 = 各字段生效来源的并集：只声明过落败类型的来源不得出现
            assertEquals(Set.of("src-decimal", "src-id-1", "src-id-2"), e.getSources(),
                    "第" + round + "轮：实体来源清单必须恰好是实际生效的来源集合");
            // 每个来源的去向二选一可查询：在清单中=生效；不在清单中=可在拒绝台账查到
            for (String s : rejectedSources) {
                assertFalse(e.getSources().contains(s),
                        "第" + round + "轮：被取代/被拒绝来源 " + s + " 不得留在清单");
                assertTrue(v.getRejectedClaims().containsKey(s),
                        "第" + round + "轮：来源 " + s + " 不在清单中，其去向必须能在拒绝台账查到");
            }
        }
        log.info("[乱序] 12 种提交顺序最终快照完全一致：{}", baseline);
    }

    @Test
    @DisplayName("并发收敛：冲突+相同类型+书写变体混合并发 20 轮，实体来源清单与来源去向与线程调度无关")
    void concurrentMixedSubmissionsConvergeAcrossRounds() throws InterruptedException {
        int threads = 8;
        String baseline = null;
        for (int round = 0; round < 20; round++) {
            LocalMetadataStore store = new LocalMetadataStore();
            RegistrationService reg = newService(store);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            String[] qnVariants = {"db.par", "DB.PAR", " db.Par ", "Db.PAR "};
            String[] fieldVariants = {"v", "V", " v ", "V "};
            // src-0/1 decimal（相同类型合并），src-2/3 double，src-4/5 bigint，src-6/7 string（均冲突）
            String[] types = {"decimal", "decimal", "double", "double", "bigint", "bigint", "string", "string"};
            List<RegistrationResult> results = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(reg.register(new RegistrationRequest("src-" + idx,
                                qnVariants[idx % qnVariants.length], "TABLE",
                                List.of(new FieldClaim(fieldVariants[idx % fieldVariants.length], types[idx])))));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "第" + round + "轮：并发提交必须全部完成");

            String snap = snapshot(store, "db.par");
            if (round == 0) {
                log.info("[并发] 第0轮各请求结论={}",
                        results.stream().map(r -> r.decision() + "/" + r.reason()).toList());
            }
            log.info("[并发] 第{}轮最终快照：{}", round, snap);
            if (baseline == null) {
                baseline = snap;
            } else {
                assertEquals(baseline, snap, "第" + round + "轮：并发最终状态必须与第0轮一致，与线程调度无关");
            }

            EntityDef e = store.getEntity("db.par");
            assertEquals(1, store.allEntities().size(), "第" + round + "轮：书写变体归一为同一实体");
            FieldDef v = e.getFields().get("v");
            assertEquals(DataType.DECIMAL, v.getCanonicalType(), "第" + round + "轮：规范类型收敛到 DECIMAL");
            assertEquals(Set.of("src-0", "src-1"), v.getSources().keySet(),
                    "第" + round + "轮：两个 decimal 来源都生效");
            assertEquals(Set.of("src-2", "src-3", "src-4", "src-5", "src-6", "src-7"),
                    v.getRejectedClaims().keySet(), "第" + round + "轮：落败来源全部入拒绝台账");
            assertEquals(Set.of("src-0", "src-1"), e.getSources(),
                    "第" + round + "轮：实体来源清单恰好是生效来源，被取代/被拒绝来源不出现");
        }
        log.info("[并发] 20 轮并发混合提交最终快照完全一致：{}", baseline);
    }

    @Test
    @DisplayName("最小复现：两份冲突声明两种顺序，逐请求结论可不同但最终状态与来源清单完全一致")
    void twoOrderingsOfSamePairConverge() {
        // 顺序一：低优先级先提交（先接受，后被取代）
        LocalMetadataStore store1 = new LocalMetadataStore();
        RegistrationService reg1 = newService(store1);
        RegistrationResult a1 = reg1.register(new RegistrationRequest("src-a", "db.pair", "TABLE",
                List.of(new FieldClaim("v", "int"))));
        RegistrationResult b1 = reg1.register(new RegistrationRequest("src-b", "db.pair", "TABLE",
                List.of(new FieldClaim("v", "bigint"))));
        log.info("[最小复现] 顺序一 a:int 先 b:bigint 后 -> a={}, b={}", a1.decision(), b1.decision());
        assertEquals(Decision.ACCEPTED, a1.decision());
        assertEquals(Decision.MERGED, b1.decision(), "高优先级后到：取代既有规范");

        // 顺序二：高优先级先提交（低优先级后到被 TYPE_CONFLICT 拒绝）
        LocalMetadataStore store2 = new LocalMetadataStore();
        RegistrationService reg2 = newService(store2);
        RegistrationResult b2 = reg2.register(new RegistrationRequest("src-b", "db.pair", "TABLE",
                List.of(new FieldClaim("v", "bigint"))));
        RegistrationResult a2 = reg2.register(new RegistrationRequest("src-a", "db.pair", "TABLE",
                List.of(new FieldClaim("v", "int"))));
        log.info("[最小复现] 顺序二 b:bigint 先 a:int 后 -> b={}, a={}/{}", b2.decision(), a2.decision(), a2.reason());
        assertEquals(Decision.ACCEPTED, b2.decision());
        assertEquals(Decision.REJECTED, a2.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, a2.reason());

        String snap1 = snapshot(store1, "db.pair");
        String snap2 = snapshot(store2, "db.pair");
        log.info("[最小复现] 顺序一最终快照：{}", snap1);
        log.info("[最小复现] 顺序二最终快照：{}", snap2);
        assertEquals(snap1, snap2, "两种顺序的最终目录状态必须完全一致（修复前实体来源清单为 {src-a,src-b} vs {src-b}）");

        EntityDef e = store1.getEntity("db.pair");
        assertEquals(Set.of("src-b"), e.getSources(), "实体来源清单只含最终生效来源");
        assertEquals("int", e.getFields().get("v").getRejectedClaims().get("src-a"),
                "src-a 的去向（被取代/被拒绝）必须能从拒绝台账查到，两种顺序一致");
    }

    @Test
    @DisplayName("零字段批次：实体级声明来源纳入清单，与字段来源的合并两种顺序一致")
    void zeroFieldDeclarationRecordedAndConverges() {
        // 顺序一：零字段批次先，字段批次后
        LocalMetadataStore store1 = new LocalMetadataStore();
        RegistrationService reg1 = newService(store1);
        var d1 = reg1.register(new RegistrationRequest("doc-system", "db.z", "TABLE", List.of()));
        var h1 = reg1.register(new RegistrationRequest("hive", "db.z", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("[零字段] 顺序一 doc-system(零字段)={}，hive(id:bigint)={}", d1.decision(), h1.decision());

        // 顺序二：字段批次先，零字段批次后
        LocalMetadataStore store2 = new LocalMetadataStore();
        RegistrationService reg2 = newService(store2);
        var h2 = reg2.register(new RegistrationRequest("hive", "db.z", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        var d2 = reg2.register(new RegistrationRequest("doc-system", "db.z", "TABLE", List.of()));
        log.info("[零字段] 顺序二 hive(id:bigint)={}，doc-system(零字段)={}", h2.decision(), d2.decision());

        String snap1 = snapshot(store1, "db.z");
        String snap2 = snapshot(store2, "db.z");
        log.info("[零字段] 顺序一快照：{} | 顺序二快照：{}", snap1, snap2);
        assertEquals(snap1, snap2, "零字段批次与字段批次两种顺序最终状态一致");
        assertEquals(Set.of("doc-system", "hive"), store1.getEntity("db.z").getSources(),
                "实体级声明来源（零字段批次）与字段生效来源都必须体现在清单中");
    }
}

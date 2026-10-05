package com.github.highcumontoa.metadatacatalogjava.tests;

import com.github.highcumontoa.metadatacatalogjava.dto.FieldClaim;
import com.github.highcumontoa.metadatacatalogjava.dto.RegistrationRequest;
import com.github.highcumontoa.metadatacatalogjava.model.DataType;
import com.github.highcumontoa.metadatacatalogjava.model.Decision;
import com.github.highcumontoa.metadatacatalogjava.model.EntityDef;
import com.github.highcumontoa.metadatacatalogjava.model.EntityKind;
import com.github.highcumontoa.metadatacatalogjava.model.FieldDef;
import com.github.highcumontoa.metadatacatalogjava.model.RejectReason;
import com.github.highcumontoa.metadatacatalogjava.model.SourceDisposition;
import com.github.highcumontoa.metadatacatalogjava.service.PermissionService;
import com.github.highcumontoa.metadatacatalogjava.service.RegistrationService;
import com.github.highcumontoa.metadatacatalogjava.service.TypeArbitrator;
import com.github.highcumontoa.metadatacatalogjava.service.TypeDictionary;
import com.github.highcumontoa.metadatacatalogjava.store.LocalMetadataStore;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源清单收敛测试：同一组声明无论以什么顺序提交、多少并发混合提交，
 * 实体的种类、字段规范定义、实体来源清单、每个来源的最终去向
 * （合并生效 EFFECTIVE / 被取代 SUPERSEDED / 被拒绝 REJECTED）都必须完全一致。
 * 每轮打印输入顺序、判定依据与最终快照，多轮重复以证明与线程调度无关。
 */
class SourceListConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(SourceListConvergenceTest.class);

    private LocalMetadataStore store;
    private RegistrationService registration;

    @BeforeEach
    void setUp() {
        store = new LocalMetadataStore();
        registration = new RegistrationService(store, new TypeDictionary(),
                new PermissionService(), new TypeArbitrator());
    }

    /** 实体最终状态的确定性快照：种类 + 各字段规范定义与台账 + 来源清单 + 每个来源去向。 */
    private static String snapshot(EntityDef e) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind=").append(e.getKind());
        sb.append(" | sources=").append(e.getSources());
        sb.append(" | outcomes=").append(e.getSourceOutcomes());
        sb.append(" | fields={");
        e.getFields().forEach((name, f) -> sb.append(name)
                .append("(canonical=").append(f.getCanonicalType())
                .append(", effective=").append(new TreeMap<>(f.getSources()))
                .append(", rejected=").append(new TreeMap<>(f.getRejectedClaims()))
                .append(") "));
        sb.append("}");
        return sb.toString();
    }

    @Test
    @DisplayName("乱序收敛：同一组冲突声明打乱 12 种顺序提交，来源清单与每个来源去向逐轮一致")
    void shuffledDeclarationsConvergeOnSourceListAndOutcomes() {
        // 同一组声明：四个来源对同一字段声明四种类型（优先级 DECIMAL>BIGINT>INTEGER>STRING）
        List<String[]> declarations = List.of(
                new String[]{"src-int", "int"},
                new String[]{"src-bigint", "bigint"},
                new String[]{"src-string", "string"},
                new String[]{"src-decimal", "decimal"});
        String firstSnapshot = null;
        for (int round = 0; round < 12; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            RegistrationService roundReg = new RegistrationService(roundStore, new TypeDictionary(),
                    new PermissionService(), new TypeArbitrator());
            List<String[]> shuffled = new ArrayList<>(declarations);
            Collections.shuffle(shuffled, new Random(round * 97L + 13));
            log.info("第{}轮输入顺序={}", round,
                    shuffled.stream().map(c -> c[0] + ":" + c[1]).toList());
            for (String[] c : shuffled) {
                var r = roundReg.register(new RegistrationRequest(c[0], "db.src", "TABLE",
                        List.of(new FieldClaim("v", c[1]))));
                log.info("第{}轮 {} -> decision={} reason={}", round, c[0], r.decision(), r.reason());
            }
            EntityDef e = roundStore.getEntity("db.src");
            String snap = snapshot(e);
            log.info("第{}轮最终快照={}", round, snap);

            assertEquals(EntityKind.TABLE, e.getKind(), "第" + round + "轮：实体种类必须一致");
            FieldDef v = e.getFields().get("v");
            assertEquals(DataType.DECIMAL, v.getCanonicalType(),
                    "第" + round + "轮：规范类型收敛到优先级最高的 DECIMAL");
            // 来源清单：只有生效来源在清单里，与提交顺序无关
            assertEquals(Set.of("src-decimal"), e.getSources(),
                    "第" + round + "轮：实体来源清单必须只含生效来源，与顺序无关");
            assertEquals(Set.of("src-decimal"), v.getSources().keySet());
            assertEquals(Set.of("src-int", "src-bigint", "src-string"),
                    v.getRejectedClaims().keySet(),
                    "第" + round + "轮：落败来源全部在拒绝台账，与顺序无关");
            // 每个来源的最终去向：生效 / 被取代，与顺序无关
            Map<String, SourceDisposition> outcomes = e.getSourceOutcomes();
            assertEquals(SourceDisposition.EFFECTIVE, outcomes.get("src-decimal"),
                    "第" + round + "轮：胜出来源去向=合并生效");
            for (String loser : new String[]{"src-int", "src-bigint", "src-string"}) {
                assertEquals(SourceDisposition.SUPERSEDED, outcomes.get(loser),
                        "第" + round + "轮：落败来源 " + loser + " 去向=被取代（先到被取代或后到被拒，最终一致）");
                assertFalse(e.getSources().contains(loser),
                        "第" + round + "轮：被取代来源不得留在来源清单");
            }
            if (firstSnapshot == null) {
                firstSnapshot = snap;
            } else {
                assertEquals(firstSnapshot, snap, "第" + round + "轮：最终快照必须与第0轮完全一致");
            }
        }
        log.info("用例结论：12 种乱序提交的最终快照完全一致（含来源清单与每个来源去向）");
    }

    @Test
    @DisplayName("并发收敛：书写变体 + 冲突/相同类型混合并发 20 轮，来源清单与去向与线程调度无关")
    void concurrentMixedDeclarationsConvergeOnSourceListAndOutcomes() throws InterruptedException {
        int threads = 8;
        String firstSnapshot = null;
        for (int round = 0; round < 20; round++) {
            LocalMetadataStore roundStore = new LocalMetadataStore();
            RegistrationService roundReg = new RegistrationService(roundStore, new TypeDictionary(),
                    new PermissionService(), new TypeArbitrator());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            // 实体名带大小写/空白变体；src-0..3 声明 decimal（相同类型），src-4..7 声明 bigint（互相冲突）
            String[] nameVariants = {"db.mix", "DB.MIX", " db.Mix ", "Db.mix"};
            for (int i = 0; i < threads; i++) {
                String source = "src-" + i;
                String qn = nameVariants[i % nameVariants.length];
                String type = (i < 4) ? "decimal" : "bigint";
                pool.submit(() -> {
                    try {
                        start.await();
                        roundReg.register(new RegistrationRequest(source, qn, "TABLE",
                                List.of(new FieldClaim("v", type))));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(1, roundStore.allEntities().size(), "第" + round + "轮：书写变体归一为同一实体");
            EntityDef e = roundStore.getEntity("db.mix");
            String snap = snapshot(e);
            if (round == 0) {
                log.info("第0轮并发输入=src-0..3:decimal + src-4..7:bigint（实体名书写变体），最终快照={}", snap);
            }
            assertEquals(DataType.DECIMAL, e.getFields().get("v").getCanonicalType(),
                    "第" + round + "轮：规范类型收敛到 DECIMAL");
            assertEquals(Set.of("src-0", "src-1", "src-2", "src-3"), e.getSources(),
                    "第" + round + "轮：来源清单=4 个生效来源，与调度无关");
            Map<String, SourceDisposition> outcomes = e.getSourceOutcomes();
            for (int i = 0; i < threads; i++) {
                SourceDisposition expected = i < 4
                        ? SourceDisposition.EFFECTIVE : SourceDisposition.SUPERSEDED;
                assertEquals(expected, outcomes.get("src-" + i),
                        "第" + round + "轮：src-" + i + " 去向必须为 " + expected);
            }
            if (firstSnapshot == null) {
                firstSnapshot = snap;
            } else {
                assertEquals(firstSnapshot, snap, "第" + round + "轮：并发最终快照必须与第0轮完全一致");
            }
        }
        log.info("用例结论：20 轮并发混合提交最终快照完全一致，来源清单与去向不随线程调度变化");
    }

    @Test
    @DisplayName("去向可查询：生效 / 被取代 / 被拒绝三类去向与来源清单互相对得上，且拒绝边界不变")
    void sourceOutcomesAreQueryableAndConsistentWithSourceList() {
        // 生效来源
        registration.register(new RegistrationRequest("hive", "db.out", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        // 被取代来源：低优先级类型后被拒（若先到则被取代，殊途同归）
        var loser = registration.register(new RegistrationRequest("mysql", "db.out", "TABLE",
                List.of(new FieldClaim("id", "int"))));
        log.info("输入 mysql/id:int -> decision={} reason={}（判定依据：固定类型优先级 BIGINT>INTEGER）",
                loser.decision(), loser.reason());
        assertEquals(Decision.REJECTED, loser.decision());
        assertEquals(RejectReason.TYPE_CONFLICT, loser.reason());
        // 被拒绝来源：种类不符（既有判定边界保持不变）
        var kindConflict = registration.register(new RegistrationRequest("kafka", "db.out", "STREAM",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("输入 kafka/STREAM -> decision={} reason={}（判定依据：种类不符拒绝合并）",
                kindConflict.decision(), kindConflict.reason());
        assertEquals(RejectReason.KIND_CONFLICT, kindConflict.reason());

        EntityDef e = store.getEntity("db.out");
        Map<String, SourceDisposition> outcomes = e.getSourceOutcomes();
        log.info("来源清单={} 每个来源去向={}", e.getSources(), outcomes);
        assertEquals(Set.of("hive"), e.getSources(), "来源清单只含生效来源");
        assertEquals(SourceDisposition.EFFECTIVE, outcomes.get("hive"));
        assertEquals(SourceDisposition.SUPERSEDED, outcomes.get("mysql"),
                "类型裁决落败来源去向=被取代，原始声明见字段拒绝台账");
        assertEquals("int", e.getFields().get("id").getRejectedClaims().get("mysql"));
        assertEquals(SourceDisposition.REJECTED, outcomes.get("kafka"),
                "种类不符来源去向=被拒绝");
        // 清单与去向互相对得上：清单 = 去向为 EFFECTIVE 的来源
        outcomes.forEach((source, disposition) -> {
            if (disposition == SourceDisposition.EFFECTIVE) {
                assertTrue(e.getSources().contains(source), "生效来源必须在清单中：" + source);
            } else {
                assertFalse(e.getSources().contains(source), "非生效来源不得在清单中：" + source);
            }
        });
        // 规范数据未被污染
        assertEquals(EntityKind.TABLE, e.getKind());
        assertEquals(DataType.BIGINT, e.getFields().get("id").getCanonicalType());

        // 被取代来源改以符合规范的类型重新登记 -> 翻转为生效，台账同步清除
        var retry = registration.register(new RegistrationRequest("mysql", "db.out", "TABLE",
                List.of(new FieldClaim("id", "bigint"))));
        log.info("输入 mysql/id:bigint（改投规范类型） -> decision={}", retry.decision());
        assertEquals(Decision.MERGED, retry.decision());
        assertEquals(SourceDisposition.EFFECTIVE, store.getEntity("db.out")
                .getSourceOutcomes().get("mysql"), "重新合规登记后来源去向=合并生效");
        assertTrue(store.getEntity("db.out").getSources().contains("mysql"));
        assertFalse(store.getEntity("db.out").getFields().get("id").getRejectedClaims()
                .containsKey("mysql"), "生效后来源在台账中的记录应清除（最终去向以生效为准）");
        log.info("用例结论：三类去向可查询、与来源清单一致，重新合规登记可翻转为生效");
    }

    @Test
    @DisplayName("多字段来源：部分字段被取代时来源仍为生效，落败字段去向入台账，多种顺序结果一致")
    void partiallySupersededSourceStaysEffective() {
        // src-a 的合法字段与冲突字段分两个批次声明（混合批次整批拒绝是既有边界，见
        // NormalizationConvergenceTest.mixedBatchRejectedAtomicallyWithoutPollution，
        // 本用例验证多来源分批声明同一实体的收敛性）
        List<String[]> batches = List.of(
                new String[]{"src-a", "keep", "string"},
                new String[]{"src-a", "v", "int"},
                new String[]{"src-b", "v", "decimal"});
        String firstSnapshot = null;
        for (int round = 0; round < 6; round++) {
            LocalMetadataStore s = new LocalMetadataStore();
            RegistrationService reg = new RegistrationService(s, new TypeDictionary(),
                    new PermissionService(), new TypeArbitrator());
            List<String[]> shuffled = new ArrayList<>(batches);
            Collections.shuffle(shuffled, new Random(round * 41L + 5));
            log.info("第{}轮输入顺序={}", round, shuffled.stream()
                    .map(c -> c[0] + "/" + c[1] + ":" + c[2]).toList());
            for (String[] c : shuffled) {
                reg.register(new RegistrationRequest(c[0], "db.multi", "TABLE",
                        List.of(new FieldClaim(c[1], c[2]))));
            }
            EntityDef e = s.getEntity("db.multi");
            String snap = snapshot(e);
            log.info("第{}轮最终快照={}", round, snap);
            // src-a 的 keep 字段仍生效 -> 来源整体为生效；v 字段落败 -> 入该字段台账
            assertEquals(SourceDisposition.EFFECTIVE, e.getSourceOutcomes().get("src-a"),
                    "第" + round + "轮：仍有生效字段的来源去向=合并生效");
            assertEquals(SourceDisposition.EFFECTIVE, e.getSourceOutcomes().get("src-b"));
            assertEquals(Set.of("src-a", "src-b"), e.getSources(),
                    "第" + round + "轮：两个来源都在清单中，与顺序无关");
            assertEquals(DataType.DECIMAL, e.getFields().get("v").getCanonicalType());
            assertEquals("int", e.getFields().get("v").getRejectedClaims().get("src-a"),
                    "第" + round + "轮：落败字段的原始声明入台账，与顺序无关");
            if (firstSnapshot == null) {
                firstSnapshot = snap;
            } else {
                assertEquals(firstSnapshot, snap, "第" + round + "轮：最终快照必须与第0轮完全一致");
            }
        }
        log.info("用例结论：多字段来源部分被取代时，来源清单与去向在多种顺序下完全一致");
    }
}

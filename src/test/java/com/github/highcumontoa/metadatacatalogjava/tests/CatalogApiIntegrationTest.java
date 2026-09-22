package com.github.highcumontoa.metadatacatalogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端集成测试：登记合并 / 冲突拒绝 / 血缘成环 / 破坏性变更 / 权限与审计，
 * 服务层日志（[REGISTER]/[LINEAGE]/[CHANGE]）中打印输入与判定依据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CatalogApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("端到端：登记->冲突拒绝->血缘成环->破坏性变更->权限拒绝->审计可查")
    void fullFlow() throws Exception {
        // 1. 登记实体
        String entity = """
                {"source":"hive","qualifiedName":"ods.user","kind":"TABLE",
                 "fields":[{"name":"id","rawType":"bigint"},{"name":"name","rawType":"varchar"}]}
                """;
        mockMvc.perform(post("/api/catalog/entities")
                        .contentType(MediaType.APPLICATION_JSON).content(entity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ACCEPTED"));
        log.info("端到端步骤1 登记 ods.user -> ACCEPTED");

        mockMvc.perform(post("/api/catalog/entities")
                        .contentType(MediaType.APPLICATION_JSON).content(entity))
                .andExpect(jsonPath("$.decision").value("NO_CHANGE"));
        log.info("端到端步骤2 重复登记 -> NO_CHANGE");

        // 2. 类型冲突登记被拒
        String conflict = """
                {"source":"mysql","qualifiedName":"ods.user","kind":"TABLE",
                 "fields":[{"name":"id","rawType":"string"}]}
                """;
        mockMvc.perform(post("/api/catalog/entities")
                        .contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("TYPE_CONFLICT"));
        log.info("端到端步骤3 id bigint vs string -> REJECTED/TYPE_CONFLICT");

        // 3. 第二个实体 + 血缘，然后尝试成环
        String order = """
                {"source":"hive","qualifiedName":"ods.order","kind":"TABLE",
                 "fields":[{"name":"uid","rawType":"bigint"}]}
                """;
        mockMvc.perform(post("/api/catalog/entities")
                .contentType(MediaType.APPLICATION_JSON).content(order)).andExpect(status().isOk());

        mockMvc.perform(post("/api/catalog/lineage").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"hive","upstream":"ods.user","downstream":"ods.order"}"""))
                .andExpect(jsonPath("$.decision").value("ACCEPTED"));
        mockMvc.perform(post("/api/catalog/lineage").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"hive","upstream":"ods.order","downstream":"ods.user"}"""))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("CYCLE_DETECTED"));
        log.info("端到端步骤4 反向血缘 -> REJECTED/CYCLE_DETECTED");

        // 多跳影响
        String impactBody = mockMvc.perform(get("/api/catalog/impact/downstream/ods.user"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode impact = json.readTree(impactBody);
        log.info("端到端步骤5 下游影响={}", impactBody);
        org.junit.jupiter.api.Assertions.assertEquals("ods.order",
                impact.path("hops").get(0).path("entities").get(0).asText());

        // 4. 破坏性变更（删除字段）被拒
        String drop = """
                {"operator":"admin","qualifiedName":"ods.user",
                 "ops":[{"op":"DROP","fieldName":"name"}]}
                """;
        mockMvc.perform(post("/api/catalog/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(drop))
                .andExpect(jsonPath("$.reason").value("FIELD_REMOVED_INCOMPATIBLE"));
        log.info("端到端步骤6 DROP name -> FIELD_REMOVED_INCOMPATIBLE");

        // 5. 权限：白名单后非授权操作者被拒
        mockMvc.perform(post("/api/catalog/permissions/ods.user").param("operator", "bob"))
                .andExpect(status().isOk());
        String addByCarol = """
                {"operator":"carol","qualifiedName":"ods.user",
                 "ops":[{"op":"ADD","fieldName":"x","rawType":"string"}]}
                """;
        mockMvc.perform(post("/api/catalog/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(addByCarol))
                .andExpect(jsonPath("$.reason").value("PERMISSION_DENIED"));
        log.info("端到端步骤7 carol 非白名单 -> PERMISSION_DENIED");

        // 6. 审计可追溯
        String auditBody = mockMvc.perform(get("/api/catalog/audit").param("qualifiedName", "ods.user"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode audit = json.readTree(auditBody);
        log.info("端到端步骤8 ods.user 审计条数={}", audit.size());
        org.junit.jupiter.api.Assertions.assertTrue(audit.size() >= 5, "接受与拒绝均应有审计记录");

        // 7. 移除实体后无悬空血缘
        mockMvc.perform(delete("/api/catalog/entities/ods.order").param("operator", "admin"))
                .andExpect(jsonPath("$.decision").value("ACCEPTED"));
        mockMvc.perform(get("/api/catalog/entities"))
                .andExpect(jsonPath("$.ods.order").doesNotExist());
        log.info("端到端步骤9 移除 ods.order，血缘级联清理完成");
    }
}

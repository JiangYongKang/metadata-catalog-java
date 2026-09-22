package com.github.highcumontoa.metadatacatalogjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end REST check: rejection codes are exposed on the wire and
 * audit is queryable. Uses only the locally running Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void registerConflictRejectAndAuditOverHttp() throws Exception {
        mvc.perform(post("/api/catalog/register")
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"hive","entity":"web_orders","kind":"table","fields":[
                                  {"name":"id","type":"bigint","nullable":false},
                                  {"name":"amount","type":"decimal(10,2)","nullable":true}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        mvc.perform(post("/api/catalog/register")
                        .header("X-Actor", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"mysql","entity":"WEB_ORDERS","kind":"table","fields":[
                                  {"name":"amount","type":"varchar(32)","nullable":true}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.conflictFields[0]").value("amount"));

        // illegal type -> 422 with distinguishable reason
        mvc.perform(post("/api/catalog/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"bad","entity":"web_orders","kind":"table","fields":[
                                  {"name":"x","type":"nope","nullable":true}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("ILLEGAL_FIELD_TYPE"));

        // breaking change -> 422 + reason
        mvc.perform(post("/api/catalog/entities/web_orders/fields/id/type")
                        .header("X-Actor", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"int\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("BREAKING_TYPE_CHANGE"));

        // lineage happy path, then cycle
        mvc.perform(post("/api/catalog/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"hive","entity":"web_order_items","kind":"table","fields":[
                                  {"name":"oid","type":"bigint","nullable":false}]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/catalog/lineage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":\"web_orders\",\"downstream\":\"web_order_items\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADDED"));
        mvc.perform(post("/api/catalog/lineage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":\"web_order_items\",\"downstream\":\"web_orders\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("LINEAGE_CYCLE"));

        mvc.perform(get("/api/catalog/lineage/impact/web_orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downstream[0]").value("web_order_items"));

        // audit recorded both outcomes with actors
        mvc.perform(get("/api/catalog/audit?entity=web_orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.allowed==false && @.reason=='BREAKING_TYPE_CHANGE')].actor")
                        .value(org.hamcrest.Matchers.hasItem("carol")));
    }
}

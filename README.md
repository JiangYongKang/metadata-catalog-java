# 本地元数据目录与血缘服务（metadata-catalog-java）

一个完全本地可复现的 Spring Boot 服务：用内存存储模拟多个元数据来源（hive / mysql / kafka 等）
对同一实体与字段的声明，完成**归一化登记、血缘管理、兼容性判定、权限控制与审计追溯**。
不依赖任何外部服务、数据库或凭据；启动后仅通过本地 HTTP 或单元测试即可验证全部能力。

## 1. 快速开始

```bash
# 运行全部测试（21 个，含并发登记 / 循环血缘 / 破坏性变更 / 权限拒绝 / HTTP 端到端）
mvn test

# 启动服务
mvn spring-boot:run
```

服务默认监听 `http://localhost:8080`，所有写操作可用请求头 `X-Actor` 标识操作人（默认 `anonymous`）。

## 2. 元数据模型

| 元素 | 说明 |
| --- | --- |
| `SourceDeclaration` | 某个来源对一个实体的一次声明：`source / entity / kind / fields[]` |
| `EntityDefinition` | 归一化后的实体：限定名、显示名、种类（TABLE/TOPIC/FILE/API）、字段表、来源集合、`deprecated`、`readOnly` |
| `FieldDefinition` | 归一化字段：规范类型、可空性、声明来源集合、**每个来源的原始类型声明** `declaredTypes`、`conflicted` 冲突标记 |
| `AuditRecord` | 只追加审计条目：全局递增序号、时间、动作、目标、操作人、是否放行、原因码、判定依据 |

**归一化规则**

- 限定名 / 字段名统一 `trim + lower-case`，故 `Orders`、`orders`、` ORDERS ` 指向同一实体；显示名保留首次见到的拼写。
- 原始类型映射为规范类型（`varchar/char/text→STRING`、`int/smallint→INTEGER`、`bigint→LONG`、
  `decimal/numeric/double/float→DECIMAL`、`datetime→TIMESTAMP` 等），支持 `varchar(255)`、`decimal(10,2)` 这类带长度参数的写法。
- 所有实体/字段快照不可变，任何变更都产出新快照；历史快照与审计记录不受影响。

**登记结论（`RegistrationStatus`，可区分）**

- `REGISTERED`：新实体登记；
- `MERGED`：合入已有实体并带来了新信息（新字段 / 新来源 / 可空性变化等）；
- `DUPLICATE`：同一来源重复提交了完全一致的声明，幂等忽略；
- `CONFLICT`：成功合入，但至少一个字段各来源的规范类型不一致——**冲突字段被显式标记 `conflicted=true`，
  所有原始声明都保留在 `declaredTypes` 中，绝不静默覆盖或丢弃**；
  展示用的规范类型按确定规则选取（来源 id 字典序最小者的声明），因此与并发到达顺序无关；
- `REJECTED`：输入不合法，见下方拒绝原因。

**并发一致性**：每个实体限定名对应一把锁，同一实体的登记/变更串行化；冲突解决只依赖声明内容
（来源 id 与类型字符串）而非到达时机。因此多线程重复或并发登记同一实体，最终状态完全一致。

## 3. 血缘规则

边 `upstream -> downstream` 表示数据从上游流向下游。

- **端点校验**：两端实体必须已登记，否则拒绝（`LINEAGE_ENDPOINT_NOT_FOUND`）。
- **自引用拒绝**：`a -> a`（忽略大小写）拒绝，原因 `LINEAGE_SELF_REFERENCE`。
- **循环拒绝**：仅当新边会使图成环时拒绝。判定方式：若下游节点当前已经能到达上游节点，
  则新边成环；拒绝信息会给出闭环路径（`LINEAGE_CYCLE`）。
- **幂等**：重复登记同一条边返回 `DUPLICATE`，邻接集合不会重复计数。
- **多跳影响查询**：`GET /api/catalog/lineage/impact/{name}` 返回全部传递下游实体
  （按名字排序）以及每个实体距根节点的**最短跳数**。
- **无悬空血缘**：删除实体时自动摘除其所有上下游边并注销端点；之后再登记相关边会因端点不存在被拒绝。

## 4. 兼容性策略（统一、可解释）

全服务统一采用**向后（读端）兼容**策略，集中在 `SchemaCompatibilityService`：

| 变更 | 结论 |
| --- | --- |
| 新增字段 | 兼容（老读端忽略新字段），`APPLIED` |
| 删除字段 | **破坏性**，拒绝 `BREAKING_FIELD_REMOVAL` |
| 类型加宽 `INTEGER→LONG/DECIMAL/STRING`、`LONG→DECIMAL/STRING`、`DATE→TIMESTAMP/STRING`、`BOOLEAN→STRING` | 兼容，`APPLIED` |
| 其他类型变更（如缩窄、`STRING→INTEGER`、`DECIMAL→LONG`） | **破坏性**，拒绝 `BREAKING_TYPE_CHANGE`，响应包含策略文本 |
| 类型/字段无变化 | `COMPATIBLE_NO_CHANGE` |

**权限 / 生命周期**

- 实体可被 `deprecate`（废弃）或 `setReadOnly`（只读，模拟无修改权限）。
- 处于任一状态的实体，所有写操作（加字段 / 删字段 / 改类型 / 再次废弃等）一律拒绝，
  且原因可区分：`ENTITY_DEPRECATED` 与 `ENTITY_READ_ONLY`；读操作不受影响。
- 来自来源的 `register` 属于声明对账，即使实体已废弃也允许合入（真实系统中权威来源仍可对账元数据），
  人工写操作才受生命周期闸门限制。

**审计**：无论成功还是拒绝（含所有破坏性变更与权限拒绝），都会写一条只追加的 `AuditRecord`，
含操作人、原因码与判定依据；序号由原子计数器分配，并发下保持全序。
查询：`GET /api/catalog/audit`（全部）、`GET /api/catalog/audit?entity=orders`（按实体）。

## 5. 拒绝原因分类（`RejectReason`）

| 类别 | 原因码 | 触发场景 |
| --- | --- | --- |
| 输入问题 | `BLANK_SOURCE` / `BLANK_ENTITY_NAME` / `BLANK_FIELD_NAME` | 来源、实体名或字段名为空 |
| 输入问题 | `ILLEGAL_FIELD_TYPE` | 类型字符串无法映射到任何规范类型（如 `weirdtype`） |
| 输入问题 | `KIND_MISMATCH` | 同一实体被声明为不同种类（table vs topic） |
| 实体状态 | `ENTITY_NOT_FOUND` / `FIELD_NOT_FOUND` | 目标实体/字段不存在 |
| 实体状态 | `ENTITY_DEPRECATED` | 对已废弃实体执行写操作 |
| 实体状态 | `ENTITY_READ_ONLY` | 对只读实体执行写操作（无修改权限） |
| 兼容性 | `BREAKING_TYPE_CHANGE` | 类型变更不满足加宽策略 |
| 兼容性 | `BREAKING_FIELD_REMOVAL` | 删除字段（始终破坏性） |
| 血缘 | `LINEAGE_ENDPOINT_NOT_FOUND` | 血缘端点未登记 |
| 血缘 | `LINEAGE_SELF_REFERENCE` | 自引用边 |
| 血缘 | `LINEAGE_CYCLE` | 新边会形成环（返回闭环路径） |

REST 层拒绝统一返回 HTTP `422 Unprocessable Entity`，响应体携带 `rejectReason` 与 `message`；
实体不存在的查询/删除返回 `404`。

## 6. HTTP 接口一览

```
POST   /api/catalog/register                              # 登记来源声明
GET    /api/catalog/entities                              # 实体列表
GET    /api/catalog/entities/{name}                       # 实体详情
POST   /api/catalog/entities/{name}/fields                # 新增字段 {"name","type","nullable"}
DELETE /api/catalog/entities/{name}/fields/{field}        # 删除字段（破坏性，拒绝）
POST   /api/catalog/entities/{name}/fields/{field}/type   # 类型变更 {"type"}
POST   /api/catalog/entities/{name}/deprecate             # 标记废弃
POST   /api/catalog/entities/{name}/readonly              # 设置只读 {"readOnly":true}
DELETE /api/catalog/entities/{name}                       # 删除实体并清理血缘
POST   /api/catalog/lineage                               # 登记血缘 {"upstream","downstream"}
GET    /api/catalog/lineage/impact/{name}                 # 多跳下游影响
GET    /api/catalog/audit[?entity=...]                    # 审计记录
```

### curl 演示

```bash
curl -s -X POST localhost:8080/api/catalog/register -H 'Content-Type: application/json' -H 'X-Actor: alice' \
  -d '{"source":"hive","entity":"orders","kind":"table","fields":[{"name":"id","type":"bigint","nullable":false},{"name":"amount","type":"decimal(10,2)","nullable":true}]}'

curl -s -X POST localhost:8080/api/catalog/register -H 'Content-Type: application/json' \
  -d '{"source":"mysql","entity":"ORDERS","kind":"table","fields":[{"name":"amount","type":"varchar(32)","nullable":true}]}'
# -> status=CONFLICT, conflictFields=["amount"], declaredTypes 中保留两种原始声明

curl -s -X POST localhost:8080/api/catalog/entities/orders/fields/id/type -H 'Content-Type: application/json' \
  -d '{"type":"int"}'   # -> 422 BREAKING_TYPE_CHANGE（bigint 缩窄为 int）

curl -s localhost:8080/api/catalog/audit?entity=orders
```

## 7. 验证方法

`mvn test` 执行以下用例（日志中会打印每条输入与判定依据）：

| 测试类 | 覆盖点 |
| --- | --- |
| `RegistrationTest` | 类型别名/大小写归一、幂等重复、按原因区分的输入拒绝、种类冲突、冲突合入且不覆盖、**16 线程并发登记收敛到唯一确定快照** |
| `LineageTest` | 未知端点、自引用、带路径的循环检测、重复边不重复计数、多跳最短路径、删除实体后无悬空边、**24 线程重复边仅 1 条生效** |
| `SchemaChangeTest` | 加字段兼容、类型加宽/缩窄、删字段拒绝、废弃与只读的可区分拒绝、未知实体/字段、审计同时覆盖成功与拒绝且全序 |
| `CatalogApiIntegrationTest` | 通过 MockMvc 验证登记/冲突/422 原因码/循环血缘/影响查询/审计查询的完整 HTTP 链路 |

测试日志通过 SLF4J 打印 `REGISTER input ... decision ...`、`LINEAGE input ... decision ...`、
`AUDIT seq=...` 等行，便于核对“输入 → 判定依据 → 结论”。

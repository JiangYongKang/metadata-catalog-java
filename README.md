# 本地元数据目录与血缘服务（metadata-catalog-java）

一套**完全本地可复现**的元数据目录与血缘服务：用进程内存储模拟多个来源（hive / mysql / …）
声明的数据实体与字段，提供：

1. 多来源实体 / 字段声明的**归一登记与合并**（缺失、非法、冲突不静默丢弃或覆盖）；
2. 上下游**血缘登记、环检测与多跳影响查询**（自引用 / 成环拒绝、重复不重复计数、删除无级联悬空）；
3. 字段新增 / 删除 / 类型变更的**兼容性判定**（破坏性变更拒绝并说明原因）；
4. **废弃实体、写权限**两类可区分的写保护，以及只追加的**审计轨迹**。

不依赖任何外部服务、数据库或凭据：数据全部保存在内存中，`mvn spring-boot:run` 即可使用。

---

## 1. 元数据模型

统一模型位于 `com.github.highcumontoa.metadatacatalogjava.model`：

| 类型 | 含义 |
| --- | --- |
| `EntityDef` | 实体，按 **qualifiedName（限定名）** 归一；种类为 `TABLE / VIEW / STREAM`；持有字段集合、声明来源集合、`deprecated` 标志 |
| `FieldDef` | 字段：`name`、**canonicalType（规范类型）**、`sources`（来源 -> 该来源声称的原始类型）、`deprecated` |
| `DataType` | 统一规范类型枚举：`INTEGER / BIGINT / DECIMAL / DOUBLE / STRING / BOOLEAN / TIMESTAMP` |
| `LineageEdge` | 有向血缘记录 `(upstream, downstream, source)` |
| `AuditRecord` | 只追加审计：单调序号、时间戳、动作、操作者、目标实体、结论、拒绝原因、明细 |

### 1.1 类型归一字典（`TypeDictionary`）

来源原始类型大小写不敏感地映射到规范类型，例如：

- `int/integer → INTEGER`，`bigint/long → BIGINT`，`decimal/numeric → DECIMAL`，
  `double/float → DOUBLE`；
- `string/varchar/text → STRING`，`boolean/bool → BOOLEAN`，`timestamp/datetime → TIMESTAMP`。

未登记的原始类型**无法归一**，对应字段以 `UNKNOWN_TYPE` 拒绝。

### 1.2 登记的归一与合并规则（`RegistrationService`）

- 实体以 `qualifiedName` 归一：不同来源对同一限定名的声明合并到同一实体；
- 字段以字段名归一：
  - 该来源首次声明该字段且规范类型与既有一致 → 追加来源（`MERGED`）；
  - 该来源此前已以相同规范类型声明 → `NO_CHANGE`（幂等，来源不重复计数）；
  - 规范类型与既有定义不同 → `TYPE_CONFLICT` **拒绝，绝不覆盖既有定义**；
- 整批字段中只要存在一条非法 / 冲突字段，**整批拒绝且不落库任何数据**；
- 结论枚举 `Decision`：`ACCEPTED`（新建）/ `MERGED`（补充合并）/ `NO_CHANGE`（幂等无变化）/ `REJECTED`。

### 1.3 并发确定性

- 每个实体一把锁（`LocalMetadataStore.lockFor`），同一实体的并发登记 / 变更被串行化，
  不存在丢失更新；结论与“把这些请求按某一顺序串行执行”完全一致；
- 同字段冲突类型并发提交时，**恰好一个声明建立规范类型**，其余声明得到 `TYPE_CONFLICT`，
  字段来源表不被冲突类型污染（见 `RegistrationServiceTest` 的 20 轮压测）；
- 血缘的环检测与写入在同一把血缘锁内完成；删除实体遵循“先实体锁后血缘锁”的固定加锁顺序，避免死锁。

---

## 2. 血缘与环规则（`LineageService`）

- 血缘为有向边 `upstream → downstream`，以 `upstream+"->"+downstream` 为键去重：
  重复登记返回 `NO_CHANGE`，**不会重复计数**；
- 上下游实体必须均已登记，否则 `UNKNOWN_ENTITY`；
- `upstream == downstream` → `SELF_REFERENCE` 拒绝；
- 若下游已能沿既有血缘到达上游（即新增边会闭环），通过 BFS 求出一条**既有可达路径**，
  以 `CYCLE_DETECTED` 拒绝并把路径写入响应 message，作为可解释依据；
- 多跳影响查询 `GET /api/catalog/impact/downstream/{name}`：BFS 按跳数分层返回
  （同层实体按名称排序，保证输出稳定），若图中出现回边则 `cycleMarked=true`；
- 实体被移除时级联删除其全部上下游边与邻接项，**不留下悬空血缘**，清理条数写入审计。

---

## 3. 兼容性规则（`CompatibilityService`）

一次变更可包含多个 `ADD / DROP / ALTER_TYPE` 操作；服务先在快照上完成全部判定，
**只有全部兼容才在实体锁内原子落库**，否则整批回滚。

| 操作 | 判定 |
| --- | --- |
| `ADD` 不存在字段 | 兼容（纯新增向后兼容）→ 生效 |
| `ADD` 已存在字段且类型一致 | `NO_CHANGE` |
| `ADD` 已存在字段但类型不同 | `TYPE_CHANGE_INCOMPATIBLE`（请走 `ALTER_TYPE`） |
| `DROP` 任意字段 | `FIELD_REMOVED_INCOMPATIBLE`（删除使既有消费方失效，始终破坏性） |
| `ALTER_TYPE` | 仅允许**数值放宽**：`INTEGER→BIGINT/DECIMAL/DOUBLE`、`BIGINT→DECIMAL/DOUBLE`；相同类型视为无效变更；其余（收窄 / 跨族类）→ `TYPE_CHANGE_INCOMPATIBLE` |

每条操作都在响应 `opResults` 中给出独立的字段名、结论、原因与文字依据。

### 3.1 废弃与权限（写保护，原因可区分）

- **已废弃实体**：`deprecate` 之后，对该实体的登记、字段变更、血缘写操作一律拒绝，
  原因 `ENTITY_DEPRECATED`（即使是 admin 也拒绝）；
- **修改权限**：对某实体首次调用授权接口后进入白名单模式，仅被授权操作者与超级操作者
  `admin` 可写，未授权者得到 `PERMISSION_DENIED`；未授权过的实体默认开放写。

### 3.2 审计

所有写操作（**接受与拒绝都包括**）经 `LocalMetadataStore.appendAudit` 追加一条
`AuditRecord`，序号全局单调递增，可通过审计接口按实体或全量追溯。

---

## 4. 拒绝原因分类（`RejectReason`）

| 原因 | 触发场景 |
| --- | --- |
| `INVALID_PAYLOAD` | 缺 source / qualifiedName / operator、kind 非法、未知变更操作码等 |
| `MISSING_FIELD_NAME` | 登记或变更中存在未命名的字段 |
| `UNKNOWN_TYPE` | 原始类型不在类型字典中，无法归一 |
| `DUPLICATE_FIELD_IN_PAYLOAD` | 同一登记批次内字段重名 |
| `TYPE_CONFLICT` | 同名字段的声明与既有规范类型冲突，拒绝覆盖 |
| `ENTITY_DEPRECATED` | 对已废弃实体执行写操作 |
| `PERMISSION_DENIED` | 操作者不在实体写白名单内 |
| `UNKNOWN_ENTITY` | 目标实体不存在（含血缘端点、变更目标） |
| `SELF_REFERENCE` | 血缘上下游为同一实体 |
| `CYCLE_DETECTED` | 新增血缘边会形成环（响应附既有可达路径） |
| `FIELD_REMOVED_INCOMPATIBLE` | 删除字段这一破坏性变更 |
| `TYPE_CHANGE_INCOMPATIBLE` | 不被允许的类型变更（收窄 / 跨族类 / 无效变更） |

---

## 5. HTTP 接口

基址 `http://localhost:8080/api/catalog`

| 方法与路径 | 说明 |
| --- | --- |
| `POST /entities` | 登记实体声明，body 见下 |
| `GET /entities` | 查看全量目录 |
| `POST /changes` | 提交字段变更并做兼容判定 |
| `POST /lineage` | 登记血缘 |
| `GET /impact/downstream/{qualifiedName}` | 多跳下游影响（按跳分层） |
| `POST /entities/{qualifiedName}/deprecate?operator=` | 标记废弃 |
| `DELETE /entities/{qualifiedName}?operator=` | 移除实体并级联清理血缘 |
| `POST /permissions/{qualifiedName}?operator=` | 授予写权限（进入白名单模式） |
| `GET /audit?qualifiedName=` | 审计轨迹（省略参数查全部） |

登记请求示例：

```json
{
  "source": "hive",
  "qualifiedName": "ods.user",
  "kind": "TABLE",
  "fields": [
    { "name": "id", "rawType": "bigint" },
    { "name": "name", "rawType": "varchar" }
  ]
}
```

变更请求示例：

```json
{
  "operator": "alice",
  "qualifiedName": "ods.user",
  "ops": [
    { "op": "ADD", "fieldName": "remark", "rawType": "text" },
    { "op": "ALTER_TYPE", "fieldName": "id", "rawType": "decimal" }
  ]
}
```

快速手工验证：

```bash
mvn -q spring-boot:run
curl -s -XPOST localhost:8080/api/catalog/entities -H 'Content-Type: application/json' \
  -d '{"source":"hive","qualifiedName":"ods.user","kind":"TABLE","fields":[{"name":"id","rawType":"bigint"}]}'
curl -s "localhost:8080/api/catalog/audit?qualifiedName=ods.user"
```

---

## 6. 验证方法

```bash
mvn test
```

共 26 个测试，全部无外部依赖：

- `RegistrationServiceTest`（7）：归一合并、幂等、非法 / 缺失 / 批次重复字段、
  类型冲突、**16 线程并发相同声明**与 **12 线程 × 20 轮并发冲突类型**的确定性；
- `LineageServiceTest`（7）：自引用、成环（附路径）、重复与并发重复不计数、
  并发关闭环缺口、多跳影响分层、删除后无悬空血缘、未知端点；
- `CompatibilityServiceTest`（10）：新增 / 删除 / 放宽 / 收窄 / 跨族类变更、
  混合批次原子回滚、非法类型、权限拒绝、废弃拒绝、审计留痕；
- `CatalogApiIntegrationTest`（1）：REST 端到端串联全部结论分类；
- `ApplicationTests`（1）：Spring 上下文加载。

测试与服务日志使用 `[REGISTER] / [LINEAGE] / [IMPACT] / [CHANGE] / [DEPRECATE] / [REMOVE]`
前缀，**打印每次输入与判定依据（含逐字段 / 逐操作结论、成环路径）**，便于人工核对。

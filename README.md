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
| `EntityDef` | 实体，按 **qualifiedName（限定名）** 归一；种类为 `TABLE / VIEW / STREAM`；持有字段集合、`deprecated` 标志；**来源清单（`getSources()`）与每个来源的最终去向（`getSourceOutcomes()`）从最终状态确定性推导**（见 1.2.2） |
| `FieldDef` | 字段：`name`、**canonicalType（规范类型）**、`sources`（生效来源 -> 其原始类型）、`rejectedClaims`（拒绝台账：落败来源 -> 其原始类型）、`deprecated`；两个 map 均按来源名排序输出 |
| `SourceDisposition` | 来源最终去向枚举：`EFFECTIVE`（合并生效）/ `SUPERSEDED`（被取代）/ `REJECTED`（被拒绝） |
| `DataType` | 统一规范类型枚举：`INTEGER / BIGINT / DECIMAL / DOUBLE / STRING / BOOLEAN / TIMESTAMP` |
| `LineageEdge` | 有向血缘记录 `(upstream, downstream, source)` |
| `AuditRecord` | 只追加审计：单调序号、时间戳、动作、操作者、目标实体、结论、拒绝原因、明细 |

### 1.0 对象名归一（`NameNormalizer`）

所有写入口（登记、字段变更、血缘登记、废弃、移除）与读路径使用**同一套归一规则**识别对象：
去掉首尾空白并按 `Locale.ROOT` 转小写。因此实体名 / 字段名仅大小写或首尾空白不同
（如 `" ODS.User "` 与 `"ods.user"`、`" ID"` 与 `"id"`）时，**必然被当成同一个对象**：
不会各自建一份定义，也不会各算一套血缘；权限授权、审计查询同样按归一后的名字匹配。

### 1.1 类型归一字典（`TypeDictionary`）

来源原始类型大小写不敏感地映射到规范类型，例如：

- `int/integer → INTEGER`，`bigint/long → BIGINT`，`decimal/numeric → DECIMAL`，
  `double/float → DOUBLE`；
- `string/varchar/text → STRING`，`boolean/bool → BOOLEAN`，`timestamp/datetime → TIMESTAMP`。

未登记的原始类型**无法归一**，对应字段以 `UNKNOWN_TYPE` 拒绝。

### 1.2 登记的归一与合并规则（`RegistrationService`）

- 实体以归一后的 `qualifiedName` 识别：不同来源对同一限定名的声明合并到同一实体；
- 同名实体被声明为**不同种类**（如 TABLE vs VIEW）→ 整批以 `KIND_CONFLICT` 拒绝，
  **绝不悄悄合成一条记录**，与字段级 `TYPE_CONFLICT`、输入级 `INVALID_PAYLOAD` 明确区分；
- 字段以归一后的字段名识别：
  - 该来源首次声明该字段且规范类型与既有一致 → 追加来源（`MERGED`）；
  - 该来源此前已以相同规范类型声明 → `NO_CHANGE`（幂等，来源不重复计数）；
  - 规范类型与既有定义不同 → 按 `TypeArbitrator` **固定类型优先级裁决**（见 1.2.1），
    与到达顺序、并发调度无关；
- 整批字段中只要存在一条非法 / 冲突 / 种类不符的声明，**整批拒绝且规范数据不落库**；
  批次内每条声明（含本身合法但未落库的）都会给出逐条结论与理由；
  落败来源仅记入字段的 `rejectedClaims` 拒绝台账（交代去向，不属于规范数据），
  已登记数据不被污染；
- 结论枚举 `Decision`：`ACCEPTED`（新建）/ `MERGED`（补充合并 / 裁决取代）/
  `NO_CHANGE`（幂等无变化）/ `REJECTED`。

#### 1.2.1 类型裁决规则（`TypeArbitrator`）

同名字段出现多种规范类型声明时，按**固定类型优先级**裁决，全序如下：

`DECIMAL > DOUBLE > BIGINT > INTEGER > TIMESTAMP > STRING > BOOLEAN`

- 新声明优先级**高于**既有规范类型 → 新声明胜出：规范类型被取代，
  既有生效来源转入该字段的拒绝台账（`MERGED`，结论中写明裁决依据）；
- 新声明优先级**低于**既有规范类型 → 新声明以 `TYPE_CONFLICT` 拒绝并记入拒绝台账。

因为裁决依据是与到达顺序无关的全序，**同一实体的同一组声明无论先来后到、多少并发，
最终规范类型、生效来源集合与每个来源的去向（生效 / 被拒）都完全一致**——
不是"谁先到谁说了算"。每个来源的最终去向可从字段状态直接查询：
在 `sources` 中 = 生效合并；在 `rejectedClaims` 中 = 被 `TYPE_CONFLICT` 拒绝 / 取代。

#### 1.2.2 实体来源清单与来源去向的收敛（`EntityDef.getSources()` / `getSourceOutcomes()`）

实体携带一份"哪些来源声明过本实体"的**来源清单**，以及每个来源的**最终去向**。
两者都不是按到达先后增量叠加的，而是从实体最终状态**确定性推导**，
因此与提交顺序、并发调度完全无关——不会出现"同一来源有时算进去、有时算不进去"：

| 最终去向 | 判定依据（按优先级依次判定） |
| --- | --- |
| `EFFECTIVE`（合并生效） | 该来源在任一字段的生效声明 `sources` 中；或成功登记过且没有任何声明落入拒绝台账（如零字段登记） |
| `SUPERSEDED`（被取代） | 无生效声明，但在任一字段的拒绝台账 `rejectedClaims` 中（类型裁决落败——无论先到后被取代、还是后到被拒，最终一致） |
| `REJECTED`（被拒绝） | 无生效声明、无台账记录，但批次因实体状态原因（`KIND_CONFLICT` / `ENTITY_DEPRECATED` / `PERMISSION_DENIED`）被整体拒绝 |

- `getSources()`（来源清单）= 去向为 `EFFECTIVE` 的来源集合，按来源名排序；
  **生效、参与合并的来源必然在清单中，被取代 / 被拒绝的来源必然不在**；
- 清单与去向必然互相对得上：清单 = 去向为 EFFECTIVE 的来源，二者由同一份状态推导；
- 被取代来源的原始声明在对应字段的 `rejectedClaims` 台账中可查；
  被拒绝批次的逐条结论与原因在登记结果与审计日志中可查；
  输入级非法（`INVALID_PAYLOAD` / `UNKNOWN_TYPE` 等）的批次与实体状态无关、
  每次提交结论都相同，其去向以逐条结论与审计为准；
- 被取代 / 被拒绝的来源改以符合规范的类型重新登记后，去向翻转为 `EFFECTIVE`，
  台账中的记录同步清除（最终去向以生效为准）；
- 实体字段集合、字段的生效来源与拒绝台账在输出时均按名称排序，
  目录对外给出的结果不随提交顺序变化。

### 1.3 并发确定性

- 每个实体一把锁（`LocalMetadataStore.lockFor`），同一实体的并发登记 / 变更被串行化，
  不存在丢失更新；结论与“把这些请求按某一顺序串行执行”完全一致；
- 同字段冲突类型并发提交时，按固定类型优先级裁决：无论线程如何调度，
  收敛到同一个规范类型、同一组生效来源与拒绝台账
  （见 `RegistrationServiceTest` 与 `NormalizationConvergenceTest` 的多轮压测）；
- 实体来源清单与每个来源的最终去向同样收敛：并发混合提交（一部分类型互相冲突、
  一部分类型相同、实体名仅大小写 / 首尾空白不同）多轮重复，
  最终来源清单、`EFFECTIVE / SUPERSEDED / REJECTED` 去向完全一致
  （见 `SourceListConvergenceTest`）；
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
| `DROP` 不存在字段 | `UNKNOWN_FIELD`（与实体不存在 `UNKNOWN_ENTITY` 明确区分） |
| `DROP` 已存在字段 | `FIELD_REMOVED_INCOMPATIBLE`（删除使既有消费方失效，始终破坏性） |
| `ALTER_TYPE` 不存在字段 | `UNKNOWN_FIELD` |
| `ALTER_TYPE` 已存在字段 | 仅允许**数值放宽**：`INTEGER→BIGINT/DECIMAL/DOUBLE`、`BIGINT→DECIMAL/DOUBLE`；相同类型视为无效变更；其余（收窄 / 跨族类）→ `TYPE_CHANGE_INCOMPATIBLE` |

每条操作都在响应 `opResults` 中给出独立的字段名、结论、原因与文字依据。
`ALTER_TYPE` 放宽落库时保留字段的拒绝台账，被取代来源的去向不随类型放宽而丢失。

### 3.1 废弃与权限（写保护，所有入口一致，原因可区分）

- **已废弃实体**：`deprecate` 之后，对该实体的**一切写操作**——登记、字段变更、
  血缘登记（作为上游或下游）、再次废弃、移除——一律拒绝，
  原因 `ENTITY_DEPRECATED`（即使是 admin 也拒绝）；
- **只读授权状态**：对某实体首次调用授权接口后进入白名单模式，仅被授权操作者与
  超级操作者 `admin` 可写，其余操作者对该实体只读，五个写入口统一得到
  `PERMISSION_DENIED`；未授权过的实体默认开放写；
- 两类拒绝原因始终可区分：`ENTITY_DEPRECATED` ≠ `PERMISSION_DENIED`，
  且与 `UNKNOWN_ENTITY`（实体不存在）、`UNKNOWN_FIELD`（字段不存在）互不混淆。

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
| `TYPE_CONFLICT` | 同名字段的声明类型按固定优先级裁决落败，拒绝覆盖 |
| `KIND_CONFLICT` | 同名实体被声明为不同种类（如 TABLE vs VIEW），拒绝合并 |
| `UNKNOWN_FIELD` | 目标字段不存在（ALTER_TYPE / DROP），与 UNKNOWN_ENTITY 明确区分 |
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

共 39 个测试，全部无外部依赖、全部在本地完成：

- `SourceListConvergenceTest`（4，本次新增）：**来源清单与来源去向的收敛**——
  同一组冲突声明打乱 12 种顺序提交、8 线程 × 20 轮并发混合提交
  （类型互相冲突 + 类型相同 + 实体名大小写 / 首尾空白变体）、
  多字段来源部分被取代的 6 种顺序，逐轮断言实体种类、字段规范定义、
  实体来源清单、每个来源的 `EFFECTIVE / SUPERSEDED / REJECTED` 去向
  与完整状态快照完全一致；并验证三类去向可查询、与来源清单互相对得上、
  被取代来源重新合规登记后翻转为生效；
- `RegistrationServiceTest`（7）：归一合并、幂等、非法 / 缺失 / 批次重复字段、
  类型冲突、16 线程并发相同声明、**12 线程 × 20 轮并发冲突类型的收敛一致性**
  （每轮规范类型、生效来源、拒绝台账完全相同）；
- `NormalizationConvergenceTest`（6）：书写差异（大小写 / 首尾空白）在登记 / 变更 /
  血缘 / 废弃入口归一到同一对象；裁决取代可解释；**10 轮乱序提交**与
  **8 线程 × 20 轮并发混合声明**最终状态与调度无关；种类不一致 `KIND_CONFLICT`；
  混合批次整体不落库且既有数据不被污染；
- `WriteProtectionConsistencyTest`（3）：已废弃实体五个写入口统一
  `ENTITY_DEPRECATED`；只读（白名单外）操作者五个写入口统一 `PERMISSION_DENIED`；
  `UNKNOWN_ENTITY` 与 `UNKNOWN_FIELD` / `UNKNOWN_TYPE` / `PERMISSION_DENIED` 明确区分；
- `LineageServiceTest`（7）：自引用、成环（附路径）、重复与并发重复不计数、
  并发关闭环缺口、多跳影响分层、删除后无悬空血缘、未知端点；
- `CompatibilityServiceTest`（10）：新增 / 删除 / 放宽 / 收窄 / 跨族类变更、
  混合批次原子回滚、非法类型、权限拒绝、废弃拒绝、审计留痕；
- `CatalogApiIntegrationTest`（1）：REST 端到端串联全部结论分类；
- `ApplicationTests`（1）：Spring 上下文加载。

测试与服务日志使用 `[REGISTER] / [LINEAGE] / [IMPACT] / [CHANGE] / [DEPRECATE] / [REMOVE]`
前缀，**打印每次输入与判定依据（含逐字段 / 逐操作结论、裁决优先级、来源去向、成环路径）**，
便于人工核对。

### 6.1 兼容范围

- 归一仅覆盖**对象名书写差异**（大小写、首尾空白）与**类型字典内别名**
  （如 `varchar`/`text` → `STRING`）；不做语义级匹配（如同义词、命名风格转换）；
- 种类（TABLE / VIEW / STREAM）不参与归一合并：同名不同种类一律 `KIND_CONFLICT` 拒绝
  （保持"先到建档、后来拒绝"的既有边界，不属于收敛保证的范围）；
- 来源清单与来源去向的收敛保证针对**同一实体的合法声明集合**（含互相冲突的类型声明、
  书写变体、任意并发混合）；混合批次"整批拒绝"的既有边界不变——
  批次内同时含合法新字段与冲突字段时，该批次整体不落库（见
  `NormalizationConvergenceTest.mixedBatchRejectedAtomicallyWithoutPollution`）；
- 类型裁决只在**同一字段的多种规范类型声明**之间进行；`ALTER_TYPE` 变更仍按
  第 3 节的放宽白名单判定，两套规则互不影响；
- 全部状态保存在进程内存中，重启即清空；不依赖任何外部服务。

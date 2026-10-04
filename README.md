# 本地元数据目录与血缘服务（metadata-catalog-java）

一套**完全本地可复现**的元数据目录与血缘服务：用进程内存储模拟多个来源（hive / mysql / …）
声明的数据实体与字段，提供：

1. 多来源实体 / 字段声明的**归一登记、内容裁决与收敛**（书写差异归一、缺失/非法/冲突/种类不符不静默丢弃或覆盖，结论与请求到达顺序及线程调度无关）；
2. 上下游**血缘登记、环检测与多跳影响查询**（自引用 / 成环拒绝、重复不重复计数、删除无级联悬空）；
3. 字段新增 / 删除 / 类型变更的**兼容性判定**（破坏性变更拒绝并说明原因）；
4. **废弃实体、只读授权**两类在全部写入口（登记/字段变更/血缘/废弃/移除）一致生效的写保护，以及只追加的**审计轨迹**。

不依赖任何外部服务、数据库或凭据：数据全部保存在内存中，`mvn spring-boot:run` 即可使用。

---

## 1. 元数据模型

统一模型位于 `com.github.highcumontoa.metadatacatalogjava.model`：

| 类型 | 含义 |
| --- | --- |
| `EntityDef` | 实体，按 **qualifiedName 规范键（去空白+小写折叠）** 归一；种类为 `TABLE / VIEW / STREAM`；持有字段集合、声明来源集合、`deprecated` 标志 |
| `FieldDef` | 字段：`name`（规范键）、**canonicalType（内容裁决后的规范类型）**、`sources`（胜出来源 -> 原始类型）、`losingClaims`（落败来源最后声明 -> 用于顺序无关重裁决）、`deprecated` |
| `DataType` | 统一规范类型枚举：`INTEGER / BIGINT / DECIMAL / DOUBLE / STRING / BOOLEAN / TIMESTAMP` |
| `LineageEdge` | 有向血缘记录 `(upstream, downstream, source)` |
| `AuditRecord` | 只追加审计：单调序号、时间戳、动作、操作者、目标实体、结论、拒绝原因、明细 |

### 1.1 类型归一字典（`TypeDictionary`）

来源原始类型大小写不敏感地映射到规范类型，例如：

- `int/integer → INTEGER`，`bigint/long → BIGINT`，`decimal/numeric → DECIMAL`，
  `double/float → DOUBLE`；
- `string/varchar/text → STRING`，`boolean/bool → BOOLEAN`，`timestamp/datetime → TIMESTAMP`。

未登记的原始类型**无法归一**，对应字段以 `UNKNOWN_TYPE` 拒绝。

### 1.2 名称归一规则（`util/Names`，所有写入口共用）

实体限定名（qualifiedName）与字段名（fieldName，含变更操作中的目标字段、血缘端点）一律：

1. 去除首尾空白；
2. 按 `Locale.ROOT` 做大小写折叠（`toLowerCase`）。

因此 `"ODS.User" / " ods.user " / "ods.user"` 是**同一个实体**，
`"ID" / " id " / "id"` 是**同一个字段**——不会因书写差异各建一份记录、
也不会各算一套血缘。操作者名（operator/source 作为写权限主体时）去首尾空白并大小写不敏感匹配；
原始类型名继续由类型字典大小写不敏感归一。实体在目录中的键、血缘边键、权限白名单键、
审计目标均为规范名，保证五个写入口识别对象用的是同一套规则。

### 1.3 登记的归一、内容裁决与收敛规则（`RegistrationService`）

**种类裁决**：同一规范实体名被声明为不同种类（`TABLE/VIEW/STREAM`）时，以 `KIND_CONFLICT`
整批拒绝、逐条给结论，绝不把两种种类静默合成一条记录；实体既有种类与字段不被污染。

**批次内裁决**（同一请求体）：

- 字段缺名 → `MISSING_FIELD_NAME`；原始类型无法归一 → `UNKNOWN_TYPE`；
- 同规范字段名重复且规范类型一致 → `DUPLICATE_FIELD_IN_PAYLOAD`；
- 同规范字段名重复但规范类型不同（声明互相冲突）→ `FIELD_CONFLICT_IN_PAYLOAD`；
- 批次中只要出现任一输入级非法 / 互相冲突 / 种类不符声明，**整批拒绝**：
  不创建实体、不新增字段、不登记实体来源、甚至不登记落败台账，已有数据零污染；每条声明都给独立结论。

**字段规范类型的内容裁决（与到达顺序、线程调度无关）**：

- 对每个字段收集“裁决全集” = 该字段全部已登记的胜出声明 + 落败声明（落败来源保留其最后一次声明）
  + 本批声明（同一来源以本次声明替换其旧声明）；
- 规范类型取全集中**规范类型枚举名字典序最小者**，即固定全序
  `BIGINT < BOOLEAN < DECIMAL < DOUBLE < INTEGER < STRING < TIMESTAMP`。
  这是一条公开、固定、只依赖声明内容的裁决依据——**不是谁先到谁说了算**；
- 声明胜出版本的来源挂到字段上；声明落败版本的来源得到 `TYPE_CONFLICT`，
  其最后声明写入字段“落败台账”（`FieldDef.losingClaims`），不污染规范定义，
  并保证之后任何重裁决都在完整声明全集上进行；胜出者易主（翻转）时旧胜出来源整体转入落败集合；
- 当整批声明全部合法、仅与内容裁决冲突时，规范定义（规范类型/胜出集合/新字段）不写入，
  仅追加落败台账作为去向依据；实体级来源集合按字段最终胜出/落败结果收敛，
  “所有字段均落败”的来源不进入实体来源集合；
- 该来源此前已以相同规范类型、相同原始类型声明 → `NO_CHANGE`（幂等，来源不重复计数）；
- 结论枚举 `Decision`：`ACCEPTED`（新建）/ `MERGED`（来源并入或翻转收敛）/ `NO_CHANGE` / `REJECTED`。
  立即响应中的 ACCEPTED/MERGED/REJECTED 序列可能随线程调度变化，但**最终规范类型与每个来源的
  最终去向是声明全集的纯函数**，任何顺序、任意并发下完全一致；裁决依据写入逐条 detail 与审计记录。

### 1.4 并发确定性

- 每个规范实体名一把锁（`LocalMetadataStore.lockFor`，按规范键取锁），同一实体的并发登记 /
  变更被串行化，不存在丢失更新；
- 同字段冲突类型并发提交时，按 1.3 的内容裁决收敛：规范类型恒为字典序最小者，
  每个来源的胜出/落败去向在 20/30 轮重复压测中逐项恒定（见
  `RegistrationServiceTest` 与 `WriteEntryConsistencyTest`）；
- 血缘写路径按“端点实体锁（规范名升序，自引用只取一把）→ 全局血缘锁”的固定顺序加锁，
  环检测与写入在同一临界区完成；删除实体遵循“先实体锁后血缘锁”，全系统锁顺序一致，避免死锁。

---

## 2. 血缘与环规则（`LineageService`）

- 血缘为有向边 `upstream → downstream`，端点先经名称归一，以
  `canonical(upstream)+"->"+canonical(downstream)` 为键去重：
  大小写/空白差异视为同一条边，重复登记返回 `NO_CHANGE`，**不会重复计数**；
- 所有写入口共用同一套前置检查顺序：**必填校验 → 端点存在 → 自引用 → 已废弃 → 权限 → 业务规则**；
  上下游实体必须均已登记，否则 `UNKNOWN_ENTITY`；
- `upstream == downstream`（归一后）→ `SELF_REFERENCE` 拒绝；
- 任一端点实体已废弃 → `ENTITY_DEPRECATED`；操作者对任一端点无写权限 → `PERMISSION_DENIED`；
- 若下游已能沿既有血缘到达上游（即新增边会闭环），通过 BFS 求出一条**既有可达路径**，
  以 `CYCLE_DETECTED` 拒绝并把路径写入响应 message，作为可解释依据；
- 多跳影响查询 `GET /api/catalog/impact/downstream/{name}`：名称同样归一，BFS 按跳数分层返回
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
| `DROP` 存在字段 | `FIELD_REMOVED_INCOMPATIBLE`（删除使既有消费方失效，始终破坏性） |
| `DROP` / `ALTER_TYPE` 指向**不存在的字段** | `UNKNOWN_FIELD`（实体存在，仅字段缺失，明确区别于 `UNKNOWN_ENTITY`） |
| `ALTER_TYPE` | 仅允许**数值放宽**：`INTEGER→BIGINT/DECIMAL/DOUBLE`、`BIGINT→DECIMAL/DOUBLE`；相同类型视为无效变更；其余（收窄 / 跨族类）→ `TYPE_CHANGE_INCOMPATIBLE` |

实体名与字段名在变更入口同样按名称规则归一；每条操作都在响应 `opResults` 中给出独立的
字段名、结论、原因与文字依据。对**不存在的实体**发起变更为实体级 `UNKNOWN_ENTITY`，
不会与字段级 `UNKNOWN_FIELD`、`UNKNOWN_TYPE`、`PERMISSION_DENIED` 混淆。

### 3.1 废弃与只读授权（全部写入口一致的写保护，原因可区分）

统一的写保护检查顺序：**实体存在 → 已废弃 → 权限 → 业务规则**（血缘端点按
“存在 → 自引用 → 废弃 → 权限 → 业务规则”）。

- **已废弃实体**：`deprecate` 之后，实体进入终态——对它的**登记、字段变更、血缘登记、
  再次废弃、移除**五类写操作一律拒绝，原因恒为 `ENTITY_DEPRECATED`，**即使 admin 也拒绝**；
  重复废弃一个已废弃实体同样得到 `ENTITY_DEPRECATED`（而非成功/幂等）；
- **只读（白名单）模式**：对某实体首次调用授权接口后进入白名单模式，仅被授权操作者
  （大小写/空白不敏感）与超级操作者 `admin` 可写，未授权者在五个写入口均得到
  `PERMISSION_DENIED`；未授权过的实体默认开放写；
- 两类原因、以及 `UNKNOWN_ENTITY` 三者互不混淆：实体先不存在则 `UNKNOWN_ENTITY`，
  存在但已废弃则 `ENTITY_DEPRECATED`，存在且未废弃但无权则 `PERMISSION_DENIED`。

### 3.2 审计

所有写操作（**接受与拒绝都包括**）经 `LocalMetadataStore.appendAudit` 追加一条
`AuditRecord`，序号全局单调递增，可通过审计接口按实体（按规范名查询）或全量追溯。
登记被拒时审计 detail 包含逐字段结论；内容裁决翻转 / 落败时 detail 写明裁决依据
（“规范类型枚举名字典序最小”、旧胜出来源转入落败集合等），做到“按什么依据裁决”可复核。

---

## 4. 拒绝原因分类（`RejectReason`）

按“输入级 → 实体状态/权限级 → 业务规则级”组织，互不混淆：

| 原因 | 级别 | 触发场景 |
| --- | --- | --- |
| `INVALID_PAYLOAD` | 输入级 | 缺 source / qualifiedName / operator、kind 非法、未知变更操作码等 |
| `MISSING_FIELD_NAME` | 输入级（字段） | 登记或变更中存在未命名的字段 |
| `UNKNOWN_TYPE` | 输入级（字段） | 原始类型不在类型字典中，无法归一 |
| `DUPLICATE_FIELD_IN_PAYLOAD` | 输入级（批次） | 同一登记批次内同规范字段名重复且类型一致 |
| `FIELD_CONFLICT_IN_PAYLOAD` | 输入级（批次） | 同一登记批次内同规范字段名被声明为**不同类型**，声明互相冲突 |
| `KIND_CONFLICT` | 种类裁决 | 同一规范实体名被不同来源声明为不同种类（TABLE/VIEW/STREAM），拒绝静默合并 |
| `TYPE_CONFLICT` | 内容裁决 | 字段声明在内容裁决中落败（胜出规范类型由声明全集按字典序决定），不覆盖规范定义 |
| `UNKNOWN_ENTITY` | 实体状态 | 目标实体不存在（含血缘端点、变更/废弃/移除目标） |
| `UNKNOWN_FIELD` | 字段状态 | **实体存在**但 DROP/ALTER_TYPE 指向的字段不存在（与 `UNKNOWN_ENTITY` 明确区分） |
| `ENTITY_DEPRECATED` | 写保护 | 实体已废弃，五个写入口（含再废弃、移除，含 admin）一律拒绝 |
| `PERMISSION_DENIED` | 写保护 | 操作者不在实体写白名单内（五个写入口一致） |
| `SELF_REFERENCE` | 血缘业务 | 血缘上下游归一后为同一实体 |
| `CYCLE_DETECTED` | 血缘业务 | 新增血缘边会形成环（响应附既有可达路径） |
| `FIELD_REMOVED_INCOMPATIBLE` | 兼容业务 | 删除字段这一破坏性变更 |
| `TYPE_CHANGE_INCOMPATIBLE` | 兼容业务 | 不被允许的类型变更（收窄 / 跨族类 / 无效变更） |

整批语义：登记批次 / 变更批次中只要出现任一非法、互相冲突或种类不符的声明，
**整批拒绝、逐条给结论**；含输入级非法声明的批次连落败台账都不产生，已有数据不被污染。

### 4.1 兼容范围说明

- 名称归一覆盖：实体限定名、字段名、血缘端点、审计查询键、权限白名单实体键；
  操作者名做去空白 + 大小写不敏感匹配；**原始类型名仍只做类型字典归一，不做对象识别**；
- 归一仅做“首尾空白 + ROOT 大小写折叠”，不做下划线/连字符/全半角等模糊等价，
  避免把本不同义的名字误并（需要时应在来源侧显式对齐）；
- 内容裁决的字典序全序只在**存在类型分歧**时决定规范类型；声明内容不变则结论幂等；
- 落败台账只保留来源最后一次声称的原始类型，用于去向追溯与重裁决，不参与对外规范定义；
- 内存存储，重启清空；无外部服务、数据库、网络、凭据依赖。

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

## 6. 验证方法（全部本地完成，不依赖外部服务）

```bash
mvn test
```

共 41 个测试，全部无外部依赖（纯内存、无数据库 / 网络 / 凭据）：

- `RegistrationServiceTest`（7）：归一合并、幂等、非法 / 缺失 / 批次重复字段、
  类型冲突裁决、**16 线程并发相同声明**与 **12 线程 × 20 轮并发冲突类型**的逐来源收敛断言；
- `WriteEntryConsistencyTest`（15，本版新增）：
  - **书写差异归一**（3）：实体 / 字段 / 血缘端点的大小写与首尾空白差异识别为同一对象；
  - **种类不一致**（1）：`KIND_CONFLICT` 与类型冲突 / 非法载荷区分且不合并污染；
  - **批次内字段互斥冲突**（1）：`FIELD_CONFLICT_IN_PAYLOAD` 与同类型重名、既有冲突区分；
  - **混合批次整体不落库**（2）：含输入级非法声明时零痕迹；仅裁决落败时只登记去向台账；
  - **内容裁决翻转可解释**（1）：字典序更强的声明翻转规范类型，旧胜出来源整体转入落败并可愈合；
  - **乱序 + 并发收敛一致性**（3）：顺序相反的串行提交结果逐字段逐来源一致；
    **12 线程 × 30 轮（含乱序建池）**冲突类型收敛、**16 线程 × 20 轮**同类型收敛、
    **12 线程 × 15 轮**血缘书写差异并发归一，每轮断言规范类型与每个来源去向完全一致；
  - **写保护一致性**（2）：废弃后五个写入口（登记/变更/血缘/再废弃/移除，含 admin）全部
    `ENTITY_DEPRECATED`；只读白名单下五个入口未授权全部 `PERMISSION_DENIED`、授权者放行，
    且废弃优先于权限；
  - **不存在对象区分**（1）：`UNKNOWN_ENTITY`（实体不存在）vs `UNKNOWN_FIELD`（字段不存在）
    在变更、血缘、废弃、移除、审计各路径均不混淆。
- `LineageServiceTest`（7）：自引用、成环（附路径）、重复与并发重复不计数、
  并发关闭环缺口、多跳影响分层、删除后无悬空血缘、未知端点；
- `CompatibilityServiceTest`（10）：新增 / 删除 / 放宽 / 收窄 / 跨族类变更、
  混合批次原子回滚、非法类型、权限拒绝、废弃拒绝、审计留痕；
- `CatalogApiIntegrationTest`（1）：REST 端到端串联全部结论分类；
- `ApplicationTests`（1）：Spring 上下文加载。

测试与服务日志使用 `[REGISTER] / [LINEAGE] / [IMPACT] / [CHANGE] / [DEPRECATE] / [REMOVE]`
前缀，**打印每次输入与判定依据（含逐字段 / 逐操作结论、内容裁决的胜出/落败依据、成环路径）**，
多轮压测在第 0 轮打印输入与收敛结果，其余轮次以逐项断言证明结论与线程调度无关，便于人工核对。

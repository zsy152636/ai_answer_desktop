# 架构决策记录（ADR）

> 本文件记录《AI 智能答题系统开发实施文档 v1.0》落地为第一版时做的取舍。
> 每条都说明「设计文档怎么说」「第一版怎么做」「为什么」，以及「何时需要重新评估」。
> 所有与设计文档不一致的地方都必须出现在这里，不允许藏在代码注释里。

## ADR-001 · 第一版范围锁定在开发文档阶段 1-4

- **设计文档**：阶段 0-8 依次为技术准入、工程骨架、数据基线、核心答题、错题闭环、LLM 配置、本地检索/AI、试卷导入、稳定发布。
- **第一版实现**：阶段 1（工程骨架）、2（数据基线）、3（核心答题）、4（错题闭环）完整交付；阶段 5（LLM 配置）只交付「配置与凭据 + 真实模型调用闭环」，阶段 6-8 不做。
- **因此以下能力第一版**不存在**：FTS5 全文检索与资料库、PDF/DOCX 试卷导入与 OCR、打包签名与自动更新。
- **重新评估时机**：核心答题与错题闭环稳定后，按阶段顺序推进；资料库页面在第一版只保留占位说明。
- **后续修订**：阶段 6（本地检索/AI）已按本文件 ADR-013 落地，本条中「FTS5 全文检索与资料库不存在」的结论随之失效。

## ADR-002 · 由 Electron 统一编排 Java 与 Python 子进程

- **设计文档 §2.1**：Electron 启动 Java → Java 按需启动 Python AI 服务并传递动态端口与短期内部令牌。
- **第一版实现**：Electron 先启动 Python（写出 `runtime/python-endpoint.json`），再启动 Java 并通过 `--aia.python-endpoint-file` 告知端点位置；Java 只做「读取端点 → 调用 → 不可用时降级」。
- **为什么**：设计文档把 Python 的生命周期交给 Java，前提是运行时分发方案已经冻结（阶段 0 附录 A 的「Python 打包」决策项）。该决策在第一版尚未冻结，若此时让 Java 负责拉起 Python，就会把「Python 解释器在哪、依赖装在哪」这种会随打包方案变化的知识固化进业务服务。交给 Electron（它本来就负责安装包与运行时目录）更稳。
- **安全性未降低**：Python 仍只监听 loopback、仍由 Java 独占调用、内部令牌仍在每次启动重新生成。
- **端点交付的健壮性**：端点除由 Python 落盘外，还可经 stdout 交由 Electron 代写（见 ADR-012）。
- **重新评估时机**：阶段 8 打包方案冻结后，可把拉起逻辑迁回 Java，接口不变。

## ADR-003 · 题目选项采用规范化表，答案只有一个权威来源

- **设计文档 §4.3 注**：`question.options_json` 与选项表二选一，建议优先规范化选项表；正确答案字段单独定义并加访问边界。
- **第一版实现**：建 `question_option` 表（`question_version_id + option_key` 唯一）；选项的 `is_correct` 由 `answer.value` 推导后写入，请求里传入的 `correct` 只作展示、不参与存储。
- **为什么**：保留两个「正确答案」来源必然产生不一致；由答案推导选项正确性，可以让判分、快照、UI 展示三处自动一致。
- **代价**：写入时多一次推导；已记录在 `GradingService.canonicalAnswer`。

## ADR-004 · 凭据存储使用 Windows DPAPI，而非 Credential Manager

- **设计文档 §6.2**：优先 Windows Credential Manager / DPAPI。
- **第一版实现**：`WindowsCredentialStore` 用 DPAPI（当前用户作用域）加密后落盘到 `<data>/secrets/*.dpapi`；DPAPI 不可用时降级为文件存储，并在系统信息页与日志明确标注后端名称。
- **为什么**：所依赖的 jna-platform 5.17.0 已不再提供 `WinCred` API（`Crypt32Util` 仍在）。改用 Credential Manager 需要额外引入或降级 JNA，收益有限——两者都由同一套 Windows 用户凭据保护，DPAPI 还不依赖用户会话交互。
- **不静默降级**：选择后端时会真实跑一次 DPAPI 加解密探针，失败才降级，并记录 `credentialBackend` 展示给用户。
- **已被取代（v1.1）**：LLM 配置改为 `<UserData>/config/setting.json` 文件驱动，不再使用 OS 凭据存储，也不再返回 `credentialBackend`；
  本条关于「凭据后端选择与降级」的结论不再适用，历史记录保留。见 ADR-014。
- **重新评估时机**：若产品要求密钥出现在系统「凭据管理器」里便于用户自行清理，则换回 Credential Manager。

## ADR-005 · 恢复采用「登记请求 + 下次启动执行」

- **设计文档 §1.1**：备份/恢复、恢复校验属于 MVP。
- **第一版实现**：`POST /system/restore` 只做校验（`integrity_check` + `foreign_key_check` + `schema_version` 存在）并写 `runtime/restore-request.json`，返回 `requiresRestart=true`；真正的文件替换发生在下次启动、连接池打开之前，替换前自动把当前库另存为 `app.db.pre-restore-*.db`。
- **为什么**：SQLite 数据库文件被连接池持有句柄时替换文件，会让连接指向错误的 inode，且 WAL/-shm 文件的处理极易出错。离线替换是唯一能保证一致性的简单做法，代价是需要一次重启——对单体桌面应用完全可接受，而且用户能看到「需要重启」的明确提示。
- **失败处理**：任何校验失败都放弃恢复并保留现有数据，绝不做「尽力而为」的半恢复。

## ADR-006 · 迁移前备份在连接池建立之前完成

- **设计文档 §2.1**：迁移前必须成功备份。
- **第一版实现**：`DatabaseBootstrap.backupBeforeMigrationIfNeeded()` 在连接池创建前，用一条独立连接执行 `VACUUM INTO`。只有「数据库文件已存在且脚本版本高于当前 schema 版本」时才备份。
- **为什么**：迁移失败的兜底必须早于任何写操作；同时避免每次启动都堆一份备份文件。

## ADR-007 · 迁移执行器自研，不引入 Flyway

- **设计文档 §4.3**：以 `schema_version` 记录版本、`applied_at`、`checksum`。
- **第一版实现**：`MigrationRunner` 读取 `classpath:db/migration/V*.sql`，按版本号排序执行，把文件 SHA-256 写入 `schema_version`；已应用版本的校验和不一致时直接拒绝启动。
- **为什么**：设计文档要求的表结构就是自研执行器的表结构；Flyway 对 SQLite 属于社区支持，事务与 DDL 行为在 WAL 下有额外不确定性。自研执行器只有 100 多行，行为完全可控，且天然支持「脚本被篡改即拒绝启动」这条硬约束。
- **代价**：不支持 undo 迁移、不支持 Java 迁移类；第一版不需要。

## ADR-008 · 错题练习复用「试卷快照」模型

- **设计文档 §5.3**：错题可再次练习。
- **第一版实现**：错题练习会生成 `paper_snapshot(kind='practice', paper_id=NULL)` 与对应快照题目，作答记录复用同一套 `attempt` 表与判分链路。
- **为什么**：若为练习另建一套题目引用模型，判分、结果页、快照可重现性都要写第二遍，且很容易与试卷链路产生行为差异。复用快照让「练习」与「考试」在数据层完全同构。
- **代价**：`paper_snapshot.paper_id` 必须允许为空，`paper`/`snapshot` 的列表查询要显式排除 practice 快照。

## ADR-009 · 主观题评分保留「AI 建议分 + 人工确认」两步

- **设计文档 §5.2、§11**：AI 结果标记为建议分数、可人工复核、待复核状态必须明确显示。
- **第一版实现**：提交时主观题落库为 `grading_status=pending_review`、`score=null`，并排队 `ai_grade` 任务；AI 完成后写入分数与 `grading_method=ai_rubric`，但状态**仍是** `pending_review`，直到人工通过 `POST /attempts/{id}/answers/{sid}/grade` 确认或改分才转为 `graded`。
- **为什么**：如果 AI 一打完分就把状态置为 `graded`，「待复核」在 UI 上就无从判断，AI 分数会被静默当成最终成绩——这正是设计文档明确禁止的。
- **契约影响**：契约新增两个端点（`POST /attempts/{id}/ai-grade`、`POST /attempts/{id}/answers/{sid}/grade`），已写入 `docs/api/api-contract.md` §4。

## ADR-010 · LLM 配置的跨字段校验由后端补齐

- **设计文档 §6.1**：以 `contracts/json-schema/llm-config.schema.json` 作为导入、导出、后端校验与测试的单一 Schema 来源。
- **第一版实现**：结构校验用 JSON Schema（networknt，Draft 2020-12）加载上述同一份文件（构建期复制进 classpath）；`defaultProfileId` 必须存在、`profiles[].id` 必须唯一这两条跨字段规则由 `LlmProfileService.validate` 在 Schema 校验之后补齐。
- **为什么**：这两条规则无法用纯 JSON Schema 表达（写 `$data` 属于草案扩展，主流校验器不支持）。把它们显式放在后端而不是散落到前端，保证前后端只有一份判定。
- **重新评估时机**：若引入支持 `$data` 的校验器，可把规则下沉回 Schema。

## ADR-011 · 判分规则版本随结果持久化

- **设计文档 §5.2**：任何题型都必须记录判分依据。
- **第一版实现**：`grading_detail_json` 固定写入 `ruleVersion`（第一版为 `1.0`）、`expected`、`actual`、命中明细与归一化规则；规则变更时必须提升 `AppInfo.GRADING_RULE_VERSION`。
- **为什么**：历史分数必须能解释「当时是按什么规则算的」，否则改规则会让历史结果变成不可解释的数字。

## ADR-012 · 端点除落盘外还经 stdout 中继，且禁止用 `tempfile.mkstemp` 写端点

- **触发问题**：桌面端出现「AI 服务不可用」。实测复现：`services/python-ai/.venv/Scripts/python.exe` 在受限环境（该文件带有
  `Mandatory Label\Low Mandatory Level` 完整性标签）下以 Low 完整性级别运行，**无法在 `%APPDATA%\ai-answer-desktop\runtime`
  创建任何文件**（`EACCES/errno 13`）。更糟的是 CPython 在 Windows 上把 `tempfile._mkstemp_inner` 的 `PermissionError`
  当成「同名目录已存在」而 `continue`，对 `os.TMP_MAX`（Windows 为 2147483647）个随机名逐个重试——
  `py-spy` 实测主线程卡在 `tempfile.py:256` 空转、进程 100% CPU、HTTP 永不响应、`python-endpoint.json` 永不落盘；
  Java 读不到端点 → `/api/v1/meta.aiService.available=false` → 前端显示「AI 服务不可用」。
- **第一版实现**：
  1. `write_endpoint_file` 不再使用 `tempfile.mkstemp`，改为「固定候选名 + `open(..., 'x')` 独占创建 + 有限次重试」，
     任何真实错误立即抛出；落盘失败只降级为 WARNING，HTTP 服务照常提供。
  2. Python 额外支持 `--endpoint-stdout`：把端点以单行 JSON（前缀 `AIA_ENDPOINT `）写到 stdout；
     `ServiceManager` 解析后**由父进程代为原子写入** `python-endpoint.json`，该行含令牌因而绝不进日志。
- **为什么**：子进程可能因完整性级别/ACL 限制写不了用户数据目录，而父进程（Electron 主进程）通常可以；
  让「谁有能力写就由谁写」，既保住 Java 仍按文件读端点的契约，又不要求放宽任何沙箱/ACL 限制。
- **代价**：端点信息会在父子进程的私有管道上多走一次（令牌不落日志、不出本机）；父进程多一条解析与代写路径。
- **契约影响**：无。Java 侧完全不变，`runtime/python-endpoint.json` 的结构与语义不变（新增 `relayedBy` 字段仅供排查）。
- **附带修复**：子进程日志编码统一。Java 侧 logback 的 CONSOLE appender 本就声明了 UTF-8，乱码只出现在
  **读日志的一端**（把 UTF-8 字节按 GBK 解码）；真正的缺陷在 Python：stdout 是管道时它退回 ANSI 代码页
  （中文环境 cp936）发 GBK 字节，而父进程按 UTF-8 解码，于是 `[python-ai]` 行变成 `锟斤拷`。
  现在 Python 强制 `PYTHONIOENCODING=utf-8`/`PYTHONUTF8=1`，父进程改用 `StringDecoder` 逐行解码
  （避免多字节字符被 chunk 切断），开发启动脚本再把控制台切到 UTF-8（`chcp 65001`）。
- **重新评估时机**：若 Python 以打包运行时（非工作区内解释器）分发，或运行环境不再做完整性级别限制，可只保留落盘路径。

## ADR-013 · 阶段 6 本地检索：能力化迁移、trigram 分词与引用来源

- **设计文档 §10**：FTS5 优先、失败降级关键词；原文与分块存普通表，FTS5 只存可检索文本与 chunk_id；
  索引重建版本化（新版本通过校验后才切换 `active_index_version`）；检索结果返回标题 + 来源页/段 + 片段，
  生成回答时引用片段且资料视为不可信输入；「FTS5 能力自检和关键词降级路径通过测试」。
- **中文分词策略（设计文档附录 A 要求冻结，此前未定义）**：`document_fts` 使用 FTS5 **`trigram`** 分词器。
  理由：`unicode61` 按空白/标点切词，中文整句会变成一个 token，子串检索基本失效；trigram 按三字符组切分，
  中文无需外部分词器即可做子串匹配，且 `sqlite-jdbc 3.44.1.0` 自带 FTS5 与 trigram（无需新依赖）。
  代价：查询词不足 3 字符时 trigram 无法命中，必须降级到关键词路径（已实现且由测试覆盖）。
- **能力化迁移（`-- @requires`）**：`document_fts` 仍由迁移脚本创建（§10.2），但该脚本声明
  `-- @requires fts5-trigram`。运行期缺少该能力时脚本被**显式跳过且不写入 `schema_version`**，其余迁移照常执行，
  环境具备能力后下次启动自动补上。这样同时满足「由迁移脚本创建」与「FTS5 不可用时降级启动」两条要求
  ——把 DDL 放在迁移里，又不会因为虚拟表建不出来而让整个应用起不来。
  这与「失败跳过」有本质区别：跳过依据是可判定的环境能力，执行错误仍然一律回滚并 `DB_MIGRATION_FAILED`。
  配套：迁移前备份改用 `latestApplicableScriptVersion(capabilities)`，避免「有脚本被跳过」导致每次启动都误判为有待执行迁移（ADR-006 的时序不变）。
- **`fts5Available` 语义收敛**：它现在表示**检索索引可用**（FTS5 + trigram），而不是「FTS5 模块存在」。
  只有前者成立时检索才走 FTS5，界面上「FTS5 可用 / 降级为关键词检索」的文案才与事实一致。
- **分块策略（设计文档只给了字段，未给算法）**：规范化（去 BOM、统一换行、去行尾空白、压缩连续空行）后按空行分段，
  装箱到 800 字符附近、单块上限 1200 字符，超长段落按句子边界硬切；**不做重叠**（重叠会让同一条命中在多个分块里重复，
  反而削弱「引用来源」的可读性）。分块是确定性的：同一原文与 `parser_version` 永远得到同一序列，否则历史检索结论无法复现。
  `parser_version` 记录分块策略版本与实际编码（`text-v1+utf8` / `markdown-v1+gb18030`）。
- **编码**：资料按 UTF-8 → GB18030 顺序严格解码，两者都不成立才报 `DOCUMENT_PARSE_FAILED`。
  中文 Windows 上的纯文本资料大量是 GBK，只认 UTF-8 会把它们全部拒之门外；记录实际编码便于事后解释检索结果。
- **字段消歧**：设计文档 §523 的分块字段（`source_file_id`…）与 §10.2 的表（`document_id`…）并列且未定义关系。
  本实现取并集并以 `document_id` 为权威外键：来源文件由 `document.file_id` 推导，不再冗余存储，
  符合「避免双权威来源」的既有原则（ADR-003）。
- **导入范围**：首期只支持 `.txt` / `.md`。设计文档阶段表把「文本 PDF/DOCX 提取、OCR、逐题校对」列在阶段 7，
  因此 PDF/DOCX 在这里返回 `DOCUMENT_FORMAT_UNSUPPORTED` 并在界面提示，不做半成品解析。
- **文件一致性（§9.1）**：临时文件 → SHA-256 → 原子移动到 `files/imports/<fileId>/<原始文件名>` → 短事务登记；
  登记失败即补偿删除文件；同一 `sha256` 磁盘只留一份（可被多条资料引用）；保留窗口 24 小时后由一致性巡检清理孤儿文件与临时文件，
  「有记录没文件」则把资料标记为 `failed` 并保留详情，不自动发布部分结果。
- **注入防护（§525、附录 B）**：检索片段与题目内容一样按不可信输入处理——固定边界标签包裹、**包裹前转义边界词**
  （否则资料自带边界标记即可逃逸，这是修复前真实存在的洞）、系统提示声明边界内是数据、命中可疑指令模式只打标记与脱敏日志。
  模型给出的引用按「本次实际提供的 refId」过滤（Python 一次、Java 再按 chunkId 复核一次），引用必须能回到具体分块。
- **前端验证方式的偏差**：设计文档 §12.1 要求 Renderer 测试覆盖表单校验与页面状态。本仓库渲染层未引入测试框架，
  沿用既有做法（`vue-tsc --noEmit` + `vite build` + README §8 的实测记录），**未新增依赖**。
  这是与设计文档的显式偏差，阶段 8 统一引入测试框架时一并处理。
- **重新评估时机**：若检索召回质量评测显示 trigram 不足，按 §526 引入轻量本地向量索引；
  若中文分词需求变化，提升 `parser_version` 并走版本化重建（旧索引在切换前一直可用）。

## ADR-014 · LLM 配置改为 setting.json 文件驱动（v1.1，取代 ADR-004 的凭据存储结论）

- **原设计（开发文档 §6.1/§6.2 + ADR-004）**：以 `contracts/json-schema/llm-config.schema.json` 为单一 Schema 来源；
  profile 存 SQLite（`llm_profile` 等表），密钥走 OS 凭据存储（Windows DPAPI 优先、探测失败降级文件），
  DB 只存 `apiKeyRef`，导出绝不含明文密钥。
- **变更（用户批准的《LLM 配置模块修改方案 v1.1》）**：LLM 配置与用户偏好改为
  **`<UserData>/config/setting.json` 文件驱动**，Schema 唯一来源为 `contracts/json-schema/setting.schema.json`；
  Java `ConfigService` 缓存该文件，`WatchService` 监听变化实现热加载；密钥以 `apiKey` 字段明文存在同一文件内。
- **为什么**：本系统定位是个人单机应用。原方案的复杂度（凭据后端选择与降级、备份需同时处理数据库与系统凭据、
  用户无法直接查看或迁移配置、配置与业务数据混在同一库里）在这个定位下收益很低；文件驱动让配置**可见、可备份、
  可跨机迁移、可手工热改**，并把配置与业务数据彻底解耦（数据库纯度提高，ADR-003 同一取向）。
- **代价（明确承认）**：① 明文密钥落盘，**安全等级低于 OS 凭据存储**，安全性转移到「本机账户安全」；
  ② 配置与数据库不再是同一份备份工件，迁移需显式导出配置；③ 外部手工改坏文件会引入新的失败面。
- **安全边界（缓解措施，均已写入契约 §6）**：文件写权限收紧到 0600；密钥只出现在该文件、内存缓存与
  「显式含密钥导出」结果中；API 响应恒掩码 `******`；日志、异常栈、审计、任务 payload、诊断包一律剔除或脱敏；
  导出默认不含明文密钥，含密钥导出必须显式传参 + 客户端二次确认 + 审计留痕；迁移过程不打印密钥。
- **失败兜底**：热加载校验失败时**保留上一份有效配置**（服务照常可用），把原因放进 `warnings` 供界面展示；
  手工 `reload` 失败返回 `CONFIG_FILE_INVALID`。
- **旧数据迁移**：`llm_profile`/`llm_config_state`/`llm_import_preview` 三张表**保留结构但不再读写**；
  首次启动（`setting.json` 不存在且表内有数据）自动迁移，尝试把已绑定密钥一并导出到 `apiKey`，
  读不到的 profile 记入 warnings 提示手工重填；迁移幂等、**绝不覆盖已存在的 setting.json**，成功后写
  `app_meta.llm_config_migrated_at` 标记。
- **刻意不做的事**：**不**给 Python 增加「配置推送 / reload 端点」（方案 §11 的该节未采纳）。
  现状是 Java 每次请求把 profile（含仅内存传递的单次密钥）交给 Python，Python 既不直连数据库也不缓存配置；
  新增推送通道只会引入缓存不一致与额外失败面。若将来要省掉每请求传参，再单独评估。
- **契约影响**：契约 §6 整体替换（端点路径沿用，`/{id}/credential` 语义改为「只更新 apiKey」，新增 `reload`，
  导出改为完整配置导出）；§0.3 调整 `CREDENTIAL_MISSING` 语义并新增 `CONFIG_FILE_INVALID`；
  §4/§7 的 `credentialRef` → `profileRef`（`profile://<id>`）；§8 的 `credentialBackend` → `configSource` + `configPath`；
  `contracts/openapi/openapi.yaml` 与渲染层、README、冒烟脚本同步。
- **与 ADR-004 的关系**：ADR-004 关于「DPAPI 优先、探测失败降级文件、展示 credentialBackend」的结论**不再适用**，
  其历史记录保留；`service/credential/**` 在实现完成后删除。
- **重新评估时机**：若本机出现多用户/共用账户场景，或配置需要纳入版本管理与分发，则回到「密钥与配置分离」
  （独立密钥文件或重新引入 OS 凭据库）。届时只需替换 `ConfigService` 里的密钥读取点，对外接口形状不变。

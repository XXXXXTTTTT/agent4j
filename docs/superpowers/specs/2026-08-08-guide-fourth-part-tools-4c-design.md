# 第四篇 4C：Skills 只读编排目录设计

## 目标

在 4A `ToolRegistry` 与 4B MCP 工具适配层之上增加 Skills 组织层。Skill 表达“已注册工具 +
使用策略”，负责可发现、版本化、精确触发和渐进式 Prompt 加载，不增加新的工具执行通道。
任何 Skill 引用的工具都必须在目录构造时从 `ToolRegistry` 精确找到，运行时仍由 Registry
执行 Schema、能力、审批、超时与审计治理。

## 教程实践取舍

教程第 14 章把 Skill 定义为元数据、工具和知识三层，并提出分层组织、三层懒加载、
SemVer、触发冲突治理与五层安全。4C 采用其中与当前架构直接兼容的部分：

- 元数据层保存名称、版本、描述和触发文本。
- 工具层只保存有序的 Registry 工具名，不保存 handler、类名或脚本。
- 知识层保存只用于模型编排的 Prompt 片段，描述调用顺序、约束和错误处理。
- 默认发现只暴露摘要；触发匹配或显式点名后才暴露完整工具元数据与知识片段。
- 工具权限、审批、审计和沙箱继续复用 4A、4B 与既有 Harness，不在 Skill 层复制。

本里程碑不实现文件系统扫描、`SKILL.md`/YAML 解析、热重载、Skill 市场、脚本执行、
反射调用、任意生产类名、可写管理 API 或前端页面。升级 Skill 时由装配层构造新的不可变
目录；文件分发与热重载必须在后续独立规格中定义信任来源和原子替换语义。

## 公开领域协议

### Skill 定义

```java
public record SkillDefinition(
        String name,
        String version,
        String description,
        List<String> triggers,
        List<String> toolNames,
        String promptFragment) {}
```

约束如下：

- `name` 精确匹配 `[a-z][a-z0-9_.-]{0,63}`。
- `version` 只接受无前导零的 `MAJOR.MINOR.PATCH`，每段为 `0` 或正整数；4C 不接受预发布
  和构建元数据。
- `description` 与 `promptFragment` 非空，分别不超过 4000 和 16000 个 Unicode code point。
- `triggers` 与 `toolNames` 保留声明顺序并复制为不可变列表；列表不得包含 null、空白或
  重复值。
- trigger 使用原始 Unicode 文本，构造和匹配均不 trim、不折叠大小写、不做 Unicode
  归一化。空白 trigger 非法；非空 trigger 的前后空白属于其精确内容。
- `toolNames` 至少包含一个工具，每项必须满足 `ToolDefinition` 的名称格式。
- `promptFragment` 只能成为模型 Prompt 的数据，不会被解释为 Java、Shell、表达式或模板。

同一目录只允许一个精确 Skill 名称；`version` 是当前活动定义的审计标识。发布新版本时
由装配层以新目录整体替换旧目录，避免同名多版本同时触发造成隐式选择。

### 渐进披露结果

```java
public record SkillSummary(String name, String version, String description) {}

public record SkillToolMetadata(
        String name,
        String description,
        JsonNode inputSchema) {}

public record ActivatedSkill(
        String name,
        String version,
        List<SkillToolMetadata> tools,
        String promptFragment) {}

public record SkillPromptContext(
        String discoverySection,
        String activationSection,
        List<SkillSummary> availableSkills,
        List<ActivatedSkill> activatedSkills,
        String fingerprint) {}
```

所有 record 在构造时冻结集合；`SkillToolMetadata.inputSchema` 构造和 accessor 两端均
deep copy。`fingerprint` 是小写 64 位 SHA-256，输入精确包含 discovery 和 activation
两个渲染分区，用于 Prompt 审计。

`discoverySection` 只包含 Skill 的名称、版本和描述，不包含 trigger、工具 Schema 或策略
正文。`activationSection` 只包含本次激活 Skill 的名称、版本、有序工具名称、描述、
输入 Schema 和 `promptFragment`。没有激活项时该分区为空字符串。

## 只读目录与匹配语义

```java
public final class SkillCatalog {
    public SkillCatalog(
            List<SkillDefinition> definitions,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper);

    public List<SkillSummary> list();
    public Optional<SkillDefinition> find(String name);
    public SkillPromptContext resolve(
            String userInput,
            Set<String> explicitlyRequestedNames);
}
```

构造过程一次性完成全部校验并建立不可变快照：

1. definitions 不得为 null、空列表或包含 null；Skill 名称不得重复。
2. 不同 Skill 不得声明完全相同的 trigger；冲突直接抛 `SkillRegistrationException`。
3. 每个 `toolNames` 元素必须通过 `ToolRegistry.find(exactName)` 找到。未知、关闭的 Registry
   或读取异常均抛带 cause 的 `SkillRegistrationException`，目录不得部分创建。
4. 目录只复制工具名称、描述和 Schema，不持有或公开 `ToolHandler`。
5. `list()` 始终按 Skill 名自然顺序返回不可变摘要；`find(name)` 只做精确名称查找，null、
   空白和大小写不同均返回 `Optional.empty()`。

`resolve` 的匹配规则保持可证明和确定：

- userInput 不得为 null；不做 trim、分词、大小写折叠、模糊匹配或同义词扩展。
- 当 `userInput.contains(exactTrigger)` 时，该 Skill 激活。不同 trigger 可使多个 Skill 同时
  激活，结果按 Skill 名自然顺序排列并按名称去重。
- `explicitlyRequestedNames` 用于第三层显式发现，元素必须精确等于已注册 Skill 名；未知
  名称抛 `SkillNotFoundException`，不静默忽略或猜测近似名称。
- trigger 命中与显式点名取并集。空 trigger 列表表示该 Skill 只能被显式点名。
- resolve 只生成 Prompt 上下文，不调用任何工具，也不根据 Prompt 内容产生工具参数。

## 工具治理与安全边界

Skill 没有 `execute`、handler、反射入口或脚本字段。调用方从 `ActivatedSkill.tools` 获得允许
呈现给模型的只读元数据，模型产出的 `ToolCall` 仍必须交给 `ToolRegistry.execute`。因此：

- Skill 无法绕过 JSON Schema 或能力校验。
- HIGH 风险工具仍返回 `APPROVAL_REQUIRED`，Skill 不能代替用户批准。
- MCP 工具在 4B 注册后与本地工具使用相同流程，Skill 不直接持有 `McpClient`。
- Prompt 片段中的任何类名、命令或代码文本都只是文本，不被 SkillCatalog 执行。
- 4C 不新增网络、文件系统或进程权限，也不记录工具参数正文。

## 错误与并发语义

- `SkillRegistrationException` 精确标识目录构造失败，保存 Skill 名、工具名或 trigger 的相关
  文本以及原始 cause；构造失败时不返回部分目录。
- `SkillNotFoundException` 精确标识显式点名了不存在的 Skill。
- 非法 record 字段使用 `IllegalArgumentException` 或 `NullPointerException` 立即失败。
- 目录构造后没有 register、replace 或 remove 方法；所有字段均为不可变快照，多虚拟线程
  可并发执行 list、find 和 resolve，无锁且不观察后续 Registry 变更。
- 指纹或 JSON 渲染失败抛 `IllegalStateException` 并保留 cause，不返回缺失审计信息的上下文。

## 测试门禁

### 领域单元测试

- `SkillDefinitionTest`：名称、核心 SemVer、长度、空 trigger、重复 trigger、未知格式工具名、
  集合冻结和精确文本保留。
- `SkillCatalogTest`：自然排序、精确 find、重复名称、跨 Skill trigger 冲突、未知工具、构造
  原子失败、摘要不泄露策略、显式点名、未知显式名称和稳定 SHA-256。
- `SkillTriggerMatchingTest`：大小写敏感、Unicode 不归一化、原始 substring、多 Skill 命中、
  无 trigger Skill 仅显式激活和结果去重。
- `SkillCatalogConcurrencyTest`：多个虚拟线程并发 list/find/resolve 返回相同不可变结果，调用方
  修改 Schema accessor 返回值不污染目录。

### Registry 与 MCP 集成测试

`SkillMcpIntegrationTest` 使用 4B 的确定性 fake transport 完成 initialize、tools/list 和注册，
再构造 SkillCatalog。测试断言发现摘要不暴露工具细节，触发后只出现声明的 MCP 工具，
实际调用仍由 Registry 拒绝非法 Schema、缺少能力和未批准 HIGH 风险操作；批准后只发出
一次 `tools/call`。

### EDD

`agent-eval/src/test/java/com/agent/eval/SkillCatalogEddTest.java` 生成
`agent-eval/target/edd/skill-catalog-edd.json`。确定性任务覆盖摘要发现、trigger 激活、显式
激活、未命中、冲突拒绝、未知工具和 Registry 治理七条路线。报告每项字段精确为
`taskId/status/activatedSkills/exposedTools/fingerprint/passed`；报告不包含 Prompt 片段全文、
Schema、工具参数或密钥。

## 后续接口

- 4D CLI Capability 消费激活后的工具元数据，但命令风险、工作区边界和审批仍由独立规格定义。
- 第七篇综合实战再把 `SkillPromptContext` 接入 Planner/Coder 的生产 Prompt，并以真实 AST、
  PTY、Docker 和 Playwright 工具验证模型编排；4C 不改变现有图路由。
- 文件分发、签名校验、热重载和前端 Skill 管理不在本里程碑内。

## 提交与文档

规格提交使用：

```text
docs(skill): define read-only skill orchestration contract
```

实现完成后更新 `docs/ENGINEERING_PITFALLS.md`，记录触发归一化误匹配、同名多版本隐式选择、
Skill 旁路 Registry 和渐进披露失效等问题。日志、EDD 输出、`.env` 与 `target/` 继续排除。

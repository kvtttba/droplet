# Droplet 记账 — 开发进度看板与跨阶段交接单

> 每个开发会话启动时先读 [`AGENTS.md`](../AGENTS.md) 与本文档；每个切片完成合入 `main` 前必须更新本文档。

## 当前状态

- **当前阶段**：`M0`（工程骨架 + Material 3 主题与 4 Tab 占位壳 + Roborazzi 截图测试 + gitleaks + GitHub Actions API 31 模拟器 CI）已完成验收并合入 `main`。
- **下一个待执行切片**：**`M1.1`**（复式记账引擎与三币种 Trading 对冲）
- **最新已合入提交**：`feat/m0-scaffold-ci` 合入 `main`（CI 全线绿灯，详见 `git log`）

---

## 已锁定基线摘要（勿重复讨论）

- **应用定位**：Droplet 记账，Android 单端（`minSdk = 31` / Android 12+），个人自用，无登录、本地优先，纯净无广告。
- **包名与工程标识**：`applicationId = "io.github.kvtttba.droplet"`，Debug APK 输出名 `droplet-debug.apk`。
- **技术栈与已锁定版本基线**：Gradle 8.11.1 + Kotlin DSL + `gradle/libs.versions.toml`；AGP 8.9.3、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Hilt 2.56.2、Compose BOM 2025.05.01、Navigation Compose 2.8.9、Room 2.7.1、WorkManager 2.10.1、OkHttp 4.12.0、Kotlinx Serialization 1.8.1、Robolectric 4.14.1、Roborazzi 1.44.0；`compileSdk = 35` / `targetSdk = 35` / `minSdk = 31`，JVM 17 字节码目标（OpenJDK 21 运行环境兼容）。
- **核心账本**：单账本 + 多标签；底层严格复式（法币 4 位定点 `Long`，投资/加密份额 8 位 `BigDecimal`，跨币种隐式 `Equity:Trading` 对冲）；前端一木式直观表单 + 可展开专业分录模式；支持 **CNY / USD / HKD**（默认本位币 CNY）。
- **资产与行情**：覆盖 A 股、公募基金、ETF、港股、美股、贵金属/黄金、加密货币、定期存款、银行理财；免费公开行情（国内直连源优先 + 备用源 + 可选代理 + 失败保留缓存并支持手动覆写），进资产页刷新 + 后台定时更新。
- **信贷/借贷/报销/退款**：信用卡额度/账单日/还款日提醒；等额本息/等额本金/固定手续费分期自动生成还款分录与提醒；往来借贷（分次还/利息/坏账）；待报销冲抵；多次关联退款。
- **AI 记账**：不做无障碍/通知栏抓账；支持图片（小票汇总/单张截图/长截图/批量多图/系统分享）、语音（AI 接口 + 系统语音双模式）、一句话文本；OpenAI Compatible 自定义 `BaseURL`/`API Key`/`Model`（视觉/文本/语音可复用或分设）；仅传名称列表不传余额与流水；支持离线草稿与人工确认入账。
- **同步与隐私**：`BiometricPrompt` + 锁屏兜底 + 切后台重锁 + `FLAG_SECURE` + 一键隐藏金额；WebDAV 单机加密快照备份恢复（AES-256-GCM，防抖自动上传 + 定时 + 历史快照 + 票据图同步开关）。
- **测试与安全**：Public 公开仓，强制 `.gitignore` + `.gitleaks.toml` + `scripts/pre-commit-secret-check.sh` + GitHub Actions（`permissions: contents: read`）跑 L1 单测 / L2 Robolectric+Room / L3 Roborazzi UI 截图 / L4 KVM API 31 模拟器 E2E + 输出 `droplet-debug.apk`。

---

## 里程碑与切片总看板（1 切片 = 1 PR）

| 编号 | 切片名称 | 状态 | 核心交付与验收标准 | PR / 备注 |
| --- | --- | --- | --- | --- |
| `DOCS` | 需求澄清与架构定稿 | ✅ 已完成 | `PRD.md`、`ARCHITECTURE.md`、`AGENTS.md`、`PROGRESS.md`、`.gitignore` | 初始文档提交 (`4a6eb00`) |
| `M0` | 工程骨架与 CI 跑通 | ✅ 已完成 | 14 模块 Gradle 结构、Material 3 主题与 4 Tab 占位壳、`gitleaks`、GitHub Actions（L1 单测 + Roborazzi 8 张截图 + API 31 KVM 模拟器 E2E + 12 MB Debug APK）全绿 | `feat/m0-scaffold-ci` 已验收合入（CI `#45964751721`） |
| `M1.1` | 复式记账引擎与三币种 Trading 对冲 | ⬜ 待开始 | `core:model` + `core:domain` 复式校验、定点数/BigDecimal、跨币种对冲、L1 高覆盖单测 | 下一切片 |
| `M1.2` | Room Schema、DAO 与预置分类/账户 | ⬜ 待开始 | `core:database` 全量表结构、预置二级分类/系统科目、L2 Robolectric + Room 测试 | — |
| `M1.3` | 明细首页、账户/标签管理与记账表单 | ⬜ 待开始 | 首页流水与筛选、账户与分类/标签 CRUD、一木式表单 + 专业分录展开、Roborazzi 截图 + APK | — |
| `M1.4` | 生物识别隐私锁、重锁与金额隐藏 | ⬜ 待开始 | `BiometricPrompt`、切后台超时重锁、`FLAG_SECURE`、首页/资产一键隐身 | — |
| `M2.1` | 免费公开汇率与全品类行情 Provider 链 | ⬜ 待开始 | CNY/USD/HKD 汇率 + 9 类资产公开源适配、降级缓存、可选代理、Mock 测试 | — |
| `M2.2` | 投资持仓引擎与定存/理财计算 | ⬜ 待开始 | 买/卖/定投/分红、加权成本与浮盈、定期存款自动计息、银行理财手动更新 | — |
| `M2.3` | 资产总览页、持仓详情与后台定时刷新 | ⬜ 待开始 | 资产页总览与币种分布、持仓行情与手动覆写价、WorkManager 定时刷新、截图 + APK | — |
| `M3.1` | 信用卡额度、账单日与还款本地提醒 | ⬜ 待开始 | 信用额度进度条、出账/还款倒计时、到期前本地通知提醒 | — |
| `M3.2` | 分期贷款引擎与到期自动还款分录 | ⬜ 待开始 | 等额本息/等额本金/固定手续费计划表、到期自动拆本息分录与通知 | — |
| `M3.3` | 往来借贷、待报销冲抵与多次退款 | ⬜ 待开始 | 往来人借入/借出/分次还/利息/坏账核销、待报销冲抵与差额转支出、多次关联退款 | — |
| `M4.1` | AI 配置、Keystore 加密与 JSON 解析引擎 | ⬜ 待开始 | OpenAI Compatible 配置页与连通性测试、Keystore 加密存储、隐私最小化 Prompt、容错解析 | — |
| `M4.2` | 图片/语音/一句话记账与草稿批量确认 UI | ⬜ 待开始 | 拍照/长截图/批量/系统分享、语音双模式、一句话多笔、离线草稿箱与批量确认流 + APK | — |
| `M5.1` | 预算管理与预警、周期记账与模板 | ⬜ 待开始 | 月/年总预算与分类预算及预警、周期自动生成、高频一键模板 | — |
| `M5.2` | 统计报表、收支日历与多格式导出 | ⬜ 待开始 | 收支趋势/分类环形/资产走势/收支日历（含标签筛选）、Excel/CSV/Beancount 导出 | — |
| `M5.3` | WebDAV 端到端加密快照备份与回滚恢复 | ⬜ 待开始 | WAL 一致性打包、AES-256-GCM 加密、防抖/定时上传、历史快照列表、本地回滚安全恢复 | — |

---

## 关键决策与交接备忘（给下一个切片）

### M0 已落地的核心约定
1. **模块分工**：`core:model`、`core:domain`、`core:network` 为纯 Kotlin/JVM 模块（零 Android 依赖，秒级单测）；`core:database`、`core:security`、`core:ai`、`core:sync`、`core:ui` 与 5 个 `feature:*` 模块及 `app` 为 Android 模块；开启 `TYPESAFE_PROJECT_ACCESSORS`。
2. **金额与隐私组件**：`core:model` 已提供 `Currency`、`AccountType`、`Money`（`scale = 4` 严格定点 `Long`）；`core:domain` 提供 `PrivacyAmountFormatter`；`core:ui` 提供 `DropletTheme`、`DropletIcons`、`LocalAmountsHidden`、`PrivacyAmountText` 与 `PrivacyToggleIconButton`。
3. **CI 门禁**：`.github/workflows/android-ci.yml` 在 `main` / `feat/**` / `fix/**` 推送与 PR 时自动运行 3 个 Job（`security-scan`、`unit-and-ui-snapshot`、`build-and-emulator-e2e`）。

### M1.1 启动入口契约（复式记账引擎与三币种 Trading 对冲）
1. 从 `main` 切出分支 `feat/m1-1-ledger-engine`，在纯 JVM 模块 `:core:model` 与 `:core:domain` 中完成全部实现与高覆盖 L1 单元测试。
2. `:core:model` 补齐领域模型：`Account`（含 5 大 `AccountType` 与 `AccountSubType`、信用账户字段）、`Transaction`、`Posting`、`ExchangeRateSnapshot`、`Quantity`（`BigDecimal` 规范字符串，支持 8 位精度）及业务枚举（`TransactionDisplayType`、`TransactionStatus`、`TransactionSource`、`ReimburseState`）。
3. `:core:domain` 实现复式记账核心引擎与生成器：
   - **借贷平衡校验器**：单币种 `SUM(amount_minor) == 0`；多币种按币种分组各自 `SUM(amount_minor) == 0`；禁止零行或单行分录。
   - **三币种（CNY / USD / HKD）隐式 `Equity:Trading:{CCY}` 自动对冲器**：跨币种消费/转账/换汇时自动补齐对应 `isSystemBalancing = true` 的 Trading 平衡行，并支持按实付+实收金额反推成交汇率。
   - **历史本位币快照锁定**：为每条 `Posting` 依据交易时汇率快照计算并锁定 `baseAmountMinor` 与 `exchangeRateSnapshot`，后续汇率变化不漂移。
   - **典型业务场景分录 Builder**：普通支出（含优惠/手续费）、收入、同币种与跨币种转账/还款、部分/多次关联退款、待报销垫付与差额冲抵、借出/借入与分次还款含息/坏账核销、分期贷款拆本息还款、投资买入/卖出/分红、专业多拆分录直通校验。
4. 配齐高覆盖 L1 边界单测（舍入精度、大额溢出防护、跨币种反推汇率、负金额/零金额拒绝、所有典型业务场景代数和归零不变量），确保 `./gradlew :core:model:test :core:domain:test` 与 GitHub Actions 全绿。

---

## 踩坑记录

- **[M0] OpenJDK 21 下纯 JVM 模块编译目标**：纯 JVM 模块不要写 `kotlin { jvmToolchain(17) }`（会触发下载独立 JDK 17），统一使用 `java.sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17` + `kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17`。
- **[M0] 纯 JVM 单测与 Android Release 编译提速**：执行 `./gradlew :core:model:test :core:domain:test :core:network:test testDebugUnitTest`，避免根任务 `./gradlew test` 触发所有 Android 模块的 Release 单测编译。
- **[M0] Debug APK 体积控制**：`material-icons-extended` 会让未混淆 Debug APK 膨胀至 59 MB；改用 `core:ui` 精选矢量 `DropletIcons` + `packaging.dex.useLegacyPackaging = true` 后 Debug APK 降至 ~12 MB，便于飞书直接交付与真机快速安装。
- **[M0] Roborazzi + Hilt 兼容性**：纯 Compose 截图测试使用 `@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")` 跳过 `DropletApplication` 的 `@HiltAndroidApp` 初始化，配合 `RoborazziOptions.CompareOptions(changeThreshold = 0.015f)` 在本地 Debian 与 GitHub Actions `ubuntu-latest` 之间稳定通过。
- **[M0] 本机 GitHub PAT 权限**：本机 `gh` PAT 具备 `contents: write` 与 `workflows: write` 权限，未开 `pull_requests: write`，可通过 `feat/**` 分支推送直接触发 CI 验证，验收后合入 `main`。

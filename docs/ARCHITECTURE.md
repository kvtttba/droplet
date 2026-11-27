# Droplet 记账 — 系统架构、复式数据库 Schema 与 CI/安全方案（v1.0）

- **对应产品文档**：[`docs/PRD.md`](PRD.md)
- **目标环境**：Android 12+（`minSdk = 31`，`targetSdk = 35`，`compileSdk = 35`）
- **核心技术栈**：Kotlin + Jetpack Compose + Material 3 + Room（SQLite）+ WorkManager + Coroutines/Flow + OkHttp + Kotlinx Serialization
- **文档状态**：v1.0 已确认（需求澄清定稿）

---

## 1. 总体架构与模块划分

采用 **Clean Architecture + UDF（单向数据流 / MVI-lite）** 分层，所有核心记账计算与不变量校验均放在纯 Kotlin/JVM 领域层，不依赖 Android Framework，便于极速单元测试与长期维护。

```text
droplet/
├── app/                     # Android 入口、Application、DI 装配、导航宿主、Share Intent 接收
├── core/
│   ├── model/               # 纯 Kotlin 领域实体、枚举、定点数 Money/Quantity 类型
│   ├── domain/              # 复式记账引擎、分期计算器、持仓成本/浮盈引擎、多币种折算引擎
│   ├── database/            # Room Entities、DAO、事务封装、Migration、WAL Snapshot 导出
│   ├── security/            # BiometricPrompt 封装、Android Keystore 加密存储、WebDAV AES-GCM 加密器
│   ├── network/             # OkHttp 客户端（含可选代理支持）、免费公开行情与汇率 Provider 链
│   ├── ai/                  # OpenAI Compatible 客户端、Prompt 构造器、JSON 严格解析与草稿映射
│   ├── sync/                # WebDAV 客户端、快照打包/校验/回滚恢复、WorkManager 自动备份调度
│   └── ui/                  # Material 3 主题（Dynamic Color）、通用金融组件、金额隐私渲染组件、图表组件
└── feature/
    ├── ledger/              # 首页明细、搜索筛选、记账表单（直观模式 + 专业分录模式）
    ├── assets/              # 资产总览、账户详情、信用卡账单、投资持仓详情、分期贷款、借贷与待报销
    ├── ai_capture/          # 拍照/选图/系统分享入口、语音录入、一句话输入、AI 草稿批量确认页
    ├── stats/               # 收支趋势、分类占比、资产负债走势、收支日历、预算进度
    └── settings/            # AI 配置、WebDAV 配置与快照管理、周期/模板/分类/标签、隐私锁、数据导出
```

### 关键三方库选型（主流、轻量、维护活跃）

| 能力 | 推荐选型 | 说明 |
| --- | --- | --- |
| UI & 主题 | Jetpack Compose + Material 3 | Android 12+ 原生动态取色、细腻动效、无跨端开销 |
| 依赖注入 | Hilt（或 Koin） | 推荐 Hilt（官方生态与 WorkManager/Compose 集成最稳） |
| 本地数据库 | Room（SQLite） | 编译期 SQL 校验、Flow 响应式订阅、事务强一致 |
| 后台调度 | WorkManager | 定时刷新行情净值/汇率、防抖/定时 WebDAV 备份、周期记账与提醒 |
| 序列化与网络 | OkHttp + Kotlinx Serialization | 轻巧无反射膨胀，兼容自定义 `BaseURL` 与可选代理 |
| 本地凭据保护 | Android Keystore（AES-256-GCM）+ DataStore | 加密存放 AI `API Key`、WebDAV 密码与备份派生参数 |
| 图表渲染 | Vico（或自定义 Compose Canvas） | 原生 Compose 绘制，样式可控且与 Material 3 高度统一 |
| Excel 导出 | FastExcel / POI-lite（或轻量化 XLSX 生成器） | 保持 APK 体积精简 |

---

## 2. 复式记账核心数学模型与不变量

### 2.1 数值精度铁律（严禁浮点数）

- **法定货币金额（CNY / USD / HKD）**：统一用 `Long` 存**定点数**（精度 `scale = 4`，即 `10000 = 1.0000 元`，前端展示按 2 位四舍五入，内部计息/汇率折算保留 4 位防累计舍入误差）。
- **投资份额（含加密货币）**：统一用 `Long` 存 8 位定点（`scale = 8`，`100_000_000 = 1.00000000 份`，无损支持 BTC/ETH 等小数份额）或 `BigDecimal` 规范字符串（建议 `BigDecimal` 规范文本存库 + 领域层 `BigDecimal` 运算，彻底规避 `Long` 溢出风险）。
- **汇率与单价**：以 `BigDecimal` 规范字符串存库（精度 8 位小数）。

### 2.2 五大科目与借贷平衡不变量

所有账户与收支分类在底层均统一为 `Account`，归属五大基础类型：

- `ASSET`（资产）：现金、储蓄卡、储值卡、投资现金户、投资持仓、借出应收、待报销
- `LIABILITY`（负债）：信用卡、花呗/白条、分期贷款、借入应付
- `EQUITY`（权益）：期初余额（`OpeningBalance`）、隐式币种兑换中转（`Trading:CNY/USD/HKD`）
- `INCOME`（收入）：工资、奖金、利息收入、分红收入等二级分类
- `EXPENSE`（支出）：餐饮、交通、利息与手续费、坏账损失等二级分类

**符号约定（有符号代数和为零，对程序实现最简洁不易出错）**：
- 对同一笔 `Transaction` 内的所有 `Posting`：
  - **单币种交易**：`SUM(amount_minor) == 0`（例：支出分类 `+38.50`，银行卡 `-38.50`，代数和为 `0`）。
  - **跨币种交易**：领域引擎自动插入隐式 `Equity:Trading:{CURRENCY}` 平衡分录，保证**每个币种各自 `SUM(amount_minor) == 0`**（UI 默认隐藏 `Trading` 行，仅在专业分录调试视图可见）。
- **历史锁定原则**：每条 `Posting` 在入账时同时记录 `base_amount_minor`（按交易当时汇率折算的本位币等值金额）与 `exchange_rate_snapshot`，历史收支报表直接汇总 `base_amount_minor`，永远不会被后续汇率变动篡改。

### 2.3 典型业务场景分录映射

| 业务场景 | 前端表单 | 自动生成的底层分录（示例） |
| --- | --- | --- |
| 普通消费（CNY） | 支出 ¥38.50 餐饮 | `Expense:餐饮:午餐` `+38.50`；`Liability:信用卡:招行` `-38.50` |
| 消费含优惠与手续费 | 支出原价 ¥100，满减 ¥15，实付 ¥85 | `Expense:购物` `+85.00`（或记原价 `+100` + `Income:优惠返现` `-15` 可配置）；`Asset:储蓄卡` `-85.00` |
| 部分退款（关联原单） | 原消费 ¥200 退 ¥50 | `Asset:储蓄卡` `+50.00`；`Expense:购物` `-50.00`（`ref_transaction_id` 指向原单） |
| 待报销垫付 | 支出 ¥500 勾选「待报销」 | `Asset:待报销` `+500.00`；`Liability:信用卡` `-500.00`（不计入个人消费） |
| 报销到账（含未报销差额） | 实报 ¥450，自付 ¥50 | `Asset:储蓄卡` `+450.00`；`Expense:差旅` `+50.00`；`Asset:待报销` `-500.00` |
| 借出 & 分次收款含息 | 借张三 ¥1000；还 ¥400 + 息 ¥20 | 借出：`Asset:借出:张三` `+1000`，`Asset:储蓄卡` `-1000`<br>收款：`Asset:储蓄卡` `+420`，`Asset:借出:张三` `-400`，`Income:利息` `-20` |
| 坏账核销 | 张三剩余 ¥600 核销 | `Expense:坏账损失` `+600.00`；`Asset:借出:张三` `-600.00` |
| 分期贷款当期还款 | 第 1 期还 ¥1050（本 ¥1000 + 息 ¥50） | `Liability:分期:房贷` `+1000.00`；`Expense:利息手续费` `+50.00`；`Asset:储蓄卡` `-1050.00` |
| 跨币种换汇 | 工行 ¥7250 换 $1000 进美股资金户 | `Asset:储蓄卡:工行` `-7250 CNY`，`Equity:Trading:CNY` `+7250 CNY`<br>`Asset:美股现金` `+1000 USD`，`Equity:Trading:USD` `-1000 USD` |
| 买入股票/基金/加密 | $2200 买 10 股 AAPL（单价 $220） | `Asset:持仓:AAPL` `+2200 USD`（`qty=+10, price=220`）；`Asset:美股现金` `-2200 USD` |
| 现金分红 | AAPL 分红 $15 | `Asset:美股现金` `+15 USD`；`Income:投资分红` `-15 USD` |

---

## 3. Room 数据库核心 Schema 设计

以下为核心表结构（完整索引、外键级联约束与枚举在实现时补齐）：

### 3.1 `accounts`（科目与账户统一表）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `TEXT PK` | UUID |
| `name` | `TEXT` | 名称（如「招行信用卡」「餐饮」「午餐」） |
| `type` | `TEXT` | `ASSET` / `LIABILITY` / `EQUITY` / `INCOME` / `EXPENSE` |
| `sub_type` | `TEXT` | `CASH` / `DEBIT_CARD` / `CREDIT_CARD` / `STORED_VALUE` / `INVEST_CASH` / `INVEST_HOLDING` / `LOAN` / `RECEIVABLE` / `PAYABLE` / `REIMBURSEMENT` / `CATEGORY` / `TRADING` / `OPENING` |
| `parent_id` | `TEXT NULL` | 父级 ID（用于二级分类、往来人归组等） |
| `currency` | `TEXT` | `CNY` / `USD` / `HKD` |
| `icon` / `color` | `TEXT` | 图标标识与主题色 |
| `include_in_net_worth` | `INTEGER` | `1/0`，是否计入总资产 |
| `is_archived` / `is_system` | `INTEGER` | 归档标志 / 系统内置科目标志 |
| `credit_limit_minor` | `INTEGER NULL` | 信用额度（仅信用账户） |
| `billing_day` / `due_day` | `INTEGER NULL` | 账单日 / 还款日（1–31） |
| `remind_days_before` | `INTEGER NULL` | 还款提前提醒天数 |
| `sort_order` / `created_at` / `updated_at` | `INTEGER` | 排序与时间戳（epoch ms） |

### 3.2 `transactions`（交易头）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `TEXT PK` | UUID |
| `occurred_at` | `INTEGER` | 交易发生时间戳（epoch ms，建索引） |
| `display_type` | `TEXT` | `EXPENSE` / `INCOME` / `TRANSFER` / `LOAN_REPAY` / `DEBT` / `REFUND` / `REIMBURSE` / `INVEST` / `SPLIT` |
| `status` | `TEXT` | `POSTED`（已入账）/ `DRAFT`（草稿）/ `VOID`（作废） |
| `payee` | `TEXT NULL` | 商户 / 交易对手方 |
| `note` | `TEXT NULL` | 备注 |
| `source` | `TEXT` | `MANUAL` / `AI_IMAGE` / `AI_VOICE` / `AI_TEXT` / `RECURRING` / `LOAN_AUTO` |
| `ref_transaction_id` | `TEXT NULL` | 关联原交易 ID（退款 / 报销冲抵追溯） |
| `reimburse_state` | `TEXT NULL` | `PENDING` / `PARTIAL` / `SETTLED` |
| `created_at` / `updated_at` | `INTEGER` | 创建与更新时间 |

### 3.3 `postings`（复式分录明细）
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `TEXT PK` | UUID |
| `transaction_id` | `TEXT FK` | 关联 `transactions.id`（`ON DELETE CASCADE`） |
| `account_id` | `TEXT FK` | 关联 `accounts.id`（建索引） |
| `currency` | `TEXT` | `CNY` / `USD` / `HKD` |
| `amount_minor` | `INTEGER` | 有符号定点数（`scale = 4`） |
| `base_currency` | `TEXT` | 入账时的本位币（如 `CNY`） |
| `base_amount_minor` | `INTEGER` | 交易发生时锁定的本位币折算额 |
| `exchange_rate_snapshot` | `TEXT` | `BigDecimal` 文本（原币种 → 本位币汇率） |
| `investment_asset_id` | `TEXT NULL` | 若涉及投资标的，关联 `investment_assets.id` |
| `quantity_decimal` | `TEXT NULL` | `BigDecimal` 文本（买入正、卖出负，支持 8 位精度） |
| `unit_price_decimal` | `TEXT NULL` | `BigDecimal` 文本（成交单价，原币种） |
| `is_system_balancing` | `INTEGER` | `1` 表示隐式 Trading 平衡行（默认 UI 折叠） |

### 3.4 投资、行情、汇率与贷款/借贷/工具表

- **`tags` / `transaction_tags`**：标签定义与多对多关联表（建复合主键与反向索引）。
- **`attachments`**：`id`、`transaction_id`、`local_rel_path`、`sha256`、`mime_type`、`size_bytes`、`sync_to_webdav`。
- **`investment_assets`**：`id`、`account_id`（对应持仓账户）、`asset_class`（`CN_STOCK` / `CN_FUND` / `ETF` / `HK_STOCK` / `US_STOCK` / `GOLD` / `CRYPTO` / `FIXED_DEPOSIT` / `BANK_WEALTH`）、`symbol`（代码如 `600519` / `00700` / `AAPL` / `BTC`）、`name`、`currency`、`quote_provider_hint`、`deposit_rate_annual`、`deposit_start_at`、`deposit_maturity_at`、`deposit_interest_mode`。
- **`market_quotes`**（独立估值缓存，不污染流水）：`asset_id PK`、`latest_price_decimal`、`prev_close_decimal`、`daily_change_pct`、`price_currency`、`source_provider`、`is_manual_override`、`fetched_at`。
- **`exchange_rates`**：`(from_currency, to_currency) PK`、`rate_decimal`、`source_provider`、`fetched_at`。
- **`loan_plans` / `loan_installments`**：贷款方案（`method`: `EQUAL_INSTALLMENT` / `EQUAL_PRINCIPAL` / `FIXED_FEE`、总本金、期数、利率/费率、首期日、关联负债户与付款户、是否自动入账）+ 逐期明细表（期号、到期日、应还本金、应还利息/手续费、状态 `PENDING`/`PAID`、关联 `transaction_id`）。
- **`debt_profiles`**：往来人信息（`name`、`receivable_account_id`、`payable_account_id`、`default_due_at`、`annual_interest_rate`、`note`）。
- **`budgets`**：周期（`MONTHLY` / `YEARLY`）、目标分类 ID（`NULL` 表示总预算）、限额、预警比例。
- **`recurring_rules` / `templates`**：周期记账规则（CRON/频率、下次执行日、模板负载 JSON、是否自动入账）与高频记账模板。
- **`ai_drafts`**：AI 识别队列（`input_type`: `IMAGE`/`VOICE`/`TEXT`、`raw_text`、`attachment_paths_json`、`status`: `PENDING_ONLINE`/`PARSING`/`READY`/`FAILED`/`COMMITTED`、`parsed_items_json`、`error_message`、`created_at`）。

---

## 4. 免费公开行情与汇率适配器架构

采用**可插拔 Provider 策略链**，每个品类按「国内直连优先 → 备用源兜底 → 缓存保留 + 手动覆写」运行：

| 品类 / 数据 | 主数据源（国内直连优先） | 备用数据源 / 特殊策略 |
| --- | --- | --- |
| A 股 / 场内 ETF | 东方财富 / 腾讯财经公开行情接口 | 新浪财经公开接口 |
| 公募基金 | 天天基金（东方财富）估值与官方净值接口 | 东方财富基金净值接口 |
| 港股 / 美股 | 东方财富 / 腾讯财经公开行情接口 | Yahoo Finance 公开接口 |
| 贵金属 / 黄金 | 东方财富 / 上金所 AU9999 公开报价 | 国际现货 XAU 公开接口 |
| 加密货币 | 主流交易所公开 Ticker API（如 OKX / Binance） | CoinGecko 公开 API；支持设置页配置可选 HTTP/SOCKS 代理 |
| 定期存款 | 本地计算引擎（按本金、年利率、计息天数实时推算） | 到期结息生成利息收入分录 |
| 银行理财 | 若有公开净值代码走基金/理财公开源 | 默认支持手动更新净值/最新收益，或通过 AI 截图更新 |
| CNY/USD/HKD 汇率 | 免费公开汇率源（如 Frankfurter / ExchangeRate-API / 东方财富外汇） | 失败保留上次缓存汇率，记账时始终允许手动覆盖 |

**节流与健壮性**：打开资产页距离上次成功拉取不足 5 分钟时默认不重复刷；后台 `WorkManager` 按设定周期（如每日两次）批量拉取并合并网络请求，单标的失败不影响其余标的更新。

---

## 5. AI 记账引擎与结构化输出契约

### 5.1 配置灵活性（OpenAI Compatible）

- 配置分为「默认全局模型」与 3 个可选独立覆盖槽位：**视觉模型（图片）**、**文本模型（一句话 / 语音文本拆单）**、**语音转写模型（Whisper 兼容或支持音频输入的多模态模型）**。
- 所有 `API Key` 均通过 Android Keystore 加密存储于本地，绝不写入明文日志或普通 SharedPreferences。

### 5.2 隐私最小化 Prompt 策略

每次请求仅向模型注入：
1. 当前日期时间与默认币种（CNY）；
2. 用户的**账户名称 + 类型 + 币种列表**（不含余额、卡号）；
3. 用户的**二级分类名称列表**与**常用标签名列表**；
4. 当前输入（压缩去 EXIF 后的图片 / 语音转写文本 / 用户输入句子）。

### 5.3 统一结构化返回格式（JSON Schema）

强制要求模型返回严格 JSON 数组（天然支持小票、长截图、批量多图、一句话多笔）：

```json
{
  "items": [
    {
      "display_type": "EXPENSE",
      "occurred_at": "2026-11-27T12:30:00+08:00",
      "amount": "38.50",
      "currency": "CNY",
      "category_path": "餐饮/午餐",
      "account_name": "招行信用卡",
      "target_account_name": null,
      "payee": "麦当劳",
      "tags": ["工作日"],
      "note": "午饭",
      "confidence": 0.92,
      "uncertain_fields": []
    }
  ]
}
```

解析器具备容错提取能力（剥离 Markdown 代码块、字段校验与默认值回退），将解析结果映射为本地候选草稿并高亮 `uncertain_fields` 供人工确认。

---

## 6. WebDAV 加密备份与恢复方案

1. **一致性快照打包**：
   - 执行 Room `PRAGMA wal_checkpoint(TRUNCATE)` 或使用 SQLite `VACUUM INTO` 生成完整一致性单文件库副本 `ledger.db`；
   - 连同 `manifest.json`（含 App 版本号、Schema 版本号、导出时间、SHA-256 校验和、是否含附件标志）及可选票据附件目录打包为 ZIP。
2. **可选端到端加密（推荐开启）**：
   - 文件格式：`DRP1` 魔数头（4B）+ Salt（16B）+ Nonce（12B）+ 密文 + GCM Tag（16B）；
   - 密钥派生：用户备份口令经 PBKDF2-HMAC-SHA256（高迭代次数）派生 256-bit 密钥；
   - 加密算法：AES-256-GCM（同时保证机密性与防篡改校验）。
3. **自动同步与安全恢复**：
   - 本地每次入账/编辑触发防抖任务（默认 30 秒合并），配合 `WorkManager` 每日定时任务上传至 WebDAV 指定目录，自动按策略清理超过保留份数（默认 10 份）的历史旧包。
   - **安全恢复流程**：下载目标快照 → 校验哈希/解密验签 → 校验 Schema 版本兼容性 → **先将本机当前数据库备份至本地应急回滚目录** → 原子替换并重载 Room 实例；若任意环节失败自动回滚，确保本地数据零损坏。

---

## 7. 测试策略、公开仓库安全规范与 GitHub Actions CI

### 7.1 四层质量保障体系

| 层级 | 运行环境 | 覆盖内容 | 触发频率 |
| --- | --- | --- | --- |
| **L1 纯 JVM 单元测试** | 本机 / GitHub Actions（秒级） | 复式借贷恒平校验、多币种 Trading 对冲、分期贷款还款表数学精度、投资持仓成本/浮盈、定期计息、AI JSON 容错解析、AES-GCM 加密解密 | 每次提交 / PR |
| **L2 Robolectric + Room 测试** | 本机 / GitHub Actions（数十秒） | Room DAO 复杂查询、多标签统计聚合、Schema 迁移、WorkManager 调度与 WebDAV Mock 同步 | 每次提交 / PR |
| **L3 Compose UI 与截图测试** | 本机 / GitHub Actions（Roborazzi / Paparazzi） | 核心页面交互断言 + 生成高清 PNG 页面渲染截图，用于 UI 美观度与深浅色/动态配色视觉验收 | 每次 UI 改动 / PR |
| **L4 真模拟器 E2E + 真机验收** | GitHub Actions 公开库免费 `ubuntu-latest`（4C16G + KVM，API 31 模拟器）+ Android 12 真机 | 完整端到端 `connectedAndroidTest`、APK 打包；真机通过直接安装 APK 或无线 ADB 验收指纹锁、拍照与系统分享 | 合入 `main` / 手动触发 / 发版 |

### 7.2 公开仓库（`kvtttba/droplet`）安全门禁

1. **零敏感信息入库**：
   - `.gitignore` 强制拦截 `local.properties`、`*.jks`、`*.keystore`、`*.p12`、`*.pem`、`.env*`、`secrets.properties` 及真实财务样例目录；
   - 严禁在源码、注释、测试用例、CI 日志中写入真实 `BaseURL`、`API Key`、WebDAV 地址/密码、内网/VPS IP 或个人真实账单截图。
2. **三重秘密扫描**：
   - GitHub 仓库开启 **Secret scanning** 与 **Push protection**；
   - CI Workflow 集成 `gitleaks` 扫描全量提交历史与 diff；
   - 本地配置 `pre-commit` 检查脚本，提交前自动拦截疑似密钥/私钥头。
3. **CI 权限最小化与签名隔离**：
   - Workflow 默认顶层权限为 `permissions: contents: read`；
   - Release 签名 4 个 Secrets（后期配置）仅绑定到受保护的发版 tag Workflow，绝不向外部 PR 暴露。

### 7.3 GitHub Actions Workflow 规划（`.github/workflows/android-ci.yml`）

- **Job 1（`security-scan`）**：运行 `gitleaks` 检测敏感信息。
- **Job 2（`unit-and-ui-snapshot`）**：OpenJDK 21 + Gradle 缓存，执行 `./gradlew testDebugUnitTest`（含 L1/L2/L3），产出 Compose 页面渲染截图与测试报告（短期 Artifact）。
- **Job 3（`build-and-emulator-e2e`）**：在 `ubuntu-latest` 上授予 `/dev/kvm` 权限，使用 `reactivecircus/android-emulator-runner@v2`（`api-level: 31`, `target: google_apis`, `arch: x86_64` + AVD 缓存）运行 `./gradlew connectedDebugAndroidTest`，并构建输出 `droplet-debug.apk`。

---

## 8. 推荐开发里程碑（Phased Roadmap）

每个里程碑均可独立产出**测试通过报告 + Compose 渲染截图 + 可安装 Debug APK**：

1. **M0 工程骨架与 CI 跑通**：Gradle 多模块结构、Material 3 主题、安全 `.gitignore`、`gitleaks`、GitHub Actions（单测 + Roborazzi + API 31 模拟器 + Debug APK 打包）绿灯跑通。
2. **M1 复式核心与日常记账闭环**：Room Schema、复式引擎、CNY/USD/HKD 多币种基础、账户与二级分类/标签管理、首页明细与一木式直观记账 + 专业分录模式、生物识别隐私锁。
3. **M2 资产、投资与行情更新**：全品类投资账户（A股/基金/ETF/港美股/黄金/加密/定存/银行理财）、买入/卖出/定投/分红、免费公开行情与汇率 Provider 链、资产页总览与后台定时更新。
4. **M3 信贷、借贷、报销与退款**：信用卡账单/还款提醒、三类分期贷款自动计划与提醒、往来借贷（含利息与坏账）、待报销冲抵与多次关联退款。
5. **M4 AI 智能记账**：OpenAI Compatible 自定义配置、图片识别（小票/单图/长截图/多图/系统分享）、语音识别（AI 接口 + 系统语音双模式）、一句话文本记账、离线草稿队列与批量确认流。
6. **M5 统计、工具与 WebDAV 加密备份**：预算与预警、周期记账与模板、四大统计图表与收支日历、Excel/CSV/Beancount 导出、WebDAV 端到端加密快照备份与安全回滚恢复。

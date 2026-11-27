# Droplet 记账

一款本地优先、纯净无广告的 Android 个人复式记账应用（Android 12+）。

## 核心特性

- **本地优先 & 隐私保护**：无需注册登录，基于 Android 系统生物识别 / 锁屏密码鉴权（`BiometricPrompt`），支持切后台重锁、防截屏（`FLAG_SECURE`）与金额一键隐藏。
- **底层复式 & 极简交互**：底层严格复式分录（单账本 + 多标签），前端默认直观表单、按需展开专业分录模式；原生支持 **CNY / USD / HKD** 多币种与自动汇率。
- **全品类资产管理**：支持现金、储蓄卡、信用卡（账单日/还款日提醒）、储值账户、分期贷款（等额本息 / 等额本金 / 固定手续费分期）、往来借贷、待报销与多次关联退款，以及 A 股、公募基金、ETF、港股、美股、贵金属黄金、加密货币、定期存款与银行理财的持仓管理及免费公开行情自动更新。
- **可控的 AI 智能记账**：不做无障碍或通知栏侵入式抓账；支持可自定义 `BaseURL` 与 `API Key`（OpenAI Compatible）的拍照/截图识别、语音双模式记账与一句话多笔文本记账，全部先预览确认再入账。
- **WebDAV 加密备份**：支持本地变更防抖自动备份、定时备份、历史快照管理与可选 AES-256-GCM 端到端加密。

## 技术栈

- **平台**：Android 12+（`minSdk = 31`，`targetSdk = 35`）
- **开发框架**：Kotlin · Jetpack Compose · Material 3（Material You）· Room · WorkManager · OkHttp · Kotlinx Serialization

## 项目文档

- [产品需求规格说明（PRD）](docs/PRD.md)
- [系统架构、复式数据库 Schema 与 CI / 安全方案](docs/ARCHITECTURE.md)
- [开发进度看板与跨阶段交接单](docs/PROGRESS.md)
- [开发与安全协作规范（AGENTS.md）](AGENTS.md)

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。

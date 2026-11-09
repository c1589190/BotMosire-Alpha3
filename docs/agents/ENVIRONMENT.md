# 本机环境事实（2026-11-09 18:54 +08）

## 当前项目

- 工作区：`/home/cna/BotMosire-Alpha3`（单 Git 仓）。
- 远端：`https://github.com/c1589190/BotMosire-Alpha3`（初始化前为**空仓**，无分支/提交；本仓随后创建 `main` 初始化提交）。
- 当前没有产品源码、构建文件、运行进程、监听端口或数据库。

## 继承来源（只读）

- 协调工作区：`/home/cna/DevMosire-iiBot`（根目录本身不是 Git 仓）。
- 内部独立 Git 仓（2026-11-09 实测）：

| 目录 | 分支 | HEAD |
|---|---|---|
| `InboxMosire/` | `main` | `e3f0a89` |
| `InboxMosire-DSH/` | `main` | `3bf7630` |
| `InboxMosire-Hermes/` | `main` | `efa51e6` |
| `iiBotMosire/` | `refactor/bot-first-main-agent` | `f3b1602` |
| `iiBotMosire-DSH/` | `main` | `42611c5` |

- `InboxMosire-OpenCode/` 尚未初始化 Git；`bot/`、`bot2/` 是运行目录，不是源码仓。
- 继承来源文档中的旧 HEAD（如 `681f976`、`9db9d29`）与实测不一致；以 `git rev-parse` 为准。这说明其 STATUS/AGENTS 存在滞后，引用前必须复核。

## 工具链

- JDK：OpenJDK 21.0.12.1（Ubuntu）。
- Maven：Apache Maven 3.8.7（系统 `mvn`；继承来源另有 `./mvnw`）。
- Node.js：v22.23.3。
- Python：3.12.3。
- ffmpeg：6.1.1。
- Git：2.43.0。
- Chromium：`/snap/bin/chromium`，Chrome 156.0.8078.47；本机 ChromeDriver 156.0.8078.80 位于 `~/.cache/bma3-chromedriver/156.0.8078.80/chromedriver`，通过 `BMA3_CHROMEDRIVER` 指定。
- Selenium Manager 自动下载在本机曾长时间无输出；webconnect 优先用显式 driver 路径。Snap Chromium 不能读 `/tmp`，浏览器 profile/页面放 home 下。

## 网络

- 2026-11-09 调查时 GitHub API/远端可达（`git ls-remote` rc=0，API 200）。
- 不假定外网始终可用；需要联网的验证必须当次确认，失败就写 `NOT_RUN` / `BLOCKED`。

## 路径规则

- 本文路径是本机 canonical 事实；新脚本从自身位置推导仓库根，或使用显式环境变量。
- 不新增固定家目录绝对路径到产品代码；文档中的绝对路径只用于本机事实记录。

# KilnLauncher

**简体中文** | [English](README_EN.md)

KilnLauncher 是基于 [HMCL **3.16.4 正式版**](https://github.com/HMCL-dev/HMCL/releases/tag/v3.16.4)
源码构建的 Minecraft 启动器分支，重点补上了「和朋友联机」这一块：把陶瓦联机（Terracotta）
的房间公开到一个**免费、无需服务器**的房间大厅里，别人可以直接看到邀请码并加入。

当前版本：**1.0.0**

## 功能

### 房间大厅

- 房主在「已启动房间」里打开「公开到房间大厅」后，邀请码会出现在所有 KilnLauncher 用户的房间大厅
- 默认私有：不主动打开就永远不会出现在大厅
- 支持**房间标签**（原版、生存、创造、冒险、解密、恐怖、跑酷、PVP、PvE、猎人游戏、mod）
- 大厅支持**按标签筛选**和**按房间名 / 房主名搜索**（房主名只在微软账户时参与匹配）
- 每行显示延迟和 `Ping X 秒前`，连续 3 分钟没有心跳才标记「可能已离线」
- 可自定义房间名称，留空则使用游戏实例名

### 房间密码

开启后邀请码会先用 `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding` 加密再发布，
公开话题里没有明文；房客必须输入正确密码才能查看邀请码、复制或加入。

### 模组依赖

下载模组时会自动解析**必需前置**，挑选与当前实例（游戏版本 + 加载器）匹配的版本，
已经在实例里的前置直接跳过，不再重复下载。

### 下载源

模组 / 资源包 / 光影页面支持选择 **Modrinth**、**CurseForge** 或**合并搜索**。
合并模式会同时查询两个站点并按名称去重。

> CurseForge 需要自己的 API Key（在 `console.curseforge.com` 免费申请），
> 在「设置 → 下载 → CurseForge API Key」填入即可，也可以构建时通过环境变量
> `CURSEFORGE_API_KEY` 内置。

### 其他

- 数据目录改为 `.kilnlauncher`，首次启动会自动从旧的 `.hmcl` 复制配置
- 关闭了上游 HMCL 的自动更新检查
- 窗口 / 任务栏 / EXE 图标和名称均为 KilnLauncher

## 运行要求

- JDK 17 或更高版本，推荐 21
- Windows / macOS / Linux

## 构建

```bash
# 只构建可执行 JAR（输出在 HMCL/build/libs）
./gradlew :HMCL:shadowJar

# 构建单文件可执行程序
./gradlew :HMCL:makeExecutables
```

可选环境变量：

| 变量 | 作用 |
| --- | --- |
| `CURSEFORGE_API_KEY` | 把 CurseForge API Key 编译进程序，用户端无需再填 |
| `BUILD_NUMBER` / `VERSION_TYPE` | 控制版本号（见 `config/project.properties` 的 `versionRoot`） |

如果访问 GitHub 下载 Gradle 发行版失败，可以改用镜像下载同版本 Gradle 后执行同样的任务。

## 使用

首次启动会生成 `.kilnlauncher` 文件夹。联机入口在「多人联机 → 联机状态」，
房间大厅在「多人联机 → 房间大厅」。

房间大厅基于公共的 ntfy 话题，无需账号和服务器，可以用系统属性替换成自建实例：

```bash
-Dkiln.rooms.server=https://ntfy.sh
-Dkiln.rooms.topic=kilnlauncher-rooms-v1
```

陶瓦中继节点失效时启动器会自动补一个可用节点，也可以用
`-Dkiln.terracotta.nodes=...` 追加自己的中继。

## 已知限制

- 房间大厅是公开话题，任何拿到消息的人都能看到房间里公开展示的信息；
  房间密码只能防止陌生人随意加入，不能防止针对性暴力破解，请设置足够长的密码

## 与上游的关系

本项目是 HMCL 的非官方分支，与 HMCL 开发团队、Mojang、Microsoft 均无关联。

- 上游：<https://github.com/HMCL-dev/HMCL>
- 详细文档：[KILNLAUNCHER.md](KILNLAUNCHER.md)（中文） / [KILNLAUNCHER_EN.md](KILNLAUNCHER_EN.md)（English）
- 陶瓦联机（Terracotta）是第三方开源项目，运行时下载，不随本仓库分发

## 许可证

本项目遵循上游 HMCL 的 **GPLv3** 许可证，完整文本见 [LICENSE](LICENSE)。

HMCL 版权归 huangyuhui 及贡献者所有，KilnLauncher 的修改部分版权归 KilnLauncher 贡献者所有。

---

## English

KilnLauncher is an unofficial fork of [HMCL](https://github.com/HMCL-dev/HMCL) (3.16.4, GPLv3)
that adds a serverless **public room lobby** for Terracotta multiplayer: hosts can publish their
room invite code to every KilnLauncher user, with tags, filtering, search, latency/Ping display
and an optional password that actually encrypts the invite code (AES-GCM) before publishing.
It also resolves required mod dependencies on download and can search Modrinth and CurseForge
together (CurseForge requires your own API key).

Build with `./gradlew :HMCL:shadowJar` (JDK 17+). Licensed under GPLv3, see [LICENSE](LICENSE).
Not affiliated with Mojang, Microsoft, or the HMCL team.

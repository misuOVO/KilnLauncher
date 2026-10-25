# KilnLauncher

**简体中文** | [English](KILNLAUNCHER_EN.md)

KilnLauncher 是基于 [HMCL **3.16.4 正式版**](https://github.com/HMCL-dev/HMCL/releases/tag/v3.16.4)
源码构建的 Minecraft 启动器，不是快照版。在当前免费、无服务器的前提下增加了「房间大厅」功能：
**房主可以把陶瓦联机房间公开**，让所有使用 KilnLauncher 的玩家直接看到邀请码并一键加入。

当前版本号：**1.0.0**（由 `config/project.properties` 的 `versionRoot` 与打包时的 `BUILD_NUMBER` 组成，
同时写入 `assets/hmcl.properties`、EXE 文件版本和窗口标题）。

> 本项目遵循上游 HMCL 的 GPLv3 许可证。HMCL 版权归 huangyuhui 及贡献者所有，
> 修改部分版权归 KilnLauncher 贡献者所有。

## 功能

### 房间大厅

- 位于「联机」→「房间大厅」标签页。
- 只显示**房主主动勾选公开**的房间，私人房间不会上传，也不会显示。
- 显示房间名、房主昵称、当前玩家数、陶瓦邀请码。
- 点击任意房间即可确认并直接加入。
- 每行右侧的复制按钮可以直接复制邀请码，方便分享给朋友。
- 页面可见时每 15 秒自动刷新，也可以手动刷新。

### 公开房间开关

在「联机」→「联机状态」中创建房间，进入「已启动房间」状态后，会出现
**「公开到房间大厅」**开关：

- 打开：立即把房间邀请码发布到房间大厅，并每 60 秒发送一次 Ping。
- 关闭：立即从房间大厅移除。
- 点击「退出」关闭房间、切换到房客模式或陶瓦联机异常时，也会自动移除。
- 启动器异常退出时没有机会发送移除消息，房间会在 **4 分钟**（Ping 超时）后自动消失。

> 公开房间的邀请码对所有人可见，任何拿到邀请码的人都可以加入。
> 请不要在公开房间中透露隐私信息。

隐私说明：公开房间时会向公共频道发送房间名、房主昵称、玩家数、实例名和邀请码。
关闭公开或 Ping 超时后，大厅会立即（或最迟 4 分钟后）不再显示该房间，但已经发布到
ntfy.sh 的历史消息无法撤回，可能在该服务端的缓存中保留最多 12 小时。
因此请只在愿意公开这些信息时开启开关。

## 零成本实现方式

房间大厅使用 [ntfy.sh](https://ntfy.sh) 的**公共主题**作为消息总线，不需要服务器、
账号、数据库或付费服务：

1. 房主勾选公开后，启动器向 `https://ntfy.sh/kilnlauncher-rooms-v1` 发布一条 JSON Ping，
   内容包含房间名、房主、玩家数、邀请码和 `visibility: "public"`。
2. 每 60 秒重发一次 Ping；ntfy 免费缓存最近 12 小时的消息。
3. 每个启动器轮询同一主题最近 20 分钟的消息，按房间 ID 去重、按邀请码取最新一条，
   过滤掉超过 4 分钟没有 Ping 的房间。
4. 关闭房间时发送 `action: "close"` 消息立即移除。

因为使用的是公开主题，服务端不需要知道任何用户身份；这也意味着主题是公开可写的，
客户端会对消息做格式、长度、可见性和时间校验。若社区需要更可控的部署，可以把
主题迁移到自建 ntfy 实例，无需重新编译：

```text
-Dkiln.rooms.server=https://your-ntfy.example.com
-Dkiln.rooms.topic=your-private-topic
```

所有使用同一个 `server` + `topic` 的启动器共享同一个房间大厅。

### 为什么只有 KilnLauncher 的房间？

上游 HMCL 没有「公开房间」功能，也不会把邀请码发布到任何地方，因此无法自动发现
HMCL 用户创建的私人房间。房间大厅列出的是**同样使用 KilnLauncher 并主动公开**的房间。

## 直接运行（Windows EXE）

已经生成好单文件程序：

```text
dist\KilnLauncher.exe
```

双击即可运行，文件名、图标和版本信息均为 KilnLauncher。
它是「HMCL 原生引导器 + KilnLauncher JAR」组成的单文件 EXE，需要系统安装
**Java 17 或更高版本**（推荐 Java 21）。首次运行会在 EXE 所在目录生成 `.kilnlauncher`
配置与依赖目录。如果同目录下存在旧版 `.hmcl`，启动器会**复制**一次配置到 `.kilnlauncher`
（不会移动或修改 `.hmcl`），账号和实例可以直接继续使用。

如果 Windows SmartScreen 提示“未知发布者”，点击“更多信息”后选择“仍要运行”。
`dist/README.txt` 中有给玩家的简短使用说明。

## 品牌与数据目录

- 启动器自己的配置与依赖目录改为 **`.kilnlauncher`**（Windows 用户目录下同样是 `.kilnlauncher`）。
- 如果同目录存在旧版 `.hmcl`，首次启动会把它**复制**到 `.kilnlauncher`，账号和实例可以直接继续使用；
  旧 `.hmcl` 不会被移动或修改，因此 HMCL 仍可正常使用。
- 窗口图标、标题栏图标、任务栏图标和 EXE 图标均已替换为 KilnLauncher 的 K 图标。
- 移除了 HMCL 的预览版公告、官方群组、反馈和帮助入口，避免与 HMCL 混淆。
- 游戏实例目录内部的 `.hmcl` 是实例元数据格式（保存实例配置、整合包信息等），
  为了兼容已有实例和 HMCL 生态而保留，不属于启动器自身的数据目录。

## 陶瓦版本兼容性

KilnLauncher 内置陶瓦联机 **0.4.2**，与 HMCL 3.15.2+、HMCL 3.16.4 和 FCL 一致。

注意：

- 房主和房客最好使用同一代陶瓦，混用不同版本时可能「加入房间失败」。
- 房间加入成功后仍需房主保持世界「对局域网开放」、使用 Terracotta 显示的连接地址，
  并且双方 mods 一致。
- 房间大厅目前不携带陶瓦版本标记，混用时可能看到房间但加入失败。

## 联机节点（EasyTier 中继）

陶瓦联机的房间码必须在 EasyTier 中继节点上「碰头」，中继全部不可达时，
房主端会立刻掉线并出现 `PingServerRst`，房客端则显示
`Cannot find scaffolding server`（加入房间失败）。

HMCL 默认从 `https://terracotta.glavo.site/nodes` 拉取中继列表，但这个列表是动态的：
节点可能被临时移除，也可能返回空内容，导致列表里剩下的节点全部不可用。
KilnLauncher 因此做了三层兜底（`terracotta/TerracottaNodeList.java`）：

1. 内置一个已知可用的中继，始终排在最前面；
2. 叠加远程节点列表（去重，保留原顺序）；
3. 叠加 `-Dkiln.terracotta.nodes=...` 指定的节点（逗号或空格分隔）。

也可以启动前手动追加自己的中继，不需要重新构建（必须设置在启动 KilnLauncher 的那个进程环境里）：

```bat
set "JAVA_TOOL_OPTIONS=-Dkiln.terracotta.nodes=tcp://your-relay:11010"
start "" "KilnLauncher.exe"
```

启动日志里会打印两行，可用来确认实际使用的节点：

```text
Terracotta node list: [...]
Effective Terracotta node list: [...]
```

## 房间标签、筛选与延迟

「公开到房间大厅」上方可以填写**自定义房间名称**，留空时使用当前游戏实例名。
名称在回车或输入框失焦时生效，房间已经在公开状态时会立刻重新发布。

房主在「已启动房间」里打开「公开到房间大厅」后，下方会多出一排标签，可多选：
原版、生存、创造、冒险、解密、恐怖、跑酷、PVP、PvE、猎人游戏、mod。
标签随时可以改，改动会立刻重新发布，不需要关掉再开。

房间大厅顶部有同样一排标签做筛选，支持多选；只要房间命中任意一个选中标签就会显示，
「全部」清空筛选。房间行里会显示房主选的标签和延迟。

大厅顶部还有搜索框，可以按**房间名称**或**房主名称**搜索。
房主名称只有在房主用微软账户登录时才参与匹配——离线账户可以随便起名，
算进去的话搜一个常见名字会返回一堆不相干的房间。判定逻辑在
`PublicRoom#matchesQuery`，不依赖界面，方便单独验证。

房间行会显示 Ping 时间，例如「在线 · Ping 12s ago」。房主每 60 秒发一次 Ping，
所以只有连续错过 3 次（3 分钟）才会标记为「可能已离线」，避免网络抖动导致误判。

实现细节（`terracotta/lobby/`）：

- `PublicRoomTag` 定义固定标签集合，网络上只传英文 id，显示名在本地翻译，
  这样旧版启动器收到不认识的 id 会直接忽略，不会报错；
- `PublicRoom` 增加 `tags` 与 `latency` 两个可选字段，接收端会去掉未知 id、去重、
  最多保留 8 个，延迟限制在 0~10000 ms；
- 延迟是**房主到房间大厅服务器（ntfy）的往返时间**，由房主在发布时测量并写进 Ping，
  所以它反映的是房主的网络质量，不是房客到房主的 ping：
  - 第一次请求包含 DNS 和 TLS 握手，会虚高到 900 ms 左右，
    因此发布时先发一次、再测一次，用第二次的值；
  - Ping 每 60 秒更新一次数值。
  鼠标悬停在延迟标签上有说明。

## 房间密码

房主可以打开「房间密码」开关并输入密码（输入后按回车生效）。开启后：

- 房间大厅里该房间显示锁图标，邀请码显示为「已加密」；
- 复制邀请码、加入房间都必须先输入密码；
- 密码错误会在输入框里直接提示，不用重新打开对话框。

实现说明（`terracotta/lobby/PublicRoomPassword.java`）：

- 房间大厅是公开话题，单纯在界面上遮挡没有意义，任何人读原始消息就能拿到邀请码。
  所以开密码后邀请码是**真的加密**后发布的：
  `PBKDF2WithHmacSHA256`（10 万次）派生 256 位密钥 + `AES/GCM/NoPadding`，
  随机 16 字节盐、12 字节 IV，格式为 `v1:` + base64(盐 | IV | 密文+认证标签)。
- 公开消息里只有 `locked: true` 和 `codeCipher`，没有明文邀请码；
  密码错误时 GCM 认证失败，拿不到任何数据。
- 这只防陌生人，不防专门盯着公开话题暴力破解的人：加密强度取决于房主设的密码长度。
- 关掉开关会清空密码并立刻重新发布成公开房间。

## 模组前置自动安装

在「下载 → 模组」里点开某个模组的版本并点「下载」时，KilnLauncher 会：

1. 先在后台解析该版本声明的**必需前置**（required），并沿依赖链继续向上找，最多 4 层；
2. 只挑选适配当前实例的版本，要求游戏版本和模组加载器都匹配；
3. 扫描实例的 mods 目录：已经安装的前置直接跳过，不会重复下载；
4. 弹出确认框，列出「将一起安装」和「已安装，跳过」的清单，确认后一次性下载。

实现说明：

- 解析逻辑在 `ui/download/ModDependencyResolver.java`，通过 Modrinth / CurseForge 提供的依赖信息
  递归解析，用 `ModManager` 读取实例里已有模组的 mod id 和显示名来判断是否已安装；
- 只处理 required 类型，可选前置（optional）不会自动安装，避免往实例里塞不需要的东西；
- 找不到适配版本的前置会单独列出来，提示手动安装；
- 有前置要装时会直接用远端文件名下载，不再逐个弹文件名输入框。

## 下载源与合并搜索

「下载 → 模组 / 资源包 / 光影」顶部的**下载源**可以选：

| 选项 | 说明 |
| --- | --- |
| Modrinth | 只搜 Modrinth，开箱即用 |
| CurseForge | 只搜 CurseForge，需要先配置 API Key |
| 合并 | 两个站一起搜，按名称去重（配置 Key 后才出现） |

为什么之前看不到下载源：官方 HMCL 在构建时会把 CurseForge API Key 写进 JAR，
自行构建的版本没有 Key，CurseForge 会被判定为不可用，下载源下拉框也会整个隐藏。
KilnLauncher 改成：

- 下载源**始终显示**，没有 Key 时选 CurseForge 会提示去哪里配置，然后自动切回 Modrinth；
- 「设置 → 下载」新增 **CurseForge API Key**，填进去立刻生效，不用重启；
- Key 配好之后，下拉框里会多出「合并」。

合并模式按「slug + 名称」去重，
避免同一个模组出现两次；因为两个站的分类 id 不通用，选了分类时只查 Modrinth。

## 构建与运行

要求：JDK 17 或更高版本。

```powershell
# 运行
.\gradlew.bat :HMCL:run

# 构建可执行 JAR（输出在 HMCL/build/libs，可直接 java -jar 运行）
.\gradlew.bat :HMCL:shadowJar

# 构建单文件 EXE（HMCL 原生引导器 + JAR，输出 HMCL-*.exe）
.\gradlew.bat :HMCL:makeExecutables
```

`dist/KilnLauncher.exe` 由 `HMCL-*.exe` 所用的同一套引导器制作，并额外用
`rcedit` 写入了 KilnLauncher 的图标、产品名和版本信息。

如果本机访问 GitHub 下载 Gradle 发行版失败，可以先通过镜像下载同版本 Gradle
（`gradle-9.8.0-bin.zip`），解压后使用 `gradle.bat` 执行同样的任务：

```powershell
curl.exe -L -o gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.8.0-bin.zip
Expand-Archive gradle.zip -DestinationPath "$env:TEMP\gradle-dist"
& "$env:TEMP\gradle-dist\gradle-9.8.0\bin\gradle.bat" :HMCL:shadowJar --no-daemon
```

## 与上游的差异

| 文件 | 修改 |
| --- | --- |
| `Metadata.java` | 启动器名称改为 KilnLauncher，数据目录改为 `.kilnlauncher`，禁用上游 HMCL 自动更新 |
| `EntryPoint.java` | 首次启动时把旧 `.hmcl` 配置复制到 `.kilnlauncher` |
| `assets/img/icon*.png` | 窗口/任务栏/标题图标替换为 KilnLauncher |
| `MainPage.java` | 移除 HMCL 预览版公告 |
| `RootPage.java`、`LauncherSettingsPage.java` | 移除 HMCL 官方群组、反馈、帮助入口 |
| `AboutPage.java` | 改为 KilnLauncher 版本信息并保留 HMCL 上游署名 |
| `TerracottaPage.java` | 增加「房间大厅」标签页 |
| `TerracottaControllerPage.java` | 在已启动房间中加入「公开到房间大厅」开关 |
| `terracotta/lobby/*` | 新增房间数据模型、ntfy 目录客户端、Ping 发布器和大厅页面 |
| `terracotta/lobby/PublicRoomTag.java` | 新增房间标签集合，标签随房间一起发布并用于大厅筛选 |
| `terracotta/lobby/PublicRoomPassword.java` | 新增房间密码：用 PBKDF2 + AES-GCM 加密邀请码后再发布 |
| `ui/construct/InputDialogPane.java` | 增加密码输入框（掩码）变体，用于输入房间密码 |
| `terracotta/TerracottaNodeList.java` | 叠加内置中继与 `kiln.terracotta.nodes`，避免官方节点列表失效时房间全部无法加入 |
| `I18N*.properties` | 新增中英文文案，窗口标题改为 KilnLauncher |
| `assets/css/root.css` | 新增标签与延迟徽章的样式 |

其余功能与上游 HMCL 保持一致。上游的文档、下载源和联机节点仍然可用。

## 常见问题

### 加入房间后一直停在「正在加入房间」

陶瓦联机是 **P2P 直连**，不是中继服务器；能否连上取决于房主和房客双方的 NAT 类型。
房间大厅只负责分享邀请码，不参与实际连接，因此连接失败与房间大厅本身无关。

可以尝试：

1. 双方更换网络：手机热点、有线网络、不同运营商之间多试几次。
2. 关闭代理/VPN 或改用全局代理后再试（代理可能破坏 UDP 打洞）。
3. 确认房主仍停留在「已启动房间」，并且游戏里已经「对局域网开放」。
4. 不要在**同一台电脑**上同时当房主和房客；EasyTier 会因 machine_id 冲突而无法连接。
5. 如果提示「当前网络状态极差」，说明双方网络很难直连，换网络通常比重试更有效。

### 中国大陆下载游戏很慢

- KilnLauncher 的「自动」下载源会在启动时对官方源和 BMCLAPI 测速，选择当前更快的一方；
  在测速结果出来之前，中文界面默认镜像优先。
- 可以在「设置 → 下载」中切换下载源：
  - 镜像源：优先 BMCLAPI，适合中国大陆直连。
  - 官方源：优先 Mojang，适合代理/加速器环境。
  - 自动：根据启动测速结果自动选择。
- 如果 BMCLAPI 的 CDN 持续超时，可以切换到官方源并开启系统代理；反过来如果官方源很慢，就切回镜像源。
- 需要指定其他 BMCLAPI 镜像时，可使用启动参数：
  `-Dhmcl.bmclapi.override=https://your-bmclapi-mirror`

## 免责声明

房间大厅依赖第三方公共消息服务 ntfy.sh，其可用性、限流和消息保留策略可能变化。
本项目不对因公开邀请码导致的房间被陌生人加入承担责任；公开房间前请自行评估风险。

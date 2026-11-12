<div align="center">

<img src="https://img.shields.io/badge/version-10.3.1-2C7A73?style=flat-square" alt="version">
<img src="https://img.shields.io/badge/Android-10+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="android">
<img src="https://img.shields.io/badge/LSPosed%20%2F%20Xposed-Module-555555?style=flat-square" alt="lsposed">
<img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="kotlin">

<br>

<a href="https://aill.luoyu.ltd"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Faill.luoyu.ltd%2Fapi%2Fstats.php&query=%24.data.current&label=%E5%BD%93%E5%89%8D%E5%9C%A8%E7%BA%BF&suffix=%20%E4%BA%BA&color=FFC107&style=for-the-badge&labelColor=B8860B&cacheSeconds=60" alt="当前在线人数" height="52"></a>
&nbsp;&nbsp;
<a href="https://aill.luoyu.ltd"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Faill.luoyu.ltd%2Fapi%2Fstats.php&query=%24.data.total&label=%E7%B4%AF%E8%AE%A1%E4%BD%BF%E7%94%A8&suffix=%20%E4%BA%BA&color=DC3545&style=for-the-badge&labelColor=8B1A24&cacheSeconds=300" alt="累计使用人数" height="52"></a>

<br><br>

<h1>落聊</h1>

<p><b>长按一条消息，答案就在聊天窗口里。</b></p>

<p>为微信、抖音、QQ、TIM、快手、Soul、小红书加上 AI 分析入口的 Android 模块。</p>

<p>
<a href="../../releases/latest"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88-2C7A73?style=for-the-badge&logo=android&logoColor=white" alt="下载最新版"></a>
</p>

<p>
<a href="https://aill.luoyu.ltd">官网</a> ·
<a href="#功能">功能</a> ·
<a href="#支持的宿主">宿主</a> ·
<a href="#开始使用">开始使用</a> ·
<a href="#接口配置">接口配置</a> ·
<a href="#常见问题">常见问题</a>
</p>

</div>

<br>

<table>
<tr>
<td width="33%" valign="top">

### 长按即分析

不用复制、不用切 App。长按一条消息，结果卡片直接出现在它下面。

</td>
<td width="33%" valign="top">

### 七个宿主

微信到小红书，一套模块覆盖你常用的聊天软件。分身也能用。

</td>
<td width="33%" valign="top">

### 密钥不出本机

Key 用 Android Keystore 加密存在私有目录，不进日志，不进 APK。

</td>
</tr>
</table>

<br>

---

## 功能

### 分析卡片长什么样

长按对方的一条消息 → 菜单里点「分析」。卡片会覆盖在原消息下方，包含四块内容：

| 区块 | 内容 |
| :--- | :--- |
| **字面问题** | 把这句话拆成它在问什么，附带各选项的置信概率 |
| **真实意图** | 从上下文推断对方实际想表达的东西 |
| **生气程度** | 0–10 的数值，一眼看出语气有多重 |
| **一句话点评** | 可选的一行总结 |

这四个区块可以单独开关。关掉某一个不会重新调用模型，立即生效。

卡片采用深色半透明样式，背景模糊不可用时自动退回纯半透明，文字始终清晰。

### 文本优化 · 悬浮球

长按悬浮球，把输入框里的内容交给模型改写。预览满意后点复制，**你自己粘贴**——不自动填入，更不自动发送。

### 优化模板与 Skill

分析和润色用的系统提示词都可以自定义。多套模板分开保存，日常聊天、工作沟通、情感交流各用各的。

每套模板还能挂载 **Skill**——一段命名好的指令片段，只属于某一个模板，以 Markdown 存储，支持打包导入导出。

### 多选分析

QQ 与快手支持选中多条消息一次性分析，用来快速汇总一段对话。

### 手动分析

分析记录面板标题栏有「手动」按钮，可以对不满足自动分析条件的消息单独发起一次分析——不与「长按菜单」抢入口。

---

## 支持的宿主

<table>
<tr><td width="120"><b>微信</b></td><td><code>com.tencent.mm</code></td><td>默认含 <code>.u1</code>–<code>.u9</code>、<code>.clone</code>、<code>.dual</code>、<code>.multiapp</code>、<code>.parallel</code>、<code>.virtual</code></td></tr>
<tr><td><b>抖音</b></td><td><code>com.ss.android.ugc.aweme</code></td><td>另有 <code>.lite</code> 极速版、<code>com.ss.android.ugc.trill</code> 海外版</td></tr>
<tr><td><b>QQ</b></td><td><code>com.tencent.mobileqq</code></td><td>实测 9.3.65 与 9.1.97</td></tr>
<tr><td><b>TIM</b></td><td><code>com.tencent.tim</code></td><td>与 QQ 共用同一套 NT 聊天引擎，因此共用同一套 Hook</td></tr>
<tr><td><b>快手</b></td><td><code>com.smile.gifmaker</code></td><td>官方只发行这一个包名</td></tr>
<tr><td><b>Soul</b></td><td><code>cn.soulapp.android</code></td><td>官方只发行这一个包名</td></tr>
<tr><td><b>小红书</b></td><td><code>com.xingin.xhs</code></td><td>第六个接入的宿主</td></tr>
</table>

作用域数组可以在 LSPosed 里手动编辑。第三方分身包名不在上表内时，自己添加即可。

> **兼容范围说明**
> 各宿主都在作者实机上按具体版本验证过。宿主更新后，界面结构或模块依赖的类可能变化，功能会失效。不同版本的实际兼容性需要逐个验证，本仓库不承诺「所有版本都可用」。

---

## 开始使用

### 下载

请到 **[Releases](../../releases/latest)** 页面下载最新版本。

| 版本 | 说明 |
| :--- | :--- |
| **无日志版**（推荐） | R8 混淆 + 资源压缩，约 2.9 MB |
| 有日志版 | 需自行构建，见「开发与构建」 |

下载后校验文件完整性：

```shell
# Windows
certutil -hashfile luoliao-10.3.1.apk SHA256
# macOS / Linux
shasum -a 256 luoliao-10.3.1.apk

# 应与 Release 说明里列出的 SHA256 一致
```

也可从[官网](https://aill.luoyu.ltd)下载。

### 使用条件

| 项 | 要求 |
| :--- | :--- |
| 系统 | Android **10 或更高**（`minSdk = 29`） |
| 框架 | 已启用的 **LSPosed / Xposed**，只装 APK 不生效 |
| 接口 | 一个 **OpenAI 兼容接口**的地址、Key、模型名 |

### 安装

1. 安装 APK
2. 打开一次「落聊」，填好接口地址、Key、模型名并保存
3. LSPosed 里启用模块，作用域勾选要用的宿主
4. **彻底结束并重新打开宿主 App**（退回桌面不算，要杀进程）
5. 进聊天，长按一条消息，点「分析」

更新 APK 后同样要重启宿主进程。

### 两个构建版本

| 版本 | 日志 | 用途 |
| :--- | :--- | :--- |
| release | 完全关闭 | 日常使用，体积小 |
| debug | 全量详细 | 排查问题 |

包名不同（`com.luoliaoo.wechat` / `com.luoliaoo.wechat.debug`），可以同时装。

---

## 接口配置

落聊走标准 **OpenAI Chat Completions** 接口，任何兼容的第三方中转站都能用。

设置页填三项：

| 字段 | 说明 |
| :--- | :--- |
| 接口地址 | 如 `https://api.deepseek.com`，或你自己的中转地址 |
| API Key | 你的服务商密钥 |
| 模型名 | 可点「从接口选择模型」拉取，也可手填 |

默认地址是 `https://api.deepseek.com`。

**模型名为什么支持手填**

不同「OpenAI 兼容」服务的 `/models` 实现差异很大：有的返回标准 `data[].id`，有的返回 HTML 错误页，有的返回字符串数组。落聊会尝试解析几种常见格式，但解析失败不影响使用——手动填写的模型名永远可用。

**识图**用独立的一组配置（地址、Key、模型），与文本分析分开管理。

### Key 存在哪

应用私有设置中，通过 Android Keystore 的 AES/GCM 加密。Keystore 不可用时退回普通 SharedPreferences——这个降级是为了防止加密失败把你锁在配置界面外面。

APK 内不含任何用户 Key，日志也不输出。

---

## 开发与构建

技术栈：**Kotlin** · Android 原生 View（无 Compose）· **Xposed** · **DexKit**

### 环境

| 项 | 版本 |
| :--- | :--- |
| JDK | **21** |
| Gradle | **8.13**（AGP 8.13.2） |
| Android SDK | **36**（`compileSdk = 36`） |

### 构建

```shell
# 1. 本地配置
cp local.properties.example local.properties
# 编辑 local.properties，填写 sdk.dir 与 release 签名密码

# 2. 构建
gradle :app:assembleRelease   # 无日志版
gradle :app:assembleDebug     # 有日志版
```

产物：

| 产物 | 路径 |
| :--- | :--- |
| 无日志版 | `app/build/outputs/apk/release/luoliao-10.3.1.apk` |
| 有日志版 | `app/build/outputs/apk/debug/luoliao-10.3.1-debug.apk` |

> **中文路径注意**
> `gradle.properties` 里的 `android.overridePathCheck=true` 已默认开启，中文目录能构建 APK。
> 但 **中文路径会让 Gradle 单元测试跑不起来**——测试类加载不到，报 `ClassNotFoundException`。要跑测试请放在纯英文路径下。这一点是实测得出的，不是猜测。

### 关于二改与再分发

**欢迎二改。** 代码是 MIT 协议，你可以自由修改、构建、分发，包括做成自己的版本发布。

只有一个请求：

> **请保留上报地址与相关源码，不要把它改掉、删掉或指向别处。**

原因很直接——这是作者第一次做开源项目，官网上那个累计使用人数，是开发到现在一点点攒下来的。它不是商业指标，只是「有这么多人在用我做的东西」的一个凭证。

源码里涉及这一块的位置集中在两处，改动时顺手避开即可：

| 文件 | 内容 |
| :--- | :--- |
| `core/Constants.kt` | `USAGE_ENDPOINT`（上报地址）与 `USAGE_ENABLED`（总开关） |
| `data/UsageReporter.kt` | 上报逻辑与节奏 |

如果你想彻底关闭上报再发布，请至少**保留默认配置**，或者在自己的版本中说明这一点，不要让它悄悄变成另一个统计口径。

这只是一条君子约定，不是法律限制。做得到，作者会很感谢。

### 依赖

全部来自公开仓库，无需私有源。

`org.luckypray:dexkit:2.3.0` · `com.squareup.okhttp3:okhttp:4.12.0` · `androidx.annotation:annotation:1.9.1` · `androidx.core:core-ktx:1.13.1`

测试用：`junit:junit:4.13.2` · `org.json:json:20240303`

### 签名

release 的证书路径与密码从 `local.properties` 读取（该文件不进版本库）。缺少这些键时 release 构建会直接失败——这是有意设计：宁可构建失败，也不让密码进仓库。

自行构建的 APK 签名与作者发布包不同，**无法覆盖安装**在已有作者版本的设备上，需要先卸载。

### 代码结构

```
app/src/main/java/com/luoliaoo/wechat/
├── ai/       模型调用：OpenAI 客户端、提示词拼装、结果解析、识图
├── core/     宿主识别、常量、日志、Hook 基础设施
├── data/     配置存储、模板、Skill、历史记录、结果缓存
├── hook/     各宿主的具体 Hook 实现（按宿主分文件）
├── ui/       全部界面（原生 View 绘制）
└── voice/    微信语音读取

app/src/stub/   de.robv Xposed API 编译期桩（compileOnly，不进 APK）
app/src/test/   JVM 单元测试
```

`src/stub` 下的 Xposed API 必须保持 `compileOnly`。一旦打进 APK，LSPosed 会以「APK 自带 Xposed API」为由拒绝加载模块。

### 新增一个宿主要改五处

代码注释里记下的经验。前三处漏了会编译报错或测试变红，后两处**静默失效**：

1. `HostApp` 的枚举常量、包名列表与 `of()` —— 漏了 `when` 分支编译不过
2. `RealHostsListEveryHostAndExcludeUnknown` 测试 —— 精确列表，必须同步更新
3. `WeChatHooks` 里的宿主分支 —— 漏了 `when` 分支编译不过
4. **作用域数组** `res/values/arrays.xml` —— 不报错，但用户在 LSPosed 里根本勾不到这个 App
5. **`AndroidManifest.xml` 的 `<queries>` 块** —— 不报错，但 Android 11+ 上 `getPackageInfo` 抛异常，界面显示「未安装」

---

## 隐私边界

- 被分析的文本会发送到**你自己填写的接口地址**，由你选择的服务商处理
- Key 加密存储在应用私有目录，不写日志，不随 APK 分发
- 配置与分析结果存在落聊自己的应用目录，不写入宿主的数据目录，也不修改宿主数据库
- 不做全量历史导出，只处理当前聊天界面上能拿到的消息

---

## 常见问题

<details>
<summary><b>长按没有「分析」这一项？</b></summary>

依次检查：

1. LSPosed 里模块是否启用
2. 作用域是否勾选了当前宿主
3. 是否彻底重启过宿主进程（退回桌面不算）
4. 接口配置是否已保存

宿主大版本更新后界面结构可能变化，需要重新适配。

</details>

<details>
<summary><b>界面显示「未安装」，但 App 明明装着？</b></summary>

`AndroidManifest.xml` 的 `<queries>` 块要声明宿主包名，否则 Android 11+ 上查不到。自行加宿主时容易漏掉这一处——它不报编译错误，只是界面上静默显示错误状态。

</details>

<details>
<summary><b>分析失败或结果为空？</b></summary>

先确认接口配置：地址、Key、模型名三项都对吗？可以点「从接口选择模型」验证连通性。中转站的模型名可能与你以为的不同，建议先拉列表确认。

</details>

<details>
<summary><b>为什么模型名要手动填？</b></summary>

见上文「模型名为什么支持手填」。简单说：兼容接口的 `/models` 实现五花八门，手动填写一定可用，列表只是方便。

</details>

<details>
<summary><b>会帮我自动回复吗？</b></summary>

不会。只提供复制按钮，粘贴和发送都由你自己完成。

</details>

<details>
<summary><b>数据在哪？卸载会丢吗？</b></summary>

落聊自己的应用私有目录（`/data/data/com.luoliaoo.wechat/`）。卸载落聊或清除其数据会一并删除。

</details>

---

## 进一步了解

- **[必看.md](必看.md)** — 开发过程中真实踩过的坑。每条对应一次实际排查，写明踩了什么、为什么踩、怎么避。目前 129 条。

## 致谢

- **LSPosed / Xposed** — 模块框架
- **DexKit** — 运行期类查找

本项目为独立开发的第三方模块，与微信、抖音、QQ 等任何公司无隶属关系。

---

## 免责声明

**请在使用前完整阅读本节。继续安装或使用即表示你已理解并接受以下全部内容。**

### 一、软件性质

本项目是一个运行在本地的**辅助工具**，通过 Android 的 Xposed / LSPosed 框架对宿主 App 的界面做扩展。它不是任何官方产品的组成部分，也不修改宿主 App 的原始安装包。

作者是个人开发者，本项目为业余时间开发的免费项目，不提供任何形式的商业承诺。

### 二、无任何担保

软件按「现状」提供，**不附带任何明示或暗示的担保**，包括但不限于：

- **可用性**：不保证在所有设备、系统版本、宿主 App 版本上都能正常工作
- **连续性**：不保证功能不会因宿主 App 更新而失效
- **准确性**：不保证 AI 生成的分析结果、建议或改写文本正确、恰当或符合你的预期

AI 输出由你选择的第三方模型服务生成，其内容不代表作者的观点，也可能存在错误、偏见或不适当的表述。**分析结果仅供参考，不构成任何专业建议**（包括法律、医疗、心理、财务等任何领域）。

### 三、使用风险自负

使用本软件可能带来的风险包括但不限于：

1. **账号风险**：使用 Xposed 类框架或第三方模块可能违反宿主 App 的用户协议，存在被限制功能或封禁账号的可能。**此风险由使用者自行承担。**
2. **数据风险**：进行分析时，被分析的聊天内容会发送到**你自己填写的第三方接口**。内容是否会被记录、如何使用，取决于该服务商的隐私政策，与作者无关。
3. **费用风险**：调用模型接口会产生费用，由你的服务商按规则计费。作者不承担任何费用，也无法预知你的消耗。
4. **稳定性风险**：模块运行在宿主进程内，极端情况下可能引起宿主 App 崩溃或异常。建议先用有日志版在非重要环境验证。

### 四、合规责任

使用者应自行确保其使用行为符合所在地法律法规及宿主 App 的服务条款。**因使用本软件产生的任何法律责任，由使用者自行承担。**

请勿将本软件用于：

- 侵犯他人隐私
- 冒充他人身份
- 任何违法违规用途

### 五、责任限制

在适用法律允许的最大范围内，**作者不对因使用或无法使用本软件而产生的任何直接或间接损失承担责任**，包括但不限于数据丢失、账号损失、设备损坏、商业利益损失、第三方索赔。

若你不接受上述任一条款，请立即停止使用并卸载本软件。

### 六、其他

- 本项目为独立开发的第三方模块，与腾讯、字节跳动、快手、Soul、小红书等任何公司**均无隶属、合作或授权关系**
- 文中提及的商标、产品名称归各自所有者所有
- 分析完成后请自行判断内容是否合适，**复制、粘贴、发送的动作都由你完成，责任也在你**

---

<div align="center">

## 联系作者

**Telegram** · LUO1314AI &nbsp;&nbsp;|&nbsp;&nbsp; **QQ** · 628843841 &nbsp;&nbsp;|&nbsp;&nbsp; **酷安** · 言无0503

问题反馈可提交 Issues，请附 Android 版本、宿主版本、模块版本和复现步骤。
发日志前记得去掉聊天内容和 API Key。

<br>

**落聊完全免费，没有会员或付费解锁。**
第三方模型服务按其自身规则计费，费用与本项目无关。

<br>

<a href="https://aill.luoyu.ltd">aill.luoyu.ltd</a>

</div>

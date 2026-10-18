# magpie 安卓版（Android 16）

把 [yetone/magpie](https://github.com/yetone/magpie) 搬到手机上：magpie 的网关直接在
Android 里跑起来，网关端口默认 **3425**（和桌面版一致），magpie 自己的网页界面也一起带着，
手机上就能配好各个 agent 要用的模型和账号。

和桌面版一样，magpie 本体还是 Go 写的、不开图形界面（用上游的 `nogui` 构建标签，也就是
Termux 那套路径），外面套一层很小的安卓壳子：一个前台服务把网关跑在后台，一个 WebView
显示 magpie 的网页界面。

已在 Android 16（API 36）的 x86_64 模拟器上实测通过：网关监听 `127.0.0.1:3425`、网页界面
正常打开、应用进程里联网和 DNS 都正常、局域网开关会改成监听 `0.0.0.0:3425` 并强制要求网关
密钥。arm64（手机）那份由同一套脚本和 NDK 编出，二进制检查过是合法的安卓 AArch64 PIE，
但没有在真机上跑过——真机上如果遇到问题，先看菜单里的日志。

## 长什么样

`screenshots/` 里是模拟器上截的图：主界面（magpie 网页跑在 WebView 里）、顶栏菜单、
设置弹窗（网关端口 3425、网页端口 3430）、深色模式和插件页（三个社区插件已装好，
OpenCode Zen Free 已登录）。

界面是照着手机做的：暖色纸张调色板、底部弹层（菜单、设置、日志都从下面滑上来）、
导航标签条能横向滑动、选中项是白色胶囊。深色模式跟着系统走，切换时原生外壳和网页
同时变色，网页不用重新加载。

## 界面是怎么改的

magpie 的网页界面内嵌在网关卡里，改不了它的源码，所以壳子在网页加载时把两个文件注入
进去：`android/app/src/main/assets/magpie-mobile.css`（配色、圆角、底部弹层、手机排版）
和 `magpie-mobile.js`（标签条滑动边缘、选中标签滚进视野、插件页的说明等）。
`upstream/` 依旧没有任何改动。

原生那一侧（顶栏、菜单、设置、日志）在 `android/app/src/main/res/` 里，配色写在
`values/colors.xml` 和 `values-night/colors.xml` 两份，日夜两套一眼能对上。

## 目录

| 目录 | 内容 |
| --- | --- |
| `upstream/` | magpie 上游源码（main 分支，2026-10-16 抓取），完全没有改动 |
| `android/` | 安卓工程，壳子代码在 `app/src/main/java/com/magpie/android/` |
| `scripts/` | 构建脚本：`build-magpie-android.ps1` 编译网关，`build-apk.ps1` 打 APK |

## 需要什么

- **JDK 21**
- **Android SDK**，要有 Android 16（API 36）的 platform 和 build-tools
- **Android NDK r28c**（只在编译网关时用，Gradle 不依赖它）
- **Go 1.26.8**：`upstream/go.mod` 要求这个版本，本机版本低的话 Go 会自己下载
  （脚本默认把 `GOPROXY` 设成 `goproxy.cn`）

NDK 不必放进 SDK，脚本会自己找：`-Ndk` 参数、`ANDROID_NDK_HOME`、SDK 的 `ndk/` 目录、
`~/sdk/android-ndk-*` 都能认出来。

```powershell
# 只下载这一次，713 MB
curl.exe -L -o ndk.zip https://dl.google.com/android/repository/android-ndk-r28c-windows.zip
Expand-Archive ndk.zip -DestinationPath $env:USERPROFILE\sdk
```

r29 以后才有 API 36 的编译器包装，r28c 最高只到 35——用 35 编出来的二进制在 36 上跑没
问题，脚本会自动挑当前 NDK 支持的最高一档。

## 构建

```powershell
# 一条命令：先编两个架构的网关，再打 APK
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-apk.ps1

# 只要 APK（网关没改，复用上次编好的）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -SkipGateway

# release 版（仍用 debug 签名，方便直接装；要发出去请换成自己的签名）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -Release
```

首次编译网关要几分钟（要下载 Go 依赖和工具链）。默认产物是
`android/app/build/outputs/apk/debug/app-debug.apk`，加 `-Release` 后的产物是
`android/app/build/outputs/apk/release/app-release.apk`，两者都在 268 MB 上下——两个架构的
magpie 二进制加起来 98 MB，随包带的 Bun（两个架构各一份）又占了 176 MB 左右（为什么要带
见“插件与内置 Bun”），只想给真机编可以加 `-Abis arm64-v8a`，体积能小到大约一半（约 135 MB）。

如果 PowerShell 说“禁止运行脚本”，就是上面 `-ExecutionPolicy Bypass` 的作用。
`JAVA_HOME` 没指向 JDK 21 的话先设一下：`$env:JAVA_HOME='C:\path\to\jdk-21'`。

## 安装和使用

```powershell
adb install -r android\app\build\outputs\apk\release\app-release.apk
```

打开 magpie 应用，它会自己把网关拉起来，界面就是 magpie 的网页：

1. 顶部一行是状态：绿点 + 端口，右边 `⋮` 是菜单。
2. 在网页里加好要用的供应商和账号，和桌面版完全一样。
3. 菜单里 **复制网关地址** 会得到 `http://127.0.0.1:3425/v1`，把它填给手机上的 agent 当
   base URL（Claude Code、Codex、Gemini CLI 这些在 Termux 或别的终端里跑的都能用）。
4. 菜单里 **设置** 可以改端口、开局域网访问、开开机自启；改完点“保存并重启”立即生效。
5. 菜单里 **查看日志** 是 magpie 自己的输出，出问题先看它。

网关和网页界面是同一个进程。进程意外退出会自动重启（逐次退避），界面重连上之后也会
自己把网页加载出来。

## 插件与内置 Bun

magpie 的外部插件就是一个个 npm 包，跑它们的是 Bun。上游默认在第一次用到时把 Bun 下载到
应用自己的私有目录里，但 Android 10 以后不允许执行私有目录里的文件（W^X），插件一装就报
`fork/exec ... permission denied`——安装、更新、移除全都不行。这就是自己打的 APK 里插件
装不上的原因。

解决办法是把 Bun 一起打进 APK：构建脚本把 Bun 1.4.2 的二进制放到
`android/app/src/main/jniLibs/<abi>/libmagpiebun.so`——能执行的只有 `lib/<abi>/` 下的
`.so`，`extractNativeLibs` 还得开着，和网关二进制是同一条规矩（见“为什么编译网关要用
NDK”）。应用拉起网关时会把它的绝对路径写进环境变量 `MAGPIE_BUN`，日志里有这么一行：

```text
plugins run on the bundled Bun: /data/app/.../lib/<abi>/libmagpiebun.so
```

实测：在 Plugins → Discover 点 Factory 的 Install，页头计数从 `Installed · 1` 变成
`Installed · 2`，卡片按钮变成 Sign in，日志里那行也出现——插件安装链路整个通了。

### 登录和外部链接

插件登录（ZCode、Factory 之类）是网页用 `window.open(url, "_blank")` 打开厂商页面的。
安卓 WebView 默认没有“新窗口”这个东西，这个请求会被直接丢掉，按钮看着像没反应。壳子
现在处理了 `onCreateWindow`：把新窗口的地址交给手机上的浏览器，magpie 自己的页面原地
不动，登录完切回来就行。页面里其它离开 magpie 的链接（下载、厂商主页）也一样走浏览器。

ZCode 的登录是**轮询式**的：浏览器里登录完，magpie 在后台问 Z.ai“好了没”，不需要
本地回调端口，所以手机上这条路是通的。`ZCode app's sign-in` 那种要读桌面 ZCode 客户端
凭据的方式在手机上自然没有东西可读，用 Z.ai / BigModel 或 API key 那几条即可。

### 已知装不上的插件

`zcode-provider` 不是给 magpie 用的 OpenCode 登录插件，而是一个要拉起 ZCode
app-server 的 pi 包。它在 Bun 1.4.2 的安卓版上解包依赖时会以
`signal: bad system call`（退出码 159）崩溃，包还没写进磁盘就结束了——这是 Bun 的安卓
构建的问题，不是 magpie 或壳子的权限问题。插件页的“Have one in mind?”里输入
`zcode-provider` 会直接给出这条说明；其它社区插件不受影响。

已经实测能装的：`@magpie-community/opencode-zen-free-auth`（免费访问，密钥填
`public`）、`@magpie-community/opencode-zcode-auth`、`@magpie-community/opencode-factory-auth`，
以及 Discover 列表里的其它社区插件。Zen Free 登录后经网关真实发过请求并拿到
`MAGPIE_ANDROID_OK`；ZCode 的浏览器登录页和 API key 两条路都走到了 Z.ai 那一跳。

### 体积代价

| 组成 | 大小 |
| --- | --- |
| 两个架构的 magpie 网关 | 约 98 MB |
| arm64-v8a 的 libmagpiebun.so | 约 86.8 MB |
| x86_64 的 libmagpiebun.so | 约 89.4 MB |

所以 release APK 从原来的约 101 MB 涨到 **268.5 MB**。只给手机编 arm64-v8a
（`-Abis arm64-v8a`）大约 135 MB；完全不要插件就用 `-NoBun`，APK 回到 100 MB 出头。

### 构建脚本的开关

- `-NoBun`：不下载、不打包 Bun，出来的 APK 里没有 libmagpiebun.so，插件页会显示
  “本次构建未内置插件运行时”并把外部插件的按钮置灰，内置插件不受影响。注意 jniLibs 里
  如果留着上次的 libmagpiebun.so，`-NoBun` 拦不住它进包，脚本会提醒你。
- `-BunMirror <前缀式镜像地址>`：优先从镜像下载；也可以用环境变量 `MAGPIE_BUN_MIRROR`
  （命令行参数优先）。下载顺序是镜像 → GitHub 直连 → `https://gh-proxy.com/<GitHub 直连
  地址>`；zip 缓存在 `android/build/bun-cache/`，按上游的 SHA-256 校验，curl 挂了会自己
  换下一个源。
- Bun 下载失败不会让构建失败：脚本打印
  `no source had Bun 1.4.2 for <abi>; leaving plugins off this build`，照常出 APK，只是这
  个包里没有插件运行时。
- `-Abis` 也管这一步：只编 arm64-v8a，就只为它拉 Bun。

## 端口

| 端口 | 用途 | 默认 |
| --- | --- | --- |
| 3425 | **网关**，agent 指向这里 | 应用默认，可在设置里改 |
| 3430 | magpie 网页界面，**只绑本机**并带密钥 | 沿用上游默认 |

3425 是 magpie 自己的默认端口，写在 `MagpiePrefs.java` 的 `DEFAULT_GATEWAY_PORT` 里，装好
之后也能随时在设置里改（想用别的端口直接填）。传给 magpie 的是环境变量 `MAGPIE_ADDR`
（`127.0.0.1:3425`，开局域网时是 `0.0.0.0:3425`），
magpie 优先用它，所以不需要改上游代码。

网页界面的密钥在应用首次启动时随机生成并保存（`MAGPIE_WEB_KEY`），WebView 每次都直接进
得去，不用手动登录。想在电脑上看这个页面，用菜单里的 **用浏览器打开**。

## 安全

- 网页界面能看到、也能改所有账号密钥，所以它**始终只绑 127.0.0.1**，不受局域网开关影响。
- 局域网开关打开后网关绑 `0.0.0.0`，此时**必须**在 magpie 的 Gateway 设置里配好网关密钥
  （magpie 会拒绝没有密钥的请求）。别在公共 Wi-Fi 下开。
- Android 16 的本地网络保护默认关闭。应用已声明 `ACCESS_LOCAL_NETWORK`，开启局域网访问时
  会顺手申请一次，等 Android 17 强制要求时也能继续用。

## 后台运行

网关跑在前台服务里，通知栏有一条常驻通知（带“打开 / 停止”按钮），这是 Android 允许应用
长期在后台的正规方式。想在息屏后长时间稳定运行，建议在菜单里点一次 **忽略电池优化**；
想开机自动起来，就在设置里打开 **开机自动启动**。部分国产 ROM 还会额外清后台，需要在系统
的电池/自启动白名单里再放行一次。

## 为什么编译网关要用 NDK

Go 自带那套 DNS 解析在 Android 上不工作（[golang/go#10714](https://github.com/golang/go/issues/10714)：
它去读 Android 根本没有的 `/etc/resolv.conf`，最后退化成只问本机 53 端口），结果就是
`CGO_ENABLED=0` 编出来的二进制能启动、能监听，但**任何域名都解析不了**，网关一联外网就废。

所以脚本用 `CGO_ENABLED=1` 加 NDK 的 clang 来编，DNS 走 Android 自己的 bionic 和 netd，
和 Termux 里装 magpie 是同一条路（上游 Makefile 也是这么写的）。这条是实测确认的：cgo 版本
在设备上 `sync` 能拉到 models.dev 的目录，`CGO_ENABLED=0` 的版本直接报 DNS 错误。

另一个安卓特有的点：APK 里能执行的二进制只能放在 `lib/<abi>/` 下，后缀必须是 `.so`，而且
`extractNativeLibs` 得是开的。所以脚本把 magpie 编成
`android/app/src/main/jniLibs/<abi>/libmagpie.so`，应用再从 `nativeLibraryDir` 里执行它。
magpie 要写的东西（配置、缓存、日志）通过 `HOME` 和 `XDG_*` 变量全指到应用自己的 `files/`
目录，和 Docker 里那套做法一样，不会碰手机别的目录。

## 常见问题

**编译时说找不到 NDK** — 按上面下载解压，或用 `-Ndk C:\android-ndk-r28c` 指过去。

**Gradle 抱怨 SDK 路径** — 脚本会往 `android/local.properties` 写 `sdk.dir`；用 Android
Studio 打开工程时它也会自己写。

**APK 莫名其妙比 268 MB 大很多** — 排除 Bun（约 176 MB，见“插件与内置 Bun”；`-NoBun`
或只编一个架构都能瘦下来）之后还是异常大，就是改过架构或打包设置，先清一次：
`cd android; .\gradlew.bat clean assembleDebug`。增量打包偶尔会把上一版的大二进制留在
APK 的空隙里（实测出现过多出 48 MB 的情况），清一次就正常了。

**装上去提示“应用未安装”** — 看设备架构：手机用 arm64-v8a 那份，模拟器是 Intel/AMD 镜像
就用 x86_64。两个都在 APK 里，不用自己挑。

**WebView 白屏、提示界面没有加载成功** — 菜单里看日志。网页要等网关起来，第一次大概几秒；
日志里出现 `magpie web on http://127.0.0.1:3430/?k=…` 就说明好了。

**插件页显示“本次构建未内置插件运行时”、外部插件按钮是灰的** — 这个 APK 打包时没带上
Bun：用了 `-NoBun`，或者下载 Bun 那一步失败了（构建日志里会有 `no source had Bun 1.4.2
for <abi>; leaving plugins off this build` 这行）。重新打包时去掉 `-NoBun` 就行，上不了
GitHub 就用 `-BunMirror` 指一个能用的镜像。内置插件不受影响。

**插件安装失败、或者一直转圈** — 插件是 npm 包，默认从 npm 官方源拉，国内经常拉不动：把
Plugins 页上的 **Mirrors in China**（中文界面叫“国内镜像”）开关打开，magpie 就会走国内
镜像。还不行就看菜单里的“查看日志”——先确认这个包带了 Bun（有 `plugins run on the
bundled Bun: …` 这行），再顺着日志看是哪个包、哪一步在报错。

**点插件的 Sign in 没反应** — 这个 APK 是旧版壳子时才会发生（`window.open` 被 WebView
丢掉了）；现在的版本会把厂商登录页交给手机浏览器打开，登录完切回 magpie 即可。如果浏览器
都没起来，说明手机没装浏览器，或者默认浏览器被禁用了。

**装 `zcode-provider` 报 `signal: bad system call`** — 这不是权限问题，是 Bun 1.4.2 的
安卓版在解包这个包时自己崩了（退出码 159）。它也不是 magpie 的登录插件，是另一个 agent
的 pi 包；换成 `@magpie-community/opencode-zcode-auth` 即可，ZCode 的 Z.ai / BigModel /
API key 三条登录方式在手机上都验证过。

**想改支持的安卓版本** — 改 `android/app/build.gradle.kts`：`minSdk` 现在是 36
（Android 16），往下调低也可以，壳子代码没用到更新的 API；`compileSdk`/`targetSdk` 是 36，
要退回旧版本就一起改。

**想只编一个架构、或者加 32 位** — `-Abis` 支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`、
`x86`，改完记得同步 `app/build.gradle.kts` 里的 `abiFilters`。

**换上游新版 magpie** — 用新版覆盖 `upstream/` 里的文件，重跑
`scripts/build-apk.ps1` 就行。`upstream/` 里没有任何改动，方便对比和跟进上游。

## 不想打包 APK 的话

手机上的 Termux 里也能直接跑 magpie（上游本来就支持）：

```bash
pkg install git golang
git clone https://github.com/yetone/magpie
cd magpie && make build
MAGPIE_ADDR=127.0.0.1:3425 ./build/magpie serve
```

代价是要自己管前台进程和开机启动，Termux 一被杀网关就没了。打包成 APK 之后，网关有常驻
通知、崩溃自启，网页界面也有地方点，日常用更省心。

## 代码在哪

- 启动与界面：`android/app/src/main/java/com/magpie/android/MainActivity.java`
- 拉起网关进程、环境变量、日志、崩溃重启：
  `android/app/src/main/java/com/magpie/android/MagpieGateway.java`
- 前台服务与通知：`android/app/src/main/java/com/magpie/android/MagpieService.java`
- 端口、局域网、开机启动、网页密钥的存取：
  `android/app/src/main/java/com/magpie/android/MagpiePrefs.java`
- 注入网页的手机皮肤与脚本：
  `android/app/src/main/assets/magpie-mobile.css`、`magpie-mobile.js`
- 图标生成脚本：`scripts/gen-icons/main.go`
- 插件要用的 Bun（构建时放进来的）：`android/app/src/main/jniLibs/<abi>/libmagpiebun.so`

magpie 自己的一切（网关、网页、各个 agent 的适配）都在 `upstream/` 里。壳子不碰它，只负责
把它拉起来、给它一个能跑的环境。

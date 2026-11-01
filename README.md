<div align="center">
  <img src="docs/logo.png" width="140" alt="ScreenshotBye logo"/>
  <h1>ScreenshotBye</h1>
  <p>截图常常只是临时用一下，用完就忘在相册里。这个应用持续在后台盯着相册，过一段时间问你一句「还需要这张截图吗」，回答不需要就删掉它——删除前先备份，反悔可以还原。</p>
  <p>
    <img alt="platform" src="https://img.shields.io/badge/Android-8.0%2B-green"/>
    <img alt="release" src="https://img.shields.io/badge/release-v1.0.3-blue"/>
    <img alt="lang" src="https://img.shields.io/badge/Kotlin-2.0.21-orange"/>
    <img alt="offline" src="https://img.shields.io/badge/联网-不需要-informational"/>
    <img alt="license" src="https://img.shields.io/badge/License-MIT-lightgrey"/>
  </p>
</div>

---

## 一次询问会发生什么

1. 引导页走完、打开**后台监控截图**，之后每次开机它都会自己在后台跑起来（常驻前台服务 + 开机自启 + 看门狗）。
2. 你截了一张图。系统把它写进相册并通知 MediaStore，`ContentObserver` 收到变化，350ms 防抖后按 `DATE_ADDED` 水位线做一次增量查询，用「所在目录 / 文件名 / 自定义关键词」三条规则判断这是不是截图。
3. 是截图 → 记进待确认清单，并用 WorkManager 排一个 N 分钟后的一次性任务（默认 5 分钟，可调 1–60）。任务持久化在 WorkManager 数据库里，进程被杀、设备重启都照样会到点。
4. 到点 → **通知栏只出现一条**提醒。连截十张也合并成一条「10 张截图待确认」，不会刷屏；同一条通知会按节流规则重新出声（见「提醒方式与自检」）。
5. 点通知进入逐个确认页：一张一张看，三个选择——`还要留着` / `10 分钟后再问` / `不需要，删除`，左右滑动切换。看完进入反选屏，勾着的这批一次处理。
6. 删除时先把原图复制进应用内回收站，再删相册里的原件；**备份失败就不会删除**。没给「所有文件访问」时，整批共用一次系统删除确认弹窗。
7. 一直没回答的询问不会永远挂着：超过设定天数（默认 3 天）自动按「保留」出队。

> 通知上也有快捷按钮，直接处理最早到期那一张，不用点开应用；第三个按钮 `全部稍后再问` 把整批往后推 10 分钟。
> 常驻的监控通知在有待确认截图时会变成入口：点它直接进逐个确认页，不用再从主页找按钮。

## 提醒方式与自检

「提醒不够强、回来要在通知栏里翻」是 v1.0.2 针对的问题。那一版做了「安静 / 横幅+震动 / 横幅+震动+声音」三档，
v1.0.3 把这个档位**撤掉了**：声音、震动、横幅、锁屏显示本来就归系统的通知设置管，应用再做一层开关只是把同一个设置
复制一遍，还容易和 ROM 自己的开关打架。现在只剩一条「截图待确认」渠道，响不响、震不震由你在系统里定。

- **一条提醒渠道 + 一键直达系统设置**：主页和自检页都有 `在系统设置里管理提醒方式`，直接跳到这条渠道的系统页面
  （Android 8 起声音、震动、横幅、锁屏显示都归渠道所有，应用内改不了，也不该改）。
  1.0.2 短暂建过的三条强度渠道在启动时会被删掉，系统设置里不会留下重复项；渠道 id 没变，你在系统里调过的铃声和横幅
  开关仍然生效。
- **会重新出声的询问**：同一条通知默认只在第一次响，后面的截图到期就完全无感。现在按节流规则重发提醒——
  连拍保护 90 秒（几秒内多张一起到期只响一次）、可设间隔（默认 20 分钟，0 = 不重复）、可设最多次数（默认 5 次，
  到了就只更新通知不再出声）。通知被划掉不等于回答了，条目留在队列里并安排一次补提醒；`稍后再问` 会把次数清零。
- **应用内自检页**：主页 `自检` 一份纯文本报告——通知权限、渠道实际 importance、当前勿扰状态、监控服务是否活着、
  内容观察器和最近扫描的时间戳、相册水位线是否落后、到点任务的状态、队列里每一条的状态和已提醒次数、
  以及哪些条件正在挡住提醒。`发一条测试提醒` 走真实渠道真发一条，用来区分「应用没发」还是「系统压掉了」。
- **闪退留痕**：应用自己挂了 `Thread.setDefaultUncaughtExceptionHandler`，把最后一条未捕获异常的类名、消息和前六层
  调用栈同步写进偏好（异步写会在进程被杀时丢掉），自检页的 `上次未捕获异常` 直接显示它。没有 adb 也能把堆栈带进反馈里。

## 下载

最新 release：[v1.0.3](https://github.com/ChangAkira/ScreenshotBye/releases/tag/v1.0.3) —
直接安装 [ScreenshotBye-v1.0.3.apk](https://github.com/ChangAkira/ScreenshotBye/releases/download/v1.0.3/ScreenshotBye-v1.0.3.apk)
（release 签名，minSdk 26 / Android 8.0 起）。v1.0.1 起都是同一把密钥，**直接覆盖安装即可**，清单和回收站都保留。

⚠️ v1.0.0 的包用的是 debug 签名，和 v1.0.1 起不是同一把密钥，**覆盖安装会失败**，请先卸载旧版再装新版（回收站里的备份会随卸载丢失，先还原出来）。从 v1.0.1 起所有 release 使用同一把密钥，可以正常增量升级。

## 设置项

| 设置 | 形式 | 范围 | 默认 |
| --- | --- | --- | --- |
| 后台监控截图 | 开关 | — | 开 |
| 多久后询问 | 滑杆 | 1 – 60 分钟 | 5 |
| 重复提醒间隔 | 滑杆 | 0 – 120 分钟（0 = 不重复） | 20 |
| 最多提醒次数 | 滑杆 | 1 – 10 次 | 5 |
| 提醒方式（声音/震动/横幅/锁屏） | 一键跳转系统通知设置 | 由系统管，应用不代做决定 | 系统默认（横幅+声音） |
| 未回答自动过期 | 滑杆 | 0 – 14 天（0 = 永不过期） | 3 |
| 删除前备份到回收站 | 开关 | — | 开 |
| 回收站保留天数 | 滑杆 | 1 – 30 天 | 7 |
| 识别规则 | 复选 | 按目录 / 按文件名 / 额外关键词 / 含录屏视频 | 目录 + 文件名 |
| 待确认清单 | 列表 | 逐个确认、预览、单张删除或保留 | — |
| 自检 | 页面 | 通知/权限/服务/队列状态报告 + 测试提醒，可复制 | — |
| 事件日志 | 文本 | 记录「检测到 / 已询问 / 已删除 / 失败」，方便定位是没检测到还是检测到没问 | — |

## 权限与真实限制

这个应用要的权限比普通应用多，逐条说清楚为什么：

| 权限 | 用在哪里 | 必需吗 |
| --- | --- | --- |
| 读取相册（`READ_MEDIA_IMAGES`） | 查询新增截图、显示预览 | 必需，不给就什么都看不见 |
| 通知（`POST_NOTIFICATIONS`） | 到点询问、监控状态 | 必需（Android 13+），否则问不出来 |
| 媒体位置（`ACCESS_MEDIA_LOCATION`） | 系统删除确认请求的前置条件 | 必需 |
| 所有文件访问（`MANAGE_EXTERNAL_STORAGE`） | **静默删除** | 强烈建议。不授权时删除会退化成系统弹窗二次确认，这是 Android 11+ 的规定：不是你创建的文件，不能由应用悄悄删掉 |
| 存储写入（Android 10 及以下） | 直删文件 | 该版本区间必需 |
| 忽略电池优化 / 自启动白名单 | 防国产 ROM 杀后台 | 建议，不设就会中断监测 |
| 前台服务 `specialUse` | 常驻监听相册 | 必需，只有一条常驻通知 |

Manifest 里没有 `INTERNET` 权限，应用在系统层面就无法联网：图片字节只在「生成预览」和「备份」时留在本机，不上传、不做任何内容分析，也没有统计和账号。

## 它是怎么实现的

- **发现截图**：`MediaStore.ContentObserver` 挂在 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 上，这是三方应用唯一可靠的全局截图入口。`FileObserver` 只用来提前催一次扫描，不参与判定。服务重启后从水位线续扫，覆盖停摆期间产生的截图；首次启用时把水位线设为当前时刻，不会把历史截图当新截图。
- **计时**：WorkManager 一次性任务（每张一个 `ask-<mediaId>` 的 unique work），而不是 `Handler.postDelayed`——后者的排队任务会随进程一起消失，五分钟后就问不出来了。
- **处置**：`ScreenshotRepository` 是唯一的决策出口，通知按钮、逐个确认页、定时任务都调它，删除逻辑只有一份。
- **删除**：`DeleteGateway` 先备份再删；能静默删就直删，不能就生成 `MediaStore.createDeleteRequest`（一批一次弹窗），由透明中转页 `DeleteConfirmActivity` 拉起。系统弹窗结束后以「原图还在不在」为准判断用户是否真的确认，取消了就把多余的备份抹掉。
- **回收站**：`filesDir/trash/<mediaId>/` + `meta.json`，还原时重新写回 MediaStore。还原本机会让相册多出一条新记录，所以还原成功的文件名会进 3 分钟屏蔽名单，避免「刚还原又被当成新截图问一遍」。
- **存活**：`BootReceiver`（开机 / 应用更新）+ 15 分钟 `WatchdogWorker`，拉不起来时发一条「点按恢复」的通知，而不是无声失败。
- **队列卫生**：看门狗每轮先跑一次 `sweepStale()` 兜住三种「静默卡住」——`WAITING` 早过了到期时间却没被问（定时任务被 ROM 清掉）→ 立即补问；`ASKED` 超过设定天数没人回答 → 按保留出队；`DELETING` 六小时拿不到系统确认结果（弹窗开着时进程被杀）→ 退回待确认。监控关着时看门狗不在，`MainActivity.onResume` 是兜底入口。
- **提醒的出声时机**：`ScreenshotRepository` 区分 `refreshReviewNotification`（允许按节流规则重新出声）和 `updateReviewNotification`（只同步内容）。到点询问、看门狗心跳、通知被划掉后的补提醒走前者；用户在应用里自己点了保留/删除/稍后再问之后的同步走后者，不该再响一次。
- **不替系统做提醒决定**：询问通知只用一条渠道，`setOnlyAlertOnce(false)` 让同一个 id 能重新出声，声音、震动、横幅一律不写死。渠道属性在创建后不可改，所以早期版本里想「换一档」只能换渠道——这正是三档强度被撤掉的原因。
- **不让启动路径炸**：`Application.onCreate` 里的渠道创建和看门狗排期都包在 `runCatching` 里，失败只往事件日志写一行；权限探测 `Environment.isExternalStorageManager()` 同样兜住异常，一个探测不该带走主界面。两处 `FileObserver(File, int)` / `RecoverableSecurityException` 是 API 29 才有的，minSdk 26 上直接引用会让 Android 8/9 在启动监听、删除时抛 `NoSuchMethodError`——现在改用 `FileObserver(String, int)` 构造和按类名判断异常。

## 为什么不是别的做法

- **Android 14 的 `DETECT_SCREEN_CAPTURE` / `Activity.onScreenCapture()`**：只在你自己的 Activity 可见时回调，而且不返回截图文件，做不了全局截图清理。
- **亮屏广播（`ACTION_SCREEN_ON`）触发**：能弹界面，但不是「截图后 N 分钟」的语义，服务停摆期间的截图也永远补不回来。
- **MediaStore 没有公开的「移入系统回收站」API**，而要删的文件不是本应用创建的，直接删除不可逆。所以回收站是自己实现的，删除前强制备份。

## 已知限制

诚实列一下，都能在代码里对应上：

- 服务被杀到被拉回之间产生的截图会在下次启动时补扫，但询问时间会顺延。
- Doze 深睡时 WorkManager 的询问可能延后到下一个维护窗口，不是精确到秒。
- 其他应用改名保存的截图可能识别不到，用「额外关键词」补。
- 通知上的快捷按钮只作用于最早到期的那一张，批量操作要点开逐个确认页。
- 回收站在应用私有目录，**卸载应用即永久丢失**；从回收站还原会在相册里生成一条新记录。
- 国产 ROM 的后台管控是硬限制，不手动放行自启动就可能收不到询问。提醒方式也有天花板：应用只负责发通知，
  横幅到底弹不弹、锁屏上显不显示、震动和铃声认不认，最终由 ROM 决定，改只能去系统的通知设置里改。
  用自检页 + 测试提醒可以先确认是哪种情况。
- 询问过期是**按「保留」出队**，不会自动删除——宁可漏删，也不在你不在场时误删。
- 界面文案硬编码中文（包括自检报告正文），未做国际化。
- 验证手段：`gradle testDebugUnitTest` 用 Robolectric 真跑一遍启动路径（主界面、Android 8 上的监控服务、渠道创建、
  询问通知构建、旧版存档兼容、队列卫生、提醒封顶、闪退记录），`gradle lintDebug` 的全量 lint 会揪出
  `lintVitalRelease` 不管的 `NewApi`——两处 Android 8/9 崩溃就是这么发现的。**依然没有在真机上做系统化回归**，
  沙箱里没有 `/dev/kvm`，跑不了模拟器；Robolectric 只能证明「代码能跑完且不抛」，ROM 的推送、铃声、横幅策略仍测不到。

## 从源码构建

环境：JDK 17+、Android SDK Platform 35、AGP 8.7.3 / Gradle 8.10（wrapper 已配置）。依赖版本统一放在 `gradle/libs.versions.toml`。

```bash
# 调试包，无需签名材料
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 启动路径的单元测试（Robolectric，不需要模拟器或真机）
./gradlew testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html

# 全量 lint：会报出 release 打包时被跳过的 NewApi
./gradlew lintDebug
```

发布包需要签名材料。`keystore/` 与 `keystore.properties` 已被 `.gitignore` 排除，需自行放置到仓库根目录：

```properties
# keystore.properties
storeFile=keystore/screenshotbye-release.jks
storePassword=<你的密钥库口令>
keyAlias=screenshotbye-release
keyPassword=<你的密钥口令>
```

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

没有 `keystore.properties` 时构建依然会通过，只是 release 包不签名、无法直接安装。

## 签名

- 密钥别名：`screenshotbye-release`，RSA 2048，2026-10-31 起有效至 2056-10-23
- 证书 SHA-256：`31:70:EF:6B:D6:70:5A:15:B0:A1:B9:A7:6B:4A:F8:72:7F:4F:BA:25:8C:96:3E:49:F0:A1:30:3A:85:0E:97:00`
- 密钥库文件与口令不入库，由项目所有者单独保管
- 从 v1.0.1 起所有 release 使用同一把密钥；换密钥意味着老用户必须卸载重装

## 代码结构

```
app/src/main/java/com/changakira/screenshotbye/
  core/        Settings(配置 + 闪退记录), PendingShot(待确认清单), Reminders(计时), ScreenshotRepository(决策中枢)
  media/       MediaScanner(发现), ScreenshotRule(判定), DeleteGateway(删除), TrashStore(回收站)
  service/     WatchService(常驻前台服务), WatchdogWorker(存活看门狗)
  remind/      ReminderWorker(到点询问), AlertRetryWorker(被划掉后补提醒), TrashCleanupWorker(过期清理)
  notify/      Channels(常驻 + 一条提醒渠道，顺带清理已废弃的强度渠道), Notify, ActionReceiver(通知按钮与划掉事件)
  diagnostic/  Diagnostics(纯文本自检报告 + 测试提醒)
  perm/        Perms(权限状态、OEM 跳转、直达通知渠道设置)
  ui/          MainActivity, ReviewActivity(逐个确认+批量反选), TrashActivity, DiagnosticsActivity,
               DeleteConfirmActivity(系统删除确认中转), OnboardingActivity(首次引导), Adapters, Thumbs
app/src/test/java/com/changakira/screenshotbye/
               AppSmokeTest(把上面这些真跑一遍)
```

`DeleteConfirmActivity` 是必需的：Android 11+ 的 `MediaStore.createDeleteRequest` 只能由 Activity 发起，
所以批量删除先落到这个透明中转页，由它拉起一次系统确认框，再把结果回报给 `ScreenshotRepository`。

## 技术栈

Kotlin 2.0.21 · AGP 8.7.3 · Material 3（Views + ViewBinding）· WorkManager 2.10.0 · JUnit 4 + Robolectric 4.13（单元测试）· Room/DataStore/Compose 都没用：状态就是一层 SharedPreferences + 一份 JSON 清单，引入数据库只是给自己加迁移负担。

## English summary

ScreenshotBye keeps an eye on your camera roll for you. When a new screenshot shows up, it waits a configurable delay (5 minutes by default) and then asks — in one merged notification, no matter how many screenshots piled up — whether you still need them. Tap through the cards, keep, snooze, or mark them gone; marked shots are deleted in one batch, after being copied into the app's own trash so you can restore them within the retention window.

On Android 11 and later, deleting files the app didn't create requires either the "all files access" grant or a system confirmation dialog; ScreenshotBye backs up first and asks the system once per batch, never silently. Detection uses a `MediaStore` content observer plus a `DATE_ADDED` watermark (Android 14's `DETECT_SCREEN_CAPTURE` only fires for your own visible activity and gives you no file), timing uses WorkManager so it survives process death, and a watchdog plus boot receiver bring the foreground service back when aggressive ROMs kill it.

No `INTERNET` permission at all — photos, settings and trash stay on the device. Requires Android 8.0+.

Asks re-alert instead of firing once and going silent: a 90-second burst guard, a configurable repeat interval and a per-item alert cap, all applied to a single notification channel — sound, vibration, heads-up banner and lock-screen behaviour are owned by the system notification settings, and the app just links straight to them rather than duplicating a switch you can already flip in Android itself. (v1.0.2 tried a three-level "alert intensity" picker backed by three channels; since channel settings are immutable once created, that meant three parallel copies of one system setting, so it's gone — the retired channels get deleted on startup and any ringtone or banner choice you'd already made carries over.) Swiping the notification away schedules a follow-up rather than muting the item forever, unanswered asks expire into "kept" after a configurable number of days, and stuck `DELETING` entries fall back into the queue. A built-in diagnostics screen dumps notification permission and channel importance, DND state, service liveness, observer and scan timestamps, WorkManager job states, per-item queue state and the last uncaught crash (captured synchronously, stack frames and all) as copyable plain text, plus a "send test alert" button that posts through the real channel — useful when the question is whether the app didn't fire or the ROM ate the banner.

## License

[MIT](LICENSE) — 简单说：随便用、随便改、随便闭源再分发，保留版权声明即可，出问题不追究作者。

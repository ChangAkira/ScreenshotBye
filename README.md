# ScreenshotBye

Android 上持续后台监听系统截图，过一段时间（默认 5 分钟）问你一句「还需要这张截图吗」，
回答不需要就删掉它，删除前自动留一份可还原的备份。

适合「截图只是临时用一下，用完就忘在相册里」的人。

## 功能

- 常驻前台服务持续监听相册变化，截图落盘即被发现
- 可配置 1–60 分钟的延迟，到点发通知询问，三个按钮：`不需要，删除` / `还要留着` / `10 分钟后再问`
- 删除前强制备份到应用内回收站，保留期内可一键还原；过期自动彻底清除
- 识别规则可配：按截图目录、按文件名、按自定义关键词，同时处理录屏视频可选
- 开机自启 + 看门狗自动恢复被 ROM 杀掉的监控
- 事件日志，方便确认到底是"没检测到"还是"检测到没问"

## 它怎么工作

1. **常驻前台服务** `WatchService`（`specialUse` 类型）持有 MediaStore 监听，通知栏一直显示监控状态。
2. **发现截图** `MediaScanner` 在 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 上注册 `ContentObserver`，
   截图被系统索引后触发 `onChange`；350ms 防抖后按 `DATE_ADDED` 水位线做增量查询，
   再用「所在目录 / 文件名 / 自定义关键词」三条规则判定是不是截图。
   `FileObserver` 只用来提前催一次扫描，不参与判定。
   服务重启后从水位线续扫，覆盖停摆期间产生的截图；首次启用时把水位线设为当前时刻，不会把历史截图当新截图。
3. **计时询问** 命中后写入待确认清单，并用 WorkManager 排一个 N 分钟的一次性任务。
   任务持久化在 WorkManager 数据库里，进程被杀或设备重启后仍会补发；服务重启时到期的立即补问。
4. **删除** 先把原图复制到应用私有目录的回收站，再删相册里的原件；备份失败就不删除。
   还原后的文件会进入短期屏蔽名单，避免"刚还原又被当成新截图问一遍"。

## 为什么不是别的做法

- **Android 14 的 `DETECT_SCREEN_CAPTURE` / `onScreenCapture()`** 只在你自己的 Activity 可见时回调，
  而且不返回截图文件，做不了全局截图清理。
- **MediaStore 没有公开的「移入系统回收站」API**，而要删的文件不是本应用创建的，直接删除不可逆。
  所以回收站是自己实现的（`filesDir/trash/`），删除前强制备份。
- **不用 `Handler.postDelayed` 计时**：排队任务随进程一起消失，5 分钟后就问不出来了。

## 权限与真实限制

| 权限 | 必要性 | 说明 |
| --- | --- | --- |
| 读取相册 / 图片和视频 | 必需 | 没有它连截图都看不到 |
| 通知 | Android 13+ 必需 | 否则询问发不出去 |
| 所有文件访问 | 强烈建议 | Android 11+ 只有它能**静默删除**。不授权时删除会退化成"系统弹窗要你确认"——点完 `不需要，删除` 还要再点一次系统对话框的删除 |
| 忽略电池优化 / 自启动白名单 | 建议 | 国产 ROM（MIUI、ColorOS、HarmonyOS、OriginOS 等）会杀后台，不设置就会中断监测 |

已知边界：

- 服务被杀到被拉回之间产生的截图，会在下次启动时补扫，但询问时间会顺延。
- Doze 深睡时 WorkManager 的询问可能延后到下一个维护窗口，不是精确到秒。
- 其他应用改名保存的截图可能识别不到，用「额外关键词」补。
- 回收站在应用私有目录，**卸载应用即永久丢失**；从回收站还原会在相册里生成一条新记录。
- 图片字节只在「生成缩略图」和「备份」时留在本机，不上传、不做任何内容分析。

## 构建与安装

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

环境要求：JDK 17+、Android SDK Platform 35、AGP 8.7.3 / Gradle 8.10，最低系统 Android 8.0（API 26）。

`release` 构建目前复用 debug 签名，方便直接安装自测；上架前请换成自己的 keystore。
`specialUse` 前台服务类型上架时需要在 Play 控制台填写用途说明。

Releases 页提供已签名的 debug APK，可直接安装。

## 代码结构

```
app/src/main/java/com/changakira/screenshotbye/
  core/        Settings(配置), PendingShot(待确认清单), Reminders(计时), ScreenshotRepository(决策中枢)
  media/       MediaScanner(发现), ScreenshotRule(判定), DeleteGateway(删除), TrashStore(回收站)
  service/     WatchService(常驻前台服务), WatchdogWorker(存活看门狗)
  remind/      ReminderWorker(到点询问), TrashCleanupWorker(过期清理)
  notify/      Channels, Notify, ActionReceiver(通知按钮)
  perm/        Perms(权限状态与 OEM 跳转)
  ui/          MainActivity, TrashActivity, DeleteConfirmActivity, Adapters, Thumbs
```

`DeleteConfirmActivity` 是必需的：Android 11+ 的 `MediaStore.createDeleteRequest` 只能由 Activity 发起，
所以通知点按后先落到这个透明中转页，由它拉起系统确认框，再把结果回报给 `ScreenshotRepository`。

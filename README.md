# 小睦工具箱（BiliToolbox）

哔哩哔哩 Android 客户端的 LSPosed 多功能模块。目前装了**一个**功能：视频下载器
（入口在「分享」面板里）；架构按「一个功能一个开关」搭的，以后加东西只在
`BiliToolbox.install()` 里多一行。

- 模块包名：`io.github.zakoalice.bilitoolbox`，版本 1.8
- 作用域：`tv.danmaku.bili`（手机版）/ `tv.danmaku.bilibilihd`（HD）
- 设置入口：**我的 → 更多服务 → 小睦工具箱**
- 逆向样本：哔哩哔哩 **9.17.0（9170300）**（另保留 9.19 的兼容路径）
- 无联网上传、无数据收集；不装 LSPosed / 无 root 用不了

---

## 功能一：视频下载器（入口在分享面板）

从「B站下载器-Edge 扩展」搬过来的能力，改成 App 里的样子。

### 怎么用

1. 打开视频播放页，点右下角 **分享**
2. 分享面板里多了 **下载视频** 这一项（和微信、QQ、复制链接并列）
3. 点它 → 弹出清晰度列表（现请求 playurl 拿**真实可用**画质，不是写死的）
4. 选一个 → 进度框里看百分比
5. 完成后文件在 `手机存储/Download/小睦工具箱/`

### 两条下载路线

| 路线 | 画质 | 产物 | 说明 |
| --- | --- | --- | --- |
| 音画合一 MP4 | 最高 720P | 一个 `.mp4` | 走 `platform=html5&high_quality=1` 老接口，下载完直接能播 |
| DASH 高清 | 到 4K/8K | `_video.m4s` + `_audio.m4s` | 音视频分离，需要自己合并 |

合并命令（电脑上任意 ffmpeg）：

```bash
ffmpeg -i xxx_video.m4s -i xxx_audio.m4s -c copy out.mp4
```

> 高清（1080P 及以上）要登录态 + 对应权益。模块会先读宿主 WebView 的 cookie，
> 读不到就匿名请求 —— 匿名一般只给到 480P/720P。这是服务端策略，模块绕不过去。

---

## 分享面板的入口是怎么插进去的

面板是 Kotlin + Compose 自绘的，硬插 View 插不进去；但宿主自己就把「数据 → 菜单项」
抽成了独立方法，所以不碰 UI，只插数据。

**9.17 的真实面板**是 `kntr.common.share`（详见下面「9.17 分享面板是怎么找到的」）。
一句话版本：

```java
// 注入：面板渲染前最后一步，返回「一行行菜单项」
kntr.common.share.domain.model.e#c(List<List<b$a>>) -> 补一条 b$a
// 点击：所有项的点击都从这里过，q$a 里带着被点的那一项
kntr.common.share.domain.ShareViewModel#c(domain.q, …) -> 认出自己的 id 就吞掉
```

`b$a(id, 名字, ImageBitmap, 图标URL, DrawableResource, …)` 是纯数据类，可以直接 new；
图标从面板里现成项复制，不写死资源 id，也不联网。

### 点击怎么接管

我们的项 id 填自己的标记 `xiaomu_share_download` —— 宿主不认识这个 id，
就算漏到它的分发逻辑里也不会乱做事。`ShareViewModel#c` 上认出 id 就 `setResult(null)`
吃掉这次点击，改开自己的清晰度选择框（`ShareEntry.fire()`）。

### 老版本 / 兜底

`supermenu` 那一套（`SuperMenu.addMenus` + `itemClickListener` 动态代理）和 9.19 的
`kntr.common.share.domain.v1.ChannelList` 路径都还留着，只是新框架挂上后就不再注册了
—— 换版本时先看日志有没有「分享面板已挂钩（新框架 …）」，没有才轮到它们。


## 「我的」页设置入口

那条「更多服务」列表也是服务端下发的：`MenuGroup` 卡片装着一串 `MenuGroup.Item`
（标题 + 图标 + uri）。往正确的 group 的 itemList 里追加一个 Item 就行。

- hook `tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment` 的 `onResume` / `onViewCreated`
  （本类没声明就退到父类挂）
- 遍历 view 树找 RecyclerView → 找 adapter 里「元素类型是 MenuGroup」的 List →
  挑标题含「更多服务」的卡片 → append 一个 Item
- 因为设置界面是 Dialog 不是 Activity，item 的 uri 路由跳不过去，所以 hook 网格 holder 的
  `onClick(View)`，只在 uri 是自己那个时才接管

几个坑：**不能 `instanceof RecyclerView`**（模块编译时引用 SDK 里的 androidx，宿主有自己的
那份，类对象不相等，只能按类名比）；**字段名被 R8 改名**（按列表元素类型认，不认字段名）；
**列表异步下发**（注入要重试，塞进去了就停）；**每次 bind 都要补一遍**（服务端刷新会冲掉）。

---

## 安装

1. 装 `dist/BiliToolbox-v1.3.apk`
2. LSPosed 里启用模块，作用域勾 **哔哩哔哩 `tv.danmaku.bili`**
3. **强制停止**哔哩哔哩，再打开
4. 随便点开一个视频 → 分享 → 应该能看到「下载视频」

## 验证是否生效

```bash
adb logcat -s BiliToolbox:V
```

强停重开后应该看到（9.17 上）：

```
BiliToolbox: 小睦工具箱 v1.8 启动，宿主 tv.danmaku.bili 9.17.0 (9170300)
BiliToolbox: 分享面板已挂钩（新框架 model.e#c）
BiliToolbox: 分享面板点击已挂钩（ShareViewModel#1）
BiliToolbox: 分享面板渲染层已挂钩（menu.u#2）
BiliToolbox: 「更多服务」已插入小睦工具箱入口
```

打开分享面板后，应该看到（面板内容变了就会重新打一条，竖屏/横屏各一套）：

```
BiliToolbox: 分享面板（数据层）2 组：第1行 12 项[WEIXIN/微信, …]
BiliToolbox: 分享面板（渲染层）2 组：第1行 12 项[WEIXIN/微信, …]
BiliToolbox: 分享面板已注入（渲染层）：下载视频 在第 1 行第 1 位，共 26 项
```

点我们的项会出现这两行，紧跟清晰度选择框：

```
BiliToolbox: 分享参数：scene=… oid=… sid=…
BiliToolbox: 分享面板里点了「下载视频」，挂在 VideoDetailActivity
```

`挂在 …` 后面那个类名就是弹窗所在的页面 —— 如果它是 `MainActivityV2` 而你正停在播放页，
说明页面栈又认错了，把这两行发我。

对不上时按这几行排查：

- 没有 `渲染层已挂钩` → 这个版本 `common/ui/menu/u` 换包名了（把日志发我）
- 有 `渲染层已挂钩` 但打开面板没出现 `分享面板（渲染层）` → 手机端面板走的是别的布局函数
- 两条日志都有、格子还是没出现 → 说明 `z.d` 那一层还按 id 过滤，得改成复用宿主已有的 id

## 已知边界

- **版本敏感**：9.17 的主注入点是 `kntr.common.share.domain.model.e#c`（数据层）
  + `kntr.common.share.common.ui.menu.u`（渲染层），点击在 `ShareViewModel#c`。
  老版本的 `SuperMenu` / `ShareChannels` 那两套作为兜底保留，都不命中就静默跳过，
  日志会写清楚停在哪一步。
- 「我的」页入口实测正常（9.17.0，日志 `「更多服务」已插入小睦工具箱入口`）。
- 视频 ID 从 Intent 取。从动态/搜索等非常规入口进播放页时可能取不到，点「下载视频」会提示
  「这里读不到视频信息」。
- 下载走 HTTP 接口，**没有做过任何绕过会员的事**：接口给什么就下什么。
- 未做框架隐藏。若 B站检测 Xposed，请自行配 LSPosed 的隐藏选项。

## 9.17 分享面板是怎么找到的（v1.4 记）

1.0 → 1.3 一直往 `com.bilibili.app.comm.supermenu`（`SuperMenu.addMenus` / `ShareChannels`
/ `ShareMenuBuilderV2`）上挂钩，全都挂成功、却一次都没触发。用自写的 DEX 调用点扫描确认：
**`SuperMenu.addMenus` 在整个 9.17 APK 里零调用点**，那套类虽然还在包里，但对视频分享来说
是死代码（只有评论区长图、动态等少数入口在用）。

视频播放页的分享从 `com.bilibili.app.gemini.share.q`（Kotlin + Compose）发起，落到
`kntr.common.share`（R8 全局混淆后的包名，`classes26.dex`）：

```
ShareFacade / ShareStore（flowredux 状态机）
  └─ domain/service/i  服务端渠道 id → 查表(service/c) → 造菜单项 b$a
  └─ domain/model/e    c(List<List<b$a>>)  面板渲染前对「行」做过滤/整理
  └─ common/ui/menu/ShareMenuKt$ShareMenuView   Compose 画格子
  └─ domain/ShareViewModel.c(domain.q, ...)     点击统一入口，q$a = 点了某一项
```

所以 v1.4 只做两件事：

- **注入**：hook `domain/model/e#c`，在**第一行行首**插一条自己造的 `b$a`
  （面板每行是横向滑动的、一屏只露 6 个，加在末尾会跑到屏幕外面看不见——v1.4 踩过这个坑）
  （`b$a(id, 名字, ImageBitmap, 图标URL, DrawableResource, …)`，用 5 参构造 + mask 0x18）。
  图标不写死，从面板里现成项复制 `d`（i0.hdslb.com/bfs/share/xxx.png）和 `e`，优先抄「下载/缓存」那一项。
- **点击**：hook `ShareViewModel#c(q, …)`，`q$a.a.a` 是我们的 id 就 `setResult(null)` 吞掉，
  改开自己的清晰度选择框（`ShareEntry.fire()`）。面板 UI 传的 item 本来就是 null，
  所以不需要给这一项准备 `core/base/a` 实体。

调试日志（`adb logcat | grep 小睦`）：

```
分享面板已挂钩（新框架 model.e#c）
分享面板已打开（kntr.common.share）
分享面板（新框架）N 组：第1行 5 项[WEIXIN/微信, …]
分享面板已注入：下载视频（第 1 行第 1 位，该行现有 13 项）
分享面板点击：xiaomu_share_download / 下载视频
```

## 为什么「注入成功」却看不见（v1.6 记）

`domain/model/e#c` 上注入确实成功、日志也打了，但屏幕上没有。两个坑叠在一起：

1. **面板有可见格数上限**。`common/ui/menu/u` 的 `a`/`b` 两个布局拿到状态后做的是
   `flatten → chunked(每行几格) → take(最多几行)`，一屏只画 `每行几格 × 行数` 个
   （实测横屏 6×2 = 12 个）。排在后面的项（包括我们加在行尾的那条）根本不会被画。
2. **数据到画面之间还有一道白名单**。9.17 里宿主的 `SINA/微博` 明明在数据列表里，
   屏幕上也没有——说明过滤不在 `e#c`，在它后面。

所以 v1.6 换到**渲染层**注入：hook `common/ui/menu/u.a` 和 `u.b`（两个布局函数），
把 `state.a` 换成「我们的项 + 原有全部」，用 `model.b#a(state, 新列表, null, null, 0x3fe)`
（就是 `state.copy(a = 新列表)` 的入口，0x3fe 表示其余字段照抄）。
这样我们的项永远落在 flatten 后的第 0 位，也就是第一行第一格，不受格数上限和白名单影响。

数据层那条 `e#c` 注入留着当兜底，渲染层会按 id 去重，不会出现两条。


## v1.7 的两个收尾

- **只往「分享目标行」插，不再两行都插**。面板每一行是**单独一次布局调用**，
  v1.6 见行就插 → 上下两行第一位各出现一个「下载视频」。现在按行内渠道判断：
  只有含 `WEIXIN / WEIXIN_MONMENT / QQ / QZONE / SINA / COPY / GENERIC` 的那一行
  （分享目标行）才插，倍速/镜像那行不动。
- **视频 id 改成优先取分享面板自己的参数**。有些入口（竖屏播放、推荐流直接分享）Intent 里
  没有任何视频字段，只靠 Intent 会提示「读不到视频信息」。现在点击时从
  `ShareViewModel.a`（`model.l`）→ `.a`（`ShareParams`）读 `getOid()`，
  数字就是 aid、`BV…` 就是 bvid，再交给下载器；读不到才退回 Intent。


## v1.8：弹窗弹到首页上去了

现象：横屏视频里点「下载视频」，视频页什么都不出来；退回首页才看见下载框。

根因在 `Activities.top()`：原来是扫 `ActivityThread.mActivities`、取「最后遍历到的那个未销毁页面」，
而 `mActivities` 是 **HashMap，遍历顺序没有任何保证**。首页和播放页同时在栈里时，
有一半概率挑到首页 → Dialog 挂的是后台页面的 window token，前台什么都看不见。

三层改法：

1. **自己记页面栈**：`Activities.track()` 在 install 时 hook `Activity.onResume`（入栈）/
   `onPause`、`onDestroy`（出栈）。顺序确定，栈顶就是用户正在看的那一个。
2. **兜底也讲道理**：扫 `mActivities` 时优先 `record.paused == false`（当前 resumed），
   并且一律跳过 `isFinishing()` 的页面 —— 往正在收摊的页面挂 Dialog 等于白挂。
3. **等面板退场再弹**：点击后延迟 260ms 再取页面（分享面板可能是独立页面，退场动画没走完），
   取到的页面如果正在 finish 就每 200ms 重试，最多 3 次。

顺带收紧了 `oid`：`ShareParams.getOid()` 不总是 aid —— 分享动态/会话场景会给 15~19 位的
动态 id，之前当成 aid 用会去下别的视频。现在只认 1~12 位纯数字（或 `BV…`），
否则日志写「oid 不像视频 id，忽略」，退回读页面 Intent。

日志多了一句，能直接看出挂在哪个页面上：

```
分享面板里点了「下载视频」，挂在 StoryVideoActivity
```


## 构建

需要 JDK 17+、Android SDK（build-tools 36.0.0 + platforms/android-36）。

```bash
cd module
bash build.sh
```

产物：`dist/BiliToolbox-v1.8.apk`

> 工程路径含中文（`模块`）时，`apksigner.bat` 读不了中文路径，所以脚本先把 keystore 和
> 输出拷到 `build/` 下的纯英文路径再签，签完才复制到 `dist/`。

## 目录

```
module/src/io/github/zakoalice/bilitoolbox/
├── MainHook.java          LSPosed 入口，只在主进程 / 对应包名里初始化
├── BiliToolbox.java       装配点：一个功能一行
├── Hooks.java             hook 工具（含「本类没声明就挂父类」的兜底）
├── Prefs.java             配置（存宿主私有 SharedPreferences）
├── Reflect.java           失败即返回 null 的反射工具
├── Activities.java        从 Context / ActivityThread 里掏当前 Activity
├── XLog.java              三路日志（LSPosed / logcat / 面板内环形缓冲）
├── share/
│   ├── ShareEntry.java    分享入口总装配 + 点击后调起下载框
│   └── KntrShareEntry.java 9.17 新分享面板（kntr.common.share）注入 + 点击认领
├── ui/
│   ├── MineEntry.java     我的页入口注入 + 点击接管
│   ├── SettingsDialog.java 设置面板、日志面板
│   └── Ui.java            代码构建 B 站风格控件的工具
└── download/
    ├── VideoDownloader.java 画质选择、下载编排、进度框
    ├── BiliApi.java         playurl / view 接口
    └── Downloader.java      下载、进度、落盘（MediaStore）
```

## 免责

仅供个人学习与本机使用。模块不修改、不分发 B 站客户端本身，也不绕过任何付费内容。
使用前请自行评估相关服务条款。

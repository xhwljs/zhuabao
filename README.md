# 答案显示模块（LSPosed Hook）

> 基于 LSPosed 框架开发的 Android 模块，针对目标应用 `tz.ycsy.az`，通过 Hook HTTP 请求自动识别并标记答案，同时支持**自动点击选中**正确答案。

---

## ✨ 功能亮点

- **🎯 答案自动识别**：拦截 `getQuestion` 接口，解析 JSON，将 `isRight == 1` 的选项标记为「【 xxx 正确答案 】」
- **📝 多题型支持**：兼容单选、多选、判断、填空题等多种题型
- **⚡ 自动点击**：检测到正确答案后自动点击选中选项，无需手动操作（可在模块界面随时开关）
- **🔒 跨进程数据共享**：通过 `ContentProvider` 写入统计数据，模块 UI 可以实时读取
- **📊 统计与请求日志**：在模块界面查看命中次数、请求总数、最近请求明细（分页）
- **🎨 全新模块界面**：顶部信息卡 + 状态卡 + 快捷操作 + 数据统计 + 请求记录 + 工作原理，纯 Java 代码构建，无需 XML layout

---

## 📦 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── xposed_init                          # LSPosed 入口类声明
├── java/com/answer/revealer/
│   ├── XposedInit.java                      # Hook 主入口（IXposedHookLoadPackage）
│   ├── StatsProvider.java                   # ContentProvider（跨进程数据共享）
│   └── MainActivity.java                    # 模块配置界面（纯代码 UI）
└── res/
    ├── drawable/
    │   └── ic_launcher_foreground.xml       # 模块应用图标（渐变圆形 + 答案卡片）
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml
    │   └── ic_launcher_round.xml
    └── values/
        ├── colors.xml                        # 主题色（主色蓝、强调色绿）
        ├── strings.xml                       # 字符串资源 + 作用域声明
        └── themes.xml                        # 主题配置
```

---

## 🏗️ 核心实现要点

### 1. XposedInit — Hook 入口
实现 `IXposedHookLoadPackage`，在目标应用启动时注入：

- **包名筛选**：仅在 `lpparam.packageName == "tz.ycsy.az"` 时 Hook；模块自身包名仅 Hook `isModuleActive()` 用于状态检测
- **多渠道 Hook**：
  - `okhttp3.RealCall / okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()`
  - `java.net.HttpURLConnection.getInputStream()`
  - `android.webkit.WebViewClient.shouldInterceptRequest()`（新旧 API 都覆盖）
- **响应读取**：通过 `response.body().bytes()` 或反射调用获取原始字节，避免消耗原流
- **JSON 修改**：
  - 解析响应 JSON 中的 `data.answerOptionList`
  - 遍历找到 `isRight == 1` 的选项，将 `optionText` 替换为 `【 xxx 正确答案 】`
  - 重新构建 `ResponseBody` 并通过 `param.setResult()` 替换
- **自动点击**：
  - 读取 `auto_select_enabled` 开关状态（来自 ContentProvider / SharedPreferences）
  - 若开启，在 WebView 页面加载后注入 JS 自动选中带有「正确答案」标记的选项
  - 在 `Activity.onResume`、`WebViewClient.onPageStarted`、`WebChromeClient.onProgressChanged` 等时机多次尝试注入，提高成功率
- **数据上报**：通过 `ContentResolver.update(Uri.parse("content://com.answer.revealer.stats/update"), values)` 写入统计

### 2. StatsProvider — 跨进程数据容器
- `content://com.answer.revealer.stats/update`：写入 `request_count / target_hit_count / last_hook_time / detected_clients / auto_select_enabled`
- `content://com.answer.revealer.stats/request`：写入最近请求记录（URL + 时间）
- `content://com.answer.revealer.stats/query`：查询全部统计字段（供模块 UI 读取）
- `content://com.answer.revealer.stats/answer`：写入/读取正确答案文本（供 Hook 端使用）
- **关键修复**：`/clear` 路径清空时**保留 `auto_select_enabled` 状态**，避免用户清空统计后自动答题被关闭

### 3. MainActivity — 模块配置界面
**纯 Java 代码构建 UI**（无 XML layout），卡片顺序：

| 卡片 | 内容 |
|------|------|
| 顶部栏 | 模块图标 + 名称「答案显示模块」+ 副标题 + 目标应用信息（安装状态/包名/版本） |
| 模块状态 | 激活/未激活 + ACTIVE/INACTIVE 状态徽章 + 5 项功能亮点 + 自动答题 ON/OFF 状态标签 |
| 快捷操作 | **自动答题开关**（渐变大图标 + 自定义滑动开关 + 状态描述）+ 启动目标应用 + 刷新数据 + 清空统计 |
| 统计数据 | 答案命中、请求总数、最近活跃时间 |
| HTTP 客户端列表 | 目标应用已检测到的 HTTP 库（OkHttp/OkHttp3 等） |
| 最近请求 | URL 列表（分页显示，点击查看完整 URL 并复制到剪贴板） |
| 工作原理 | 5 步带序号步骤：Hook 注入 → 请求拦截 → 答案标记/点击 → 数据共享 → 界面展示 |

**模块激活检测**：
- 未 Hook 时 `isModuleActive()` 返回 `false`
- Hook 成功后 `MainActivity.isModuleActive()` 被改写返回 `true`

### 4. AndroidManifest — LSPosed 元信息
```xml
<application>
    <meta-data android:name="xposedmodule" android:value="true" />
    <meta-data android:name="xposeddescription" android:value="题目答案显示 - 作用域 tz.ycsy.az" />
    <meta-data android:name="xposedminversion" android:value="82" />
    <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />

    <provider android:name=".StatsProvider"
              android:authorities="com.answer.revealer.stats"
              android:exported="true"
              android:grantUriPermissions="true" />
</application>
```

作用域声明位于 `strings.xml`：`["tz.ycsy.az", "com.answer.revealer"]`。

### 5. 依赖（app/build.gradle）
```gradle
compileOnly 'de.robv.android.xposed:api:82'
compileOnly 'de.robv.android.xposed:api:82:sources'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
```
`compileOnly` 表示 Xposed API 仅在编译期提供符号，不打包进 APK（运行时由 Xposed 框架注入）。

**编译环境**：`compileSdk 34`、`minSdk 24`、`targetSdk 34`、`sourceCompatibility Java 1.8`

---

## ⚙️ 编译与部署

### 方法一：Android Studio
1. 用 Android Studio 打开项目根目录
2. 等待 Gradle Sync 完成（首次会下载 AGP 8.3.2 与依赖）
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

### 方法二：命令行
```bash
cd 项目根目录
./gradlew assembleDebug --no-daemon
# 产物在 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 方法三：GitHub Actions 自动构建
- 推送代码到 `main` 分支或 `trae/**` 分支即可触发 `.github/workflows/build.yml`
- 使用 JDK 17 + Gradle 8.4 + Android SDK
- 构建成功后 APK 以 artifact 形式保存 30 天
- 若推送的是 tag，还会自动发布 Release

---

## 📱 使用步骤

1. **安装模块 APK** 到已安装 LSPosed 的手机（需要 Root 或 LSPatch）
2. **在 LSPosed 管理器启用本模块**，作用域默认勾选 `tz.ycsy.az` 与 `com.answer.revealer`
3. **强制停止目标应用**：`adb shell am force-stop tz.ycsy.az` 或在系统设置中杀掉
4. **冷启动目标应用**，进入答题页面
5. **打开模块界面**（答案显示模块）可查看：
   - 模块激活状态（ACTIVE/INACTIVE）
   - 自动答题功能开关（可随时开启/关闭）
   - 答案命中次数、请求总数、最近活跃时间
   - 最近请求明细（分页浏览）

---

## 🎛️ 功能开关说明

在模块界面「快捷操作」卡片上方的**自动答题**开关：

| 开关状态 | 行为 |
|---------|------|
| ON（绿色） | 检测到正确答案后自动高亮并点击选中 |
| OFF（灰色） | 仅高亮标记答案，不自动点击 |

> **提示**：清空统计数据**不会**影响自动答题开关状态。

---

## ❓ 常见问题

**Q: 模块状态显示 INACTIVE？**
- 检查 LSPosed 管理器中是否已启用本模块，且作用域包含 `tz.ycsy.az` 与 `com.answer.revealer`
- 启用模块后必须**冷启动目标应用**（杀掉进程重开）
- 模块界面进入后按「刷新数据」可立即刷新状态

**Q: 答案被标红但不自动点击？**
- 在模块界面的「快捷操作」中打开自动答题开关
- 目标应用页面可能使用 JS 动态渲染选项，Hook 会在 `onPageStarted / onProgressChanged / onResume` 等多个时机多次注入 JS 以提高成功率
- 部分页面使用原生 UI（非 WebView），Hook 会尝试通过 `TextView.setText` 内容与 `performClick` 方式处理

**Q: 答案文本显示为原始 `<span>` 标签？**
- 目标应用使用纯文本 TextView 展示，不解析 HTML。可在 `XposedInit.modifyAnswerBodyWithStyle()` 中改用纯文本标记（如 `【 xxx ✓ 】`）

**Q: 响应 body 为空或乱码？**
- 某些 OkHttp 版本使用压缩传输（gzip），或响应体被加密。可在 `processOkHttpResponse` 中添加更灵活的读取分支
- 如果是端到端加密，Hook HTTP 层不够，需进一步 Hook 解密函数

**Q: 如何修改目标包名或 Hook 路径？**
- 修改 `XposedInit.java` 顶部的 `TARGET_PACKAGE` 常量（包名）
- 修改 `TARGET_PATH_KEYWORD`（接口路径关键字）
- 修改 `MainActivity.java` 顶部的 `TARGET_PACKAGE` 以同步界面展示
- 重新编译安装

**Q: 为什么 Xposed API 必须用 `compileOnly`？**
- Xposed 框架在运行时注入这些类。`implementation` 会把 API 打包进 APK，可能与框架版本冲突导致类加载异常

---

## 🛠️ 开发笔记

- 所有 UI 均通过 Java 代码动态创建，使用 `GradientDrawable` 实现卡片/按钮/徽章背景
- 模块界面使用统一色板：主蓝 `#2196F3`、强调绿 `#4CAF50`、警告橙 `#FF9800`、危险红 `#F44336`
- 日志已清理，不主动写入 Xposed log
- 统计数据通过 ContentProvider 传递，避免依赖 SharedPreferences 多进程文件锁
- `auto_select_enabled` 字段在清空统计时会被保留，不影响用户使用习惯

---

## 📄 License
本项目仅供学习与研究使用。请遵守相关应用的服务条款与当地法律法规。

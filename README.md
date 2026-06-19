# LSPosed 模块 - 答案显示

## 项目目标
针对 Android 应用 `tz.ycsy.az`，拦截其 HTTP 请求 `/edu-core-server/app/exam/getQuestion`，并在响应中把正确答案的选项用红色粗体标识出来，同时弹窗显示请求与响应内容。

## 项目结构
```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── xposed_init                  # LSPosed 入口类声明
├── java/com/answer/revealer/
│   ├── XposedInit.java              # Hook 主入口，实现 IXposedHookLoadPackage
│   └── MainActivity.java            # 模块配置界面（显示状态/说明）
└── res/
    ├── drawable/
    │   └── ic_launcher_foreground.xml
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml
    │   └── ic_launcher_round.xml
    └── values/
        ├── colors.xml
        ├── strings.xml
        └── themes.xml
```

## 核心实现要点

### 1. XposedInit - 模块 Hook 入口
实现 `IXposedHookLoadPackage`：

- **包名筛选**：仅当 `lpparam.packageName == "tz.ycsy.az"` 时才执行 Hook；当包名是模块自己 `com.answer.revealer` 时，仅 hook `isModuleActive()` 用于状态检测
- **OkHttp Hook**：Hook `okhttp3.RealCall.getResponseWithInterceptorChain()` 和 `okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()` 覆盖新旧版本 OkHttp
- **响应读取**：通过反射调用 `response.body().source().getBuffer().clone()` 获取底层字节数组（避免消费原流）；失败时回退到 `response.body().bytes()`
- **JSON 修改**：
  - 解析响应 JSON，检查 `code == "success"`
  - 遍历 `data.answerOptionList`，将 `isRight == 1` 的选项 text 替换为 `<span style="color:#c62828;font-weight:900;font-size:16px;">【 text 正确答案 】</span>`
  - 重新构建 ResponseBody 并通过 `param.setResult()` 替换响应
- **弹窗**：切到主线程，用 `AlertDialog` 显示 URL、原始响应、修改后响应

### 2. MainActivity - 配置界面
纯代码构建界面（无 XML layout），显示模块状态、目标包名、Hook 路径和功能说明。模块是否激活由 Xposed Hook 返回值检测：

- Xposed 未激活 → `isModuleActive()` 默认返回 `false`
- Xposed 已激活 → Hook `MainActivity.isModuleActive()` 返回 `true`

### 3. AndroidManifest.xml - LSPosed 识别
三个关键 meta-data：
```xml
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="显示题目答案 - 作用于 tz.ycsy.az" />
<meta-data android:name="xposedminversion" android:value="53" />
<meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
```
`xposed_scope` 在 `strings.xml` 中声明为 `["tz.ycsy.az"]`，LSPosed 会自动在启用时勾选这个包名。

### 4. app/build.gradle - 依赖
```gradle
compileOnly 'de.robv.android.xposed:api:82'
compileOnly 'de.robv.android.xposed:api:82:sources'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
```
Xposed API 使用 `compileOnly`，只在编译期提供符号，不打包进 APK（由 Xposed 框架在运行时注入）。

## 编译与部署

### 方法一：Android Studio
1. 用 Android Studio 打开项目根目录
2. 等待 Gradle Sync 完成
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 在 `app/build/outputs/apk/debug/app-debug.apk` 得到产物

### 方法二：命令行
```bash
cd 项目根目录
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用步骤
1. **安装本模块 APK** 到已 Root 并装有 LSPosed 的手机
2. **打开 LSPosed 管理器**，在模块列表中找到「答案显示模块」并启用
3. **确认作用域**：自动应已勾选 `tz.ycsy.az`；如未勾选需手动勾选
4. **强制停止目标应用**：在系统设置或 LSPosed 中杀掉 `tz.ycsy.az` 进程
5. **重新打开目标应用**，进入答题页面
6. 当目标应用请求 `/edu-core-server/app/exam/getQuestion` 时：
   - 会弹窗显示完整请求 URL 和响应内容
   - 如果响应中包含 `isRight == 1` 的选项，其文本会被标红加粗并改写为「【 xxx 正确答案 】」
   - 修改后的响应会被返回给目标应用的业务代码，UI 会直接呈现红色答案

## 常见问题

**Q: 弹窗不出现？**
- 检查 LSPosed 中是否已启用本模块且勾选了正确作用域
- 确认目标应用进程被重启（首次启用模块必须冷启动）
- 目标应用可能使用了非 OkHttp 的 HTTP 客户端；可在 Logcat 搜索「AnswerRevealer」调试

**Q: 答案没被标红但弹窗出现了？**
- 目标应用可能不解析 HTML（`<span>` 标签按文本原样显示）。如需适配，可在 `modifyAnswerBody` 中改用纯文本标记（如 `【 xxx ✓ 】`）
- 目标应用可能将 `optionText` 直接用于纯文本 TextView；改为 `Html.fromHtml(optionText)` 才能呈现颜色

**Q: 响应 body 为空或乱码？**
- 某些版本 OkHttp 使用 `body().byteStream()` 或压缩传输；可在 `processOkHttpResponse` 中加入更多读取路径
- 响应可能被加密/混淆（端到端加密），此时需要针对性 Hook 解密函数而不是 HTTP 层

**Q: 如何改目标路径或包名？**
- 修改 `XposedInit.java` 顶部的 `TARGET_PACKAGE` / `TARGET_PATH` 常量，重新编译即可

**Q: 为什么用 compileOnly 而不是 implementation？**
- Xposed 框架会在运行时注入这些类。如果用 `implementation` 会把 API 打包进 APK，可能与框架版本冲突并导致类加载异常

package com.answer.revealer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口：Hook 目标应用 tz.ycsy.az 的答题接口
 * 数据写入 StatsProvider (content://com.answer.revealer.stats) 供模块 UI 读取
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String TARGET_PATH_KEYWORD = "getQuestion";

    // ContentProvider URI
    private static final Uri PROVIDER_URI = Uri.parse("content://com.answer.revealer.stats/update");
    private static final Uri PROVIDER_REQUEST_URI = Uri.parse("content://com.answer.revealer.stats/request");
    private static final Uri PROVIDER_QUERY_URI = Uri.parse("content://com.answer.revealer.stats/query");
    private static final Uri PROVIDER_LOG_URI = Uri.parse("content://com.answer.revealer.stats/log");

    // 配置
    private static final String CONFIG_KEY_AUTO_SELECT = "auto_select_enabled";
    private static final String CONFIG_KEY_AUTO_NEXT = "auto_next_enabled";
    private static final String CONFIG_SP_NAME = "answer_revealer_status";
    private static final String ANSWER_MARKER = "正确答案";

    private static final java.util.Set<String> initializedPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final AtomicInteger targetHitCounter = new AtomicInteger(0);

    // 存储当前题目的正确答案文本（用于 JS 直接选中）
    private static volatile String sCorrectAnswerText = null;
    // 存储当前正确答案标记文本（【 xxx 正确答案 】）
    private static volatile String sMarkedAnswerText = null;
    // 记录上次更新答案的时间戳（防止使用过期答案）
    private static volatile long sCorrectAnswerTimestamp = 0;
    // 防重标志：是否已经成功自动选中过答案（整个应用生命周期）
    private static final java.util.concurrent.atomic.AtomicBoolean sAlreadyAutoSelected = new java.util.concurrent.atomic.AtomicBoolean(false);

    // 防重：记录已注入过的答案哈希（同一个答案只注入一次，新答案自动重置）
    private static final java.util.Set<Integer> sInjectedAnswers =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    // 防重：注入成功后的冷却时间（毫秒），冷却期内不允许再次注入（防止多个延迟任务在不同题目上连续触发）
    private static volatile long sLastSuccessTime = 0;
    private static final long AUTO_SELECT_COOLDOWN_MS = 8000;

    // 防重：调度防抖 - 如果最近已经排过注入任务，则不再重复排（防止 shouldInterceptRequest 多次触发堆积）
    private static volatile long sLastScheduledTime = 0;
    private static final long SCHEDULE_DEBOUNCE_MS = 3000;

    // 防重：onProgressChanged 同一个 WebView 只在第一次达到100%时触发
    private static final java.util.Set<Integer> sProgressDoneWebViews =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    // ContentProvider URI - 答案文本
    private static final Uri PROVIDER_ANSWER_URI = Uri.parse("content://com.answer.revealer.stats/answer");
    private static final String KEY_ANSWER_TEXT = "answer_text";
    private static final String KEY_ANSWER_MARKED = "answer_marked_text";
    private static final String KEY_ANSWER_TIME = "answer_time";

    private static volatile Context appContext;

    // ============ ActivityThread Context 获取 ============
    private static Context getAppContextFromActivityThread() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (activityThread == null) return null;
            return (Context) XposedHelpers.callMethod(activityThread, "getApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        // 模块自己的包：hook isModuleActive() 方法
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        MODULE_PACKAGE + ".MainActivity", lpparam.classLoader, "isModuleActive",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(true);
                            }
                        }
                );
            } catch (Throwable ignored) {}
            return;
        }

        // 非目标包跳过
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // 每次进入目标应用都执行统计写入（不做去重）
        // initializedPackages 仅用于避免重复安装 hook 方法
        if (initializedPackages.add(TARGET_PACKAGE)) {
            // 首次初始化：安装 hook 方法
            final ClassLoader cl = lpparam.classLoader;

            // 获取 Context
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = getAppContextFromActivityThread();
                        if (ctx != null) appContext = ctx;
                        XposedHelpers.findAndHookMethod(
                                "android.app.Application", cl, "onCreate",
                                new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        try {
                                            Context c = (Context) param.thisObject;
                                            if (appContext == null) appContext = c;
                                        } catch (Throwable ignored) {}
                                    }
                                }
                        );
                    } catch (Throwable ignored) {}
                }
            }).start();

            // 安装网络 Hook
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        setupNetworkHooks(cl);
                        setupAutoSelectHooks(cl);

                        // 写入 ContentProvider
                        writeStatsToProvider(PROVIDER_URI);

                        // Toast 提示

                    } catch (Throwable t) {
                        // 初始化错误（已清除日志）
                    }
                }
            }).start();
        } else {
            // 已初始化过：只做一次完整统计写入（确保数据刷新）
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                        if (ctx == null) return;
                        ContentValues values = new ContentValues();
                        values.put("last_hook_time", System.currentTimeMillis());
                        ctx.getContentResolver().update(PROVIDER_URI, values, null, null);
                    } catch (Throwable ignored) {}
                }
            }).start();
        }
    }

    // ============ 安装网络 Hook ============
    private void setupNetworkHooks(final ClassLoader cl) {

        // === 1. OkHttp ===
        if (hasClass(cl, "okhttp3.OkHttpClient") || hasClass(cl, "okhttp3.Request")) {
            // Hook RealCall.execute() / getResponseWithInterceptorChain
            for (String realCallName : new String[]{"okhttp3.RealCall", "okhttp3.internal.connection.RealCall"}) {
                try {
                    XposedHelpers.findAndHookMethod(realCallName, cl, "getResponseWithInterceptorChain",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object response = param.getResult();
                                        if (response != null) processOkHttpResponse(response, param);
                                    } catch (Throwable ignored) {}
                                }
                            }
                    );
                } catch (Throwable ignored) {}
            }

            try {
                XposedHelpers.findAndHookMethod("okhttp3.Call", cl, "execute",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) processOkHttpResponse(response, param);
                                } catch (Throwable ignored) {}
                            }
                        }
                );
            } catch (Throwable ignored) {}
        }

        // === 2. HttpURLConnection ===
        try {
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", cl, "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                String urlStr = url != null ? url.toString() : "";
                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                Object bodyIn = param.getResult();
                                if (!(bodyIn instanceof java.io.InputStream)) return;

                                // 读取内容，修改后替换
                                byte[] bytes = readAll((java.io.InputStream) bodyIn);
                                if (bytes == null) return;
                                String body = new String(bytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                if (!modified.equals(body)) {

                                }
                                param.setResult(new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable t) {
                                // HttpURLConnection 处理异常（已清除日志）
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // === 3. WebView: shouldInterceptRequest (目标是检测 getQuestion API 并替换响应) ===
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", "android.webkit.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj == null) return;
                                String urlStr = urlObj.toString();

                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                // 转发 headers
                                Map<String, String> headers = new HashMap<>();
                                try {
                                    Object h = XposedHelpers.callMethod(req, "getRequestHeaders");
                                    if (h instanceof Map) {
                                        for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                                            if (e.getKey() != null && e.getValue() != null) {
                                                headers.put(e.getKey().toString(), e.getValue().toString());
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}

                                // 发起请求读取响应
                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    for (Map.Entry<String, String> e : headers.entrySet()) {
                                        try { conn.addRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
                                    }
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    // WebView 请求失败（已清除日志）
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                if (!modified.equals(body)) {

                                }

                                // 返回修改后的响应给 WebView
                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try {
                                    wresp.setStatusCodeAndReasonPhrase(statusCode, "OK");
                                    Map<String, String> respHeaders = new HashMap<>();
                                    respHeaders.put("Content-Type", "application/json; charset=UTF-8");
                                    try { wresp.setResponseHeaders(respHeaders); } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                                param.setResult(wresp);

                                // === 只做 JS 注入 —— 3 次延迟，完全移除 Java 层触摸 ===
                                final Object webViewObj = param.args[0];
                                if (webViewObj != null) {
                                    // 调度防抖：3秒内已经排过任务就不再排（防止多个 API 请求堆积延迟任务）
                                    long now = System.currentTimeMillis();
                                    if (now - sLastScheduledTime < SCHEDULE_DEBOUNCE_MS) return;
                                    sLastScheduledTime = now;

                                    // 2500ms / 3500ms / 4500ms 三次 JS 注入
                                    long[] delays = {2500, 3500, 4500};
                                    for (long delay : delays) {
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-" + delay + "ms]");
                                            }
                                        }, delay);
                                    }
                                }
                            } catch (Throwable t) {
                                // WebView shouldInterceptRequest 异常（已清除日志）
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            // WebView shouldInterceptRequest hook 失败（已清除日志）
        }

        // === 4. 旧版 WebView shouldInterceptRequest(WebView, String) ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String urlStr = (String) param.args[1];
                                if (urlStr == null || !urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    // WebView旧版 请求失败（已清除日志）
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                if (!modified.equals(body)) {

                                }

                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                // WebView旧版 异常（已清除日志）
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // === 5. 腾讯 X5 WebView ===
        try {
            XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebViewClient", cl, "shouldInterceptRequest",
                    "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.export.external.interfaces.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj == null) return;
                                String urlStr = urlObj.toString();
                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    // X5 请求失败（已清除日志）
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);


                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                try {
                                    // 优先用 X5 的 WebResourceResponse
                                    Class<?> x5respCls = XposedHelpers.findClassIfExists(
                                            "com.tencent.smtt.export.external.interfaces.WebResourceResponse", cl);
                                    if (x5respCls != null) {
                                        Object x5resp = x5respCls.getConstructor(String.class, String.class, java.io.InputStream.class)
                                                .newInstance("application/json", "UTF-8", new ByteArrayInputStream(outBytes));
                                        param.setResult(x5resp);
                                        return;
                                    }
                                } catch (Throwable ignored) {}
                                // fallback: 系统类
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                // X5 异常（已清除日志）
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }

    // ============ OkHttp 响应处理 ============
    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            String urlStr = extractOkHttpUrl(response);
            if (urlStr == null) return;
            if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

            targetHitCounter.incrementAndGet();
            updateHitCountInProvider();

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBodyWithStyle(body);
            if (!modified.equals(body)) {
                String contentType = extractOkHttpContentType(response);
                Object newResp = buildOkHttpResponse(response, modified, contentType);
                if (newResp != null) {
                    param.setResult(newResp);

                }
            }
        } catch (Throwable t) {
            // OkHttp处理异常（已清除日志）
        }
    }

    private String extractOkHttpUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return null;
            Object url = XposedHelpers.callMethod(request, "url");
            return url != null ? url.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] extractOkHttpBodyBytes(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            try {
                Object bytes = XposedHelpers.callMethod(body, "bytes");
                if (bytes instanceof byte[]) return (byte[]) bytes;
            } catch (Throwable ignored) {}
            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) return ((String) str).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractOkHttpContentType(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            Object ct = XposedHelpers.callMethod(body, "contentType");
            return ct != null ? ct.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildOkHttpResponse(Object originalResponse, String newBodyStr, String contentType) {
        try {
            // 获取原始 response 的 classloader
            ClassLoader cl = originalResponse.getClass().getClassLoader();
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();

            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", cl);
            if (responseBodyCls == null) return null;

            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);
            Object mediaType = null;
            try {
                if (contentType != null && !contentType.isEmpty()) {
                    Class<?> mtCls = XposedHelpers.findClassIfExists("okhttp3.MediaType", cl);
                    if (mtCls != null) {
                        mediaType = XposedHelpers.callStaticMethod(mtCls, "parse", contentType);
                    }
                }
            } catch (Throwable ignored) {}

            Object newBody = null;
            try {
                newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes);
            } catch (Throwable ignored) {}
            if (newBody == null) {
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, newBodyStr);
                } catch (Throwable ignored) {}
            }
            if (newBody == null) return null;

            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ============ 修改 JSON：在正确选项前加红色标记 ============
    private String modifyAnswerBodyWithStyle(String bodyStr) {
        try {
            JSONObject root = new JSONObject(bodyStr);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return bodyStr;
            JSONArray options = data.optJSONArray("answerOptionList");
            if (options == null || options.length() == 0) return bodyStr;
            boolean changed = false;
            java.util.List<String> correctAnswers = new java.util.ArrayList<String>();
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;
                // 兼容：isRight 可能是 Int(1) 或 Boolean(true)；也可能叫 isCorrect / correct
                boolean isCorrect = false;
                try {
                    // 先试 Int 1
                    if (opt.optInt("isRight") == 1) isCorrect = true;
                    // 再试 Boolean true（optInt对Boolean返回0，所以单独判断）
                    if (!isCorrect) {
                        if (opt.has("isRight")) {
                            Object v = opt.get("isRight");
                            if (Boolean.TRUE.equals(v)) isCorrect = true;
                            else if (Boolean.FALSE.equals(v)) isCorrect = false;
                        }
                    }
                    // 兜底：其他字段名
                    if (!isCorrect) {
                        String[] fields = {"isRight", "isCorrect", "correct", "optionRight", "isAnswer"};
                        for (String field : fields) {
                            if (opt.optBoolean(field)) { isCorrect = true; break; }
                            if (opt.optInt(field) == 1) { isCorrect = true; break; }
                        }
                    }
                } catch (Throwable ignored) {}
                if (isCorrect) {
                    String text = opt.optString("optionText", "");
                    correctAnswers.add(text);
                    opt.put("optionText", "【 " + text + " 正确答案 】");
                    changed = true;
                }
            }
            if (changed && correctAnswers.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < correctAnswers.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(correctAnswers.get(i));
                }
                sCorrectAnswerText = sb.toString();
                sMarkedAnswerText = "【 " + sb.toString() + " 正确答案 】";
                sCorrectAnswerTimestamp = System.currentTimeMillis();
                sAlreadyAutoSelected.set(false);
                writeAnswerToProvider(sCorrectAnswerText, sMarkedAnswerText);
            }
            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
    }

    // ============ 写入答案文本到 ContentProvider ============
    private static void writeAnswerToProvider(String answerText, String markedText) {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return;
            ContentValues values = new ContentValues();
            values.put(KEY_ANSWER_TEXT, answerText != null ? answerText : "");
            values.put(KEY_ANSWER_MARKED, markedText != null ? markedText : "");
            values.put(KEY_ANSWER_TIME, System.currentTimeMillis());
            ctx.getContentResolver().update(PROVIDER_ANSWER_URI, values, null, null);
        } catch (Throwable ignored) {}
    }

    // ============ 从 ContentProvider 读取答案文本（备用）============
    private static String readAnswerFromProvider() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return null;
            Cursor cursor = ctx.getContentResolver().query(PROVIDER_ANSWER_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                try {
                    int idx = cursor.getColumnIndex("value_str");
                    if (idx >= 0) {
                        String v = cursor.getString(idx);
                        if (v != null && !v.isEmpty()) {
                            cursor.close();
                            return v;
                        }
                    }
                } catch (Throwable ignored) {}
                cursor.close();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ============ 工具类 ============
    private static byte[] readAll(java.io.InputStream is) {
        if (is == null) return null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasClass(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============ 自动选中答案：读取开关状态 ============
    private static boolean readAutoSelectEnabled() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return false;

            // 1. 先尝试 ContentProvider 查询
            try {
                Cursor cursor = ctx.getContentResolver().query(PROVIDER_QUERY_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        try {
                            String key = cursor.getString(cursor.getColumnIndex("key"));
                            if (CONFIG_KEY_AUTO_SELECT.equals(key)) {
                                long val = cursor.getLong(cursor.getColumnIndex("value"));
                                String valueStr = cursor.getString(cursor.getColumnIndex("value_str"));
                                cursor.close();
                                return val > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                            }
                        } catch (Throwable ignored) {}
                    } while (cursor.moveToNext());
                    cursor.close();
                }
            } catch (Throwable ignored) {}

            // 2. Fallback：从目标应用 SP 读取
            try {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(
                        CONFIG_SP_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                return sp.getBoolean(CONFIG_KEY_AUTO_SELECT, false);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 自动下一题：读取开关状态 ============
    private static boolean readAutoNextEnabled() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return false;

            // 1. ContentProvider 查询
            try {
                Cursor cursor = ctx.getContentResolver().query(PROVIDER_QUERY_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        try {
                            String key = cursor.getString(cursor.getColumnIndex("key"));
                            if (CONFIG_KEY_AUTO_NEXT.equals(key)) {
                                long val = cursor.getLong(cursor.getColumnIndex("value"));
                                String valueStr = cursor.getString(cursor.getColumnIndex("value_str"));
                                cursor.close();
                                return val > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                            }
                        } catch (Throwable ignored) {}
                    } while (cursor.moveToNext());
                    cursor.close();
                }
            } catch (Throwable ignored) {}

            // 2. Fallback：从目标应用 SP 读取
            try {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(
                        CONFIG_SP_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                return sp.getBoolean(CONFIG_KEY_AUTO_NEXT, false);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 写入日志记录 ============
    private static void writeLog(final String type, final String method, final String detail) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                    if (ctx == null) return;
                    ContentValues values = new ContentValues();
                    values.put("type", type);
                    values.put("method", method);
                    values.put("detail", detail != null ? detail : "");
                    ctx.getContentResolver().insert(PROVIDER_LOG_URI, values);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    // ============ 从 JS 返回值提取 SEL 数量 ============
    private static String extractSelCount(String valStr) {
        try {
            int idx = valStr.indexOf("SEL=");
            if (idx >= 0) {
                String rest = valStr.substring(idx + 4);
                int end = rest.indexOf("|");
                if (end >= 0) rest = rest.substring(0, end);
                return rest.trim();
            }
        } catch (Throwable ignored) {}
        return "1";
    }

    // ============ 安装自动选中 Hook ============
    private void setupAutoSelectHooks(final ClassLoader cl) {
        // === 1. WebChromeClient.onConsoleMessage（新版API）===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onConsoleMessage",
                    "android.webkit.ConsoleMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object msg = param.args[0];
                                if (msg == null) return;
                                XposedHelpers.callMethod(msg, "message");
                                // 已清除日志输出
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 1b. onConsoleMessage(String, int, String)（旧版API）===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onConsoleMessage",
                    String.class, int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object arg = param.args[0];
                                if (arg != null) arg.toString();
                                // 已清除日志输出
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 1c. 腾讯X5内核 onConsoleMessage ===
        try {
            XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebChromeClient", cl, "onConsoleMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                for (Object arg : param.args) {
                                    if (arg != null) arg.toString();
                                    // 已清除日志输出
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 2. WebViewClient.onPageStarted → 页面加载后 2.5s/3.5s/4.5s 注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageStarted",
                    "android.webkit.WebView", String.class, "android.graphics.Bitmap",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                long[] delays = {2500, 3500, 4500};
                                for (long d : delays) {
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            injectJsIntoWebView(webView, "[onPageStarted-" + ((int)d) + "ms]");
                                        }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 3. WebViewClient.onPageFinished → 页面加载完成后 1.5s/2.5s/3.5s 注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                // 调度防抖：3秒内已经排过任务就不再排
                                long now = System.currentTimeMillis();
                                if (now - sLastScheduledTime < SCHEDULE_DEBOUNCE_MS) return;
                                sLastScheduledTime = now;
                                long[] delays = {1500, 2500, 3500};
                                for (long d : delays) {
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            injectJsIntoWebView(webView, "[onPageFinished-" + ((int)d) + "ms]");
                                        }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 4. WebChromeClient.onProgressChanged → 进度变化时（100%时）注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onProgressChanged",
                    "android.webkit.WebView", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                int progress = (Integer) param.args[1];
                                if (progress < 100) return; // 只在100%时注入
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                // 同一个 WebView 只在第一次达到100%时触发
                                int wvId = System.identityHashCode(webView);
                                if (sProgressDoneWebViews.contains(wvId)) return;
                                sProgressDoneWebViews.add(wvId);

                                // 调度防抖：3秒内已经排过任务就不再排
                                long now = System.currentTimeMillis();
                                if (now - sLastScheduledTime < SCHEDULE_DEBOUNCE_MS) return;
                                sLastScheduledTime = now;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        injectJsIntoWebView(webView, "[onProgressChanged-100-" + progress + "%]");
                                    }
                                }, 2000);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 5. Activity onResume → 扫描原生 UI 找正确答案标记并点击（备选） ===
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                if (sAlreadyAutoSelected.get()) return;
                                final Object activityObj = param.thisObject;
                                if (activityObj == null) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            Object window = XposedHelpers.callMethod(activityObj, "getWindow");
                                            if (window == null) return;
                                            Object decorView = XposedHelpers.callMethod(window, "getDecorView");
                                            if (decorView instanceof View) {
                                                clickViewWithMarkerEnhanced((View) decorView, "Activity.onResume");
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 1500);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 6. TextView setText → 检测动态内容含"正确答案"时点击父容器（备选） ===
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                    CharSequence.class, android.widget.TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                if (sAlreadyAutoSelected.get()) return;
                                CharSequence text = (CharSequence) param.args[0];
                                if (text == null || !text.toString().contains(ANSWER_MARKER)) return;

                                final Object tvObj = param.thisObject;
                                if (!(tvObj instanceof View)) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            View cur = (View) tvObj;
                                            for (int j = 0; j < 6; j++) {
                                                if (cur == null) break;
                                                if (cur.isClickable() && cur.isEnabled()) {
                                                    if (performEnhancedClick(cur, "TextView.setText")) return;
                                                }
                                                Object parent = cur.getParent();
                                                if (parent instanceof View) cur = (View) parent; else break;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 800);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ============ 统一的 JS 注入入口（核心：多渠道返回执行结果到LSPosed日志） ============
    private static void injectJsIntoWebView(final Object webViewObj, final String sourceTag) {
        try {
            if (!readAutoSelectEnabled()) return;
            if (webViewObj == null) return;

            // 如果已经成功选中过，就不再重复（防止在选项间乱跳）
            if (sAlreadyAutoSelected.get()) return;

            // 冷却时间检查：上次成功后5秒内不允许再次注入（防止多个延迟任务在不同题目上连续触发）
            long now = System.currentTimeMillis();
            if (sLastSuccessTime > 0 && now - sLastSuccessTime < AUTO_SELECT_COOLDOWN_MS) return;

            String answerText = sCorrectAnswerText;
            if (answerText == null || answerText.isEmpty()) return;

            long age = System.currentTimeMillis() - sCorrectAnswerTimestamp;
            if (sCorrectAnswerTimestamp > 0 && age > 30000) return;

            // 同一个答案只注入一次（防止同一页面多个回调点重复注入）
            int answerHash = answerText.hashCode();
            if (sInjectedAnswers.contains(answerHash)) return;
            sInjectedAnswers.add(answerHash);

            // 构建 JS 参数
            String markerText = sMarkedAnswerText != null ? sMarkedAnswerText : ("【 " + answerText + " 正确答案 】");
            String safeM = escapeJsString(markerText);
            String safeA = escapeJsString(answerText);
            String safeTag = escapeJsString(sourceTag);

            final boolean autoNext = readAutoNextEnabled();
            final String js = buildAutoClickJS2(safeA, safeM, safeTag, autoNext);

            // === 构造 ValueCallback 来捕获 evaluateJavascript 的返回值 ===
            Object callback = null;
            try {
                callback = java.lang.reflect.Proxy.newProxyInstance(
                        android.webkit.ValueCallback.class.getClassLoader(),
                        new Class<?>[]{android.webkit.ValueCallback.class},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                                try {
                                    Object val = args != null && args.length > 0 ? args[0] : null;
                                    String valStr = val == null ? "null" : val.toString();
                                    // 如果 JS 返回值中显示成功选中，立即标记
                                    if (valStr.contains("SUCCESS") || valStr.contains("SEL=1")) {
                                        sAlreadyAutoSelected.set(true);
                                        sLastSuccessTime = System.currentTimeMillis();
                                        // 记录日志：提取 FOUND 信息（选中方法）
                                        String methodInfo = "";
                                        int foundIdx = valStr.indexOf("FOUND=");
                                        if (foundIdx >= 0) {
                                            methodInfo = valStr.substring(foundIdx + 6);
                                            int pipeIdx = methodInfo.indexOf("|");
                                            if (pipeIdx >= 0) methodInfo = methodInfo.substring(0, pipeIdx);
                                        }
                                        writeLog("answer", methodInfo.isEmpty() ? "SUCCESS" : methodInfo,
                                                "SEL=" + (valStr.contains("SEL=") ? extractSelCount(valStr) : "1"));
                                        // 答案选中成功后，触发自动下一题（模块化调用）
                                        if (autoNext) {
                                            triggerAutoNext(webViewObj);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                return null;
                            }
                        });
            } catch (Throwable t) {
                // ValueCallback 创建失败（已清除日志）
            }

            // === 方式1：evaluateJavascript + ValueCallback ===
            boolean injected = false;
            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, callback);
                injected = true;
            } catch (Throwable te) {
                // evaluateJavascript 异常（已清除日志）
            }

            // === 方式2：loadUrl("javascript:") 兜底 ===
            if (!injected) {
                try {
                    XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + js);
                    injected = true;
                } catch (Throwable te) {
                    // loadUrl 异常（已清除日志）
                }
            }
        } catch (Throwable t) {
            // 注入顶级异常（已清除日志）
        }
    }

    // ============ JS 字符串转义（处理所有可能破坏 JS 字符串的字符） ============
    private static String escapeJsString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '<': sb.append("\\u003C"); break;
                case '>': sb.append("\\u003E"); break;
                case '/': sb.append("\\u002F"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ============ 构建自动选中 JS（v8 — try块内返回明确的 SUCCESS/FAIL 字符串 + 自动下一题）============
    private static String buildAutoClickJS2(String safeA, String safeM, String safeTag, final boolean autoNext) {
        StringBuilder sb = new StringBuilder();
        // 整体 try-catch，确保任何情况都有返回值
        sb.append("(function(){try{");
        // TAG: Java传进来的注入点标识，如 [onPageFinished-1500ms] 或 [shouldInterceptRequest-2500ms]
        sb.append("var TAG='").append(safeTag).append("';");
        sb.append("var AT='").append(safeA).append("';");
        sb.append("var D=document;var SEL=0;var FOUND='';");
        // IS_MULTI: AT 含"、"分隔符 → 多选题, 否则单选题走原逻辑
        sb.append("var IS_MULTI=(AT.indexOf('、')>=0);");
        // SEL_SET: 已点击元素的集合，用于去重（避免多策略重复点击同一元素）
        sb.append("var SEL_SET=[];");

        // l(msg)：空函数（已清除日志输出）
        sb.append("function l(m){}");

        // dc(el)：终极点击。分类型处理：
        //   - INPUT: 直接 checked=true + change 事件（不再 click，避免 toggle 回 false）
        //   - LABEL: 找关联 input，直接设 checked=true（不依赖 label.click() 的浏览器实现）
        //   - BUTTON/A/其他: click + dispatchEvent
        sb.append("function dc(el){if((!IS_MULTI&&SEL>0)||!el||SEL_SET.indexOf(el)>=0)return;SEL_SET.push(el);");
        sb.append("FOUND=TAG+' tag='+el.tagName+' cls='+(el.className||'')+' txt='+(el.innerText||el.value||'').toString().substring(0,30);");
        sb.append("l('★CLICK! '+FOUND);");
        sb.append("var tn=(el.tagName||'').toUpperCase();");
        // 1) INPUT(radio/checkbox): 只 checked=true + dispatch change，不再 click()
        sb.append("if(tn==='INPUT'){try{el.checked=true;el.setAttribute('checked','checked');}catch(e){}");
        sb.append("try{var cevt;try{cevt=new Event('change',{bubbles:true,cancelable:true});}catch(e2){cevt=D.createEvent('HTMLEvents');cevt.initEvent('change',true,true);}el.dispatchEvent(cevt);}catch(e){}");
        sb.append("SEL++;l('DC:INPUT:checked SEL='+SEL);return;}");
        // 2) LABEL: 找关联 input（label.for/id 或 label 内嵌套 input），直接设 checked=true
        sb.append("if(tn==='LABEL'){var inp=null;var lf=el.getAttribute?el.getAttribute('for'):null;");
        sb.append("if(lf){inp=D.getElementById(lf);}if(!inp&&el.querySelector){inp=el.querySelector('input');}if(inp){l('DC:LABEL→input '+inp.tagName+' id='+lf);try{inp.checked=true;inp.setAttribute('checked','checked');}catch(e){}try{var c2;try{c2=new Event('change',{bubbles:true,cancelable:true});}catch(e2){c2=D.createEvent('HTMLEvents');c2.initEvent('change',true,true);}inp.dispatchEvent(c2);}catch(e){}SEL++;l('DC:LABEL:checked SEL='+SEL);return;}else{l('DC:LABEL无input,尝试click');try{el.click();}catch(e){}l('DC:LABEL:click fallback');}return;}");
        // 3) BUTTON/A/其他: click + dispatchEvent
        sb.append("try{if(el.click)el.click();}catch(e){}");
        sb.append("try{var evs=['click','mousedown','mouseup','change','input'];for(var vi=0;vi<evs.length;vi++){try{var evt;if(evs[vi]==='click'||evs[vi].indexOf('mouse')>=0){evt=new MouseEvent(evs[vi],{bubbles:true,cancelable:true,view:window,button:0});}else{evt=D.createEvent('HTMLEvents');evt.initEvent(evs[vi],true,true);}el.dispatchEvent(evt);}catch(e){}}}catch(e){}");
        sb.append("SEL++;l('DC:done SEL='+SEL);}");

        // fc(el)：从元素向上找可点击的元素。使用 closest() API 精确查找。
        // 找最近的 LABEL (含关联input) 或 INPUT → dc() 点击
        sb.append("function fc(el){if(!IS_MULTI&&SEL>0)return;l('FC:from '+el.tagName);");
        // ============= 多选题专用（不影响单选题）=============
        // 在 el 内部/兄弟节点/祖先链 中找 checkbox-like 元素，checked=true，不 click（避免 toggle）
        sb.append("if(IS_MULTI){var qm='input[type=checkbox],input[type=radio],*[class*=check],*[class*=radio],*[role=checkbox],*[role=radio]';");
        // 1) el 内部找
        sb.append("var mf=null;try{mf=el.querySelector?el.querySelector(qm):null;}catch(e){}");
        // 2) el 的兄弟节点中找（常见：文本和 checkbox 是兄弟）
        sb.append("if(!mf){var pr=el.parentElement;if(pr&&pr.children){for(var bi=0;bi<pr.children.length;bi++){var s=pr.children[bi];if(s===el)continue;try{var sf=s.querySelector?s.querySelector(qm):null;if(sf){mf=sf;break;}}catch(e){}try{var sc=(s.className||'').toString();if(sc.indexOf('check')>=0||sc.indexOf('radio')>=0){mf=s;break;}}catch(e){}}}}");
        // 3) 往上 5 层祖先链，每层内部搜
        sb.append("if(!mf){var anc=el.parentElement;for(var lv=0;lv<5&&anc;lv++){if(anc.children){for(var ci=0;ci<anc.children.length;ci++){try{var caf=anc.children[ci].querySelector(qm);if(caf){mf=caf;break;}}catch(e){}}}if(mf)break;anc=anc.parentElement;}}");
        // 4) 找到后设 checked=true
        sb.append("if(mf){l('FC:MULTI 找到checkbox:'+mf.tagName);");
        sb.append("if(mf.tagName&&mf.tagName.toUpperCase()==='INPUT'){try{mf.checked=true;mf.setAttribute('checked','checked');}catch(e){}try{var cme;try{cme=new Event('change',{bubbles:true,cancelable:true});}catch(e2){cme=D.createEvent('HTMLEvents');cme.initEvent('change',true,true);}mf.dispatchEvent(cme);}catch(e){}SEL++;SEL_SET.push(mf);return;}");
        // 自定义组件：先在内部找 input，找不到才 click
        sb.append("var minp=null;try{minp=mf.querySelector?mf.querySelector('input[type=checkbox],input[type=radio]'):null;}catch(e){}if(minp){try{minp.checked=true;minp.setAttribute('checked','checked');}catch(e){}try{var cme2;try{cme2=new Event('change',{bubbles:true,cancelable:true});}catch(e2){cme2=D.createEvent('HTMLEvents');cme2.initEvent('change',true,true);}minp.dispatchEvent(cme2);}catch(e){}SEL++;SEL_SET.push(minp);return;}");
        sb.append("try{if(mf.click)mf.click();}catch(e){}try{var cme3;try{cme3=new Event('change',{bubbles:true,cancelable:true});}catch(e2){cme3=D.createEvent('HTMLEvents');cme3.initEvent('change',true,true);}mf.dispatchEvent(cme3);}catch(e){}SEL++;SEL_SET.push(mf);return;}");
        sb.append("l('FC:MULTI 未找到checkbox, fallback click el');try{if(el.click)el.click();}catch(e){}SEL++;SEL_SET.push(el);return;}");
        // ============= 原逻辑（IS_MULTI=false，单选/判断题完全不动）=============
        // 优先找最近的 LABEL
        sb.append("var lb=el.closest?el.closest('label'):null;");
        sb.append("if(lb){l('FC:LABEL→'+lb.tagName);dc(lb);return;}");
        // 再找最近的 INPUT
        sb.append("var inp=el.closest?el.closest('input,button,a,select,textarea'):null;");
        sb.append("if(inp){l('FC:INPUT→'+inp.tagName);dc(inp);return;}");
        // 兜底：找含onclick属性的元素
        sb.append("var cur=el.parentElement;while(cur&&cur.tagName&&cur.tagName!=='BODY'&&cur.tagName!=='HTML'){if(cur.getAttribute&&cur.getAttribute('onclick')){l('FC:onclick→'+cur.tagName);dc(cur);return;}cur=cur.parentElement;}");
        sb.append("l('FC:fallback dc(el)');dc(el);}");

        // 策略1：TreeWalker 找"正确答案"标记
        sb.append("l('v8 start AT='+AT+' MULTI='+IS_MULTI);");
        sb.append("if(!D.body){l('body null');}else{");
        sb.append("var tw=D.createTreeWalker(D.body,NodeFilter.SHOW_TEXT,null,false);");
        // 收集所有含"正确答案"的节点,循环每个都调用fc();单选题时IS_MULTI=false,fc()内部dc()只点一个后return
        sb.append("var node, mts=[];while(node=tw.nextNode()){if(node.nodeValue&&node.nodeValue.indexOf('正确答案')>=0){var p=node.parentElement;if(p){mts.push(p);}}}");
        sb.append("for(var mi=0;mi<mts.length;mi++){try{fc(mts[mi]);}catch(e){}if(!IS_MULTI&&SEL>0)break;}");
        sb.append("if(mts.length===0){l('策略1:未找到');}");

        // 策略2：querySelectorAll 兜底
        sb.append("if(IS_MULTI?true:SEL===0){var all=D.body.querySelectorAll('*');l('策略2:scan '+all.length);");
        sb.append("for(var qi=0;qi<all.length;qi++){var txt3='';try{txt3=(all[qi].innerText||all[qi].textContent||'').toString();}catch(e){}");
        sb.append("if(txt3&&txt3.indexOf('正确答案')>=0){l('策略2:找到 '+all[qi].tagName);fc(all[qi]);if(!IS_MULTI&&SEL>0)break;}}}");

        // 策略3：按原始答案文本搜索（多选题时按"、"拆分关键词逐个搜索）
        sb.append("if((IS_MULTI?true:SEL===0)&&AT){l('策略3:按文本搜索 '+AT);");
        sb.append("var kwArr=IS_MULTI?AT.split('、'):[AT];");
        sb.append("var all3=D.body.querySelectorAll('label,div,span,li,p,input,button');");
        sb.append("for(var ki=0;ki<kwArr.length;ki++){if(!IS_MULTI&&SEL>0)break;var kw=String(kwArr[ki]||'').trim();if(!kw)continue;");
        sb.append("for(var si=0;si<all3.length;si++){var t3='';try{t3=(all3[si].innerText||all3[si].textContent||all3[si].value||'').toString();}catch(e){}if(t3&&t3.length<200&&t3.indexOf(kw)>=0){l('策略3:找到 '+all3[si].tagName+' k='+kw.substring(0,20));fc(all3[si]);if(!IS_MULTI&&SEL>0)break;}}}");
        sb.append("}");
        sb.append("}"); // end if D.body

        // 策略4：MutationObserver（延迟执行的兜底；多选题时保持持续观察10秒,单选题首次成功即断开）
        sb.append("if((IS_MULTI?true:SEL===0)&&window.MutationObserver){l('策略4:观察DOM MULTI='+IS_MULTI);try{var obs=new MutationObserver(function(){var ns=D.body.querySelectorAll('label,div,span,li,input,button');for(var oi=0;oi<ns.length;oi++){var t4='';try{t4=(ns[oi].innerText||ns[oi].textContent||'').toString();}catch(e){}if(t4&&t4.indexOf('正确答案')>=0){fc(ns[oi]);}}if(!IS_MULTI&&SEL>0){obs.disconnect();}});obs.observe(D.body||D.documentElement,{childList:true,subtree:true,characterData:true});setTimeout(function(){try{obs.disconnect();}catch(e){}},10000);}catch(e){l('策略4失败:'+e.message);}}");

        sb.append("l('v8 done SEL='+SEL);");

        // === 答案选中验证：如果页面有radio/checkbox但都没选中，说明点击没生效 ===
        // 防止误判导致提前触发自动下一题
        sb.append("if(SEL>0){try{");
        sb.append("var inputs=D.body.querySelectorAll('input[type=radio],input[type=checkbox]');");
        sb.append("if(inputs&&inputs.length>0){var hasChecked=false;");
        sb.append("for(var vi=0;vi<inputs.length;vi++){if(inputs[vi].checked){hasChecked=true;break;}}");
        sb.append("if(!hasChecked){SEL=0;FOUND='VERIFY_FAIL_NO_CHECKED';}");
        sb.append("}}catch(e){}");
        // 额外验证：检查是否有 aria-checked=true 的自定义组件
        sb.append("try{");
        sb.append("var ariaChecks=D.body.querySelectorAll('[role=radio],[role=checkbox]');");
        sb.append("if(ariaChecks&&ariaChecks.length>0&&SEL===0){var ariaOk=false;");
        sb.append("for(var ai=0;ai<ariaChecks.length;ai++){var ac=ariaChecks[ai].getAttribute&&ariaChecks[ai].getAttribute('aria-checked');");
        sb.append("if(ac==='true'||ac==='mixed'){ariaOk=true;break;}}");
        sb.append("if(!ariaOk){SEL=0;}");
        sb.append("}}catch(e){}");
        sb.append("}");

        // === 关键：在 try 块内返回明确格式的字符串！===
        // 这是 evaluateJavascript 的 ValueCallback 会捕获的值
        sb.append("return(SEL>0?'[ANSWER]SUCCESS|'+TAG+'|SEL='+SEL+'|FOUND='+FOUND:'[ANSWER]FAIL|'+TAG+'|SEL=0');");

        // 捕获异常后也返回字符串（失败情况）
        sb.append("}catch(e){try{document.title='[ERR]'+TAG+':'+e.message;}catch(e2){}");
        sb.append("return '[ANSWER]EXCEPTION|'+TAG+'|err='+e.message;");
        sb.append("}})();");
        return sb.toString();
    }

    // ============ 自动下一题：触发入口（模块化，独立调用） ============
    // 答案选中成功后调用，延迟 800ms 后开始查找并点击下一题按钮
    private static void triggerAutoNext(final Object webViewObj) {
        if (webViewObj == null) return;
        try {
            // 记录日志：自动下一题触发
            writeLog("next", "VALUE_CALLBACK", "答案选中后延迟800ms触发");
            // 延迟 800ms，确保答案点击动画完成后再点下一题
            final String nextJs = buildAutoNextJS(800);

            // evaluateJavascript 注入
            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", nextJs, null);
            } catch (Throwable te) {
                // 兜底：loadUrl
                try {
                    XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + nextJs);
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
    }

    // ============ 自动下一题：模块化 JS 构建器 ============
    // 独立模块，不影响自动选中答案功能
    // 使用 window.__AR_NEXT_CLICKED 全局变量防止重复触发
    private static String buildAutoNextJS(long delayMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){try{");
        sb.append("var TAG='AUTO_NEXT';");
        sb.append("var D=document;");
        sb.append("var DELAY=").append(delayMs).append(";");

        // 检查是否已触发过（全局变量防重复）
        sb.append("if(window.__AR_NEXT_CLICKED){return '[NEXT]ALREADY_CLICKED';}");

        // 核心点击函数：对找到的元素执行点击
        sb.append("function doClick(el){");
        sb.append("if(!el)return false;");
        sb.append("window.__AR_NEXT_CLICKED=true;");
        // 尝试多种点击方式
        sb.append("try{if(el.click)el.click();}catch(e){}");
        sb.append("try{var evs=['click','mousedown','mouseup','touchstart','touchend'];for(var vi=0;vi<evs.length;vi++){try{var evt;if(evs[vi].indexOf('mouse')>=0||evs[vi]==='click'){evt=new MouseEvent(evs[vi],{bubbles:true,cancelable:true,view:window,button:0});}else if(evs[vi].indexOf('touch')>=0){evt=new TouchEvent(evs[vi],{bubbles:true,cancelable:true});}else{evt=D.createEvent('HTMLEvents');evt.initEvent(evs[vi],true,true);}el.dispatchEvent(evt);}catch(e){}}}catch(e){}");
        // 点击成功后立即重置标志，确保下一题能继续触发
        sb.append("setTimeout(function(){window.__AR_NEXT_CLICKED=false;},500);");
        sb.append("return true;}");

        // 策略1：按文本精确匹配搜索按钮
        sb.append("function findByText(){");
        sb.append("var keywords=['下一题','下一个','下一页','继续','提交','下道题','下一关','Next','NEXT','next','Continue','continue'];");
        sb.append("var candidates=D.body.querySelectorAll('button,a,div,span,li,p,label,input[type=button],input[type=submit]');");
        sb.append("for(var i=0;i<candidates.length;i++){");
        sb.append("var el=candidates[i];");
        sb.append("var txt='';try{txt=(el.innerText||el.textContent||el.value||'').toString().trim();}catch(e){}");
        sb.append("if(!txt||txt.length>20)continue;");
        sb.append("for(var ki=0;ki<keywords.length;ki++){");
        sb.append("if(txt===keywords[ki]){return el;}");
        sb.append("}");
        sb.append("}");
        sb.append("return null;}");

        // 策略2：按 class/id 属性关键词搜索
        sb.append("function findByAttr(){");
        sb.append("var attrs=['next-btn','nextButton','next_button','btn-next','btnNext','next-btn','submit-btn','submitButton','submit_button','btn-submit','btnSubmit','下一题','next'];");
        sb.append("var all=D.body.querySelectorAll('*');");
        sb.append("for(var i=0;i<all.length;i++){");
        sb.append("var el=all[i];");
        sb.append("var cls='',id='';");
        sb.append("try{cls=(el.className||'').toString();}catch(e){}");
        sb.append("try{id=(el.id||'').toString();}catch(e){}");
        sb.append("if(!cls&&!id)continue;");
        sb.append("for(var ai=0;ai<attrs.length;ai++){");
        sb.append("if(cls.indexOf(attrs[ai])>=0||id.indexOf(attrs[ai])>=0){return el;}");
        sb.append("}");
        sb.append("}");
        sb.append("return null;}");

        // 策略3：找包含"下一题"文本的任意元素（模糊匹配）
        sb.append("function findByFuzzyText(){");
        sb.append("var all=D.body.querySelectorAll('button,a,div,span,li');");
        sb.append("for(var i=0;i<all.length;i++){");
        sb.append("var txt='';try{txt=(all[i].innerText||all[i].textContent||'').toString().trim();}catch(e){}");
        sb.append("if(txt&&txt.length<=10&&(txt.indexOf('下一题')>=0||txt.indexOf('下一个')>=0||txt.indexOf('继续')>=0)){return all[i];}");
        sb.append("}");
        sb.append("return null;}");

        // 策略4：从可点击元素向上找（针对 uni-app 自定义组件）
        sb.append("function findByParent(){");
        sb.append("var all=D.body.querySelectorAll('*');");
        sb.append("for(var i=0;i<all.length;i++){");
        sb.append("var txt='';try{txt=(all[i].innerText||all[i].textContent||'').toString().trim();}catch(e){}");
        sb.append("if(txt==='下一题'||txt==='下一个'||txt==='继续'){");
        sb.append("var cur=all[i];for(var lv=0;lv<5&&cur;lv++){");
        sb.append("try{if(cur.click&&cur.tagName!=='BODY'&&cur.tagName!=='HTML'){return cur;}}catch(e){}");
        sb.append("cur=cur.parentElement;");
        sb.append("}");
        sb.append("return all[i];");
        sb.append("}");
        sb.append("}");
        sb.append("return null;}");

        // 执行查找并点击（先验证答案已选中，防止误触发）
        sb.append("function tryClick(){");
        sb.append("if(window.__AR_NEXT_CLICKED)return;");
        // 验证答案是否已选中：检查 radio/checkbox 的 checked 状态
        sb.append("var ansChecked=false;try{");
        sb.append("var inputs=D.body.querySelectorAll('input[type=radio],input[type=checkbox]');");
        sb.append("if(inputs&&inputs.length>0){for(var ii=0;ii<inputs.length;ii++){if(inputs[ii].checked){ansChecked=true;break;}}}");
        // 额外检查 aria-checked 自定义组件
        sb.append("if(!ansChecked){var acs=D.body.querySelectorAll('[role=radio],[role=checkbox]');");
        sb.append("if(acs&&acs.length>0){for(var ai=0;ai<acs.length;ai++){var av=acs[ai].getAttribute&&acs[ai].getAttribute('aria-checked');");
        sb.append("if(av==='true'||av==='mixed'){ansChecked=true;break;}}}");
        sb.append("}}catch(e){}");
        // 如果有选项但都没选中，说明答案还没选好，不触发下一题
        sb.append("var hasOptions=false;try{");
        sb.append("hasOptions=(D.body.querySelectorAll('input[type=radio],input[type=checkbox],[role=radio],[role=checkbox]').length>0);");
        sb.append("}catch(e){}");
        sb.append("if(hasOptions&&!ansChecked){return false;}");
        sb.append("var el=null;");
        sb.append("if(!el)el=findByText();");
        sb.append("if(!el)el=findByAttr();");
        sb.append("if(!el)el=findByFuzzyText();");
        sb.append("if(!el)el=findByParent();");
        sb.append("if(el){if(doClick(el)){return true;}}");
        sb.append("return false;}");

        // 主逻辑：延迟执行 + 多次重试
        sb.append("var retries=0;");
        sb.append("function retryLoop(){");
        sb.append("if(tryClick()){return;}");
        sb.append("retries++;");
        sb.append("if(retries<10){setTimeout(retryLoop,300);}");
        sb.append("}");
        sb.append("setTimeout(retryLoop,DELAY);");

        // MutationObserver 兜底：持续观察直到找到按钮
        sb.append("if(window.MutationObserver){");
        sb.append("try{var obs=new MutationObserver(function(){");
        sb.append("if(window.__AR_NEXT_CLICKED){obs.disconnect();return;}");
        sb.append("tryClick();");
        sb.append("if(window.__AR_NEXT_CLICKED){obs.disconnect();}");
        sb.append("});obs.observe(D.body||D.documentElement,{childList:true,subtree:true});");
        sb.append("setTimeout(function(){try{obs.disconnect();}catch(e){}},15000);}catch(e){}");
        sb.append("}");

        sb.append("return '[NEXT]SCHEDULED|delay='+DELAY;");
        sb.append("}catch(e){return '[NEXT]ERROR|'+e.message;}");
        sb.append("})();");
        return sb.toString();
    }

    // ============ 增强版点击：多种方式尝试 ============
    private static boolean performEnhancedClick(View view, String source) {
        if (view == null) return false;
        try {
            // 方式1: performClick
            try {
                if (view.performClick()) {

                    return true;
                }
            } catch (Throwable ignored) {}

            // 方式2: 反射调用 dispatchTouchEvent
            try {
                Class<?> motionEventCls = Class.forName("android.view.MotionEvent");
                Object downEvent = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                        System.currentTimeMillis(), System.currentTimeMillis(), 0,
                        (float) view.getWidth() / 2, (float) view.getHeight() / 2, 0);
                Object upEvent = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                        System.currentTimeMillis(), System.currentTimeMillis(), 1,
                        (float) view.getWidth() / 2, (float) view.getHeight() / 2, 0);
                XposedHelpers.callMethod(view, "dispatchTouchEvent", downEvent);
                XposedHelpers.callMethod(view, "dispatchTouchEvent", upEvent);

                return true;
            } catch (Throwable ignored) {}

            // 方式3: 反射调用 setPressed + invalidate
            try {
                view.setPressed(true);
                view.invalidate();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        view.setPressed(false);
                        view.invalidate();
                    }
                }, 100);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 递归扫描视图树，点击含"正确答案"标记的元素（增强版） ============
    private static boolean clickViewWithMarkerEnhanced(View view, String source) {
        if (view == null) return false;
        try {
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                CharSequence text = tv.getText();
                if (text != null && text.toString().contains(ANSWER_MARKER)) {
                    View cur = tv;
                    for (int i = 0; i < 8; i++) {
                        if (cur == null) break;
                        if (cur.isClickable() && cur.isEnabled()) {
                            if (performEnhancedClick(cur, source)) {
                                return true;
                            }
                        }
                        Object parent = cur.getParent();
                        if (parent instanceof View) {
                            cur = (View) parent;
                        } else {
                            break;
                        }
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (clickViewWithMarkerEnhanced(child, source)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 写入 ContentProvider ============
    private static void writeStatsToProvider(Uri uri) {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return;

            ContentValues values = new ContentValues();
            values.put("last_hook_time", System.currentTimeMillis());
            values.put("package_hooked", TARGET_PACKAGE);
            values.put("target_hit_count", targetHitCounter.get());

            // 跨进程 ContentProvider 写入偶尔会失败，最多重试 3 次
            boolean success = false;
            for (int attempt = 0; attempt < 3 && !success; attempt++) {
                try {
                    ctx.getContentResolver().update(uri, values, null, null);
                    success = true;
                } catch (Throwable t) {
                    try { Thread.sleep(200); } catch (Throwable ignored2) {}
                    if (attempt == 2) {
                        throw t;
                    }
                }
            }
        } catch (Throwable t) {
            // 若 ContentProvider 失败，写入目标应用自己的 SP 作为备用
            try {
                Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                if (ctx != null) {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences("answer_revealer_status",
                            Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                    sp.edit()
                            .putLong("last_hook_time", System.currentTimeMillis())
                            .putInt("target_hit_count", targetHitCounter.get())
                            .apply();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void updateHitCountInProvider() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                    if (ctx == null) return;
                    ContentValues values = new ContentValues();
                    values.put("target_hit_count", targetHitCounter.get());
                    values.put("last_hook_time", System.currentTimeMillis());

                    // 重试 2 次提高成功率
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            ctx.getContentResolver().update(PROVIDER_URI, values, null, null);
                            return;
                        } catch (Throwable ignored) {
                            try { Thread.sleep(150); } catch (Throwable ignored2) {}
                        }
                    }

                    // ContentProvider 失败 → fallback 写入目标应用自己的 SP
                    try {
                        android.content.SharedPreferences sp = ctx.getSharedPreferences(
                                "answer_revealer_status",
                                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                        sp.edit()
                                .putInt("target_hit_count", targetHitCounter.get())
                                .putLong("last_hook_time", System.currentTimeMillis())
                                .apply();
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        }).start();
    }
}

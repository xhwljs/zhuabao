package com.answer.revealer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String TARGET_PATH = "/edu-core-server/app/exam/getQuestion";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";
    private static final String SP_KEY_ACTIVE = "module_active_v1";
    private static final String SP_KEY_LAST = "last_intercept_time";

    private static volatile Context appContext;
    private static ClassLoader targetClassLoader;
    private static volatile boolean hooksInstalled = false;
    private static final ConcurrentHashMap<String, String> requestUrlMap = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;

        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                sp.edit().putBoolean(SP_KEY_ACTIVE, true).commit();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            return;
        }

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        if (hooksInstalled) return;

        targetClassLoader = lpparam.classLoader;

        XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        appContext = (Context) param.thisObject;
                        try {
                            SharedPreferences sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                            sp.edit().putBoolean(SP_KEY_ACTIVE, true)
                                    .putLong(SP_KEY_LAST, System.currentTimeMillis()).commit();
                        } catch (Throwable ignored) {
                        }
                        showModuleStartedToast(appContext);
                    }
                }
        );

        // ========== 核心 Hook：尝试覆盖所有 HTTP 客户端 ==========

        // 1. OkHttp: okhttp3.RealCall.getResponseWithInterceptorChain()
        hookOkHttpRealCall(lpparam.classLoader);

        // 2. OkHttp: okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()
        hookOkHttpInternalRealCall(lpparam.classLoader);

        // 3. OkHttp: okhttp3.Call.execute()
        hookOkHttpCallExecute(lpparam.classLoader);

        // 4. OkHttp: okhttp3.Callback.onResponse()
        hookOkHttpCallback(lpparam.classLoader);

        // 5. OkHttp: okhttp3.OkHttpClient.newCall() - 记录请求 URL
        hookOkHttpNewCall(lpparam.classLoader);

        // 6. OkHttp: okhttp3.Dispatcher.enqueue() - 拦截异步请求
        hookOkHttpDispatcherEnqueue(lpparam.classLoader);

        // 7. OkHttp: okhttp3.Dispatcher.execute() - 拦截同步请求
        hookOkHttpDispatcherExecute(lpparam.classLoader);

        // 8. HttpURLConnection: getInputStream() - Android 原生 HTTP
        hookHttpURLConnectionGetInputStream(lpparam.classLoader);

        // 9. HttpURLConnection: getResponseCode() - 获取响应码
        hookHttpURLConnectionResponseCode(lpparam.classLoader);

        // 10. HttpClient: org.apache.http.HttpClient.execute()
        hookApacheHttpClientExecute(lpparam.classLoader);

        // 11. OkHttp 拦截器链: 尝试 hook Interceptor.intercept()
        hookOkHttpInterceptor(lpparam.classLoader);

        // 12. 尝试 hook 所有含 "execute" 的方法（兜底）
        hookAllExecuteMethods(lpparam.classLoader);

        hooksInstalled = true;

        showToast("已安装 " + getHookCount() + " 个 Hook 入口");
    }

    private int hookCount = 0;

    private void recordHook(String name) {
        hookCount++;
        showToast("Hook 安装成功: " + name);
    }

    private int getHookCount() {
        return hookCount;
    }

    private void showToast(final String message) {
        if (appContext == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void hookSelf(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PACKAGE + ".MainActivity",
                    cl,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== OkHttp Hook 方法 ==========

    private void hookOkHttpRealCall(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.RealCall",
                    cl,
                    "getResponseWithInterceptorChain",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processResponse(response, param, cl);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.RealCall");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpInternalRealCall(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.internal.connection.RealCall",
                    cl,
                    "getResponseWithInterceptorChain",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processResponse(response, param, cl);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.internal.connection.RealCall");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpCallExecute(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Call",
                    cl,
                    "execute",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processResponse(response, param, cl);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.Call.execute()");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpCallback(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Callback",
                    cl,
                    "onResponse",
                    "okhttp3.Call",
                    "okhttp3.Response",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.args[1];
                                if (response != null) {
                                    showToast("检测到 OkHttp 回调响应");
                                    processResponseCallback(response, param, cl);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.Callback.onResponse()");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpNewCall(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.OkHttpClient",
                    cl,
                    "newCall",
                    "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object request = param.args[0];
                                if (request != null) {
                                    Object url = XposedHelpers.callMethod(request, "url");
                                    if (url != null) {
                                        String urlStr = url.toString();
                                        showToast("请求: " + urlStr.substring(0, Math.min(urlStr.length(), 50)));
                                        requestUrlMap.put(String.valueOf(request.hashCode()), urlStr);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.OkHttpClient.newCall()");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpDispatcherEnqueue(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Dispatcher",
                    cl,
                    "enqueue",
                    "okhttp3.Dispatcher$AsyncCall",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object asyncCall = param.args[0];
                                Object call = XposedHelpers.getObjectField(asyncCall, "call");
                                Object request = XposedHelpers.callMethod(call, "request");
                                Object url = XposedHelpers.callMethod(request, "url");
                                if (url != null) {
                                    showToast("异步请求: " + url.toString().substring(0, 50));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.Dispatcher.enqueue()");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpDispatcherExecute(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Dispatcher",
                    cl,
                    "execute",
                    "okhttp3.Call",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object call = param.args[0];
                                Object request = XposedHelpers.callMethod(call, "request");
                                Object url = XposedHelpers.callMethod(request, "url");
                                if (url != null) {
                                    showToast("同步请求: " + url.toString().substring(0, 50));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.Dispatcher.execute()");
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpInterceptor(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Interceptor$Chain",
                    cl,
                    "proceed",
                    "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processResponse(response, param, cl);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("okhttp3.Interceptor.Chain.proceed()");
        } catch (Throwable ignored) {
        }
    }

    // ========== HttpURLConnection Hook ==========

    private void hookHttpURLConnectionGetInputStream(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection",
                    cl,
                    "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object urlObj = XposedHelpers.getObjectField(conn, "url");
                                String urlStr = urlObj != null ? urlObj.toString() : "";

                                if (!urlStr.contains(TARGET_PATH)) return;

                                InputStream is = (InputStream) param.getResult();
                                if (is == null) return;

                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buffer = new byte[4096];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    baos.write(buffer, 0, len);
                                }
                                is.close();

                                String bodyStr = baos.toString("UTF-8");
                                String modified = modifyAnswerBody(bodyStr);

                                if (!modified.equals(bodyStr)) {
                                    showDialog(urlStr, bodyStr, modified);
                                    InputStream newIs = new java.io.ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8));
                                    param.setResult(newIs);
                                } else {
                                    showDialog(urlStr, bodyStr, bodyStr);
                                    InputStream newIs = new java.io.ByteArrayInputStream(bodyStr.getBytes(StandardCharsets.UTF_8));
                                    param.setResult(newIs);
                                }
                            } catch (Throwable t) {
                                showToast("HttpURLConnection 处理失败: " + t.getMessage());
                            }
                        }
                    }
            );
            recordHook("HttpURLConnection.getInputStream()");
        } catch (Throwable ignored) {
        }
    }

    private void hookHttpURLConnectionResponseCode(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection",
                    cl,
                    "getResponseCode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object urlObj = XposedHelpers.getObjectField(conn, "url");
                                String urlStr = urlObj != null ? urlObj.toString() : "";
                                if (urlStr.contains(TARGET_PATH)) {
                                    showToast("检测到目标接口请求: " + urlStr.substring(0, 50));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("HttpURLConnection.getResponseCode()");
        } catch (Throwable ignored) {
        }
    }

    // ========== Apache HttpClient Hook ==========

    private void hookApacheHttpClientExecute(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "org.apache.http.client.HttpClient",
                    cl,
                    "execute",
                    "org.apache.http.HttpRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    Object entity = XposedHelpers.callMethod(response, "getEntity");
                                    if (entity != null) {
                                        InputStream is = (InputStream) XposedHelpers.callMethod(entity, "getContent");
                                        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                                        StringBuilder sb = new StringBuilder();
                                        String line;
                                        while ((line = reader.readLine()) != null) {
                                            sb.append(line);
                                        }
                                        reader.close();
                                        String bodyStr = sb.toString();

                                        Object request = param.args[0];
                                        Object uri = XposedHelpers.callMethod(request, "getRequestLine");
                                        String urlStr = uri != null ? uri.toString() : "";

                                        if (urlStr.contains(TARGET_PATH)) {
                                            String modified = modifyAnswerBody(bodyStr);
                                            if (!modified.equals(bodyStr)) {
                                                showDialog(urlStr, bodyStr, modified);
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            recordHook("Apache HttpClient.execute()");
        } catch (Throwable ignored) {
        }
    }

    // ========== 兜底 Hook：尝试 hook 所有含 execute 的方法 ==========

    private void hookAllExecuteMethods(ClassLoader cl) {
        try {
            // 通过反射查找所有可能的 Call 类
            Class<?> callCls = XposedHelpers.findClassIfExists("okhttp3.Call", cl);
            if (callCls != null) {
                XposedHelpers.findAndHookMethod(
                        callCls,
                        "execute",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) {
                                        processResponse(response, param, cl);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                );
                recordHook("okhttp3.Call (类对象)");
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== 响应处理方法 ==========

    private void processResponse(Object response, XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        try {
            String urlStr = extractUrl(response);
            if (urlStr == null) {
                showToast("extractUrl 返回 null");
                return;
            }

            showToast("响应 URL: " + urlStr.substring(0, Math.min(urlStr.length(), 50)));

            if (!urlStr.contains(TARGET_PATH)) {
                return;
            }

            showToast("=== 检测到目标接口！ ===");

            byte[] bodyBytes = extractBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) {
                showToast("bodyBytes 为空");
                return;
            }

            String contentType = extractContentType(response);
            String original = new String(bodyBytes, StandardCharsets.UTF_8);

            showToast("响应长度: " + bodyBytes.length + " 字节");

            String modified = modifyAnswerBody(original);

            if (!modified.equals(original)) {
                showToast("答案已修改");
                Object newResponse = buildNewResponse(response, modified, contentType, cl);
                if (newResponse != null) {
                    param.setResult(newResponse);
                    showDialog(urlStr, original, modified);
                    return;
                } else {
                    showToast("buildNewResponse 返回 null");
                }
            }
            showDialog(urlStr, original, original);
        } catch (Throwable t) {
            showToast("processResponse 异常: " + t.getMessage());
        }
    }

    private void processResponseCallback(Object response, XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        try {
            String urlStr = extractUrl(response);
            if (urlStr == null || !urlStr.contains(TARGET_PATH)) return;

            showToast("Callback 检测到目标接口");

            byte[] bodyBytes = extractBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String original = new String(bodyBytes, StandardCharsets.UTF_8);
            showDialog(urlStr, original, original);
        } catch (Throwable t) {
            showToast("processResponseCallback 异常: " + t.getMessage());
        }
    }

    private String extractUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return null;
            Object url = XposedHelpers.callMethod(request, "url");
            return url != null ? url.toString() : null;
        } catch (Throwable t) {
            showToast("extractUrl 异常: " + t.getMessage());
            return null;
        }
    }

    private byte[] extractBodyBytes(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) {
                showToast("body 为 null");
                return null;
            }

            try {
                Object bytes = XposedHelpers.callMethod(body, "bytes");
                if (bytes instanceof byte[]) {
                    return (byte[]) bytes;
                }
            } catch (Throwable ignored) {
            }

            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) {
                    return ((String) str).getBytes(StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }

            try {
                Object sourceObj = XposedHelpers.callMethod(body, "source");
                if (sourceObj != null) {
                    Object bufferObj = XposedHelpers.callMethod(sourceObj, "getBuffer");
                    if (bufferObj != null) {
                        Object clone = XposedHelpers.callMethod(bufferObj, "clone");
                        if (clone instanceof byte[]) {
                            return (byte[]) clone;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            showToast("无法提取 body 内容");
        } catch (Throwable t) {
            showToast("extractBodyBytes 异常: " + t.getMessage());
        }
        return null;
    }

    private String extractContentType(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            Object ct = XposedHelpers.callMethod(body, "contentType");
            return ct != null ? ct.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildNewResponse(Object originalResponse, String newBodyStr, String contentType, ClassLoader cl) {
        try {
            ClassLoader safeCl = (cl != null) ? cl : targetClassLoader;
            if (safeCl == null) return null;

            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);

            Object mediaTypeObj = null;
            try {
                if (contentType != null && !contentType.isEmpty()) {
                    Class<?> mediaTypeCls = XposedHelpers.findClassIfExists("okhttp3.MediaType", safeCl);
                    if (mediaTypeCls != null) {
                        mediaTypeObj = XposedHelpers.callStaticMethod(mediaTypeCls, "parse", contentType);
                    }
                }
            } catch (Throwable ignored) {
            }

            Object newBody = null;
            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", safeCl);
            if (responseBodyCls != null) {
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaTypeObj, bodyBytes);
                } catch (Throwable ignored) {
                }
                if (newBody == null) {
                    try {
                        newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaTypeObj, newBodyStr);
                    } catch (Throwable ignored) {
                    }
                }
                if (newBody == null) {
                    try {
                        Object buffer = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClassIfExists("okio.Buffer", safeCl),
                                "writeUtf8", newBodyStr);
                        if (buffer != null) {
                            newBody = XposedHelpers.callStaticMethod(
                                    responseBodyCls, "create",
                                    mediaTypeObj, (long) bodyBytes.length, buffer);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (newBody == null) return null;

            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            showToast("buildNewResponse 异常: " + t.getMessage());
            return null;
        }
    }

    private String modifyAnswerBody(String bodyStr) {
        try {
            showToast("开始解析 JSON，长度: " + bodyStr.length());

            JSONObject root = new JSONObject(bodyStr);
            String code = root.optString("code");
            showToast("code = " + code);

            if (!"success".equals(code)) {
                showToast("code 不是 success");
                return bodyStr;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                showToast("data 为 null");
                return bodyStr;
            }

            JSONArray options = data.optJSONArray("answerOptionList");
            if (options == null || options.length() == 0) {
                showToast("answerOptionList 为空或不存在");
                return bodyStr;
            }

            showToast("找到 answerOptionList，长度: " + options.length());

            boolean changed = false;
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;

                int isRight = opt.optInt("isRight");
                showToast("选项 " + i + ": isRight = " + isRight);

                if (isRight == 1) {
                    String text = opt.optString("optionText", "");
                    String wrapped = "【 " + text + " 正确答案 】";
                    opt.put("optionText", wrapped);
                    changed = true;
                    showToast("已修改正确答案: " + text);
                }
            }

            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            showToast("modifyAnswerBody 异常: " + t.getMessage());
            return bodyStr;
        }
    }

    private void showModuleStartedToast(final Context ctx) {
        if (ctx == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, "答案显示模块已生效，正在监控 tz.ycsy.az", Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void showDialog(final String url, final String originalBody, final String modifiedBody) {
        final Context ctx = appContext;
        if (ctx == null) return;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    int pad = dp2px(ctx, 16);

                    LinearLayout container = new LinearLayout(ctx);
                    container.setOrientation(LinearLayout.VERTICAL);
                    container.setPadding(pad, pad, pad, pad);

                    TextView titleReq = new TextView(ctx);
                    titleReq.setText("请求 URL");
                    titleReq.setTextSize(14);
                    titleReq.setTextColor(0xFF1976D2);
                    container.addView(titleReq, lp());

                    TextView urlView = new TextView(ctx);
                    urlView.setText(url);
                    urlView.setTextSize(11);
                    urlView.setTextColor(0xFF212121);
                    container.addView(urlView, lp());

                    TextView titleResp = new TextView(ctx);
                    titleResp.setText("响应内容");
                    titleResp.setTextSize(14);
                    titleResp.setTextColor(0xFF1976D2);
                    LinearLayout.LayoutParams lpTitle = lp();
                    lpTitle.topMargin = pad;
                    container.addView(titleResp, lpTitle);

                    TextView bodyView = new TextView(ctx);
                    bodyView.setText(preview(originalBody));
                    bodyView.setTextSize(11);
                    bodyView.setTextColor(0xFF212121);
                    bodyView.setMovementMethod(new ScrollingMovementMethod());
                    bodyView.setMaxHeight(dp2px(ctx, 300));
                    container.addView(bodyView, lp());

                    if (!originalBody.equals(modifiedBody)) {
                        TextView titleMod = new TextView(ctx);
                        titleMod.setText("已修改（正确答案已标记）");
                        titleMod.setTextSize(14);
                        titleMod.setTextColor(0xFFc62828);
                        LinearLayout.LayoutParams lpM = lp();
                        lpM.topMargin = pad;
                        container.addView(titleMod, lpM);

                        TextView modView = new TextView(ctx);
                        modView.setText(preview(modifiedBody));
                        modView.setTextSize(11);
                        modView.setTextColor(0xFF212121);
                        modView.setMovementMethod(new ScrollingMovementMethod());
                        modView.setMaxHeight(dp2px(ctx, 300));
                        container.addView(modView, lpM);
                    }

                    ScrollView scrollView = new ScrollView(ctx);
                    scrollView.addView(container);

                    new AlertDialog.Builder(ctx)
                            .setTitle("题目响应已拦截")
                            .setView(scrollView)
                            .setPositiveButton("关闭", null)
                            .setCancelable(true)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String preview(String body) {
        if (body == null) return "";
        if (body.length() > 4000) return body.substring(0, 4000) + "\n... (已截断)";
        return body;
    }

    private static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp2px(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}

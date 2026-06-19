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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String TARGET_PATH = "/edu-core-server/app/exam/getQuestion";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";

    private static final String[] HTTP_CLIENT_CLASS_NAMES = {
            // OkHttp
            "okhttp3.OkHttpClient",
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.Dispatcher",
            "okhttp3.Request",
            "okhttp3.Response",
            "okhttp3.ResponseBody",
            // Retrofit
            "retrofit2.Retrofit",
            "retrofit2.OkHttpCall",
            "retrofit2.DefaultCallAdapterFactory",
            // Apache HttpClient
            "org.apache.http.impl.client.DefaultHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "org.apache.http.client.HttpClient",
            // Android/Java 原生
            "java.net.HttpURLConnection",
            "javax.net.ssl.HttpsURLConnection",
            // Volley
            "com.android.volley.toolbox.Volley",
            "com.android.volley.toolbox.StringRequest",
            "com.android.volley.toolbox.JsonObjectRequest",
            // WebView
            "android.webkit.WebView",
            "android.webkit.WebViewClient",
            // XUtils / 其他国产框架
            "org.xutils.http.RequestParams",
            "org.xutils.x",
            "com.lidroid.xutils.HttpUtils",
            "com.loopj.android.http.AsyncHttpClient",
            "com.squareup.okhttp.OkHttpClient"
    };

    // 原子标记，确保每个 app 只执行一次初始化
    private static final Set<String> initializedPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // 检测到的 HTTP 客户端类列表
    private static final List<String> detectedClients =
            Collections.synchronizedList(new ArrayList<String>());

    // 已处理的请求计数（用于防止重复弹窗）
    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final AtomicInteger targetHitCounter = new AtomicInteger(0);

    // 共享状态
    private static volatile Context appContext;
    private static ClassLoader targetClassLoader;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        // —— 处理模块自己的包 ——
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
                                appContext = ctx;
                                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                sp.edit().putBoolean("module_active_v1", true)
                                        .putLong("last_hook_time", System.currentTimeMillis()).commit();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            return;
        }

        // —— 非目标包，跳过 ——
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // —— 确保只初始化一次 ——
        if (!initializedPackages.add(TARGET_PACKAGE)) return;

        targetClassLoader = lpparam.classLoader;

        // ============== 先扫描目标应用的 HTTP 客户端 ==============
        scanHttpClients();

        // ============== 关键修复 ==============
        // 1. 先 Hook Application.onCreate，拿到 Context 后立即弹 Toast
        // 2. 用 param.thisObject 直接弹（不依赖静态 appContext）
        XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context ctx = (Context) param.thisObject;
                            appContext = ctx;
                            // 记录状态：同时写入目标应用的 SP 和模块的 SP
                            try {
                                // 目标应用自己的 SP（用于检测模块是否激活等）
                                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                sp.edit().putBoolean("module_active_v1", true)
                                        .putLong("last_hook_time", System.currentTimeMillis())
                                        .commit();
                                // 把请求记录也写入模块的 SP，让 UI 能读取
                                writeDetectionToModuleSP(ctx);
                            } catch (Throwable ignored) {
                            }
                            // 直接弹 Toast（这里 Context 一定存在）
                            Toast.makeText(ctx, "【答案模块】已加载 - 包名 " + TARGET_PACKAGE,
                                    Toast.LENGTH_LONG).show();
                        } catch (Throwable t) {
                            try {
                                XposedBridge.log("[答案模块] Application.onCreate hook failed: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
        );

        // ============== 网络 Hook 入口（覆盖所有可能的 HTTP 客户端）==============
        if (hasClass("okhttp3.OkHttpClient") || hasClass("okhttp3.Request")) {
            try {
                hookOkHttpLayer(lpparam.classLoader);
            } catch (Throwable ignored) {
            }
        }

        // ============== 5. Hook HttpURLConnection ==============
        try {
            hookHttpURLConnectionLayer(lpparam.classLoader);
        } catch (Throwable ignored) {
        }

        // ============== 6. Hook 底层 Socket（最底层的网络访问）==============
        try {
            hookSocketLayer(lpparam.classLoader);
        } catch (Throwable ignored) {
        }

        // ============== 7. Hook WebView（如果用 WebView 做答题）==============
        try {
            hookWebViewLayer(lpparam.classLoader);
        } catch (Throwable ignored) {
        }

        // ============== 8. Hook URL.openConnection（URL 级访问）==============
        try {
            hookURLOpenConnection(lpparam.classLoader);
        } catch (Throwable ignored) {
        }

        // ============== 9. Hook 所有包含 "http" 或 "network" 的类（兜底）==============
        // 暂时不启用这个，扫描成本太高，放到后面按需启用
    }

    // ========== 把检测结果写入模块自己的 SharedPreferences ==========
    private static void writeDetectionToModuleSP(Context appCtx) {
        try {
            Context moduleCtx = appCtx.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            SharedPreferences.Editor editor = sp.edit();
            // 写入 HTTP 客户端检测结果
            if (detectedClients != null && !detectedClients.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : detectedClients) {
                    sb.append(s).append("\n");
                }
                editor.putString("detected_clients", sb.toString());
            }
            // 写入计数信息
            editor.putInt("target_hit_count", targetHitCounter.get());
            editor.putInt("request_count", requestCounter.get());
            editor.putLong("last_hook_time", System.currentTimeMillis());
            editor.commit();
        } catch (Throwable ignored) {
        }
    }

    // ========== 辅助方法：检测类是否存在 ==========
    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, targetClassLoader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ========== 扫描并记录 HTTP 客户端 ==========
    private static void scanHttpClients() {
        try {
            String[] HTTP_CLIENT_CLASS_NAMES = {
                    "okhttp3.OkHttpClient", "okhttp3.RealCall",
                    "okhttp3.internal.connection.RealCall", "okhttp3.Dispatcher",
                    "okhttp3.Request", "okhttp3.Response", "okhttp3.ResponseBody",
                    "retrofit2.Retrofit", "retrofit2.OkHttpCall",
                    "org.apache.http.impl.client.DefaultHttpClient",
                    "org.apache.http.impl.client.CloseableHttpClient",
                    "org.apache.http.client.HttpClient",
                    "java.net.HttpURLConnection", "javax.net.ssl.HttpsURLConnection",
                    "com.android.volley.toolbox.Volley",
                    "com.android.volley.toolbox.StringRequest",
                    "android.webkit.WebView", "android.webkit.WebViewClient",
                    "org.xutils.http.RequestParams", "org.xutils.x",
                    "com.lidroid.xutils.HttpUtils",
                    "com.loopj.android.http.AsyncHttpClient",
                    "com.squareup.okhttp.OkHttpClient"
            };
            for (String className : HTTP_CLIENT_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className, false, targetClassLoader);
                    if (clazz != null) {
                        detectedClients.add(className);
                    }
                } catch (ClassNotFoundException ignored) {
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== 模块自己的 Hook：让 isModuleActive 返回 true ==========
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

    // ========== OkHttp Hook（覆盖多个入口） ==========
    private void hookOkHttpLayer(final ClassLoader cl) {
        // Hook okhttp3.RealCall / okhttp3.internal.connection.RealCall 的 getResponseWithInterceptorChain()
        for (String realCallName : new String[]{"okhttp3.RealCall", "okhttp3.internal.connection.RealCall"}) {
            try {
                XposedHelpers.findAndHookMethod(
                        realCallName, cl, "getResponseWithInterceptorChain",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) {
                                        processOkHttpResponse(response, param);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                );
            } catch (Throwable ignored) {
            }
        }

        // Hook okhttp3.Call.execute()
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Call", cl, "execute",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processOkHttpResponse(response, param);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook okhttp3.Callback.onResponse(Call, Response)
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Callback", cl, "onResponse",
                    "okhttp3.Call", "okhttp3.Response",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.args[1];
                                if (response != null) {
                                    processOkHttpResponse(response, param);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook okhttp3.Interceptor.Chain.proceed(Request)
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Interceptor$Chain", cl, "proceed", "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processOkHttpResponse(response, param);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook OkHttpClient.newCall(Request) - 记录所有请求
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.OkHttpClient", cl, "newCall", "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object request = param.args[0];
                                Object url = XposedHelpers.callMethod(request, "url");
                                if (url != null) {
                                    String urlStr = url.toString();
                                    showToastSafe("[OkHttp 请求] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("OKHTTP", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== HttpURLConnection Hook ==========
    private void hookHttpURLConnectionLayer(final ClassLoader cl) {
        // Hook getResponseCode() - 最简单的检测点
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection", cl, "getResponseCode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                if (url != null) {
                                    String urlStr = url.toString();
                                    showToastSafe("[HttpURLConnection] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("HTTP_URL_CONN", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook getInputStream() - 获取响应体
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection", cl, "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                String urlStr = url != null ? url.toString() : "";
                                if (!urlStr.contains(TARGET_PATH)) {
                                    return;
                                }
                                InputStream is = (InputStream) param.getResult();
                                if (is == null) return;
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = is.read(buf)) != -1) {
                                    baos.write(buf, 0, n);
                                }
                                is.close();
                                String bodyStr = baos.toString("UTF-8");
                                String modified = modifyAnswerBody(bodyStr);
                                // 无论是否修改，都弹窗
                                showDialog(urlStr, bodyStr, modified);
                                param.setResult(new java.io.ByteArrayInputStream(
                                        modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable t) {
                                showToastSafe("[HttpURLConnection 处理] " + t.getMessage());
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== Socket 底层 Hook（最兜底） ==========
    private void hookSocketLayer(final ClassLoader cl) {
        // Hook Socket.connect(SocketAddress, timeout)
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.Socket", cl, "connect",
                    "java.net.SocketAddress", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object addr = param.args[0];
                                if (addr instanceof InetSocketAddress) {
                                    InetSocketAddress isa = (InetSocketAddress) addr;
                                    String host = isa.getHostName();
                                    showToastSafe("[Socket] " + host + ":" + isa.getPort());
                                    writeRequestRecord("SOCKET", host + ":" + isa.getPort());
                                } else if (addr != null) {
                                    showToastSafe("[Socket] " + addr.toString());
                                    writeRequestRecord("SOCKET", addr.toString());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook Socket.getOutputStream() - 记录发送内容
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.Socket", cl, "getOutputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object socket = param.thisObject;
                                Object addr = XposedHelpers.callMethod(socket, "getRemoteSocketAddress");
                                if (addr != null) {
                                    showToastSafe("[Socket 输出] " + addr.toString().substring(0,
                                            Math.min(addr.toString().length(), 60)));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== URL.openConnection Hook ==========
    private void hookURLOpenConnection(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.URL", cl, "openConnection",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                URL url = (URL) param.thisObject;
                                String urlStr = url.toString();
                                showToastSafe("[URL] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                writeRequestRecord("URL_CONN", urlStr);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== WebView Hook（答题页面可能用 webview 渲染）==============
    private void hookWebViewLayer(final ClassLoader cl) {
        // Hook WebView.loadUrl(String)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", cl, "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    showToastSafe("[WebView] " + url.substring(0, Math.min(url.length(), 60)));
                                    writeRequestRecord("WEBVIEW", url);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook WebView.loadUrl(String, Map<String,String>)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", cl, "loadUrl",
                    String.class, java.util.Map.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    showToastSafe("[WebView+Headers] " + url.substring(0, Math.min(url.length(), 60)));
                                    writeRequestRecord("WEBVIEW", url);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // Hook WebViewClient.shouldInterceptRequest - 捕获请求和响应
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", "android.webkit.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj != null) {
                                    String urlStr = urlObj.toString();
                                    showToastSafe("[WebView 拦截] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("WEBVIEW_INTERCEPT", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== 处理 OkHttp 响应 ==========
    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            String urlStr = extractOkHttpUrl(response);
            if (urlStr == null) return;

            showToastSafe("[响应] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
            writeRequestRecord("OKHTTP_RESP", urlStr);

            if (!urlStr.contains(TARGET_PATH)) return;

            showToastSafe("=== 目标接口命中！ ===");
            targetHitCounter.incrementAndGet();

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) {
                showToastSafe("响应体为空");
                return;
            }

            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBody(bodyStr);

            if (!modified.equals(bodyStr)) {
                // 尝试替换响应体
                String contentType = extractOkHttpContentType(response);
                Object newResp = buildOkHttpResponse(response, modified, contentType, targetClassLoader);
                if (newResp != null) {
                    param.setResult(newResp);
                    showDialog(urlStr, bodyStr, modified);
                    return;
                }
            }
            // 即使没修改也弹窗
            showDialog(urlStr, bodyStr, bodyStr);
        } catch (Throwable t) {
            showToastSafe("处理响应失败: " + t.getMessage());
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

            // 优先用 bytes()
            try {
                Object bytes = XposedHelpers.callMethod(body, "bytes");
                if (bytes instanceof byte[]) return (byte[]) bytes;
            } catch (Throwable ignored) {
            }
            // 备选 string()
            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) return ((String) str).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            // 再备选：source().getBuffer().clone()
            try {
                Object source = XposedHelpers.callMethod(body, "source");
                if (source != null) {
                    Object buffer = XposedHelpers.callMethod(source, "getBuffer");
                    if (buffer != null) {
                        Object clone = XposedHelpers.callMethod(buffer, "clone");
                        if (clone instanceof byte[]) return (byte[]) clone;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
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

    private Object buildOkHttpResponse(Object originalResponse, String newBodyStr, String contentType, ClassLoader cl) {
        try {
            ClassLoader safeCl = cl != null ? cl : targetClassLoader;
            if (safeCl == null) return null;

            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);

            Object mediaType = null;
            try {
                if (contentType != null && !contentType.isEmpty()) {
                    Class<?> mediaTypeCls = XposedHelpers.findClassIfExists("okhttp3.MediaType", safeCl);
                    if (mediaTypeCls != null) {
                        mediaType = XposedHelpers.callStaticMethod(mediaTypeCls, "parse", contentType);
                    }
                }
            } catch (Throwable ignored) {
            }

            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", safeCl);
            if (responseBodyCls == null) return null;

            Object newBody = null;
            // 尝试多种 create 签名
            try {
                newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes);
            } catch (Throwable ignored) {
            }
            if (newBody == null) {
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, newBodyStr);
                } catch (Throwable ignored) {
                }
            }
            if (newBody == null) {
                try {
                    Object buffer = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClassIfExists("okio.Buffer", safeCl),
                            "writeUtf8", newBodyStr);
                    if (buffer != null) {
                        newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create",
                                mediaType, (long) bodyBytes.length, buffer);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (newBody == null) return null;

            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ========== 修改答案内容 ==========
    private String modifyAnswerBody(String bodyStr) {
        try {
            JSONObject root = new JSONObject(bodyStr);
            if (!"success".equals(root.optString("code"))) return bodyStr;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return bodyStr;
            JSONArray options = data.optJSONArray("answerOptionList");
            if (options == null || options.length() == 0) return bodyStr;

            boolean changed = false;
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;
                if (opt.optInt("isRight") == 1) {
                    String text = opt.optString("optionText", "");
                    opt.put("optionText", "【 " + text + " 正确答案 】");
                    changed = true;
                }
            }
            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
    }

    // ========== 写入请求记录到模块的 SharedPreferences（让 UI 能读取）==========
    private void writeRequestRecord(String type, String urlStr) {
        try {
            Context ctx = appContext;
            if (ctx == null) return;
            // 获取模块包的 Context（跨进程写入）
            try {
                ctx = ctx.createPackageContext(MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            } catch (Throwable ignored) {
            }
            SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            int counter = requestCounter.incrementAndGet();
            String key = "req_" + counter + "_" + System.currentTimeMillis();
            sp.edit().putString(key, type + "|" + urlStr).commit();
        } catch (Throwable ignored) {
        }
    }

    // ========== Toast（安全，总是尝试用可用的 Context） ==========
    private void showToastSafe(final String message) {
        // 优先用已存储的 appContext
        if (appContext != null) {
            postToast(appContext, message);
            return;
        }
        // 否则尝试从 ActivityThread 获取当前 Application
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (activityThread != null) {
                Context ctx = (Context) XposedHelpers.callMethod(activityThread, "getApplication");
                if (ctx != null) {
                    appContext = ctx;
                    postToast(ctx, message);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        // 最后手段：记录到 Xposed 日志
        try {
            XposedBridge.log("[答案模块] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static void postToast(final Context ctx, final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ========== 弹窗 ==========
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
                    container.addView(titleReq, lpMatch());

                    TextView urlView = new TextView(ctx);
                    urlView.setText(url);
                    urlView.setTextSize(11);
                    urlView.setTextColor(0xFF212121);
                    container.addView(urlView, lpMatch());

                    TextView titleResp = new TextView(ctx);
                    titleResp.setText("响应内容");
                    titleResp.setTextSize(14);
                    titleResp.setTextColor(0xFF1976D2);
                    container.addView(titleResp, lpMatch());

                    TextView bodyView = new TextView(ctx);
                    bodyView.setText(preview(originalBody));
                    bodyView.setTextSize(11);
                    bodyView.setTextColor(0xFF212121);
                    bodyView.setMovementMethod(new ScrollingMovementMethod());
                    bodyView.setMaxHeight(dp2px(ctx, 300));
                    container.addView(bodyView, lpMatch());

                    if (!originalBody.equals(modifiedBody)) {
                        TextView titleMod = new TextView(ctx);
                        titleMod.setText("已修改（正确答案已标记）");
                        titleMod.setTextSize(14);
                        titleMod.setTextColor(0xFFc62828);
                        container.addView(titleMod, lpMatch());

                        TextView modView = new TextView(ctx);
                        modView.setText(preview(modifiedBody));
                        modView.setTextSize(11);
                        modView.setTextColor(0xFF212121);
                        modView.setMovementMethod(new ScrollingMovementMethod());
                        modView.setMaxHeight(dp2px(ctx, 300));
                        container.addView(modView, lpMatch());
                    }

                    ScrollView scrollView = new ScrollView(ctx);
                    scrollView.addView(container);

                    new AlertDialog.Builder(ctx)
                            .setTitle("【题目响应已拦截】")
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

    private static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp2px(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    // ========== 暴露给 UI 读取检测结果的方法 ==========
    public static List<String> getDetectedClients() {
        return new ArrayList<String>(detectedClients);
    }

    public static int getTargetHitCount() {
        return targetHitCounter.get();
    }

    public static int getRequestCount() {
        return requestCounter.get();
    }
}

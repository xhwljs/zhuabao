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

import java.nio.charset.StandardCharsets;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;

        // 处理模块自己的包：Hook isModuleActive 让 UI 检测到模块已激活
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            // 在模块自己的包中写入"模块已激活"标记，便于不需要 hookSelf 时也能检测
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

        // 只处理目标包
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        if (hooksInstalled) return;

        targetClassLoader = lpparam.classLoader;

        // 记录 Application 上下文，用于弹 dialog 和 Toast
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
                            boolean firstTime = !sp.getBoolean(SP_KEY_ACTIVE, false);
                            sp.edit().putBoolean(SP_KEY_ACTIVE, true)
                                    .putLong(SP_KEY_LAST, System.currentTimeMillis()).commit();

                            // 首次 hook 时显示 Toast 提示
                            if (firstTime) {
                                showModuleStartedToast(appContext);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );

        // 尝试多种 OkHttp Hook 路径，兼容不同版本
        hookOkHttpRealCall(lpparam.classLoader);
        hookOkHttpInternalRealCall(lpparam.classLoader);
        hookOkHttpCallback(lpparam.classLoader);
        hookOkHttpCallExecute(lpparam.classLoader);

        hooksInstalled = true;
    }

    private void hookSelf(ClassLoader cl) {
        try {
            // hook isModuleActive 方法：在调用前就替换为直接返回 true
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

    // OkHttp 3.x: okhttp3.RealCall.getResponseWithInterceptorChain()
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
                                if (response == null) return;
                                processResponse(response, param, cl);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // OkHttp 4.x / 5.x: okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()
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
                                if (response == null) return;
                                processResponse(response, param, cl);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // OkHttp 异步回调：okhttp3.Callback.onResponse(Call, Response)
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
                                if (response == null) return;
                                processResponseCallback(response, param, cl);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // OkHttp 同步调用：okhttp3.Call.execute()
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
                                if (response == null) return;
                                processResponse(response, param, cl);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // 处理来自 getResponseWithInterceptorChain / execute 的 Response
    private void processResponse(Object response, XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        try {
            String urlStr = extractUrl(response);
            if (urlStr == null || !urlStr.contains(TARGET_PATH)) return;

            byte[] bodyBytes = extractBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String contentType = extractContentType(response);
            String original = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBody(original);

            if (!modified.equals(original)) {
                // 尝试替换 response body
                Object newResponse = buildNewResponse(response, modified, contentType, cl);
                if (newResponse != null) {
                    param.setResult(newResponse);
                    showDialog(urlStr, original, modified);
                    return;
                }
            }
            // 即使不修改也弹窗（调试时方便观察）
            showDialog(urlStr, original, original);
        } catch (Throwable ignored) {
        }
    }

    // 处理来自 Callback.onResponse 的 Response —— 注意：这里无法替换返回值，只能显示
    private void processResponseCallback(Object response, XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        try {
            String urlStr = extractUrl(response);
            if (urlStr == null || !urlStr.contains(TARGET_PATH)) return;

            byte[] bodyBytes = extractBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String original = new String(bodyBytes, StandardCharsets.UTF_8);
            showDialog(urlStr, original, original);
        } catch (Throwable ignored) {
        }
    }

    private String extractUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return null;
            Object url = XposedHelpers.callMethod(request, "url");
            return url != null ? url.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] extractBodyBytes(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;

            // 路径 1：通过 source().getBuffer().clone() 获取底层字节
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

            // 路径 2：通过 bytes() 方法
            try {
                Object bytes = XposedHelpers.callMethod(body, "bytes");
                if (bytes instanceof byte[]) {
                    return (byte[]) bytes;
                }
            } catch (Throwable ignored) {
            }

            // 路径 3：通过 string() 方法，再转回 bytes
            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) {
                    return ((String) str).getBytes(StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String extractContentType(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            Object ct = XposedHelpers.callMethod(body, "contentType");
            if (ct != null) return ct.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object buildNewResponse(Object originalResponse, String newBodyStr, String contentType, ClassLoader cl) {
        try {
            ClassLoader safeCl = (cl != null) ? cl : targetClassLoader;
            if (safeCl == null) return null;

            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);

            // 构造 MediaType（可选）
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

            // 构造 ResponseBody - 尝试多种签名
            Object newBody = null;
            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", safeCl);
            if (responseBodyCls != null) {
                // 签名 1：create(MediaType, byte[])
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaTypeObj, bodyBytes);
                } catch (Throwable ignored) {
                }
                // 签名 2：create(MediaType, String)
                if (newBody == null) {
                    try {
                        newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaTypeObj, newBodyStr);
                    } catch (Throwable ignored) {
                    }
                }
                // 签名 3：create(MediaType, long, okio.BufferedSource)
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

            // 用 Response.Builder 构造新的 Response
            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

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
                    String wrapped = "【 " + text + " 正确答案 】";
                    opt.put("optionText", wrapped);
                    changed = true;
                }
            }

            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
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
                        container.addView(modView, lp());
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

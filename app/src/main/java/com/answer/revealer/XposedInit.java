package com.answer.revealer;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private static volatile Context appContext;
    private static ClassLoader targetClassLoader;

    private void hookSelf(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PACKAGE + ".MainActivity",
                    cl,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;

        if (XposedInit.class.getName().startsWith(lpparam.packageName)
                || "com.answer.revealer".equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            return;
        }

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        targetClassLoader = lpparam.classLoader;

        XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        appContext = (Context) param.thisObject;
                    }
                }
        );

        hookOkHttp(lpparam.classLoader);
        hookOkHttpCall(lpparam.classLoader);
    }

    private void hookOkHttp(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.RealCall",
                    classLoader,
                    "getResponseWithInterceptorChain",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object response = param.getResult();
                            if (response == null) return;
                            processOkHttpResponse(response, param);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookOkHttpCall(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.internal.connection.RealCall",
                    classLoader,
                    "getResponseWithInterceptorChain",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object response = param.getResult();
                            if (response == null) return;
                            processOkHttpResponse(response, param);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return;
            Object url = XposedHelpers.callMethod(request, "url");
            String urlStr = url != null ? url.toString() : "";
            if (urlStr == null || !urlStr.contains(TARGET_PATH)) return;

            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return;

            byte[] bodyBytes = null;
            String contentType = null;

            try {
                Object sourceObj = XposedHelpers.callMethod(body, "source");
                if (sourceObj != null) {
                    Object bufferObj = XposedHelpers.callMethod(sourceObj, "getBuffer");
                    if (bufferObj != null) {
                        bodyBytes = (byte[]) XposedHelpers.callMethod(bufferObj, "clone");
                    }
                }
            } catch (Throwable ignored) {
            }

            if (bodyBytes == null || bodyBytes.length == 0) {
                try {
                    Object bytes = XposedHelpers.callMethod(body, "bytes");
                    if (bytes instanceof byte[]) {
                        bodyBytes = (byte[]) bytes;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (bodyBytes == null || bodyBytes.length == 0) return;

            try {
                Object ct = XposedHelpers.callMethod(body, "contentType");
                if (ct != null) contentType = ct.toString();
            } catch (Throwable ignored) {
            }

            String original = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBody(original);

            if (!modified.equals(original)) {
                ClassLoader cl = targetClassLoader != null ? targetClassLoader
                        : param.thisObject.getClass().getClassLoader();
                try {
                    Object mediaTypeObj = null;
                    if (contentType != null) {
                        Class<?> mediaTypeCls = XposedHelpers.findClass("okhttp3.MediaType", cl);
                        mediaTypeObj = XposedHelpers.callStaticMethod(mediaTypeCls, "parse", contentType);
                    }
                    Class<?> responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
                    Object newBody;
                    if (mediaTypeObj != null) {
                        newBody = XposedHelpers.callStaticMethod(
                                responseBodyCls,
                                "create",
                                mediaTypeObj,
                                modified.getBytes(StandardCharsets.UTF_8)
                        );
                    } else {
                        newBody = XposedHelpers.callStaticMethod(
                                responseBodyCls,
                                "create",
                                null,
                                modified.getBytes(StandardCharsets.UTF_8)
                        );
                    }

                    Object newBuilder = XposedHelpers.callMethod(response, "newBuilder");
                    newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
                    Object newResponse = XposedHelpers.callMethod(newBuilder, "build");
                    param.setResult(newResponse);

                    showDialog(urlStr, original, modified);
                } catch (Throwable t) {
                    showDialog(urlStr, original, original);
                }
            } else {
                showDialog(urlStr, original, original);
            }
        } catch (Throwable ignored) {
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
                    String wrapped = "<span style=\"color:#c62828;font-weight:900;font-size:16px;\">【 " + text + " 正确答案 】</span>";
                    opt.put("optionText", wrapped);
                    changed = true;
                }
            }

            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
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
                    bodyView.setText(Html.fromHtml(preview(originalBody)));
                    bodyView.setTextSize(11);
                    bodyView.setTextColor(0xFF212121);
                    bodyView.setMovementMethod(new ScrollingMovementMethod());
                    bodyView.setMaxHeight(dp2px(ctx, 300));
                    container.addView(bodyView, lp());

                    if (!originalBody.equals(modifiedBody)) {
                        TextView titleMod = new TextView(ctx);
                        titleMod.setText("已修改（注入答案）");
                        titleMod.setTextSize(14);
                        titleMod.setTextColor(0xFFc62828);
                        LinearLayout.LayoutParams lpM = lp();
                        lpM.topMargin = pad;
                        container.addView(titleMod, lpM);

                        TextView modView = new TextView(ctx);
                        modView.setText(Html.fromHtml(preview(modifiedBody)));
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

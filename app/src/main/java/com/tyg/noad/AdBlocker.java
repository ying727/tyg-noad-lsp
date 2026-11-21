package com.tyg.noad;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 只屏蔽广告，不修改登录、房间、授权码和 WebSocket 开门协议。
 * hook 点来自对 com.tyg.tygsmart V1.3.11 的静态逆向：
 *  - Global.isShowAd 是广告总开关，OpenDoorFragment/HomeFragment 在它为 false 时
 *    会调用 showAdvs(8) 把广告容器设为 GONE。
 *  - ScreenAdManager 负责开屏和开门后全屏广告，TTFeedAdLoadView/ToBidFeedAdLoadView
 *    负责信息流，TTAdManagerHolder/WindMillAd 负责第三方广告 SDK 初始化。
 *
 * 找不到的类/字段/方法不逐条刷日志，统一收进 missed，安装结束时输出一条汇总，
 * 目标 App 更新后从 LSPosed 日志一眼能看出哪些 hook 点失效了。
 */
final class AdBlocker {
    private static final String GLOBAL = "com.tyg.tygsmart.constant.Global";
    private static final String AD_FLAG_PREF = "tyg_isshow_ad";

    private AdBlocker() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Set<String> missed = new LinkedHashSet<>();
        forceGlobalFlagsOff(module, cl, missed);
        hookAdSwitchPreference(module, cl, missed);

        // 广告状态、广告列表、第三方广告配置与广告埋点请求。
        noOp(module, cl, missed, "com.tyg.tygsmart.network.RxjavaRetrofitNetworkUtils",
                "getAppAdOpenStatus", "downloadAdvs", "getThirdAppAdConfig",
                "getRewardVideoAdCodeId", "reportThirdAppAdEventLog");

        // 第三方广告 SDK 初始化（推送、SIP 对讲、XMPP 不在此列）。
        noOp(module, cl, missed, "com.tyg.tygsmart.InitSDK", "initAdSdksInBackground");
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.ttad.TTAdManagerHolder", "init", "reInit", "doInit", "start");
        noOp(module, cl, missed, "com.bytedance.sdk.openadsdk.TTAdSdk", "init", "start");
        noOp(module, cl, missed, "com.windmill.sdk.WindMillAd", "startWithAppId");

        // 开门成功后的反馈弹窗就是 ScreenAdManager 的默认弹窗（showAdv →
        // showAdvDialog(null, 17, dialog_app_adv)），不能拦；无广告源时 App
        // 自己走的就是这条路径。loadAdv/mediationPreloadAds 在 isShowAd=false
        // 时是空操作，还负责给 ScreenAdManager.activity 赋值，拦了弹窗反而
        // 弹不出来。这里只拦全屏广告 Activity 的启动入口，广告内容由
        // ScreenTTAdView/ScreenToBidAdView 的 hook 挡住。
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.ScreenAdManager", "showScreenTTAdvDialog");
        returnConstant(module, cl, missed, "com.tyg.tygsmart.ad.ScreenAdManager", "hasNotSource", true);
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.ttad.ScreenTTAdView",
                "showScreenTTAd", "showTTAdView", "changeScreenAdView", "loadExpressAd", "mediationPreloadAds");
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.tobid.ScreenToBidAdView",
                "showScreenTTAd", "showTTAdView", "changeScreenAdView", "loadExpressAd");

        // 开门页/首页的信息流广告。
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.ttad.TTFeedAdLoadView", "startLoadAd", "loadExpressAd", "showFeedAd");
        noOp(module, cl, missed, "com.tyg.tygsmart.ad.tobid.ToBidFeedAdLoadView", "startLoadAd");
        noOp(module, cl, missed, "com.tyg.tygsmart.ui.open_door.OpenDoorFragment", "getAdvs", "displayAdvs");
        noOp(module, cl, missed, "com.tyg.tygsmart.ui.home.HomeFragment", "getAdvs", "displayAdvs");
        forceContainerGone(module, cl, missed, "com.tyg.tygsmart.ui.open_door.OpenDoorFragment", "showAdvs");
        forceContainerGone(module, cl, missed, "com.tyg.tygsmart.ui.home.HomeFragment", "showAdvs");

        // 最后防线：万一还有路径启动广告 Activity，直接结束它。
        finishAdActivity(module, cl, missed);

        if (missed.isEmpty()) {
            module.log(Log.INFO, MainHook.TAG, "广告屏蔽已全部生效");
        } else {
            module.log(Log.WARN, MainHook.TAG, "桃园谷 App 可能已更新，有 " + missed.size() + " 个屏蔽点未生效: "
                    + String.join("; ", missed));
        }
    }

    private static void forceGlobalFlagsOff(XposedModule module, ClassLoader cl, Set<String> missed) {
        Class<?> global = findClass(cl, GLOBAL, missed);
        if (global == null) {
            return;
        }
        setStaticBooleanField(module, global, "isShowAd", false, missed);
        setStaticBooleanField(module, global, "isSdkReadyTTAd", false, missed);
        setStaticBooleanField(module, global, "isSdkReadyWindMill", false, missed);
        setStaticBooleanField(module, global, "isSdkReadyAdGain", false, missed);
        clearStaticCollectionField(module, global, "sourceBeanList", missed);
    }

    private static void hookAdSwitchPreference(XposedModule module, ClassLoader cl, Set<String> missed) {
        Method method = findMethod(cl, "com.tyg.tygsmart.util.PrefUtils", "getBoolean",
                missed, Context.class, String.class, boolean.class);
        if (method == null) {
            return;
        }
        Class<?> global = findClass(cl, GLOBAL, missed);
        try {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (AD_FLAG_PREF.equals(chain.getArg(1))) {
                    setStaticBooleanField(module, global, "isShowAd", false, missed);
                    return false;
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, MainHook.TAG, "hookAdSwitchPreference: " + t);
        }
    }

    private static void forceContainerGone(XposedModule module, ClassLoader cl, Set<String> missed,
                                           String className, String methodName) {
        for (Method method : declaredMethods(cl, className, methodName, missed)) {
            try {
                module.hook(method).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof Integer) {
                        Object[] newArgs = args.toArray();
                        // View.GONE = 8。原版在 isShowAd=false 时也是传 8。
                        newArgs[0] = 8;
                        return chain.proceed(newArgs);
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                module.log(Log.ERROR, MainHook.TAG, "hook " + className + "." + methodName + ": " + t);
            }
        }
    }

    private static void finishAdActivity(XposedModule module, ClassLoader cl, Set<String> missed) {
        Method method = findMethod(cl, "com.tyg.tygsmart.ui.TygAdActivity", "onCreate", missed, Bundle.class);
        if (method == null) {
            return;
        }
        try {
            module.hook(method).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        ((Activity) self).finish();
                    }
                }
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, MainHook.TAG, "finishAdActivity: " + t);
        }
    }

    private static void noOp(XposedModule module, ClassLoader cl, Set<String> missed,
                             String className, String... methodNames) {
        for (String methodName : methodNames) {
            for (Method method : declaredMethods(cl, className, methodName, missed)) {
                Class<?> returnType = method.getReturnType();
                try {
                    module.hook(method).intercept(chain -> defaultValue(returnType));
                } catch (Throwable t) {
                    module.log(Log.ERROR, MainHook.TAG, "hook " + className + "." + methodName + ": " + t);
                }
            }
        }
    }

    private static void returnConstant(XposedModule module, ClassLoader cl, Set<String> missed,
                                       String className, String methodName, Object value) {
        for (Method method : declaredMethods(cl, className, methodName, missed)) {
            try {
                module.hook(method).intercept(chain -> value);
            } catch (Throwable t) {
                module.log(Log.ERROR, MainHook.TAG, "hook " + className + "." + methodName + ": " + t);
            }
        }
    }

    private static List<Method> declaredMethods(ClassLoader cl, String className,
                                                String methodName, Set<String> missed) {
        try {
            Class<?> target = Class.forName(className, false, cl);
            List<Method> matches = new ArrayList<>();
            for (Method method : target.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    matches.add(method);
                }
            }
            if (matches.isEmpty()) {
                missed.add(className + "." + methodName);
            }
            return matches;
        } catch (Throwable t) {
            missed.add(className + "（类未找到）");
            return new ArrayList<>();
        }
    }

    private static Class<?> findClass(ClassLoader cl, String className, Set<String> missed) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            missed.add(className + "（类未找到）");
            return null;
        }
    }

    private static Method findMethod(ClassLoader cl, String className, String methodName,
                                     Set<String> missed, Class<?>... parameterTypes) {
        Class<?> target = findClass(cl, className, missed);
        if (target == null) {
            return null;
        }
        try {
            return target.getDeclaredMethod(methodName, parameterTypes);
        } catch (Throwable t) {
            missed.add(className + "." + methodName);
            return null;
        }
    }

    private static void setStaticBooleanField(XposedModule module, Class<?> target, String fieldName,
                                              boolean value, Set<String> missed) {
        if (target == null) {
            return;
        }
        try {
            Field field = target.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable t) {
            missed.add(target.getName() + "." + fieldName);
            module.log(Log.WARN, MainHook.TAG, "set " + target.getName() + "." + fieldName + ": " + t);
        }
    }

    // 清空静态集合字段时按字段声明类型实例化空集合，避免拿 ArrayList 强赋给
    // CopyOnWriteArrayList 之类的不兼容类型（旧实现会抛异常后静默跳过）。
    private static void clearStaticCollectionField(XposedModule module, Class<?> target, String fieldName,
                                                   Set<String> missed) {
        if (target == null) {
            return;
        }
        try {
            Field field = target.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object empty = emptyInstanceOf(field.getType());
            if (empty == null) {
                missed.add(target.getName() + "." + fieldName);
                module.log(Log.WARN, MainHook.TAG, "clear " + target.getName() + "." + fieldName
                        + ": 无法为 " + field.getType().getName() + " 构造空集合");
                return;
            }
            field.set(null, empty);
        } catch (Throwable t) {
            missed.add(target.getName() + "." + fieldName);
            module.log(Log.WARN, MainHook.TAG, "clear " + target.getName() + "." + fieldName + ": " + t);
        }
    }

    private static Object emptyInstanceOf(Class<?> type) {
        if (type.isAssignableFrom(ArrayList.class)) {
            // List/Collection 等接口，或者字段本身就是 ArrayList。
            return new ArrayList<>();
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == short.class || type == Short.class) {
            return (short) 0;
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) 0;
        }
        if (type == float.class || type == Float.class) {
            return 0f;
        }
        if (type == double.class || type == Double.class) {
            return 0d;
        }
        if (type == char.class || type == Character.class) {
            return (char) 0;
        }
        return null;
    }
}

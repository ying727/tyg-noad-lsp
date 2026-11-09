package com.tyg.noad;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 只屏蔽广告，不修改登录、房间、授权码和 WebSocket 开门协议。
 * hook 点来自对 com.tyg.tygsmart V1.3.11 的静态逆向：
 *  - Global.isShowAd 是广告总开关，OpenDoorFragment/HomeFragment 在它为 false 时
 *    会调用 showAdvs(8) 把广告容器设为 GONE。
 *  - ScreenAdManager 负责开屏和开门后全屏广告，TTFeedAdLoadView/ToBidFeedAdLoadView
 *    负责信息流，TTAdManagerHolder/WindMillAd 负责第三方广告 SDK 初始化。
 */
final class AdBlocker {
    private static final String GLOBAL = "com.tyg.tygsmart.constant.Global";
    private static final String AD_FLAG_PREF = "tyg_isshow_ad";

    private AdBlocker() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        forceGlobalFlagsOff(module, cl);
        hookAdSwitchPreference(module, cl);

        // 广告状态、广告列表、第三方广告配置与广告埋点请求。
        noOp(module, cl, "com.tyg.tygsmart.network.RxjavaRetrofitNetworkUtils",
                "getAppAdOpenStatus", "downloadAdvs", "getThirdAppAdConfig",
                "getRewardVideoAdCodeId", "reportThirdAppAdEventLog");

        // 第三方广告 SDK 初始化（推送、SIP 对讲、XMPP 不在此列）。
        noOp(module, cl, "com.tyg.tygsmart.InitSDK", "initAdSdksInBackground");
        noOp(module, cl, "com.tyg.tygsmart.ad.ttad.TTAdManagerHolder", "init", "reInit", "doInit", "start");
        noOp(module, cl, "com.bytedance.sdk.openadsdk.TTAdSdk", "init", "start");
        noOp(module, cl, "com.windmill.sdk.WindMillAd", "startWithAppId");

        // 开屏广告、开门成功后的全屏广告。
        noOp(module, cl, "com.tyg.tygsmart.ad.ScreenAdManager",
                "loadAdv", "showAdv", "mediationPreloadAds", "showScreenTTAdvDialog", "showAdvDialog");
        returnConstant(module, cl, "com.tyg.tygsmart.ad.ScreenAdManager", "hasNotSource", true);
        noOp(module, cl, "com.tyg.tygsmart.ad.ttad.ScreenTTAdView",
                "showScreenTTAd", "showTTAdView", "changeScreenAdView", "loadExpressAd", "mediationPreloadAds");
        noOp(module, cl, "com.tyg.tygsmart.ad.tobid.ScreenToBidAdView",
                "showScreenTTAd", "showTTAdView", "changeScreenAdView", "loadExpressAd");

        // 开门页/首页的信息流广告。
        noOp(module, cl, "com.tyg.tygsmart.ad.ttad.TTFeedAdLoadView", "startLoadAd", "loadExpressAd", "showFeedAd");
        noOp(module, cl, "com.tyg.tygsmart.ad.tobid.ToBidFeedAdLoadView", "startLoadAd");
        noOp(module, cl, "com.tyg.tygsmart.ui.open_door.OpenDoorFragment", "getAdvs", "displayAdvs");
        noOp(module, cl, "com.tyg.tygsmart.ui.home.HomeFragment", "getAdvs", "displayAdvs");
        forceContainerGone(module, cl, "com.tyg.tygsmart.ui.open_door.OpenDoorFragment", "showAdvs");
        forceContainerGone(module, cl, "com.tyg.tygsmart.ui.home.HomeFragment", "showAdvs");

        // 最后防线：万一还有路径启动广告 Activity，直接结束它。
        finishAdActivity(module, cl);
    }

    private static void forceGlobalFlagsOff(XposedModule module, ClassLoader cl) {
        Class<?> global = findClass(module, cl, GLOBAL);
        if (global == null) {
            return;
        }
        setStaticBooleanField(module, global, "isShowAd", false);
        setStaticBooleanField(module, global, "isSdkReadyTTAd", false);
        setStaticBooleanField(module, global, "isSdkReadyWindMill", false);
        setStaticBooleanField(module, global, "isSdkReadyAdGain", false);
        setStaticObjectField(module, global, "sourceBeanList", new ArrayList<>());
    }

    private static void hookAdSwitchPreference(XposedModule module, ClassLoader cl) {
        Method method = findMethod(module, cl, "com.tyg.tygsmart.util.PrefUtils", "getBoolean",
                Context.class, String.class, boolean.class);
        if (method == null) {
            return;
        }
        try {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (AD_FLAG_PREF.equals(chain.getArg(1))) {
                    setStaticBooleanField(module, findClass(module, cl, GLOBAL), "isShowAd", false);
                    return false;
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, MainHook.TAG, "hookAdSwitchPreference: " + t);
        }
    }

    private static void forceContainerGone(XposedModule module, ClassLoader cl, String className, String methodName) {
        for (Method method : declaredMethods(module, cl, className, methodName)) {
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

    private static void finishAdActivity(XposedModule module, ClassLoader cl) {
        Method method = findMethod(module, cl, "com.tyg.tygsmart.ui.TygAdActivity", "onCreate", Bundle.class);
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

    private static void noOp(XposedModule module, ClassLoader cl, String className, String... methodNames) {
        for (String methodName : methodNames) {
            for (Method method : declaredMethods(module, cl, className, methodName)) {
                Class<?> returnType = method.getReturnType();
                try {
                    module.hook(method).intercept(chain -> defaultValue(returnType));
                } catch (Throwable t) {
                    module.log(Log.ERROR, MainHook.TAG, "hook " + className + "." + methodName + ": " + t);
                }
            }
        }
    }

    private static void returnConstant(XposedModule module, ClassLoader cl, String className, String methodName, Object value) {
        for (Method method : declaredMethods(module, cl, className, methodName)) {
            try {
                module.hook(method).intercept(chain -> value);
            } catch (Throwable t) {
                module.log(Log.ERROR, MainHook.TAG, "hook " + className + "." + methodName + ": " + t);
            }
        }
    }

    private static Method[] declaredMethods(XposedModule module, ClassLoader cl, String className, String methodName) {
        try {
            Class<?> target = Class.forName(className, false, cl);
            ArrayList<Method> matches = new ArrayList<>();
            for (Method method : target.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    matches.add(method);
                }
            }
            if (matches.isEmpty()) {
                module.log(Log.WARN, MainHook.TAG, "method not found: " + className + "." + methodName);
            }
            return matches.toArray(new Method[0]);
        } catch (Throwable t) {
            module.log(Log.DEBUG, MainHook.TAG, "class not found: " + className);
            return new Method[0];
        }
    }

    private static Class<?> findClass(XposedModule module, ClassLoader cl, String className) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            module.log(Log.DEBUG, MainHook.TAG, "class not found: " + className);
            return null;
        }
    }

    private static Method findMethod(XposedModule module, ClassLoader cl, String className, String methodName,
                                     Class<?>... parameterTypes) {
        Class<?> target = findClass(module, cl, className);
        if (target == null) {
            return null;
        }
        try {
            return target.getDeclaredMethod(methodName, parameterTypes);
        } catch (Throwable t) {
            module.log(Log.WARN, MainHook.TAG, "method not found: " + className + "." + methodName);
            return null;
        }
    }

    private static void setStaticBooleanField(XposedModule module, Class<?> target, String fieldName, boolean value) {
        if (target == null) {
            return;
        }
        try {
            Field field = target.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable t) {
            module.log(Log.WARN, MainHook.TAG, "set " + target.getName() + "." + fieldName + ": " + t);
        }
    }

    private static void setStaticObjectField(XposedModule module, Class<?> target, String fieldName, Object value) {
        if (target == null) {
            return;
        }
        try {
            Field field = target.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable t) {
            module.log(Log.WARN, MainHook.TAG, "set " + target.getName() + "." + fieldName + ": " + t);
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

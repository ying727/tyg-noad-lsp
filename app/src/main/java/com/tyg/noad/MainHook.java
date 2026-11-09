package com.tyg.noad;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class MainHook extends XposedModule {
    static final String TAG = "TygNoAd";
    static final String TARGET_PACKAGE = "com.tyg.tygsmart";

    private boolean installed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (installed || !TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        installed = true;
        log(Log.INFO, TAG, "attached to " + param.getPackageName());
        try {
            AdBlocker.install(this, param.getClassLoader());
            log(Log.INFO, TAG, "ad blockers installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed", t);
        }
    }
}

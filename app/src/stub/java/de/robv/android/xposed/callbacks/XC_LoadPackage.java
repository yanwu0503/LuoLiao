package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

import de.robv.android.xposed.XCallback;

/**
 * Stub: the framework provides the real class at run time.
 *
 * Extends XCallback from the PARENT package, which is why the import is explicit -- Java does not search the
 * parent package for a superclass.
 */
public class XC_LoadPackage extends XCallback {

    public static class LoadPackageParam extends Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }

    public static class Param {
    }
}

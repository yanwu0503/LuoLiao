package de.robv.android.xposed;

/** Stub: the framework provides the real interface at run time. */
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;

    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}

// Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by caikelun on 2019-03-07.
package xcrash;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.util.Map;

class NativeHandler {

    private static final NativeHandler instance = new NativeHandler();

    private ICrashCallback crashCallback = null;
    private ICrashCallback anrCallback = null;

    private boolean initNativeLibOk = false;
    private boolean anrEnable = false;

    private NativeHandler() {
    }

    static NativeHandler getInstance() {
        return instance;
    }

    int initialize(Context ctx,
                   ILibLoader libLoader,
                   String appId,
                   String appVersion,
                   String logDir,
                   boolean crashEnable,
                   boolean crashRethrow,
                   int crashLogcatSystemLines,
                   int crashLogcatEventsLines,
                   int crashLogcatMainLines,
                   boolean crashDumpElfHash,
                   boolean crashDumpMap,
                   boolean crashDumpFds,
                   boolean crashDumpAllThreads,
                   int crashDumpAllThreadsCountMax,
                   String[] crashDumpAllThreadsWhiteList,
                   ICrashCallback crashCallback,
                   boolean anrEnable,
                   int anrLogCountMax,
                   int anrLogcatSystemLines,
                   int anrLogcatEventsLines,
                   int anrLogcatMainLines,
                   boolean anrDumpFds,
                   ICrashCallback anrCallback) {
        //load lib
        if (libLoader == null) {
            try {
                System.loadLibrary("xcrash");
            } catch (Throwable e) {
                XCrash.getLogger().e(Util.TAG, "NativeHandler System.loadLibrary failed", e);
                return Errno.LOAD_LIBRARY_FAILED;
            }
        } else {
            try {
                libLoader.loadLibrary("xcrash");
            } catch (Throwable e) {
                XCrash.getLogger().e(Util.TAG, "NativeHandler ILibLoader.loadLibrary failed", e);
                return Errno.LOAD_LIBRARY_FAILED;
            }
        }

        this.crashCallback = crashCallback;
        this.anrCallback = anrCallback;
        this.anrEnable = (anrEnable && Build.VERSION.SDK_INT >= 21);

        //init native lib
        try {
            int r = nativeInit(
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE,
                Util.getAbiList(),
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.FINGERPRINT,
                appId,
                appVersion,
                ctx.getApplicationInfo().nativeLibraryDir,
                logDir,
                crashEnable,
                crashRethrow,
                crashLogcatSystemLines,
                crashLogcatEventsLines,
                crashLogcatMainLines,
                crashDumpElfHash,
                crashDumpMap,
                crashDumpFds,
                crashDumpAllThreads,
                crashDumpAllThreadsCountMax,
                crashDumpAllThreadsWhiteList,
                this.anrEnable,
                anrLogCountMax,
                anrLogcatSystemLines,
                anrLogcatEventsLines,
                anrLogcatMainLines,
                anrDumpFds);
            if (r != 0) {
                XCrash.getLogger().e(Util.TAG, "NativeHandler init failed");
                return Errno.INIT_LIBRARY_FAILED;
            }
            initNativeLibOk = true;
            return 0; //OK
        } catch (Throwable e) {
            XCrash.getLogger().e(Util.TAG, "NativeHandler init failed", e);
            return Errno.INIT_LIBRARY_FAILED;
        }
    }

    void notifyJavaCrashed() {
        if (initNativeLibOk && this.anrEnable) {
            NativeHandler.nativeNotifyJavaCrashed();
        }
    }

    void testNativeCrash(boolean runInNewThread) {
        if (initNativeLibOk) {
            NativeHandler.nativeTestCrash(runInNewThread ? 1 : 0);
        }
    }

    void testAnr() {
        if (initNativeLibOk) {
            NativeHandler.nativeTestAnr();
        }
    }

    private static String getStacktraceByThreadName(boolean isMainThread, String threadName) {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thd = entry.getKey();
                if ((isMainThread && thd.getName().equals("main")) || (!isMainThread && thd.getName().contains(threadName))) {
                    StringBuilder sb = new StringBuilder();
                    for (StackTraceElement element : entry.getValue()) {
                        sb.append("    at ").append(element.toString()).append("\n");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "NativeHandler getStacktraceByThreadName failed", e);
        }
        return null;
    }

    // do NOT obfuscate this method
    @SuppressWarnings("unused")
    private static void crashCallback(String logPath, String emergency, boolean dumpJavaStacktrace, boolean isMainThread, String threadName) {
        if (!TextUtils.isEmpty(logPath)) {

            //append java stacktrace
            if (dumpJavaStacktrace) {
                String stacktrace = getStacktraceByThreadName(isMainThread, threadName);
                if (!TextUtils.isEmpty(stacktrace)) {
                    TombstoneManager.appendSection(logPath, "java stacktrace", stacktrace);
                }
            }

            //append memory info
            TombstoneManager.appendSection(logPath, "memory info", Util.getProcessMemoryInfo());
        }

        ICrashCallback callback = NativeHandler.getInstance().crashCallback;
        if (callback != null) {
            try {
                callback.onCrash(logPath, emergency);
            } catch (Exception e) {
                XCrash.getLogger().w(Util.TAG, "NativeHandler native crash callback.onCrash failed", e);
            }
        }
    }

    // do NOT obfuscate this method
    @SuppressWarnings("unused")
    private static void anrCallback(String logPath, String emergency) {
        if (!TextUtils.isEmpty(logPath)) {
            //append memory info
            TombstoneManager.appendSection(logPath, "memory info", Util.getProcessMemoryInfo());
        }

        ICrashCallback callback = NativeHandler.getInstance().anrCallback;
        if (callback != null) {
            try {
                callback.onCrash(logPath, emergency);
            } catch (Exception e) {
                XCrash.getLogger().w(Util.TAG, "NativeHandler ANR callback.onCrash failed", e);
            }
        }
    }

    private static native int nativeInit(
            int apiLevel,
            String osVersion,
            String abiList,
            String manufacturer,
            String brand,
            String model,
            String buildFingerprint,
            String appId,
            String appVersion,
            String appLibDir,
            String logDir,
            boolean crashEnable,
            boolean crashRethrow,
            int crashLogcatSystemLines,
            int crashLogcatEventsLines,
            int crashLogcatMainLines,
            boolean crashDumpElfHash,
            boolean crashDumpMap,
            boolean crashDumpFds,
            boolean crashDumpAllThreads,
            int crashDumpAllThreadsCountMax,
            String[] crashDumpAllThreadsWhiteList,
            boolean anrEnable,
            int anrLogCountMax,
            int anrLogcatSystemLines,
            int anrLogcatEventsLines,
            int anrLogcatMainLines,
            boolean anrDumpFds);

    private static native void nativeNotifyJavaCrashed();

    private static native void nativeTestCrash(int runInNewThread);

    private static native void nativeTestAnr();
}
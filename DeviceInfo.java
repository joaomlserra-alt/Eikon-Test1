package com.eikon.diag;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;

final class DeviceInfo {
    static final double MB = 1048576.0;

    static long availMem(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    static String battery(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return "n/d";
        int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int pl = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return String.format(Locale.ROOT, "%d%%, %.1f °C, %s", lvl * 100 / Math.max(1, sc), t / 10.0,
                pl == 0 ? "a descarregar" : "ligado ao carregador");
    }

    static String thermal(Context c) {
        if (Build.VERSION.SDK_INT < 29) return "n/d (Android < 10)";
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            int s = pm.getCurrentThermalStatus();
            String[] n = {"nenhum", "leve", "moderado", "severo", "crítico", "emergência", "desligar"};
            return s >= 0 && s < n.length ? n[s] : String.valueOf(s);
        } catch (Throwable t) {
            return "n/d";
        }
    }

    private static String readLine(String path, String prefix) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String ln;
            while ((ln = r.readLine()) != null) if (ln.startsWith(prefix)) return ln.trim();
        } catch (Throwable ignored) { }
        return null;
    }

    static void run(Context c, Rep rep) { run(c, rep, "EIKON DIAG v1"); }

    static void run(Context c, Rep rep, String title) {
        rep.h("Dispositivo e sistema");
        rep.kv("Modelo", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        rep.kv("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), patch " + Build.VERSION.SECURITY_PATCH);
        rep.kv("Build", Build.DISPLAY);
        rep.kv("Hardware / board", Build.HARDWARE + " / " + Build.BOARD);
        if (Build.VERSION.SDK_INT >= 31) rep.kv("SoC", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        rep.kv("ABIs", Arrays.toString(Build.SUPPORTED_ABIS));
        int cores = Runtime.getRuntime().availableProcessors();
        rep.kv("Núcleos visíveis", cores);
        StringBuilder fr = new StringBuilder();
        for (int i = 0; i < cores; i++) {
            String v = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq", "");
            if (v == null) {
                try (BufferedReader r = new BufferedReader(new FileReader("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))) {
                    v = r.readLine();
                } catch (Throwable t) { v = null; }
            }
            fr.append(v == null ? "?" : (Long.parseLong(v.trim()) / 1000) + "MHz").append(i < cores - 1 ? " " : "");
        }
        rep.kv("Freq. máx por núcleo", fr);
        String hw = readLine("/proc/cpuinfo", "Hardware");
        if (hw != null) rep.kv("cpuinfo", hw);

        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        rep.kv("RAM total", String.format(Locale.ROOT, "%.0f MB", mi.totalMem / MB));
        rep.kv("RAM livre (availMem)", String.format(Locale.ROOT, "%.0f MB", mi.availMem / MB));
        rep.kv("Limiar de pouca memória", String.format(Locale.ROOT, "%.0f MB", mi.threshold / MB));
        rep.kv("Pouca memória agora", mi.lowMemory);
        rep.kv("memoryClass / large", am.getMemoryClass() + " MB / " + am.getLargeMemoryClass() + " MB");
        rep.kv("Heap Java máx (com largeHeap)", String.format(Locale.ROOT, "%.0f MB", Runtime.getRuntime().maxMemory() / MB));
        rep.kv("isLowRamDevice", am.isLowRamDevice());
        for (String k : new String[]{"MemTotal", "MemFree", "MemAvailable", "Cached", "SwapTotal", "SwapFree"}) {
            String v = readLine("/proc/meminfo", k + ":");
            if (v != null) rep.kv("/proc/meminfo " + k, v.substring(k.length() + 1).trim());
        }
        try {
            StatFs sf = new StatFs(c.getCacheDir().getAbsolutePath());
            rep.kv("Armazenamento livre", String.format(Locale.ROOT, "%.1f GB", sf.getAvailableBytes() / 1073741824.0));
        } catch (Throwable ignored) { }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        rep.kv("Ecrã", dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi + "dpi");
        rep.kv("Bateria", battery(c));
        rep.kv("Estado térmico", thermal(c));
        ConfigurationInfo ci = am.getDeviceConfigurationInfo();
        rep.kv("OpenGL ES", ci.getGlEsVersion());
        PackageManager pm = c.getPackageManager();
        String[] feats = {"android.hardware.camera", "android.hardware.camera.front", "android.hardware.camera.autofocus",
                "android.hardware.camera.flash", "android.hardware.camera.level.full", "android.hardware.camera.capability.manual_sensor",
                "android.hardware.camera.capability.manual_post_processing", "android.hardware.camera.capability.raw",
                "android.hardware.camera.concurrent", "android.hardware.vulkan.version", "android.hardware.opengles.aep"};
        for (String f : feats) rep.kv("feature " + f.replace("android.hardware.", ""), pm.hasSystemFeature(f));

        rep.sum(title + " | " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new java.util.Date()));
        rep.sum("Telemóvel: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        rep.sum(String.format(Locale.ROOT, "RAM: total %.0f MB, livre no arranque %.0f MB, heap Java máx %.0f MB, %d núcleos",
                mi.totalMem / MB, mi.availMem / MB, Runtime.getRuntime().maxMemory() / MB, cores));
        rep.sum("Bateria: " + battery(c) + " | Térmico: " + thermal(c));
    }

    static File cache(Context c) { return c.getCacheDir(); }
}

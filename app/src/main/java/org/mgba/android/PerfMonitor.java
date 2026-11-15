package org.mgba.android;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Collects the numbers shown in the on-screen performance monitor. Everything here is best
 * effort: Android hides many of these values from normal apps, in which case "n/d" is shown.
 */
final class PerfMonitor {

    private static final String[] GPU_LOAD_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",   // Adreno: "12 %"
            "/sys/class/kgsl/kgsl-3d0/gpubusy",               // Adreno: "busy total"
            "/sys/class/misc/mali0/device/utilization",       // Mali
            "/sys/kernel/gpu/gpu_busy",                       // generic vendor node: "12%"
    };
    private static final String[] GPU_CLOCK_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpuclk",                // Hz
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",      // Hz
            "/sys/kernel/gpu/gpu_clock",                      // MHz
    };

    private final ActivityManager activityManager;
    private final ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
    private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

    private long lastCpuMs = -1, lastWallNs;
    private int gpuLoadPath = -2, gpuClockPath = -2; // -2 = not probed yet, -1 = unavailable
    private boolean cpuClockReadable = true;

    PerfMonitor(Context context) {
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /** Builds the text lines for the monitor. Called about once per second, off the UI thread. */
    String[] sample(String fpsLine, String rendererLine) {
        ArrayList<String> out = new ArrayList<>();
        out.add(fpsLine);
        out.add(rendererLine);
        out.add(cpuLine());
        out.add("Clock CPU: " + cpuClocks());
        out.add(gpuLine());
        out.add(ramLine());
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ CPU
    private String cpuLine() {
        long cpuMs = android.os.Process.getElapsedCpuTime();
        long now = System.nanoTime();
        String text = "CPU (app): …";
        if (lastCpuMs >= 0 && now > lastWallNs) {
            double wallMs = (now - lastWallNs) / 1e6;
            double coresUsed = (cpuMs - lastCpuMs) / wallMs;
            text = String.format(Locale.US, "CPU (app): %.0f%% (%.1f de %d núcleos)",
                    coresUsed * 100.0 / cores, coresUsed, cores);
        }
        lastCpuMs = cpuMs;
        lastWallNs = now;
        return text;
    }

    /** Current frequency of every core, grouped, e.g. "4×1.8 3×2.4 1×3.0 GHz". */
    private String cpuClocks() {
        if (!cpuClockReadable) return "n/d";
        StringBuilder sb = new StringBuilder();
        long prev = -1;
        int run = 0, found = 0;
        for (int i = 0; i <= cores; i++) {
            long khz = -1;
            if (i < cores) {
                khz = parseLong(readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"));
                if (khz > 0) found++;
            }
            long rounded = (khz > 0) ? Math.round(khz / 100000.0) : -1; // tenths of a GHz
            if (i < cores && rounded == prev) {
                run++;
                continue;
            }
            if (run > 0 && prev > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(run).append('×').append(prev / 10).append('.').append(prev % 10);
            }
            prev = rounded;
            run = 1;
        }
        if (found == 0) {
            cpuClockReadable = false;
            return "n/d";
        }
        return sb.append(" GHz").toString();
    }

    // ------------------------------------------------------------------ GPU
    private String gpuLine() {
        if (gpuLoadPath == -2) gpuLoadPath = probe(GPU_LOAD_PATHS);
        if (gpuClockPath == -2) gpuClockPath = probe(GPU_CLOCK_PATHS);

        String load = null, clock = null;
        if (gpuLoadPath >= 0) {
            String line = readLine(GPU_LOAD_PATHS[gpuLoadPath]);
            if (line != null) {
                String[] p = line.trim().split("[\\s%]+");
                try {
                    if (gpuLoadPath == 1 && p.length >= 2) {
                        long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
                        load = (total > 0 ? Math.round(busy * 100.0 / total) : 0) + "%";
                    } else if (p.length >= 1 && !p[0].isEmpty()) {
                        load = Long.parseLong(p[0]) + "%";
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        if (gpuClockPath >= 0) {
            long v = parseLong(readLine(GPU_CLOCK_PATHS[gpuClockPath]));
            if (v > 0) clock = Math.round(v > 100000 ? v / 1e6 : v) + " MHz";
        }
        if (load == null && clock == null) return "GPU: n/d (o Android não libera)";
        return "GPU: " + (load != null ? load : "uso n/d") + " · " + (clock != null ? clock : "clock n/d");
    }

    private static int probe(String[] paths) {
        for (int i = 0; i < paths.length; i++) {
            if (readLine(paths[i]) != null) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ RAM
    private String ramLine() {
        long rssKb = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    rssKb = parseLong(line.substring(6).replace("kB", ""));
                    break;
                }
            }
        } catch (Exception ignored) { }
        String app = (rssKb > 0) ? (rssKb / 1024) + " MB" : "n/d";
        String sys = "";
        try {
            activityManager.getMemoryInfo(memInfo);
            sys = String.format(Locale.US, " · livre %.1f de %.1f GB",
                    memInfo.availMem / 1073741824.0, memInfo.totalMem / 1073741824.0);
        } catch (RuntimeException ignored) { }
        return "RAM (app): " + app + sys;
    }

    // ------------------------------------------------------------------ helpers
    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path), 128)) {
            return r.readLine();
        } catch (Exception e) {
            return null; // missing file or blocked by the system
        }
    }

    private static long parseLong(String s) {
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

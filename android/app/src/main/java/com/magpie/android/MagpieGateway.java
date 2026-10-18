package com.magpie.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The bundled magpie binary, run as a child process of this app.
 *
 * <p>The binary ships as {@code lib/<abi>/libmagpie.so}: an APK can only carry
 * an executable that way, and the one directory Android lets an app exec from
 * is its native library directory. Everything magpie writes is pointed at the
 * app's own files directory through HOME and the XDG variables, exactly the way
 * the Docker image does it.
 */
public final class MagpieGateway {

    private static final String TAG = "MagpieGateway";
    private static final String BINARY = "libmagpie.so";
    /** Bun, the external plugins' runtime, carried as a library for the same reason. */
    private static final String BUN = "libmagpiebun.so";
    private static final String LOG_NAME = "magpie.log";
    private static final long LOG_MAX_BYTES = 2L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean stopping = new AtomicBoolean(false);
    private static Process process;
    private static int restarts;

    private MagpieGateway() {
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    /** Starts the gateway unless it is already up. */
    public static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                return;
            }
            stopping.set(false);
            spawn(app);
        }
    }

    /** Stops the gateway and leaves it stopped (no watchdog restart). */
    public static void stop() {
        Process p;
        synchronized (LOCK) {
            stopping.set(true);
            p = process;
            process = null;
        }
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether something accepts connections on host:port right now. */
    public static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The address agents point at, e.g. http://127.0.0.1:3425. */
    public static String gatewayUrl(Context ctx) {
        return "http://127.0.0.1:" + MagpiePrefs.gatewayPort(ctx);
    }

    /** The page's URL, carrying the key, as magpie's own `magpie web` prints it. */
    public static String pageUrl(Context ctx) {
        return "http://127.0.0.1:" + MagpiePrefs.webPort(ctx) + "/?k=" + MagpiePrefs.webKey(ctx);
    }

    public static File logFile(Context ctx) {
        return new File(dir(ctx, "logs"), LOG_NAME);
    }

    /**
     * The bundled Bun, or null when this build carries none: magpie then
     * downloads one itself, which Android won't let it run (see spawn).
     */
    public static File bundledBun(Context ctx) {
        File bun = new File(ctx.getApplicationInfo().nativeLibraryDir, BUN);
        return bun.isFile() ? bun : null;
    }

    /** Whether this build carries Bun, so external plugins can run. */
    public static boolean hasBundledBun(Context ctx) {
        return bundledBun(ctx) != null;
    }

    public static void logLine(Context ctx, String line) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (OutputStream out = new FileOutputStream(logFile(ctx), true)) {
            out.write((stamp + "  " + line + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "could not append to the log", e);
        }
    }

    private static void spawn(Context ctx) {
        File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, BINARY);
        if (!bin.isFile()) {
            logLine(ctx, "the gateway binary is missing: " + bin);
            return;
        }
        bin.setExecutable(true, false);
        if (!bin.canExecute()) {
            logLine(ctx, "the gateway binary cannot be executed: " + bin);
        }

        File home = dir(ctx, "home");
        File logs = dir(ctx, "logs");
        File log = logFile(ctx);
        if (log.length() > LOG_MAX_BYTES) {
            File old = new File(logs, LOG_NAME + ".1");
            old.delete();
            log.renameTo(old);
        }

        int gatewayPort = MagpiePrefs.gatewayPort(ctx);
        int webPort = MagpiePrefs.webPort(ctx);

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        // The page stays on loopback: it can show and change every API key, so
        // it is not the thing to put on a network.
        cmd.add("--web=127.0.0.1:" + webPort);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", dir(ctx, "config").getAbsolutePath());
        env.put("XDG_CACHE_HOME", dir(ctx, "cache").getAbsolutePath());
        env.put("XDG_DATA_HOME", dir(ctx, "data").getAbsolutePath());
        env.put("XDG_STATE_HOME", dir(ctx, "state").getAbsolutePath());
        env.put("TMPDIR", dir(ctx, "tmp").getAbsolutePath());
        env.put("TMP", dir(ctx, "tmp").getAbsolutePath());
        env.put("PATH", ctx.getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin:/product/bin");
        env.put("LANG", "C.UTF-8");
        env.put("MAGPIE_ADDR", (MagpiePrefs.lan(ctx) ? "0.0.0.0:" : "127.0.0.1:") + gatewayPort);
        env.put("MAGPIE_WEB_KEY", MagpiePrefs.webKey(ctx));
        // Bun is exec'd from the native library directory, the one place
        // Android runs an app's own binaries from: without this, magpie would
        // download a Bun into its files directory and fail to run it, since
        // Android 10+ forbids executing anything there.
        File bun = bundledBun(ctx);
        if (bun != null) {
            bun.setExecutable(true, false);
            env.put("MAGPIE_BUN", bun.getAbsolutePath());
            // Android's seccomp filter refuses a syscall Bun's JIT makes while
            // it installs a package with many dependencies: bun add dies with
            // "signal: bad system call" and the plugin is never written. With
            // the JIT off the same install completes, so the plugin host and
            // every bun add/update run interpretively.
            env.put("BUN_JSC_useJIT", "0");
            env.put("BUN_JSC_useConcurrentJIT", "0");
            logLine(ctx, "plugins run on the bundled Bun: " + bun.getAbsolutePath());
        }
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");

        try {
            Process p = pb.start();
            synchronized (LOCK) {
                process = p;
            }
            logLine(ctx, "started " + (MagpiePrefs.lan(ctx) ? "0.0.0.0:" : "127.0.0.1:") + gatewayPort
                    + ", page on 127.0.0.1:" + webPort);
            pump(p, log);
            watch(ctx, p);
        } catch (IOException e) {
            logLine(ctx, "could not start the gateway: " + e);
            Log.w(TAG, "could not start the gateway", e);
        }
    }

    /** The child's output, straight into the log file the menu shows. */
    private static void pump(Process p, File log) {
        Thread thread = new Thread(() -> {
            try (OutputStream out = new FileOutputStream(log, true);
                 InputStream in = p.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // the process ended; the watchdog says so
            }
        }, "magpie-log");
        thread.setDaemon(true);
        thread.start();
    }

    /** Restarts the gateway when it exits on its own, backing off as it repeats. */
    private static void watch(Context ctx, Process p) {
        Thread thread = new Thread(() -> {
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            synchronized (LOCK) {
                if (process == p) {
                    process = null;
                }
            }
            if (stopping.get()) {
                logLine(ctx, "the gateway stopped");
                return;
            }
            long delay = backoff(++restarts);
            logLine(ctx, "the gateway exited (code " + code + "); starting it again in " + delay + " ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            if (!stopping.get()) {
                start(ctx);
            }
        }, "magpie-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private static long backoff(int attempt) {
        switch (attempt) {
            case 1:
                return 1000;
            case 2:
                return 2000;
            case 3:
                return 5000;
            case 4:
                return 15000;
            default:
                return 30000;
        }
    }

    private static File dir(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "could not make " + dir);
        }
        return dir;
    }
}

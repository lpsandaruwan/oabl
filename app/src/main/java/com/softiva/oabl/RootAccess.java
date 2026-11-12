package com.softiva.oabl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Actual per-app superuser access, not build tags or the presence of a root app. */
final class RootAccess {
    interface Runner { Result run(String command); }
    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }
    static boolean granted(Result result) {
        return result != null && result.exitCode == 0 && "0".equals(result.output.trim());
    }
    static boolean check(Runner runner) { return granted(runner.run("id -u")); }
    static boolean check() { return check(RootAccess::run); }

    // Always invoke off the main thread. A permission prompt may be shown by the root manager.
    static Result run(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final InputStream stream = process.getInputStream();
            final String[] collected = {null};
            Thread reader = new Thread(() -> {
                try { collected[0] = read(stream); }
                catch (IOException failed) { collected[0] = null; }
            }, "oabl-root-output");
            reader.setDaemon(true); reader.start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (true) {
                try {
                    int code = process.exitValue();
                    reader.join(1000);
                    return collected[0] == null ? new Result(-1, "Root output unavailable or truncated.") : new Result(code, collected[0]);
                } catch (IllegalThreadStateException waiting) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline)
                        return new Result(-1, "Root permission check timed out. Grant access in your root manager, then retry.");
                    Thread.sleep(40);
                }
            }
        } catch (IOException unavailable) {
            return new Result(-1, "Superuser command unavailable.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Root operation cancelled.");
        } finally {
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
        }
    }
    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512]; int count;
        boolean truncated = false;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size()+count <= 32768) output.write(buffer, 0, count);
            else truncated = true; // Drain the pipe, but never accept incomplete policy/sensor reports.
        }
        if (truncated) throw new IOException("Root output too large");
        return output.toString("UTF-8");
    }
}

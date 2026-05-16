package frb.axeron.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AxeronCommandSession {
    private final AtomicBoolean isProcessRunning = new AtomicBoolean(false);
    private final AtomicInteger pid = new AtomicInteger(-1);
    private final AtomicInteger exitCode = new AtomicInteger(-1);
    Handler mainHandler = new Handler(Looper.getMainLooper());
    Handler outputHandler = new Handler(Looper.getMainLooper());
    Thread outThread, errThread, waitThread;
    Handler finishHandler = new Handler(Looper.getMainLooper());
    private String injectEnv;
    private String injectExport;
    private AxeronNewProcess process;
    private BufferedWriter writer;
    private BufferedReader bufferedReader;
    private BufferedReader bufferedError;
    private final AtomicReference<String> lastOutput = new AtomicReference<>("");
    private ResultListener resultListener;
    private ProcessListener processListener;

    public static String[] getQuickCmd(
            String cmd,
            boolean useBusybox,
            boolean withPid
    ) {
        String execCmd;

        if (withPid) {
            execCmd =
                    "export PARENT_PID=$$; " +
                            "echo $PARENT_PID 1>&2; " +
                            "exec -a \"QuickShell\" sh -c \"$0\"";
        } else {
            execCmd =
                    "exec -a \"QuickShell\" sh -c \"$0\"";
        }

        if (useBusybox) {
            return new String[]{
                    AxeronPluginService.INSTANCE.getBUSYBOX(),
                    "setsid",
                    "sh",
                    "-c",
                    execCmd,
                    cmd
            };
        } else {
            return new String[]{
                    "setsid",
                    "sh",
                    "-c",
                    execCmd,
                    cmd
            };
        }
    }

    public String getEnv() {
        return injectEnv;
    }

    public void setEnv(String env) {
        injectEnv = env;
    }

    public String getExport() {
        return injectExport;
    }

    public void setExport(String export) {
        this.injectExport = export;
    }

    public void setResultListener(ResultListener resultListener) {
        this.resultListener = resultListener;
    }

    public void setProcessListener(ProcessListener processListener) {
        this.processListener = processListener;
    }

    public synchronized void runCommand(String input, boolean isCompatModeEnabled) {
        try {
            if (isProcessRunning.get()) {
                Log.d("CmdOut", "write: " + input);
                writeToProcess(input);
            } else {
                Log.d("CmdOut", "newProcess: " + input);
                startNewProcess(input, isCompatModeEnabled);
            }
        } catch (IOException | RuntimeException e) {
            errorListener("command: " + e.getMessage());
        }
    }

    public void killSession() {
        if (pid.get() > 0) {
            // Kill the entire process group (negative pid = pgid).
            // setsid was used, so the shell is the process group leader
            // and pgid == pid.  SIGKILL is uncatchable.
            Axeron.newProcess("kill -KILL -- -" + pid.get());
        }
        destroy();
    }

    private void startNewProcess(String command, boolean isCompatModeEnabled) {
        destroy(); // bersihkan jika ada
        exitCode.set(0);
        pid.set(-1);

        process = Axeron.newProcess(getQuickCmd(
                        command,
                        isCompatModeEnabled,
                        true
                ),
                Axeron.getEnvironment(),
                null);
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        bufferedError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        outThread = new Thread(() -> {
            try {
                char[] buffer = new char[1024 * 2];
                int bytesRead;

                while ((bytesRead = bufferedReader.read(buffer)) != -1) {
                    String part = new String(buffer, 0, bytesRead);

                    if (isProcessRunning.get() && resultListener != null) {
                        lastOutput.set(part);
                        outputHandler.post(() -> resultListener.output(part));
                    }
                }
            } catch (IOException e) {
                if (isProcessRunning.get()) {
                    errorListener("stdout: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                errorListener("stdout: " + e.getMessage());
            }
        }, "SessionOutThread");

        errThread = new Thread(() -> {
            try {
                char[] buffer = new char[1024 * 2];
                int bytesRead;

                while ((bytesRead = bufferedError.read(buffer)) != -1) {
                    String finalLine = new String(buffer, 0, bytesRead);
                    Log.d("CmdOut", "error: " + finalLine);
                    Log.d("CmdOut", "isProcess: " + isProcessRunning.get());

                    // Detect PID from first line of stderr
                    if (finalLine.trim().matches("^\\d+$")) {
                        if (pid.compareAndSet(-1, Integer.parseInt(finalLine.trim()))) {
                            Log.d("CmdOut", "pid: " + pid.get());
                            if (processListener != null) {
                                mainHandler.post(() -> processListener.onProcessCreated(pid.get(), command));
                            }
                        }
                        continue;
                    }

                    if (isProcessRunning.get() && resultListener != null) {
                        mainHandler.post(() -> resultListener.onError(finalLine));
                    }

                }
            } catch (IOException e) {
                if (isProcessRunning.get()) {
                    errorListener("stderr: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                errorListener("stderr: " + e.getMessage());
            }
        }, "SessionErrThread");

        waitThread = new Thread(() -> {
            try {
                if (outThread != null) outThread.join();
                if (errThread != null) errThread.join();

                int code = process.waitFor();
                exitCode.set(code);

                Log.d("CommandSession", "Process selesai, exitCode = " + exitCode);
            } catch (InterruptedException | RuntimeException ignored) {
            } finally {
                destroy();
            }

        }, "SessionWaitThread");

        isProcessRunning.set(true);
        outThread.start();
        errThread.start();
        waitThread.start();
    }

    private void errorListener(String error) {
        if (resultListener != null)
            mainHandler.post(() -> resultListener.onError(error));
    }

    private void writeToProcess(String input) throws IOException {
        if (writer != null) {
            if (processListener != null) processListener.onProcessRunning(input);
            writer.write(input);
            writer.newLine();
            writer.flush();
            Log.d("CommandSession", "Input dikirim: " + input);
        }
    }

    public synchronized void destroy() {
        String outputForCallback = null;
        boolean wasRunning = isProcessRunning.getAndSet(false);

        try {
            // 1. Interrupt threads as a signal to stop
            if (outThread != null) outThread.interrupt();
            if (errThread != null) errThread.interrupt();
            if (waitThread != null && waitThread != Thread.currentThread()) waitThread.interrupt();

            // 2. Close stdin writer (always safe)
            if (writer != null) writer.close();

            // 3. Close readers — unblocks blocked read() calls.
            //    Reader threads check isProcessRunning and suppress the IOException
            if (bufferedReader != null) bufferedReader.close();
            if (bufferedError != null) bufferedError.close();

            // 4. Kill the OS process
            if (process != null) process.destroy();

            // 5. Capture last output for callback
            outputForCallback = lastOutput.get();
            lastOutput.set(null);

        } catch (IOException | RuntimeException e) {
            errorListener("destroy: " + e.getMessage());
        }

        if (wasRunning && processListener != null) {
            int code = exitCode.get();
            String finalOutput = outputForCallback != null ? outputForCallback : "";
            finishHandler.post(() -> processListener.onProcessFinished(code, finalOutput));
        }

        // 6. Cleanup references
        writer = null;
        bufferedReader = null;
        bufferedError = null;
        process = null;
        outThread = null;
        errThread = null;
        waitThread = null;
    }

    public interface ResultListener {
        void output(CharSequence output);

        void onError(CharSequence error);
    }

    public interface ProcessListener {
        void onProcessCreated(int pid, @NonNull String command);

        void onProcessRunning(@NonNull String input);

        void onProcessFinished(int exitCode, @NonNull String lastOutput);
    }

}

package com.mclauncher;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Ui implements AutoCloseable, ProgressUi {
    private final Terminal terminal;
    private final PrintStream out;
    private LogWindow logWindow;

    private final BlockingQueue<Integer> keyQueue = new LinkedBlockingQueue<>();

    private static final String[] BANNER = {
            "███╗   ███╗  ██████╗ ███████╗ ██╗",
            "████╗ ████║ ██╔════╝ ██╔════╝ ██║",
            "██╔████╔██║ ██║      █████╗   ██║",
            "██║╚██╔╝██║ ██║      ██╔══╝   ██║",
            "██║ ╚═╝ ██║ ╚██████╗ ██║      ███████╗",
            "╚═╝     ╚═╝  ╚═════╝ ╚═╝      ╚══════╝"
    };

    private final String[] infoLines = new String[3];

    private int lastLoggedPercent = -1;
    private String lastLoggedTask = "";

    private String taskName = "";
    private int taskPercent = 0;
    private long globalStartTime = 0;

    private String fileName = "";
    private int filePercent = 0;
    private long fileStartTime = 0;
    private long fileTotal = 0;
    private long fileDownloaded = 0;

    private boolean multiLineDrawn = false;
    private long lastRenderTime = 0;

    public Ui() throws IOException {
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .build();
        this.out = AnsiConsole.out();

        terminal.handle(Terminal.Signal.WINCH, signal -> {
            synchronized (this) {
                try {
                    multiLineDrawn = false;
                    clear();
                    if (globalStartTime > 0) {
                        renderMultiLine(true);
                    }
                } catch (Exception ignored) {}
            }
        });

        Thread readerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int c = terminal.reader().read();
                    if (c == -1) break;
                    keyQueue.offer(c);
                }
            } catch (Exception ignored) {}
        });
        readerThread.setDaemon(true);
        readerThread.setName("Ui-KeyReader");
        readerThread.start();

        Thread ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(500);
                    if (multiLineDrawn) {
                        renderMultiLine(true);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        ticker.setDaemon(true);
        ticker.start();
    }

    public void attachLogWindow(LogWindow w) {
        this.logWindow = w;
    }

    public synchronized void printBanner() {
        for (String line : BANNER) {
            out.println(line);
        }
        out.flush();
    }

    public synchronized void setInfo(int line, String text) {
        if (line < 0 || line >= infoLines.length) return;
        infoLines[line] = text;
        if (multiLineDrawn) renderMultiLine(true);
    }

    public synchronized void clearInfo() {
        Arrays.fill(infoLines, null);
        if (multiLineDrawn) renderMultiLine(true);
    }

    public synchronized void clear() {
        if (multiLineDrawn) {
            finishProgress();
        }
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                out.print("\033[H\033[2J");
                out.flush();
            }
        } catch (Exception ignored) {}
        multiLineDrawn = false;
        clearInfo();
        printBanner();
    }

    public synchronized void println() {
        println("");
    }

    public synchronized void println(String s) {
        if (multiLineDrawn) {
            finishProgress();
        }
        out.println(s);
        out.flush();
        if (logWindow != null) logWindow.append(s);
    }

    public void log(String s) {
        if (logWindow != null) logWindow.appendTimestamped(s);
    }

    public synchronized void print(String s) {
        if (multiLineDrawn) {
            finishProgress();
        }
        out.print(s);
        out.flush();
    }

    public int readKey() {
        try {
            return keyQueue.take();
        } catch (InterruptedException e) {
            return -1;
        }
    }

    public int readKey(long timeoutMs) {
        try {
            Integer k = keyQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return k != null ? k : -2;
        } catch (InterruptedException e) {
            return -2;
        }
    }

    public String readLine(String prompt) {
        Integer head;
        while ((head = keyQueue.peek()) != null && (head == '\n' || head == '\r')) {
            keyQueue.poll();
        }
        out.print(prompt);
        out.flush();
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c;
            try {
                c = keyQueue.take();
            } catch (InterruptedException e) {
                return null;
            }
            if (c == -1) return null;

            // ================= 彩蛋：识别 Ctrl+O (ASCII 15) =================
            // 当用户按下 Ctrl+O 时，直接返回 null。Main.java 里的逻辑遇到 null 会自动返回上一级或退出。
            if (c == 15) {
                out.println();
                out.flush();
                return null;
            }
            // ============================================================

            if (c == 27) {
                // 吞掉方向键序列
                try {
                    Integer next = keyQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (next != null) keyQueue.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}
                out.println();
                out.flush();
                return null;
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r') {
                    Integer next = keyQueue.poll();
                    if (next != null && next != '\n') keyQueue.offer(next);
                }
                out.println();
                out.flush();
                return sb.toString();
            }
            if (c == 127 || c == 8) {
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    out.print("\b \b");
                    out.flush();
                }
                continue;
            }
            if (c >= 32) {
                sb.append((char) c);
                out.print((char) c);
                out.flush();
            }
        }
    }

    private synchronized void renderMultiLine(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRenderTime < 100) return;
        lastRenderTime = now;

        int termWidth = terminal.getWidth();
        if (termWidth <= 0) termWidth = 80;
        termWidth = Math.max(40, termWidth - 2);

        int termHeight = terminal.getHeight();
        if (termHeight < 10) termHeight = 25;

        long fileElapsed = (fileStartTime > 0) ? Math.max(0, now - fileStartTime) : 0;
        long fileEta = 0;
        if (fileDownloaded > 0 && fileTotal > 0 && fileDownloaded < fileTotal) {
            double speed = fileDownloaded / (double) Math.max(1, fileElapsed);
            fileEta = (long) ((fileTotal - fileDownloaded) / speed);
        }

        long taskElapsed = (globalStartTime > 0) ? Math.max(0, now - globalStartTime) : 0;
        long taskEta = 0;
        if (taskPercent > 0 && taskPercent < 100) {
            double speed = taskPercent / (double) Math.max(1, taskElapsed);
            taskEta = (long) ((100 - taskPercent) / speed);
            if (fileEta > 0) taskEta = Math.max(taskEta, fileEta);
        }

        String fileLabel = fileName.isEmpty() ? "准备中" : fileName;
        String line1 = buildLine("拉取: " + fileLabel, filePercent, termWidth, fileElapsed, fileEta, true);

        String taskLabel = taskName.isEmpty() ? "准备中" : taskName;
        if (DownloadUtil.paused) {
            taskLabel = "[已暂停] " + taskLabel;
        }
        String line2 = buildLine("总计: " + taskLabel, taskPercent, termWidth, taskElapsed, taskEta, false);

        line1 = truncateVisual(line1, termWidth);
        line2 = truncateVisual(line2, termWidth);

        Ansi ansi = Ansi.ansi();

        int barRow2 = Math.max(2, termHeight);
        int barRow1 = Math.max(1, barRow2 - 1);
        int infoStart = BANNER.length + 1;

        for (int i = 0; i < infoLines.length; i++) {
            int row = infoStart + i;
            if (row >= barRow1) break;
            ansi.cursor(row, 1).eraseLine();
            if (infoLines[i] != null) {
                ansi.a(truncateVisual(infoLines[i], termWidth));
            }
        }

        ansi.cursor(barRow1, 1).eraseLine().a(line1);
        ansi.cursor(barRow2, 1).eraseLine().a(line2);

        out.print(ansi);
        out.flush();
        multiLineDrawn = true;
    }

    private String buildLine(String label, int percent, int termWidth, long elapsedMs, long etaMs, boolean isFile) {
        percent = Math.max(0, Math.min(100, percent));

        String elStr = formatTime(elapsedMs);
        String etStr = etaMs > 0 ? formatTime(etaMs) : "--";

        String timeStr = " 已用:" + padVisual(elStr, 7) + " 剩:" + padVisual(etStr, 7);
        timeStr = truncateVisual(timeStr, 24);
        timeStr = padVisual(timeStr, 24);

        String leftText = padDisplay(label, 20);

        int usedWidth = getVisualWidth(leftText) + 1 + getVisualWidth(timeStr) + 7;
        int barWidth = termWidth - usedWidth;
        if (barWidth < 10) barWidth = 10;

        int filled = percent * barWidth / 100;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barWidth; i++) {
            if (isFile) {
                if (i < filled) bar.append('=');
                else if (i == filled && percent < 100) bar.append('>');
                else bar.append('-');
            } else {
                if (i < filled) bar.append('#');
                else bar.append('-');
            }
        }
        bar.append("]");

        String pctStr = String.format(" %3d%%", percent);
        return leftText + " " + bar + pctStr + timeStr;
    }

    private String padDisplay(String s, int targetLen) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        int cur = 0;
        for (char c : s.toCharArray()) {
            int clen = (c >= 0x4E00 && c <= 0x9FA5) ? 2 : 1;
            if (cur + clen > targetLen - 1) {
                sb.append("~");
                cur += 1;
                break;
            }
            sb.append(c);
            cur += clen;
        }
        while (cur < targetLen) {
            sb.append(" ");
            cur++;
        }
        return sb.toString();
    }

    private String truncateVisual(String s, int maxWidth) {
        int width = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            int cw = (c >= 0x4E00 && c <= 0x9FA5) ? 2 : 1;
            if (width + cw > maxWidth) break;
            sb.append(c);
            width += cw;
        }
        return sb.toString();
    }

    private String padVisual(String s, int targetWidth) {
        if (s == null) s = "";
        int currentWidth = getVisualWidth(s);
        StringBuilder sb = new StringBuilder(s);
        while (currentWidth < targetWidth) {
            sb.append(" ");
            currentWidth++;
        }
        return sb.toString();
    }

    private int getVisualWidth(String s) {
        if (s == null) return 0;
        int width = 0;
        for (char c : s.toCharArray()) {
            width += (c >= 0x4E00 && c <= 0x9FA5) ? 2 : 1;
        }
        return width;
    }

    private String formatTime(long ms) {
        if (ms <= 0) return "0s";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;

        if (h > 0) return String.format("%dh%dm", h, m);
        if (m > 0) return String.format("%dm%ds", m, s);
        return String.format("%ds", s);
    }

    public synchronized void progress(String task, int percent) {
        percent = Math.max(0, Math.min(100, percent));
        if (globalStartTime == 0) {
            globalStartTime = System.currentTimeMillis();
        }
        taskName = task;
        taskPercent = percent;
        renderMultiLine(false);

        if (logWindow != null) {
            if (!task.startsWith("下载库文件") && !task.startsWith("下载资源")) {
                if (!task.equals(lastLoggedTask) || percent >= lastLoggedPercent + 5 || percent == 100) {
                    logWindow.append(String.format("[%3d%%] %s", percent, task));
                    lastLoggedPercent = percent;
                    lastLoggedTask = task;
                }
            }
        }
    }

    public synchronized void finishProgress() {
        if (multiLineDrawn) {
            int h = Math.max(10, terminal.getHeight());
            Ansi ansi = Ansi.ansi();
            ansi.cursor(h - 1, 1).eraseLine();
            ansi.cursor(h, 1).eraseLine();
            ansi.cursor(h - 1, 1);
            out.print(ansi);
            out.flush();
        }
        multiLineDrawn = false;
        fileName = "";
        taskName = "";
        lastLoggedPercent = -1;
        lastLoggedTask = "";
        globalStartTime = 0;
        fileStartTime = 0;
    }

    @Override
    public synchronized void onStart(String url, long total) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
        fileName = name;
        filePercent = 0;
        fileTotal = total;
        fileDownloaded = 0;
        fileStartTime = System.currentTimeMillis();
        renderMultiLine(true);
        String sizeStr = total > 0 ? formatSize(total) : "未知";
        boolean quiet = url.contains("/libraries/")
                || url.contains("/assets/")
                || url.contains("/objects/");
        if (logWindow != null && !quiet) {
            logWindow.append("[拉取] " + name + " (来源: " + url + ", 大小: " + sizeStr + ")");
        }
    }

    @Override
    public synchronized void onProgress(String url, long downloaded, long total) {
        int pct = 0;
        if (total > 0) pct = (int) (downloaded * 100 / total);
        filePercent = pct;
        fileDownloaded = downloaded;
        fileTotal = total;
        renderMultiLine(false);
    }

    @Override
    public synchronized void onEnd(String url) {
        filePercent = 100;
        renderMultiLine(true);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
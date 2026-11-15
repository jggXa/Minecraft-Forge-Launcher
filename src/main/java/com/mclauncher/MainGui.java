package com.mclauncher;


import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

public class MainGui {
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                showError("未捕获的异常 (线程: " + thread.getName() + ")", throwable));

        try {
            Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) ->
                    showError("主线程异常", throwable));
        } catch (Exception ignored) {}

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {}
        try {
            Theme.applyDefaults();
        } catch (Throwable ignored) {}

        SwingUtilities.invokeLater(() -> {
            try {
                MainWindow window = new MainWindow();
                window.setVisible(true);

                if (args.length > 0) {
                    try {
                        Path file = Path.of(args[0].replace("\"", "").trim());
                        if (Files.exists(file)) {
                            window.installModpackFromFile(file);
                        }
                    } catch (Throwable ex) {
                        showError("加载整合包失败", ex);
                    }
                }
            } catch (Throwable t) {
                showError("启动器初始化失败", t);
                System.exit(1);
            }
        });
    }

    /**
     * 错误窗口 + errors.log（写在启动器目录内）。自身绝不抛异常（headless 环境下回退到 stderr）。
     */
    public static void showError(String title, Throwable t) {
        if (t == null) t = new Throwable("(未知错误)");

        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();

        try {
            Path logFile = LauncherPaths.errorLog();
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            Files.writeString(logFile,
                    "[" + LocalDateTime.now() + "] " + title + "\n" + stack + "\n\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            System.err.println("无法写入 errors.log，以下为原始异常：");
        }

        try {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("==== " + title + " ====");
                t.printStackTrace();
                return;
            }
            final Throwable error = t;
            SwingUtilities.invokeLater(() -> Dialogs.error(null, title, error));
        } catch (Throwable t2) {
            System.err.println("==== " + title + " ====");
            t.printStackTrace();
            t2.printStackTrace();
        }
    }
}

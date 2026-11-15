package com.mclauncher;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * ProgressUi 的 Swing 实现。
 * 分两条进度条：
 *   - taskBar：总进度（下载库文件 50/200、安装原版、安装加载器等）
 *   - fileBar：当前文件进度（单个文件下载百分比）
 */
public class GuiUi implements ProgressUi {
    private final JLabel statusLabel;
    private final JProgressBar taskBar;
    private final JLabel fileLabel;
    private final JProgressBar fileBar;
    private final Consumer<String> logger;

    /** 当前正在下载的文件名，onStart 时设置，onProgress 复用 */
    private volatile String currentFileName = "";

    public GuiUi(JLabel statusLabel, JProgressBar taskBar, JLabel fileLabel,
                 JProgressBar fileBar, Consumer<String> logger) {
        this.statusLabel = statusLabel;
        this.taskBar = taskBar;
        this.fileLabel = fileLabel;
        this.fileBar = fileBar;
        this.logger = logger;
    }

    /** 兼容单进度条场景：两条都指向同一个 progressBar */
    public GuiUi(JLabel statusLabel, JProgressBar progressBar, JLabel fileLabel,
                 Consumer<String> logger) {
        this(statusLabel, progressBar, fileLabel, progressBar, logger);
    }

    @Override
    public void println(String s) {
        if (logger != null) logger.accept(s);
    }

    @Override
    public void setInfo(int line, String text) {
        if (text == null) return;
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) statusLabel.setText(text);
        });
    }

    @Override
    public void clearInfo() {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) statusLabel.setText("就绪");
        });
    }

    @Override
    public void progress(String task, int percent) {
        percent = Math.max(0, Math.min(100, percent));
        final int p = percent;
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) statusLabel.setText(task);
            if (taskBar != null) {
                taskBar.setIndeterminate(false);
                taskBar.setValue(p);
                taskBar.setString("总进度: " + task + "  " + p + "%");
            }
        });
    }

    @Override
    public void finishProgress() {
        SwingUtilities.invokeLater(() -> {
            if (fileBar != null) {
                fileBar.setIndeterminate(false);
                fileBar.setValue(0);
                fileBar.setString("当前文件: 等待中");
            }
            if (taskBar != null) {
                taskBar.setIndeterminate(false);
                taskBar.setValue(0);
                taskBar.setString("总进度: 等待中");
            }
            if (statusLabel != null) statusLabel.setText("就绪");
            if (fileLabel != null) fileLabel.setText("");
            currentFileName = "";
        });
    }

    @Override
    public void onStart(String url, long total) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
        currentFileName = name;
        final String fn = name;
        final long t = total;
        SwingUtilities.invokeLater(() -> {
            if (fileLabel != null) fileLabel.setText("正在下载: " + fn);
            if (fileBar != null) {
                if (t <= 0) {
                    fileBar.setIndeterminate(true);
                    fileBar.setString("当前文件: " + fn + "  (大小未知)");
                } else {
                    fileBar.setIndeterminate(false);
                    fileBar.setValue(0);
                    fileBar.setString("当前文件: " + fn + "  0%");
                }
            }
        });
    }

    @Override
    public void onProgress(String url, long downloaded, long total) {
        if (total > 0) {
            int pct = (int) (downloaded * 100 / total);
            pct = Math.max(0, Math.min(100, pct));
            final int p = pct;
            final String fn = currentFileName;
            SwingUtilities.invokeLater(() -> {
                if (fileBar != null) {
                    fileBar.setIndeterminate(false);
                    fileBar.setValue(p);
                    fileBar.setString("当前文件: " + fn + "  " + p + "%");
                }
            });
        }
    }

    @Override
    public void onEnd(String url) {
        SwingUtilities.invokeLater(() -> {
            if (fileBar != null) {
                fileBar.setIndeterminate(false);
                fileBar.setValue(0);
                fileBar.setString("当前文件: 完成");
            }
        });
    }
}
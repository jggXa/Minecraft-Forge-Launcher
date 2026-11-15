package com.mclauncher;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class LogWindow {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_QUEUE = 20000;

    private JFrame frame;
    private volatile JTextArea textArea;
    private final StringBuilder pending = new StringBuilder();
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUE);

    public void start() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            // 窗口标题统一为 MCFL
            frame = new JFrame(BuildInfo.APP_NAME + " - 日志");
            frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

            textArea = new JTextArea();
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);

            JScrollPane sp = new JScrollPane(textArea);
            frame.getContentPane().add(sp, BorderLayout.CENTER);
            frame.setSize(900, 560);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            frame.setExtendedState(JFrame.ICONIFIED);

            synchronized (pending) {
                if (pending.length() > 0) {
                    textArea.append(pending.toString());
                    pending.setLength(0);
                }
            }
        });

        Thread flusher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100);
                    drainQueue();
                } catch (InterruptedException e) {
                    drainQueue();
                    break;
                }
            }
        });
        flusher.setDaemon(true);
        flusher.setName("LogWindow-Flusher");
        flusher.start();
    }

    private void drainQueue() {
        if (queue.isEmpty()) return;
        List<String> batch = new ArrayList<>();
        queue.drainTo(batch, 500);
        if (batch.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : batch) sb.append(s).append('\n');
        final String text = sb.toString();
        SwingUtilities.invokeLater(() -> appendToArea(text));
    }

    private void appendToArea(String text) {
        if (textArea == null) return;
        textArea.append(text);
        if (textArea.getLineCount() > 5000) {
            try {
                int cut = textArea.getLineStartOffset(1000);
                textArea.replaceRange("", 0, cut);
            } catch (Exception ignored) {}
        }
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    public void append(String s) {
        write(s);
    }

    public void appendTimestamped(String s) {
        write("[" + LocalTime.now().format(TS) + "] " + s);
    }

    private void write(String s) {
        if (textArea == null) {
            synchronized (pending) {
                pending.append(s).append('\n');
            }
            return;
        }
        if (!queue.offer(s)) {
            queue.poll();
            queue.offer(s);
        }
    }

    public void restore() {
        if (frame != null) {
            SwingUtilities.invokeLater(() -> {
                frame.setExtendedState(JFrame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
        }
    }

    public void close() {
        if (frame != null) {
            SwingUtilities.invokeLater(() -> frame.dispose());
        }
    }
}
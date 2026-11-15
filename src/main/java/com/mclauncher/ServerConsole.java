package com.mclauncher;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务端控制台桥接：维护当前运行中的服务端进程，
 * 允许日志页向它的 stdin 写入命令。
 */
public final class ServerConsole {
    private ServerConsole() {}

    private static volatile Process current;
    private static volatile String currentLabel;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static void attach(Process process) {
        attach(process, null);
    }

    /** 注册新的服务端进程（覆盖旧的，不杀旧进程）。 */
    public static void attach(Process process, String label) {
        current = process;
        currentLabel = label;
        notifyListeners();
    }

    public static String getCurrentLabel() {
        return currentLabel;
    }

    /** 清除当前进程引用。 */
    public static void detach() {
        current = null;
        currentLabel = null;
        notifyListeners();
    }

    /** 只在当前引用就是给定进程时才清除，避免误清新进程。 */
    public static void detachIf(Process process) {
        if (current == process) {
            current = null;
            currentLabel = null;
            notifyListeners();
        }
    }

    public static boolean isActive() {
        Process p = current;
        return p != null && p.isAlive();
    }

    public static Process getProcess() {
        return current;
    }

    /** 向服务端 stdin 写入一行命令；成功返回 true。 */
    public static boolean sendCommand(String command) {
        Process p = current;
        if (p == null || !p.isAlive()) return false;
        try {
            p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void addListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void notifyListeners() {
        for (Runnable r : listeners) {
            try { SwingUtilities.invokeLater(r); }
            catch (Throwable ignored) {}
        }
    }
}
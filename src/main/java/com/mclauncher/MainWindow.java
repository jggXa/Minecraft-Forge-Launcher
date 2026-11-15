package com.mclauncher;

import com.mclauncher.OverlayHost.Anchor;


import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;
import java.awt.geom.*;
import java.awt.Color;
import java.awt.Graphics2D;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.awt.geom.Arc2D;
import javax.swing.text.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 启动器主窗口：无边框圆角外壳 + 左侧导航 + 页面栈 + 状态栏 + 覆盖层。
 * 全部视觉与动效均为自绘，业务逻辑保持原样。
 */
public class MainWindow extends JFrame implements TitleBar.WindowActions {
    /** 配置目录：启动器目录，所有运行时可写文件都在这里。 */
    private static final Path APP_DIR = LauncherPaths.root();
    /** 游戏目录：启动器目录\minecraft（versions / libraries / assets）。 */
    private static final Path GAME_DIR = LauncherPaths.gameDir();
    private static final Path CONFIG_FILE = LauncherPaths.configFile();

    private Settings settings;

    private WindowShell shell;
    private JLayeredPane workLayer;
    private JPanel base;
    private SidebarNav sidebar;
    private PageHost pageHost;
    private StatusBar statusBar;
    private TitleBar titleBar;
    private OverlayHost overlay;
    private ToastHost toastHost;

    private HomePanel homePanel;
    private DownloadPanel downloadPanel;
    private SettingsPanel settingsPanel;
    private LogPanel logPanel;

    private String currentPage;
    private boolean maximized;
    private Rectangle normalBounds;

    private final List<String> logHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int LOG_LIMIT = 3000;

    private final Map<InstallTask, InstallTask.State> lastStates =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private Timer taskTimer;

    private Throwable startupError;

    public MainWindow() {
        super(BuildInfo.APP_NAME);
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                closeWindow();
            }
        });
        setMinimumSize(new Dimension(1180, 700));
        setIconImage(Theme.appIcon());
        setBackground(Theme.BG_TOP);

        // 先把旧版本散落在 %APPDATA%/%LOCALAPPDATA% 的配置与游戏目录收拢进来，
        // 再补齐默认配置、目录骨架、许可证与图标等初始文件（已存在的保留）
        LauncherPaths.migrateLegacyData();
        LauncherPaths.bootstrap();

        try {
            settings = Settings.load(CONFIG_FILE);
            Mirror.set(Mirror.Type.fromId(settings.mirror));
            Mirror.setLoader(Mirror.LoaderType.fromId(settings.loaderMirror));
            DownloadUtil.setBufferSize(Math.max(8, settings.bufferSizeKb) * 1024);
            VersionInstaller.setConcurrency(Math.max(1, settings.downloadThreads));
        } catch (Throwable e) {
            settings = new Settings();
            startupError = e;
        }

        Theme.setDark(settings.darkMode);
        setBackground(Theme.BG_TOP);

        // 锁定窗口大小
        int w = 1280;
        int h = 800;
        setSize(w, h);
        setResizable(false); // 禁止系统级调整大小

        // 居中显示
        Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setLocation(wa.x + (wa.width - w) / 2, wa.y + (wa.height - h) / 2);

        buildUi();
        TaskManager.get().init(settings, GAME_DIR, CONFIG_FILE, this::appendLog);
        setupDnD();
        showPage("home", false);
        startTaskTimer();

        appendLog("[启动器] " + BuildInfo.APP_NAME + " 已就绪");
        appendLog("[系统] Java " + System.getProperty("java.version")
                + " · " + System.getProperty("os.name"));
        appendLog("[系统] 启动器目录: " + APP_DIR);
        appendLog("[系统] 配置文件: " + CONFIG_FILE);
        appendLog("[系统] 游戏目录: " + GAME_DIR);
        for (String note : LauncherPaths.drainNotes()) appendLog(note);

        LoaderPreloader.start(this);

        SwingUtilities.invokeLater(() -> {
            try {
                shell.updateWindowShape();
                if (homePanel != null) homePanel.refreshVersions();
            } catch (Throwable e) {
                MainGui.showError("加载版本列表失败", e);
            }
            if (startupError != null) {
                toastError("配置加载失败，已使用默认配置");
                startupError = null;
            }
        });
    }

    // ==================== 目录 ====================

    /** 启动器目录，供设置页「启动器目录」按钮使用。 */
    public Path getAppDir() {
        return APP_DIR;
    }

    // ==================== 界面骨架 ====================

    private void buildUi() {
        shell = new WindowShell(this);
        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(shell, BorderLayout.CENTER);
        setContentPane(root);
        shell.setLayout(new BorderLayout());

        titleBar = new TitleBar(this, this, BuildInfo.APP_NAME, "");
        shell.add(titleBar, BorderLayout.NORTH);

        workLayer = new JLayeredPane();
        workLayer.setLayout(null);
        workLayer.setOpaque(false);
        shell.add(workLayer, BorderLayout.CENTER);

        base = new JPanel(new BorderLayout());
        base.setOpaque(false);

        sidebar = new SidebarNav();
        sidebar.addItem("home", "开始", Icons.HOME);
        sidebar.addItem("download", "下载", Icons.DOWNLOAD);
        sidebar.addItem("settings", "设置", Icons.SETTINGS);
        sidebar.addItem("log", "日志", Icons.LOG);
        sidebar.setOnSelect(this::showPage);
        sidebar.setOnSettings(() -> showPage("settings"));
        sidebar.setUsername(settings.username);
        sidebar.setVersion("");
        base.add(sidebar, BorderLayout.WEST);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        pageHost = new PageHost();
        JPanel pageWrap = new JPanel(new BorderLayout());
        pageWrap.setOpaque(false);
        pageWrap.setBorder(new EmptyBorder(0, 0, 0, 18));
        pageWrap.add(pageHost, BorderLayout.CENTER);
        content.add(pageWrap, BorderLayout.CENTER);

        statusBar = new StatusBar();
        statusBar.setOnOpenTasks(this::showTasks);
        content.add(statusBar, BorderLayout.SOUTH);
        base.add(content, BorderLayout.CENTER);

        workLayer.add(base, Integer.valueOf(JLayeredPane.DEFAULT_LAYER));

        overlay = new OverlayHost();
        overlay.setBounds(0, 0, 1, 1);
        overlay.setVisible(false);
        workLayer.add(overlay, Integer.valueOf(JLayeredPane.MODAL_LAYER));

        toastHost = new ToastHost();
        toastHost.setOnLayoutChanged(this::layoutToastHost);
        toastHost.setVisible(false);
        workLayer.add(toastHost, Integer.valueOf(JLayeredPane.POPUP_LAYER));

        workLayer.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                layoutWorkLayer();
            }
        });
        layoutWorkLayer();
    }

    private void layoutWorkLayer() {
        int w = workLayer.getWidth(), h = workLayer.getHeight();
        if (w <= 0 || h <= 0) return;
        base.setBounds(0, 0, w, h);
        overlay.setBounds(0, 0, w, h);
        layoutToastHost();
    }

    private void layoutToastHost() {
        if (toastHost == null) return;
        Dimension p = toastHost.getPreferredSize();
        int width = Math.min(440, Math.max(320, p.width));
        int height = Math.max(48, p.height);
        int x = (workLayer.getWidth() - width) / 2;
        toastHost.setBounds(Math.max(8, x), 8, width, height);
        toastHost.revalidate();
    }

    // ==================== 页面 ====================

    public void showPage(String key) {
        showPage(key, true);
    }

    private void showPage(String key, boolean animate) {
        if (key == null) return;
        if (key.equals(currentPage)) return;
        JComponent page;
        switch (key) {
            case "home" -> {
                if (homePanel == null) homePanel = new HomePanel(this);
                page = homePanel;
            }
            case "download" -> {
                if (downloadPanel == null) downloadPanel = new DownloadPanel(this);
                page = downloadPanel;
            }
            case "settings" -> {
                if (settingsPanel == null) settingsPanel = new SettingsPanel(this);
                page = settingsPanel;
            }
            case "log" -> {
                if (logPanel == null) logPanel = new LogPanel(this);
                page = logPanel;
            }
            default -> {
                return;
            }
        }
        pageHost.setPage(page, animate && currentPage != null);
        currentPage = key;
        sidebar.setSelectedKey(key);
        if ("log".equals(key) && logPanel != null) logPanel.scrollToBottom();
    }

    public String getCurrentPage() {
        return currentPage;
    }

    public void navigate(String key) {
        showPage(key);
    }

    public void updateUsername(String username) {
        sidebar.setUsername(username);
    }

    public void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                toast("无法打开文件夹：" + dir);
            }
        } catch (Throwable t) {
            toastError("打开文件夹失败：" + t.getMessage());
        }
    }

    // ==================== 状态 / 日志 / 提示 ====================

    public Settings getSettings() { return settings; }
    public Path getGameDir() { return GAME_DIR; }
    public Path getConfigFile() { return CONFIG_FILE; }

    public void appendLog(String s) {
        if (s == null) return;
        synchronized (logHistory) {
            logHistory.add(s);
            while (logHistory.size() > LOG_LIMIT) logHistory.remove(0);
        }
        if (SwingUtilities.isEventDispatchThread()) {
            if (logPanel != null) logPanel.append(s);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (logPanel != null) logPanel.append(s);
            });
        }
    }

    public List<String> getLogHistory() {
        synchronized (logHistory) {
            return new ArrayList<>(logHistory);
        }
    }

    public void setStatus(String s) {
        if (s == null) return;
        if (SwingUtilities.isEventDispatchThread()) statusBar.setStatusText(s);
        else SwingUtilities.invokeLater(() -> statusBar.setStatusText(s));
    }

    public void applyTransferSettings() {
        Mirror.set(Mirror.Type.fromId(settings.mirror));
        Mirror.setLoader(Mirror.LoaderType.fromId(settings.loaderMirror));
        DownloadUtil.setBufferSize(Math.max(8, settings.bufferSizeKb) * 1024);
        VersionInstaller.setConcurrency(Math.max(1, settings.downloadThreads));
    }

    /** 切换深浅色：原地重着色，不重建页面，因此版本列表不会被重新扫描。 */
    public void applyTheme(boolean dark) {
        Theme.Palette palette = Theme.setDark(dark);
        recolorTree(getContentPane(), palette);
        setBackground(Theme.BG_TOP);
        if (getContentPane() != null) getContentPane().setBackground(Theme.BG_TOP);
        if (shell != null) shell.updateWindowShape();
        revalidate();
        repaint();
    }

    private void recolorTree(Component c, Theme.Palette palette) {
        if (c == null) return;
        Color bg = c.getBackground();
        Color newBg = bg != null ? palette.background().get(bg) : null;
        if (newBg != null) c.setBackground(newBg);

        Color fg = c.getForeground();
        Color newFg = fg != null ? palette.foreground().get(fg) : null;
        if (newFg != null) c.setForeground(newFg);

        if (c instanceof JComponent jc && jc.getBorder() instanceof LineBorder lb) {
            Color borderColor = palette.background().get(lb.getLineColor());
            if (borderColor != null) {
                jc.setBorder(new LineBorder(borderColor, lb.getThickness(), lb.getRoundedCorners()));
            }
        }

        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                recolorTree(child, palette);
            }
        }
        c.repaint();
    }

    public void toast(String message) {
        toast(message, ToastHost.Type.INFO);
    }

    public void toastSuccess(String message) {
        toast(message, ToastHost.Type.SUCCESS);
    }

    public void toastError(String message) {
        toast(message, ToastHost.Type.ERROR);
    }

    private void toast(String message, ToastHost.Type type) {
        if (SwingUtilities.isEventDispatchThread()) {
            toastHost.setVisible(true);
            toastHost.show(message, type);
            layoutToastHost();
        } else {
            SwingUtilities.invokeLater(() -> toast(message, type));
        }
    }

    public void showSheet(JComponent content, Anchor anchor, int width, int height,
                          boolean closable, Runnable onClosed) {
        overlay.show(content, anchor, width, height, closable, onClosed);
    }

    public void closeSheet() {
        overlay.close();
    }

    public boolean isSheetOpen() {
        return overlay.isOpen();
    }

    // ==================== 任务 ====================

    private void startTaskTimer() {
        taskTimer = new Timer(180, e -> updateTasks());
        taskTimer.start();
    }

    private void updateTasks() {
        List<InstallTask> tasks = TaskManager.get().getTasks();
        int total = tasks.size();
        int running = 0;
        int sum = 0;
        InstallTask active = null;
        for (InstallTask t : tasks) {
            switch (t.state) {
                case DONE, FAILED -> sum += 100;
                case RUNNING -> {
                    sum += Math.max(0, Math.min(100, t.totalPercent));
                    running++;
                    active = t;
                }
                case QUEUED -> running++;
            }
        }
        int overall = total == 0 ? 0 : Math.min(100, sum / total);
        boolean hasActive = TaskManager.get().hasActive();
        statusBar.setProgress(overall, hasActive);
        statusBar.setTaskInfo(TaskManager.get().getRunningTask() != null ? 1 : 0, total, hasActive);
        if (active != null) {
            String file = active.currentFileName;
            if (file == null || file.isBlank()) file = active.status;
            statusBar.setFileText(file);
        } else {
            statusBar.setFileText(hasActive ? "等待开始…" : "");
        }

        boolean changed = false;
        for (InstallTask t : tasks) {
            InstallTask.State prev = lastStates.get(t);
            if (prev != null && prev != t.state) {
                if (t.state == InstallTask.State.DONE) {
                    changed = true;
                    toastSuccess("已完成：" + t.displayName);
                } else if (t.state == InstallTask.State.FAILED) {
                    changed = true;
                    toastError("任务失败：" + t.displayName + " · " + t.status);
                }
            }
            lastStates.put(t, t.state);
        }
        if (changed && homePanel != null) {
            try { homePanel.refreshVersions(); } catch (Throwable ignored) {}
        }
        Set<InstallTask> current = Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(tasks);
        lastStates.keySet().retainAll(current);
    }

    public void showTasks() {
        if (overlay.isOpen()) overlay.close();
        TaskSheet sheet = new TaskSheet(this, this::closeSheet);
        overlay.show(sheet, Anchor.RIGHT, 436, 0, true, null);
    }

    /** 保存设置后自动重启启动器（切换外观使用）。 */
    public void restartApplication() {
        boolean started = false;
        try {
            String appPath = System.getProperty("jpackage.app-path");
            if (appPath != null && !appPath.isBlank()) {
                Path exe = Path.of(appPath);
                ProcessBuilder pb = new ProcessBuilder(exe.toString());
                if (exe.getParent() != null) pb.directory(exe.getParent().toFile());
                pb.start();
                started = true;
            } else {
                String javaHome = System.getProperty("java.home", "");
                String command = System.getProperty("sun.java.command", "");
                String jar = command.split("\\s+")[0];
                if (!javaHome.isBlank() && jar.endsWith(".jar")) {
                    Path java = Path.of(javaHome, "bin", "java.exe");
                    if (!Files.exists(java)) java = Path.of(javaHome, "bin", "java");
                    new ProcessBuilder(java.toString(), "-jar", jar)
                            .directory(LauncherPaths.root().toFile())
                            .start();
                    started = true;
                }
            }
        } catch (Throwable t) {
            appendLog("[启动器] 自动重启失败: " + t);
        }
        if (!started) {
            toastError("自动重启失败，请手动重新打开启动器");
            return;
        }
        appendLog("[启动器] 正在重启…");
        dispose();
        System.exit(0);
    }

    // ==================== 窗口控制 ====================

    @Override
    public void minimizeWindow() {
        setExtendedState(getExtendedState() | JFrame.ICONIFIED);
    }

    @Override
    public void toggleMaximize() {
        if (maximized) restoreFrame();
        else maximizeFrame();
    }

    private void maximizeFrame() {
        normalBounds = getBounds();
        Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        shell.setMaximized(true);
        maximized = true;
        setBounds(wa);
        titleBar.updateMaximizeIcon();
    }

    private void restoreFrame() {
        shell.setMaximized(false);
        maximized = false;
        if (normalBounds != null) setBounds(normalBounds);
        titleBar.updateMaximizeIcon();
    }

    @Override
    public boolean isWindowMaximized() {
        return maximized;
    }

    @Override
    public void restoreWindowForDrag() {
        if (maximized) restoreFrame();
    }

    @Override
    public void closeWindow() {
        // 兜底保存一次，避免自动保存失败或未触发时丢失设置（如深色模式）
        if (settings != null && !settings.save(CONFIG_FILE)) {
            appendLog("[设置] 关闭时保存失败: " + CONFIG_FILE + " -> "
                    + Settings.lastSaveError);
        }
        List<InstallTask> tasks = TaskManager.get().getTasks();
        List<InstallTask> activeTasks = new ArrayList<>();
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.QUEUED || t.state == InstallTask.State.RUNNING) {
                activeTasks.add(t);
            }
        }
        boolean serverRunning = ServerConsole.isActive();

        if (activeTasks.isEmpty() && !serverRunning) {
            setVisible(false);
            dispose();
            System.exit(0);
            return;
        }

        StringBuilder msg = new StringBuilder("关闭启动器后会停止以下内容：\n\n");
        int idx = 1;
        if (serverRunning) {
            String label = ServerConsole.getCurrentLabel();
            if (label == null || label.isBlank()) label = "服务端";
            msg.append(idx++).append(". ").append(label).append(" · 运行中\n");
        }
        for (InstallTask t : activeTasks) {
            String status;
            if (t.state == InstallTask.State.QUEUED) {
                status = "排队中";
            } else if (t.kind == InstallTask.Kind.DOWNLOAD_SERVER) {
                status = "运行中";
            } else if (t.kind == InstallTask.Kind.DOWNLOAD_MOD) {
                status = "下载中";
            } else {
                status = "安装中";
            }
            msg.append(idx++).append(". ").append(t.displayName).append(" · ").append(status).append("\n");
        }
        msg.append("\n确定要退出吗？");

        boolean ok = Dialogs.confirm(this, "确认关闭 " + BuildInfo.APP_NAME, msg.toString(), "确定", "取消");
        if (!ok) return;

        showShutdownAndExit();
    }

    /**
     * 显示「正在停止相关内容」遮罩，依次：
     *   1. 向服务端发送 stop，最多等 30 秒，超时 destroy/destroyForcibly
     *   2. 取消所有下载/安装任务
     *   3. 清理 *.part / *.tmp 残留
     *   4. 退出进程
     */
    private void showShutdownAndExit() {
        JPanel panel = UiKit.transparent(new BorderLayout(0, 14));
        panel.setBorder(new EmptyBorder(26, 24, 22, 24));

        JLabel title = new JLabel("正在停止相关内容");
        title.setFont(Theme.bold(18));
        title.setForeground(Theme.TEXT);
        panel.add(title, BorderLayout.NORTH);

        JLabel detail = UiKit.body("请稍候，启动器正在安全退出…");
        panel.add(detail, BorderLayout.CENTER);

        ModernProgressBar bar = new ModernProgressBar();
        bar.setBarHeight(6);
        bar.setIndeterminate(true);
        JPanel barWrap = UiKit.transparent(new BorderLayout());
        barWrap.add(bar, BorderLayout.CENTER);
        panel.add(barWrap, BorderLayout.SOUTH);

        overlay.show(panel, Anchor.CENTER, 480, 190, false, null);

        new Thread(() -> {
            if (ServerConsole.isActive()) {
                Process p = ServerConsole.getProcess();
                SwingUtilities.invokeLater(() -> detail.setText("正在向服务端发送 stop 命令…"));
                ServerConsole.sendCommand("stop");
                long deadline = System.currentTimeMillis() + 30_000L;
                while (p.isAlive() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
                if (p.isAlive()) {
                    SwingUtilities.invokeLater(() -> detail.setText("服务端未响应，正在强制结束…"));
                    p.destroy();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    if (p.isAlive()) p.destroyForcibly();
                }
                ServerConsole.detachIf(p);
            }

            SwingUtilities.invokeLater(() -> detail.setText("正在停止下载与安装任务…"));
            TaskManager.get().cancelAll();

            SwingUtilities.invokeLater(() -> detail.setText("正在清理下载残留…"));
            try {
                Path gameDir = getGameDir();
                if (Files.isDirectory(gameDir)) {
                    try (var stream = Files.walk(gameDir)) {
                        stream.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".part") || n.endsWith(".tmp");
                        }).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception ignored) {}

            SwingUtilities.invokeLater(() -> {
                setVisible(false);
                dispose();
                System.exit(0);
            });
        }, "Shutdown").start();
    }

    // ==================== 整合包安装 ====================

    private void setupDnD() {
        setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<java.io.File> files = (List<java.io.File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    for (java.io.File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".mrpack") || name.endsWith(".zip")) {
                            installModpackFromFile(f.toPath());
                            return true;
                        }
                    }
                } catch (Throwable ex) {
                    MainGui.showError("拖放失败", ex);
                }
                return false;
            }
        });
    }

    public void installModpackFromFile(Path file) {
        boolean ok = Dialogs.confirm(this, "安装整合包",
                "检测到整合包：\n" + file.getFileName()
                        + "\n\n安装将强制开启版本隔离，并把整合包内容安装到独立实例中。",
                "开始安装", "取消");
        if (!ok) return;

        settings.versionIsolation = true;
        settings.save(CONFIG_FILE);

        JPanel panel = UiKit.transparent(new BorderLayout(0, 14));
        panel.setBorder(new EmptyBorder(22, 22, 22, 22));
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel titleLbl = new JLabel("正在安装整合包");
        titleLbl.setFont(Theme.bold(19));
        titleLbl.setForeground(Theme.TEXT);
        head.add(titleLbl, BorderLayout.NORTH);
        JLabel fileLbl = new JLabel(file.getFileName().toString());
        fileLbl.setFont(Theme.font(11.5f));
        fileLbl.setForeground(Theme.TEXT_MUTED);
        head.add(fileLbl, BorderLayout.SOUTH);
        panel.add(head, BorderLayout.NORTH);

        JPanel bars = UiKit.transparent(null);
        bars.setLayout(new BoxLayout(bars, BoxLayout.Y_AXIS));
        JLabel statusLbl = UiKit.body("准备中…");
        JLabel currentLbl = UiKit.caption("等待下载");
        JLabel totalLbl = UiKit.caption("总进度 0%");
        ModernProgressBar fileBar = new ModernProgressBar();
        ModernProgressBar taskBar = new ModernProgressBar();
        fileBar.setBarHeight(7);
        taskBar.setBarHeight(7);
        JPanel fileBox = UiKit.transparent(new BorderLayout(0, 4));
        fileBox.add(currentLbl, BorderLayout.NORTH);
        fileBox.add(fileBar, BorderLayout.CENTER);
        JPanel taskBox = UiKit.transparent(new BorderLayout(0, 4));
        taskBox.add(totalLbl, BorderLayout.NORTH);
        taskBox.add(taskBar, BorderLayout.CENTER);
        bars.add(statusLbl);
        bars.add(Box.createVerticalStrut(14));
        bars.add(fileBox);
        bars.add(Box.createVerticalStrut(14));
        bars.add(taskBox);
        panel.add(bars, BorderLayout.CENTER);

        JLabel tip = UiKit.caption("安装过程中请保持启动器运行，完成后版本会自动出现在主页。");
        panel.add(tip, BorderLayout.SOUTH);

        overlay.show(panel, Anchor.CENTER, 620, 330, false, null);

        ProgressUi ui = new ProgressUi() {
            @Override public void println(String s) { appendLog(s); }
            @Override public void setInfo(int line, String text) {
                if (text != null) SwingUtilities.invokeLater(() -> statusLbl.setText(text));
            }
            @Override public void clearInfo() {
                SwingUtilities.invokeLater(() -> statusLbl.setText("准备中…"));
            }
            @Override public void progress(String task, int percent) {
                SwingUtilities.invokeLater(() -> {
                    taskBar.setProgress(percent);
                    totalLbl.setText("总进度 " + percent + "% · " + task);
                });
            }
            @Override public void finishProgress() {
                SwingUtilities.invokeLater(() -> {
                    fileBar.setProgress(0);
                    currentLbl.setText("等待下载");
                });
            }
            @Override public void onStart(String url, long totalSize) {
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
                final String fn = name;
                SwingUtilities.invokeLater(() -> {
                    currentLbl.setText("下载 " + fn);
                    fileBar.setIndeterminate(totalSize <= 0);
                    if (totalSize > 0) fileBar.setProgress(0);
                });
                appendLog("[下载] " + fn);
            }
            @Override public void onProgress(String url, long downloaded, long totalSize) {
                if (totalSize <= 0) return;
                int pct = (int) (downloaded * 100 / totalSize);
                SwingUtilities.invokeLater(() -> {
                    fileBar.setIndeterminate(false);
                    fileBar.setProgress(pct);
                    currentLbl.setText("下载中 " + pct + "%");
                });
            }
            @Override public void onEnd(String url) {
                SwingUtilities.invokeLater(() -> {
                    fileBar.setIndeterminate(false);
                    fileBar.setProgress(100);
                    currentLbl.setText("当前文件完成");
                });
            }
        };

        new Thread(() -> {
            try {
                ModpackInstaller.install(file, GAME_DIR, settings, ui);
                SwingUtilities.invokeLater(() -> {
                    overlay.closeImmediately();
                    toastSuccess("整合包安装完成");
                    if (homePanel != null) homePanel.refreshVersions();
                });
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[整合包] 安装失败: " + e);
                    overlay.closeImmediately();
                    Dialogs.error(this, "整合包安装失败", e);
                });
            }
        }, "Modpack-Installer").start();
    }
}

/**
 * 全局设计系统：颜色、字体、圆角、阴影与绘制工具。
 * 灵感来自 PCL2 的浅色卡片界面，动效参考 iOS 的柔和曲线。
 */
final class Theme {
    private Theme() {}

    // ---------- 背景与表面 ----------
    public static Color BG_TOP = new Color(0xFB, 0xFC, 0xFE);
    public static Color BG_BOTTOM = new Color(0xF1, 0xF5, 0xFA);
    public static Color SURFACE = Color.WHITE;
    public static Color SURFACE_ALT = new Color(0xF6, 0xF8, 0xFC);
    public static Color SURFACE_SUNK = new Color(0xF0, 0xF4, 0xF9);
    public static Color SURFACE_HOVER = new Color(0xF3, 0xF7, 0xFD);
    public static Color BORDER = new Color(0xE7, 0xEC, 0xF3);
    public static Color BORDER_STRONG = new Color(0xD7, 0xE0, 0xEB);

    // ---------- 文字 ----------
    public static Color TEXT = new Color(0x1B, 0x24, 0x30);
    public static Color TEXT_SUB = new Color(0x68, 0x74, 0x84);
    public static Color TEXT_MUTED = new Color(0x98, 0xA3, 0xB2);
    public static Color TEXT_ON_DARK = new Color(0xE8, 0xEE, 0xF7);

    // ---------- 品牌色 ----------
    public static Color PRIMARY = new Color(0x3B, 0x82, 0xF6);
    public static Color PRIMARY_DARK = new Color(0x25, 0x63, 0xEB);
    public static Color PRIMARY_LIGHT = new Color(0xE9, 0xF1, 0xFF);
    public static Color PRIMARY_SOFT = new Color(0xDB, 0xE9, 0xFF);
    public static Color INDIGO = new Color(0x60, 0x5C, 0xE8);

    public static Color GREEN = new Color(0x22, 0xC5, 0x5E);
    public static Color GREEN_SOFT = new Color(0xE6, 0xF8, 0xEE);
    public static Color AMBER = new Color(0xF5, 0x9E, 0x0B);
    public static Color AMBER_SOFT = new Color(0xFF, 0xF5, 0xE2);
    public static Color RED = new Color(0xEF, 0x44, 0x44);
    public static Color RED_SOFT = new Color(0xFE, 0xEC, 0xEC);
    public static Color PURPLE = new Color(0x8B, 0x5C, 0xF6);
    public static Color PURPLE_SOFT = new Color(0xF2, 0xEC, 0xFF);
    public static Color CYAN = new Color(0x06, 0xB6, 0xD4);
    public static Color CYAN_SOFT = new Color(0xE0, 0xF7, 0xFA);

    public static Color LOG_BG = new Color(0x10, 0x17, 0x26);
    public static Color LOG_BG_TOP = new Color(0x16, 0x1F, 0x31);
    public static Color LOG_TEXT = new Color(0xB8, 0xC6, 0xD9);
    public static Color LOG_COMMENT = new Color(0x7D, 0x8C, 0xA3);
    /** 日志终端标题栏的次要文字：日志区始终是深色底，固定用浅灰保证可读。 */
    public static final Color LOG_MUTED = new Color(0xAE, 0xBD, 0xD0);

    public static Color SIDEBAR_BG = new Color(0xF8, 0xFA, 0xFD);
    public static Color SIDEBAR_SEL = new Color(0xE8, 0xF0, 0xFF);

    private static boolean dark;

    public static boolean isDark() {
        return dark;
    }

    public record Palette(Map<Color, Color> background, Map<Color, Color> foreground) {}

    /** 切换明暗主题，返回“旧颜色 → 新颜色”的映射，供现有组件重着色。 */
    public static Palette setDark(boolean wantDark) {
        if (wantDark == dark) {
            return new Palette(Map.of(), Map.of());
        }
        Map<Color, Color> bg = new java.util.LinkedHashMap<>();
        Map<Color, Color> fg = new java.util.LinkedHashMap<>();

        Color old;
        old = BG_TOP; BG_TOP = wantDark ? new Color(0x0B,0x11,0x1A) : new Color(0xFB,0xFC,0xFE); bg.put(old, BG_TOP);
        old = BG_BOTTOM; BG_BOTTOM = wantDark ? new Color(0x0F,0x17,0x22) : new Color(0xF1,0xF5,0xFA); bg.put(old, BG_BOTTOM);
        old = SURFACE; SURFACE = wantDark ? new Color(0x15,0x1E,0x2B) : Color.WHITE; bg.put(old, SURFACE);
        old = SURFACE_ALT; SURFACE_ALT = wantDark ? new Color(0x1A,0x25,0x33) : new Color(0xF6,0xF8,0xFC); bg.put(old, SURFACE_ALT);
        old = SURFACE_SUNK; SURFACE_SUNK = wantDark ? new Color(0x10,0x18,0x23) : new Color(0xF0,0xF4,0xF9); bg.put(old, SURFACE_SUNK);
        old = SURFACE_HOVER; SURFACE_HOVER = wantDark ? new Color(0x20,0x2D,0x3F) : new Color(0xF3,0xF7,0xFD); bg.put(old, SURFACE_HOVER);
        old = BORDER; BORDER = wantDark ? new Color(0x2A,0x37,0x4A) : new Color(0xE7,0xEC,0xF3); bg.put(old, BORDER);
        old = BORDER_STRONG; BORDER_STRONG = wantDark ? new Color(0x3A,0x4B,0x63) : new Color(0xD7,0xE0,0xEB); bg.put(old, BORDER_STRONG);

        old = TEXT; TEXT = wantDark ? new Color(0xE7,0xEE,0xF8) : new Color(0x1B,0x24,0x30); fg.put(old, TEXT);
        // 深色模式下的次要文字统一提亮，避免灰字在深底上看不清
        old = TEXT_SUB; TEXT_SUB = wantDark ? new Color(0xD3,0xDD,0xEB) : new Color(0x68,0x74,0x84); fg.put(old, TEXT_SUB);
        old = TEXT_MUTED; TEXT_MUTED = wantDark ? new Color(0xB9,0xC7,0xDA) : new Color(0x98,0xA3,0xB2); fg.put(old, TEXT_MUTED);

        old = PRIMARY_LIGHT; PRIMARY_LIGHT = wantDark ? new Color(0x1B,0x2C,0x47) : new Color(0xE9,0xF1,0xFF); bg.put(old, PRIMARY_LIGHT);
        old = PRIMARY_SOFT; PRIMARY_SOFT = wantDark ? new Color(0x22,0x3A,0x5E) : new Color(0xDB,0xE9,0xFF); bg.put(old, PRIMARY_SOFT);
        old = GREEN_SOFT; GREEN_SOFT = wantDark ? new Color(0x12,0x33,0x23) : new Color(0xE6,0xF8,0xEE); bg.put(old, GREEN_SOFT);
        old = AMBER_SOFT; AMBER_SOFT = wantDark ? new Color(0x33,0x26,0x0F) : new Color(0xFF,0xF5,0xE2); bg.put(old, AMBER_SOFT);
        old = RED_SOFT; RED_SOFT = wantDark ? new Color(0x3A,0x1B,0x1D) : new Color(0xFE,0xEC,0xEC); bg.put(old, RED_SOFT);
        old = PURPLE_SOFT; PURPLE_SOFT = wantDark ? new Color(0x2A,0x21,0x40) : new Color(0xF2,0xEC,0xFF); bg.put(old, PURPLE_SOFT);
        old = CYAN_SOFT; CYAN_SOFT = wantDark ? new Color(0x10,0x2F,0x35) : new Color(0xE0,0xF7,0xFA); bg.put(old, CYAN_SOFT);

        old = LOG_BG; LOG_BG = wantDark ? new Color(0x07,0x0C,0x13) : new Color(0x10,0x17,0x26); bg.put(old, LOG_BG);
        old = LOG_BG_TOP; LOG_BG_TOP = wantDark ? new Color(0x0C,0x14,0x20) : new Color(0x16,0x1F,0x31); bg.put(old, LOG_BG_TOP);
        old = LOG_TEXT; LOG_TEXT = wantDark ? new Color(0xC7,0xD5,0xE6) : new Color(0xB8,0xC6,0xD9); fg.put(old, LOG_TEXT);
        old = LOG_COMMENT; LOG_COMMENT = wantDark ? new Color(0xA8,0xB9,0xCD) : new Color(0x7D,0x8C,0xA3); fg.put(old, LOG_COMMENT);

        old = SIDEBAR_BG; SIDEBAR_BG = wantDark ? new Color(0x0D,0x15,0x20) : new Color(0xF8,0xFA,0xFD); bg.put(old, SIDEBAR_BG);
        old = SIDEBAR_SEL; SIDEBAR_SEL = wantDark ? new Color(0x1B,0x2B,0x44) : new Color(0xE8,0xF0,0xFF); bg.put(old, SIDEBAR_SEL);

        dark = wantDark;
        return new Palette(bg, fg);
    }

    // ---------- 字体 ----------
    public static final String FONT_FAMILY = pickFamily();
    public static final String MONO_FAMILY = pickMonoFamily();

    private static String pickFamily() {
        String[] wanted = {"Microsoft YaHei UiKit", "Microsoft YaHei", "PingFang SC",
                "HarmonyOS Sans SC", "Segoe UiKit", "Noto Sans CJK SC", "SansSerif"};
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : wanted) {
            if (available.contains(f)) return f;
        }
        return "SansSerif";
    }

    private static String pickMonoFamily() {
        String[] wanted = {"Cascadia Mono", "JetBrains Mono", "Consolas",
                "Microsoft YaHei UiKit", "Monospaced"};
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : wanted) {
            if (available.contains(f)) return f;
        }
        return "Monospaced";
    }

    public static Font font(float size) {
        return new Font(FONT_FAMILY, Font.PLAIN, Math.round(size));
    }

    public static Font bold(float size) {
        return new Font(FONT_FAMILY, Font.BOLD, Math.round(size));
    }

    public static Font mono(float size) {
        return new Font(MONO_FAMILY, Font.PLAIN, Math.round(size));
    }

    /** 让 Swing 的默认组件（弹窗、菜单、提示）保持一致的字体。 */
    public static void applyDefaults() {
        UIManager.put("Label.font", font(13));
        UIManager.put("Button.font", font(13));
        UIManager.put("TextField.font", font(13));
        UIManager.put("TextArea.font", font(13));
        UIManager.put("ComboBox.font", font(13));
        UIManager.put("CheckBox.font", font(13));
        UIManager.put("RadioButton.font", font(13));
        UIManager.put("ToolTip.font", font(12));
        UIManager.put("OptionPane.messageFont", font(13));
        UIManager.put("OptionPane.buttonFont", font(13));
        UIManager.put("ToolTip.background", new Color(0x22, 0x2B, 0x3A));
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(5, 9, 5, 9));
        UIManager.put("ComboBox.selectionBackground", PRIMARY_LIGHT);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.background", SURFACE);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("List.selectionBackground", PRIMARY_LIGHT);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("ScrollBar.thumbDarkShadow", SURFACE_SUNK);
        UIManager.put("ScrollBar.thumb", BORDER_STRONG);
        UIManager.put("ScrollBar.track", SURFACE);
        ToolTipManager.sharedInstance().setInitialDelay(360);
        ToolTipManager.sharedInstance().setDismissDelay(12000);
    }

    // ---------- 绘图工具 ----------

    public static Graphics2D quality(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        return g2;
    }

    public static Shape roundRect(float x, float y, float w, float h, float r) {
        float d = Math.min(r * 2, Math.min(w, h));
        return new RoundRectangle2D.Float(x, y, w, h, d, d);
    }

    public static void fillRound(Graphics2D g, float x, float y, float w, float h, float r, Color c) {
        g.setColor(c);
        g.fill(roundRect(x, y, w, h, r));
    }

    public static void drawRound(Graphics2D g, float x, float y, float w, float h, float r, Color c) {
        g.setColor(c);
        g.draw(roundRect(x + 0.5f, y + 0.5f, w - 1, h - 1, r));
    }

    /** 在指定矩形下方绘制柔和阴影（调用前需预留 shadowPad 空间）。 */
    public static void shadow(Graphics2D g, float x, float y, float w, float h, float r, int spread) {
        for (int i = spread; i >= 1; i--) {
            int alpha = 4 + (spread - i) / 2;
            g.setColor(new Color(0x1B, 0x2F, 0x52, alpha));
            shapeFill(g, roundRect(x - i, y - i + 2.2f, w + i * 2, h + i * 2, r + i));
        }
    }

    public static void shapeFill(Graphics2D g, Shape s) {
        g.fill(s);
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp(a, 0, 255));
    }

    public static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String ellipsize(FontMetrics fm, String text, int maxWidth) {
        if (text == null) return "";
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid)) + fm.stringWidth(ellipsis) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, Math.max(0, lo)) + ellipsis;
    }

    public static void drawCentered(Graphics2D g, String text, Rectangle bounds, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int x = bounds.x + (bounds.width - fm.stringWidth(text)) / 2;
        int y = bounds.y + (bounds.height - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(text, x, y);
    }

    public static BufferedImage appIcon() {
        // 优先使用打包进 jar 的图标 PNG（与 launcher-icon.svg 一致）
        try (java.io.InputStream in = Theme.class.getResourceAsStream("/icons/launcher.png")) {
            if (in != null) {
                BufferedImage src = javax.imageio.ImageIO.read(in);
                if (src != null) {
                    int size = 64;
                    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = quality(img.createGraphics());
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(src, 0, 0, size, size, null);
                    g.dispose();
                    return img;
                }
            }
        } catch (Throwable ignored) {}

        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img.getGraphics());
        g.setPaint(new GradientPaint(0, 0, new Color(0x5B, 0x9C, 0xFF), size, size, new Color(0x25, 0x5F, 0xE8)));
        g.fillRoundRect(2, 2, size - 4, size - 4, 18, 18);
        g.setColor(new Color(255, 255, 255, 235));
        g.setFont(bold(40));
        FontMetrics fm = g.getFontMetrics();
        String m = "M";
        g.drawString(m, (size - fm.stringWidth(m)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.setColor(new Color(0x7C, 0xF5, 0xB0));
        g.fill(new Ellipse2D.Float(size - 17, size - 17, 9, 9));
        g.dispose();
        return img;
    }

    public static String formatCount(long n) {
        if (n >= 100_000_000L) return String.format("%.1f亿", n / 100_000_000.0);
        if (n >= 10_000L) return String.format("%.1f万", n / 10_000.0);
        if (n >= 1_000L) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    public static String formatMemory(int mb) {
        if (mb >= 1024) {
            double gb = mb / 1024.0;
            return (gb == Math.floor(gb)) ? ((int) gb) + " GB" : String.format("%.1f GB", gb);
        }
        return mb + " MB";
    }
}

/**
 * 极轻量的动画引擎：全应用共用一个 60FPS 定时器。
 * 不为每个组件创建 Timer，避免大量定时器造成的掉帧。
 */
final class Animator {
    private Animator() {}

    private static final List<AnimatedValue> ACTIVE = new ArrayList<>();
    private static Timer timer;

    private static void register(AnimatedValue v) {
        if (!ACTIVE.contains(v)) ACTIVE.add(v);
        if (timer == null) {
            timer = new Timer(15, e -> tick());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) timer.start();
    }

    private static void tick() {
        long now = System.nanoTime();
        List<AnimatedValue> done = null;
        AnimatedValue[] snapshot = ACTIVE.toArray(new AnimatedValue[0]);
        for (AnimatedValue v : snapshot) {
            if (v.tick(now)) {
                if (done == null) done = new ArrayList<>();
                done.add(v);
            }
        }
        if (done != null) ACTIVE.removeAll(done);
        if (ACTIVE.isEmpty() && timer != null) timer.stop();
    }

    public static float easeOutCubic(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    public static float easeOutQuint(float t) {
        float u = 1 - t;
        return 1 - u * u * u * u * u;
    }

    public static float easeInOutCubic(float t) {
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    public static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    /** 一个带缓动的浮点值。所有回调都在 EDT 上执行。 */
    public static final class AnimatedValue {
        private float value;
        private float from;
        private float target;
        private long start;
        private int duration;
        private final Consumer<Float> consumer;

        public AnimatedValue(float initial, Consumer<Float> consumer) {
            this.value = initial;
            this.from = initial;
            this.target = initial;
            this.consumer = consumer;
        }

        public float value() {
            return value;
        }

        public float target() {
            return target;
        }

        public boolean isAnimating() {
            return Math.abs(value - target) > 0.0005f;
        }

        public void snap(float v) {
            this.value = v;
            this.from = v;
            this.target = v;
            consumer.accept(v);
        }

        public void set(float target, int durationMs) {
            if (Math.abs(this.target - target) < 0.0005f && !isAnimating()) return;
            if (durationMs <= 0) {
                snap(target);
                return;
            }
            this.from = value;
            this.target = target;
            this.start = System.nanoTime();
            this.duration = durationMs;
            register(this);
        }

        public void set(float target) {
            set(target, 200);
        }

        boolean tick(long now) {
            float elapsed = (now - start) / 1_000_000f;
            float t = elapsed / Math.max(1, duration);
            if (t >= 1f) {
                value = target;
                safeAccept();
                return true;
            }
            float eased = Animator.easeOutCubic(t);
            value = from + (target - from) * eased;
            safeAccept();
            return false;
        }

        private void safeAccept() {
            try {
                consumer.accept(value);
            } catch (RuntimeException ignored) {
                // 组件可能已被移除，忽略绘制回调异常
            }
        }
    }
}

/**
 * 全部图标都用矢量绘制，随 DPI 缩放保持锐利。
 */
final class Icons {
    private Icons() {}

    public static void paint(Graphics2D g, Glyph glyph, int x, int y, int size, Color color) {
        if (glyph == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(x, y);
        g2.scale(size / 24.0, size / 24.0);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        glyph.paint(g2, 24, color);
        g2.dispose();
    }

    private static Stroke stroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static void line(Graphics2D g, Color c, float w, float x1, float y1, float x2, float y2) {
        g.setColor(c);
        g.setStroke(stroke(w));
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    private static void poly(Graphics2D g, Color c, float w, boolean fill, float... pts) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.closePath();
        if (fill) {
            g.setColor(c);
            g.fill(p);
        } else {
            g.setColor(c);
            g.setStroke(stroke(w));
            g.draw(p);
        }
    }

    // ================= 导航 =================

    public static final Glyph HOME = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(3.2, 10.6);
        p.lineTo(12, 3.6);
        p.lineTo(20.8, 10.6);
        p.lineTo(20.8, 19.2);
        p.quadTo(20.8, 21, 19, 21);
        p.lineTo(15, 21);
        p.lineTo(15, 14.4);
        p.lineTo(9, 14.4);
        p.lineTo(9, 21);
        p.lineTo(5, 21);
        p.quadTo(3.2, 21, 3.2, 19.2);
        p.closePath();
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(p);
    };

    public static final Glyph DOWNLOAD = (g, s, c) -> {
        line(g, c, 1.9f, 12, 3.5f, 12, 14.5f);
        line(g, c, 1.9f, 7.2f, 10.2f, 12, 15);
        line(g, c, 1.9f, 16.8f, 10.2f, 12, 15);
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4, 16.4);
        p.lineTo(4, 18.2);
        p.quadTo(4, 20.2, 6, 20.2);
        p.lineTo(18, 20.2);
        p.quadTo(20, 20.2, 20, 18.2);
        p.lineTo(20, 16.4);
        g.setColor(c);
        g.setStroke(stroke(1.9f));
        g.draw(p);
    };

    public static final Glyph SETTINGS = (g, s, c) -> {
        line(g, c, 1.8f, 3.5f, 6.5f, 20.5f, 6.5f);
        line(g, c, 1.8f, 3.5f, 12f, 20.5f, 12f);
        line(g, c, 1.8f, 3.5f, 17.5f, 20.5f, 17.5f);
        g.setColor(c);
        g.fill(new Ellipse2D.Float(7.4f, 3.9f, 5.2f, 5.2f));
        g.setColor(Theme.SURFACE);
        g.fill(new Ellipse2D.Float(8.9f, 5.4f, 2.2f, 2.2f));
        g.setColor(c);
        g.fill(new Ellipse2D.Float(13.4f, 9.4f, 5.2f, 5.2f));
        g.setColor(Theme.SURFACE);
        g.fill(new Ellipse2D.Float(14.9f, 10.9f, 2.2f, 2.2f));
        g.setColor(c);
        g.fill(new Ellipse2D.Float(6.4f, 14.9f, 5.2f, 5.2f));
        g.setColor(Theme.SURFACE);
        g.fill(new Ellipse2D.Float(7.9f, 16.4f, 2.2f, 2.2f));
    };

    public static final Glyph LOG = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(new RoundRectangle2D.Float(5, 3, 14, 18, 3.5f, 3.5f));
        line(g, c, 1.6f, 8.4f, 8, 15.6f, 8);
        line(g, c, 1.6f, 8.4f, 12, 15.6f, 12);
        line(g, c, 1.6f, 8.4f, 16, 12.8f, 16);
    };

    public static final Glyph TERMINAL = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(new RoundRectangle2D.Float(3, 4.5f, 18, 15, 3f, 3f));
        line(g, c, 1.7f, 7.2f, 9.2f, 10.2f, 12);
        line(g, c, 1.7f, 10.2f, 12, 7.2f, 14.8f);
        line(g, c, 1.7f, 12.4f, 15, 16.4f, 15);
    };

    // ================= 操作 =================

    public static final Glyph PLAY = (g, s, c) -> poly(g, c, 0, true,
            8f, 5f, 19f, 12f, 8f, 19f);

    /** 两竖条的暂停图标。 */
    public static final Glyph PAUSE = (g, s, c) -> {
        Theme.fillRound(g, 7.1f, 5f, 3.7f, 14f, 1.7f, c);
        Theme.fillRound(g, 13.2f, 5f, 3.7f, 14f, 1.7f, c);
    };

    public static final Glyph REFRESH = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.9f));
        Arc2D.Float arc = new Arc2D.Float(3.5f, 3.5f, 17, 17, 55, 285, Arc2D.OPEN);
        g.draw(arc);
        poly(g, c, 1.9f, false, 17.2f, 2.8f, 21.4f, 5.4f, 20.2f, 9.4f);
    };

    public static final Glyph SEARCH = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.9f));
        g.draw(new Ellipse2D.Float(4, 4, 11.5f, 11.5f));
        line(g, c, 2f, 14.4f, 14.4f, 20.4f, 20.4f);
    };

    public static final Glyph PLUS = (g, s, c) -> {
        line(g, c, 1.9f, 12, 4.5f, 12, 19.5f);
        line(g, c, 1.9f, 4.5f, 12, 19.5f, 12);
    };

    public static final Glyph CHECK = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4.5, 12.6);
        p.lineTo(9.6, 17.6);
        p.lineTo(19.6, 6.6);
        g.setColor(c);
        g.setStroke(stroke(2.1f));
        g.draw(p);
    };

    public static final Glyph CLOSE = (g, s, c) -> {
        line(g, c, 1.8f, 6, 6, 18, 18);
        line(g, c, 1.8f, 18, 6, 6, 18);
    };

    public static final Glyph MINIMIZE = (g, s, c) -> line(g, c, 1.7f, 5.5f, 12, 18.5f, 12);

    public static final Glyph MAXIMIZE = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        g.draw(new RoundRectangle2D.Float(5.5f, 5.5f, 13, 13, 2.5f, 2.5f));
    };

    public static final Glyph RESTORE = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        g.draw(new RoundRectangle2D.Float(4, 7.5f, 11.5f, 11.5f, 2.5f, 2.5f));
        Path2D.Float p = new Path2D.Float();
        p.moveTo(8.5, 7.5);
        p.lineTo(8.5, 5.5);
        p.quadTo(8.5, 4.5, 9.5, 4.5);
        p.lineTo(18.5, 4.5);
        p.quadTo(19.5, 4.5, 19.5, 5.5);
        p.lineTo(19.5, 14.5);
        p.quadTo(19.5, 15.5, 18.5, 15.5);
        p.lineTo(16.5, 15.5);
        g.draw(p);
    };

    public static final Glyph CHEVRON_DOWN = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(6, 9.4f);
        p.lineTo(12, 15.4f);
        p.lineTo(18, 9.4f);
        g.setColor(c);
        g.setStroke(stroke(1.9f));
        g.draw(p);
    };

    public static final Glyph CHEVRON_RIGHT = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(9, 5.8f);
        p.lineTo(15.2f, 12);
        p.lineTo(9, 18.2f);
        g.setColor(c);
        g.setStroke(stroke(1.9f));
        g.draw(p);
    };

    public static final Glyph ARROW_RIGHT = (g, s, c) -> {
        line(g, c, 1.9f, 4, 12, 20, 12);
        line(g, c, 1.9f, 13.5f, 5.5f, 20, 12);
        line(g, c, 1.9f, 13.5f, 18.5f, 20, 12);
    };

    public static final Glyph FOLDER = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(3.2, 7.2);
        p.quadTo(3.2, 5.2, 5.2, 5.2);
        p.lineTo(9.4, 5.2);
        p.lineTo(11.4, 7.4);
        p.lineTo(18.8, 7.4);
        p.quadTo(20.8, 7.4, 20.8, 9.4);
        p.lineTo(20.8, 17.8);
        p.quadTo(20.8, 19.8, 18.8, 19.8);
        p.lineTo(5.2, 19.8);
        p.quadTo(3.2, 19.8, 3.2, 17.8);
        p.closePath();
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(p);
    };

    public static final Glyph COPY = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        g.draw(new RoundRectangle2D.Float(8.2f, 8.2f, 11.6f, 11.6f, 2.5f, 2.5f));
        Path2D.Float p = new Path2D.Float();
        p.moveTo(15.8, 8.2);
        p.lineTo(15.8, 6.2);
        p.quadTo(15.8, 4.2, 13.8, 4.2);
        p.lineTo(6.2, 4.2);
        p.quadTo(4.2, 4.2, 4.2, 6.2);
        p.lineTo(4.2, 13.8);
        p.quadTo(4.2, 15.8, 6.2, 15.8);
        p.lineTo(8.2, 15.8);
        g.draw(p);
    };

    public static final Glyph TRASH = (g, s, c) -> {
        line(g, c, 1.7f, 4.2f, 7, 19.8f, 7);
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        Path2D.Float p = new Path2D.Float();
        p.moveTo(6.4, 7);
        p.lineTo(7.4, 19.2);
        p.quadTo(7.5, 20.4, 8.7, 20.4);
        p.lineTo(15.3, 20.4);
        p.quadTo(16.5, 20.4, 16.6, 19.2);
        p.lineTo(17.6, 7);
        g.draw(p);
        line(g, c, 1.7f, 9.4f, 4.2f, 14.6f, 4.2f);
        line(g, c, 1.7f, 10, 11, 10, 17);
        line(g, c, 1.7f, 14, 11, 14, 17);
    };

    public static final Glyph USER = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(new Ellipse2D.Float(8, 3.6f, 8, 8));
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4.2, 21);
        p.quadTo(4.2, 14.4, 12, 14.4);
        p.quadTo(19.8, 14.4, 19.8, 21);
        g.draw(p);
    };

    public static final Glyph CUBE = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        Path2D.Float top = new Path2D.Float();
        top.moveTo(12, 2.8);
        top.lineTo(20.4, 7.4);
        top.lineTo(12, 12);
        top.lineTo(3.6, 7.4);
        top.closePath();
        Path2D.Float left = new Path2D.Float();
        left.moveTo(3.6, 7.4);
        left.lineTo(12, 12);
        left.lineTo(12, 21.2);
        left.lineTo(3.6, 16.6);
        left.closePath();
        Path2D.Float right = new Path2D.Float();
        right.moveTo(20.4, 7.4);
        right.lineTo(12, 12);
        right.lineTo(12, 21.2);
        right.lineTo(20.4, 16.6);
        right.closePath();
        g.draw(top);
        g.draw(left);
        g.draw(right);
    };

    /** 原版：草方块。 */
    public static final Glyph GRASS = (g, s, c) -> {
        Path2D.Float top = new Path2D.Float();
        top.moveTo(12, 2.4);
        top.lineTo(20.6, 7.3);
        top.lineTo(12, 12.2);
        top.lineTo(3.4, 7.3);
        top.closePath();
        Path2D.Float left = new Path2D.Float();
        left.moveTo(3.4, 7.3);
        left.lineTo(12, 12.2);
        left.lineTo(12, 21.4);
        left.lineTo(3.4, 16.5);
        left.closePath();
        Path2D.Float right = new Path2D.Float();
        right.moveTo(20.6, 7.3);
        right.lineTo(12, 12.2);
        right.lineTo(12, 21.4);
        right.lineTo(20.6, 16.5);
        right.closePath();

        g.setColor(new Color(0x8B, 0x5A, 0x2B));
        g.fill(left);
        g.setColor(new Color(0x6E, 0x44, 0x22));
        g.fill(right);
        g.setColor(new Color(0x7C, 0xC8, 0x65));
        g.fill(top);

        Path2D.Float leftGrass = new Path2D.Float();
        leftGrass.moveTo(3.4, 7.3);
        leftGrass.lineTo(12, 12.2);
        leftGrass.lineTo(12, 14.5);
        leftGrass.lineTo(3.4, 9.6);
        leftGrass.closePath();
        Path2D.Float rightGrass = new Path2D.Float();
        rightGrass.moveTo(20.6, 7.3);
        rightGrass.lineTo(12, 12.2);
        rightGrass.lineTo(12, 14.5);
        rightGrass.lineTo(20.6, 9.6);
        rightGrass.closePath();
        g.setColor(new Color(0x5E, 0xA8, 0x4C));
        g.fill(leftGrass);
        g.fill(rightGrass);

        g.setColor(new Color(255, 255, 255, 75));
        g.setStroke(new BasicStroke(0.8f));
        g.draw(top);
        g.draw(left);
        g.draw(right);
    };

    /** Fabric / Quilt：像纸片/织物一样带折角的图标。 */
    public static final Glyph FABRIC = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.5f));
        Path2D.Float p = new Path2D.Float();
        p.moveTo(4.0, 6.2);
        p.lineTo(15.5, 6.2);
        p.lineTo(20.2, 10.8);
        p.lineTo(20.2, 17.2);
        p.lineTo(8.0, 17.2);
        p.quadTo(4.0, 17.2, 4.0, 13.0);
        p.closePath();
        g.draw(p);
        line(g, c, 1.25f, 15.5f, 6.2f, 15.5f, 10.8f);
        line(g, c, 1.25f, 15.5f, 10.8f, 20.2f, 10.8f);
        line(g, c, 1.0f, 7.2f, 8.2f, 11.0f, 12.2f);
    };

    /** Forge / NeoForge：铁砧（宽顶、窄腰、宽底）。 */
    public static final Glyph ANVIL = (g, s, c) -> {
        g.setColor(c);
        // 平顶、左侧长角、窄腰、带底座的铁砧
        g.fillRect(6, 5, 14, 4);
        Path2D.Float horn = new Path2D.Float();
        horn.moveTo(1.2, 7.4);
        horn.lineTo(6.0, 4.8);
        horn.lineTo(6.0, 9.2);
        horn.closePath();
        g.fill(horn);
        g.fillRect(10, 9, 4, 5);
        g.fillRect(6, 14, 12, 3);
        g.fillRect(5, 17, 14, 2);
    };

    public static final Glyph MEMORY = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        g.draw(new RoundRectangle2D.Float(6.5f, 6.5f, 11, 11, 2.5f, 2.5f));
        for (int i = 0; i < 3; i++) {
            float p = 9.5f + i * 2.5f;
            line(g, c, 1.5f, p, 3, p, 5.8f);
            line(g, c, 1.5f, p, 18.2f, p, 21);
            line(g, c, 1.5f, 3, p, 5.8f, p);
            line(g, c, 1.5f, 18.2f, p, 21, p);
        }
    };

    public static final Glyph GLOBE = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        g.draw(new Ellipse2D.Float(3.4f, 3.4f, 17.2f, 17.2f));
        g.draw(new Ellipse2D.Float(8.4f, 3.4f, 7.2f, 17.2f));
        line(g, c, 1.6f, 3.8f, 12, 20.2f, 12);
        line(g, c, 1.6f, 4.6f, 8, 19.4f, 8);
        line(g, c, 1.6f, 4.6f, 16, 19.4f, 16);
    };

    public static final Glyph SHIELD = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(12, 3);
        p.lineTo(20, 6.2);
        p.lineTo(20, 12);
        p.quadTo(20, 18.2, 12, 21.2);
        p.quadTo(4, 18.2, 4, 12);
        p.lineTo(4, 6.2);
        p.closePath();
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(p);
        g.setColor(c);
        g.setStroke(stroke(1.8f));
        g.draw(new Line2D.Float(8.6f, 12, 11.2f, 14.6f));
        g.draw(new Line2D.Float(11.2f, 14.6f, 15.6f, 9));
    };

    public static final Glyph BOLT = (g, s, c) -> poly(g, c, 0, true,
            13.5f, 2.5f, 5.2f, 13.4f, 11.3f, 13.4f, 10.2f, 21.5f,
            18.8f, 10.2f, 12.6f, 10.2f);

    public static final Glyph INFO = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        g.draw(new Ellipse2D.Float(3.4f, 3.4f, 17.2f, 17.2f));
        line(g, c, 2f, 12, 11, 12, 17.2f);
        g.setColor(c);
        g.fill(new Ellipse2D.Float(11f, 6.6f, 2f, 2f));
    };

    public static final Glyph WARNING = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(12, 3.2);
        p.lineTo(21.2, 19.6);
        p.lineTo(2.8, 19.6);
        p.closePath();
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        g.draw(p);
        line(g, c, 2f, 12, 9, 12, 14);
        g.setColor(c);
        g.fill(new Ellipse2D.Float(11f, 16, 2f, 2f));
    };

    public static final Glyph SPARKLE = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(12, 2.5);
        p.curveTo(12.8f, 8, 16, 11.2f, 21.5f, 12);
        p.curveTo(16, 12.8f, 12.8f, 16, 12, 21.5);
        p.curveTo(11.2f, 16, 8, 12.8f, 2.5f, 12);
        p.curveTo(8, 11.2f, 11.2f, 8, 12, 2.5);
        g.setColor(c);
        g.fill(p);
    };

    public static final Glyph TAG = (g, s, c) -> {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(3.4f, 12.4f);
        p.lineTo(12.2f, 3.6f);
        p.lineTo(20.4f, 3.6f);
        p.lineTo(20.4f, 11.8f);
        p.lineTo(11.6f, 20.6f);
        p.closePath();
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        g.draw(p);
        g.setColor(c);
        g.fill(new Ellipse2D.Float(15.6f, 6.4f, 2.6f, 2.6f));
    };

    public static final Glyph BOX = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.6f));
        g.draw(new RoundRectangle2D.Float(4.4f, 3.4f, 15.2f, 17.2f, 2.6f, 2.6f));
        line(g, c, 1.6f, 4.4f, 8, 19.6f, 8);
        line(g, c, 1.8f, 9.5f, 12, 14.5f, 12);
    };

    public static final Glyph CLOCK = (g, s, c) -> {
        g.setColor(c);
        g.setStroke(stroke(1.7f));
        g.draw(new Ellipse2D.Float(3.4f, 3.4f, 17.2f, 17.2f));
        line(g, c, 1.8f, 12, 7.4f, 12, 12.6f);
        line(g, c, 1.8f, 12, 12.6f, 15.6f, 14.6f);
    };
}

/** 在 24x24 视图盒中绘制的矢量图标。 */
@FunctionalInterface
interface Glyph {
    void paint(Graphics2D g, int size, Color color);
}

/**
 * 圆角卡片容器。可带柔和阴影与渐变背景。
 */
class Card extends JPanel {
    private int radius = 18;
    private int padding = 18;
    private int shadowPad = 0;
    private Color fill;
    private Color border;
    private Color gradientTop;
    private Color gradientBottom;

    public Card(LayoutManager layout, int padding) {
        this(layout, padding, 18, false);
    }

    public Card(LayoutManager layout, int padding, int radius, boolean shadow) {
        super(layout);
        this.padding = padding;
        this.radius = radius;
        this.shadowPad = shadow ? 9 : 0;
        setOpaque(false);
        updateBorder();
    }

    public Card withShadow() {
        this.shadowPad = 9;
        updateBorder();
        return this;
    }

    public Card withGradient(Color top, Color bottom) {
        this.gradientTop = top;
        this.gradientBottom = bottom;
        return this;
    }

    private static Color darkAdapt(Color c) {
        if (!Theme.isDark() || c == null || c.getAlpha() < 255) return c;
        int lum = (int) (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
        return lum >= 200 ? Theme.mix(c, Theme.SURFACE, 0.84f) : c;
    }

    public Card withFill(Color c) {
        this.fill = c;
        return this;
    }

    public Card withBorder(Color c) {
        this.border = c;
        return this;
    }

    public Card withRadius(int r) {
        this.radius = r;
        return this;
    }

    public void setPadding(int p) {
        this.padding = p;
        updateBorder();
    }

    public int getRadius() {
        return radius;
    }

    private void updateBorder() {
        setBorder(new EmptyBorder(shadowPad + padding, shadowPad + padding,
                shadowPad + padding, shadowPad + padding));
    }

    public Shape cardShape() {
        return Theme.roundRect(shadowPad, shadowPad,
                getWidth() - shadowPad * 2, getHeight() - shadowPad * 2, radius);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int s = shadowPad;
        float x = s, y = s, w = getWidth() - s * 2f, h = getHeight() - s * 2f;
        if (w <= 0 || h <= 0) {
            g2.dispose();
            return;
        }
        if (s > 0) {
            Theme.shadow(g2, x, y, w, h, radius, s);
        }
        Shape shape = Theme.roundRect(x, y, w, h, radius);
        if (gradientTop != null && gradientBottom != null) {
            g2.setPaint(new GradientPaint(x, y, darkAdapt(gradientTop),
                    x, y + h, darkAdapt(gradientBottom)));
        } else {
            g2.setColor(darkAdapt(fill != null ? fill : Theme.SURFACE));
        }
        g2.fill(shape);
        g2.setColor(darkAdapt(border != null ? border : Theme.BORDER));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(Theme.roundRect(x + 0.5f, y + 0.5f, w - 1, h - 1, radius));
        g2.dispose();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setOpaque(false);
    }
}

/** 静态小标签：版本类型、加载器、数量等。 */
class Badge extends JComponent {
    private String text;
    private Color bg;
    private Color fg;
    private boolean dot;
    private Color dotColor;
    private float fontSize = 10.5f;
    private int height = 20;

    public Badge(String text, Color bg, Color fg) {
        this.text = text == null ? "" : text;
        this.bg = bg;
        this.fg = fg;
        setOpaque(false);
    }

    public static Badge soft(String text, Color color) {
        return new Badge(text, Theme.alpha(color, 32), color);
    }

    public Badge withDot(Color color) {
        this.dot = true;
        this.dotColor = color;
        revalidate();
        return this;
    }

    public Badge fontSize(float size) {
        this.fontSize = size;
        revalidate();
        return this;
    }

    public Badge height(int h) {
        this.height = h;
        revalidate();
        return this;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        revalidate();
        repaint();
    }

    public void setColors(Color bg, Color fg) {
        this.bg = bg;
        this.fg = fg;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.bold(fontSize));
        int w = fm.stringWidth(text) + 16 + (dot ? 11 : 0);
        return new Dimension(w, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        Color paintBg = bg;
        Color paintFg = fg;
        if (Theme.isDark() && bg != null && bg.getAlpha() == 255) {
            int lum = (int) (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue());
            if (lum >= 200) paintBg = Theme.mix(bg, Theme.SURFACE, 0.84f);
            if (fg != null) paintFg = Theme.mix(fg, Color.WHITE, 0.35f);
        }
        g2.setColor(paintBg);
        g2.fill(Theme.roundRect(0, 0, w, h, h / 2f));
        int tx = 8;
        if (dot && dotColor != null) {
            g2.setColor(dotColor);
            g2.fill(new Ellipse2D.Float(8, h / 2f - 3, 6, 6));
            tx += 11;
        }
        g2.setFont(Theme.bold(fontSize));
        g2.setColor(paintFg);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, tx, (h - fm.getHeight()) / 2f + fm.getAscent());
        g2.dispose();
    }
}

/** 渐变圆形头像，使用名字首字作为标识。 */
class Avatar extends JComponent {
    private static final Color[][] PALETTES = {
            {new Color(0x6E, 0xA6, 0xFF), new Color(0x3B, 0x82, 0xF6)},
            {new Color(0x8B, 0x9D, 0xFF), new Color(0x60, 0x5C, 0xE8)},
            {new Color(0x67, 0xD9, 0xC1), new Color(0x14, 0xB8, 0xA6)},
            {new Color(0xFF, 0xB2, 0x7A), new Color(0xF5, 0x7C, 0x3A)},
            {new Color(0xC7, 0x9B, 0xFF), new Color(0x8B, 0x5C, 0xF6)},
            {new Color(0x7D, 0xD3, 0xFC), new Color(0x0E, 0xA5, 0xE9)}
    };

    private String name;
    private int size;

    public Avatar(String name, int size) {
        this.name = name == null ? "" : name;
        this.size = size;
        setOpaque(false);
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setMaximumSize(new Dimension(size, size));
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
        repaint();
    }

    public static void paint(Graphics2D g, float x, float y, float size, String name) {
        int hash = Math.floorMod(name == null ? 0 : name.hashCode(), PALETTES.length);
        Color[] pal = PALETTES[hash];
        g.setColor(new Color(0x1B, 0x2F, 0x52, 26));
        g.fill(new Ellipse2D.Float(x, y + 1.2f, size, size));
        g.setPaint(new GradientPaint(x, y, pal[0], x + size, y + size, pal[1]));
        g.fill(new Ellipse2D.Float(x, y, size, size));
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, size - 1, size - 1));

        String initial = initialOf(name);
        Font f = Theme.bold(size * 0.42f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(initial,
                x + (size - fm.stringWidth(initial)) / 2f,
                y + (size - fm.getHeight()) / 2f + fm.getAscent());
    }

    public static String initialOf(String name) {
        if (name == null || name.isBlank()) return "?";
        String t = name.trim();
        int cp = t.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        paint(g2, 0, 0, Math.min(getWidth(), getHeight()), name);
        g2.dispose();
    }
}

/** 自动省略号文本标签，避免长版本名把布局撑破。 */
class EllipsisLabel extends JComponent {
    private String text;
    private Font font;
    private Color color;
    private int align = SwingConstants.LEFT;

    public EllipsisLabel(String text, Font font, Color color) {
        this.text = text == null ? "" : text;
        this.font = font;
        this.color = color;
        setOpaque(false);
        setFont(font);
        setForeground(color);
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        setToolTipText(this.text);
        revalidate();
        repaint();
    }

    public String getText() {
        return text;
    }

    public void setColor(Color color) {
        this.color = color;
        setForeground(color);
        repaint();
    }

    public void setTextFont(Font font) {
        this.font = font;
        setFont(font);
        revalidate();
        repaint();
    }

    public void setHorizontalAlignment(int align) {
        this.align = align;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(font);
        return new Dimension(fm.stringWidth(text), fm.getHeight() + 2);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(10, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        String shown = Theme.ellipsize(fm, text, Math.max(0, getWidth() - 2));
        int x = align == SwingConstants.CENTER
                ? (getWidth() - fm.stringWidth(shown)) / 2
                : align == SwingConstants.RIGHT ? getWidth() - fm.stringWidth(shown) : 0;
        g2.setColor(color);
        g2.drawString(shown, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}

class WindowShell extends JPanel {
    private final JFrame frame;
    private int shadowPad = 6;
    private int radius = 22;
    private boolean maximized;
    private int resizeDir;
    private Point dragStart;
    private Rectangle startBounds;

    private static final int N = 1, S = 2, W = 4, E = 8;

    public WindowShell(JFrame frame) {
        this.frame = frame;
        setOpaque(true);
        setBackground(Theme.BG_TOP);
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(shadowPad, shadowPad, shadowPad, shadowPad));
        // installResize(); // 锁定：禁用边缘拖拽拉伸
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                updateWindowShape();
            }
        });
    }

    /** 用窗口 Shape 裁出圆角；不支持时退化为直角窗口。 */
    public void updateWindowShape() {
        if (frame == null) return;
        try {
            if (maximized) {
                frame.setShape(null);
            } else {
                int w = frame.getWidth(), h = frame.getHeight();
                if (w <= 0 || h <= 0) return;
                frame.setShape(new RoundRectangle2D.Float(0, 0, w, h, radius * 2f, radius * 2f));
            }
        } catch (Throwable ignored) {}
    }

    public int getShadowPad() {
        return shadowPad;
    }

    public void setMaximized(boolean max) {
        this.maximized = max;
        shadowPad = max ? 0 : 6;
        radius = max ? 0 : 22;
        setBorder(new EmptyBorder(shadowPad, shadowPad, shadowPad, shadowPad));
        revalidate();
        repaint();
        updateWindowShape();
    }

    public boolean isMaximized() {
        return maximized;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        g2.setPaint(new GradientPaint(0, 0, Theme.BG_TOP, 0, h, Theme.BG_BOTTOM));
        g2.fillRect(0, 0, w, h);
        if (!maximized) {
            g2.setPaint(new RadialGradientPaint(
                    new Point2D.Float(w * 0.16f, 30),
                    Math.max(w, h) * 0.55f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(0x6E, 0xA6, 0xFF, 26), new Color(0x6E, 0xA6, 0xFF, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.setColor(Theme.isDark()
                    ? Theme.BORDER_STRONG
                    : new Color(0xD9, 0xE2, 0xEE));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, radius));
        }
        g2.dispose();
    }

    private void installResize() {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (maximized || !SwingUtilities.isLeftMouseButton(e)) return;
                int dir = edgeAt(e.getX(), e.getY());
                if (dir == 0) return;
                resizeDir = dir;
                dragStart = e.getLocationOnScreen();
                startBounds = frame.getBounds();
                e.consume();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (resizeDir == 0 || startBounds == null) return;
                Point p = e.getLocationOnScreen();
                int dx = p.x - dragStart.x;
                int dy = p.y - dragStart.y;
                Rectangle r = new Rectangle(startBounds);
                int minW = 1180, minH = 700;
                if ((resizeDir & W) != 0) {
                    int nx = startBounds.x + dx;
                    int maxX = startBounds.x + startBounds.width - minW;
                    r.x = Math.min(nx, maxX);
                    r.width = startBounds.width - (r.x - startBounds.x);
                }
                if ((resizeDir & E) != 0) {
                    r.width = Math.max(minW, startBounds.width + dx);
                }
                if ((resizeDir & N) != 0) {
                    int ny = startBounds.y + dy;
                    int maxY = startBounds.y + startBounds.height - minH;
                    r.y = Math.min(ny, maxY);
                    r.height = startBounds.height - (r.y - startBounds.y);
                }
                if ((resizeDir & S) != 0) {
                    r.height = Math.max(minH, startBounds.height + dy);
                }
                frame.setBounds(r);
                e.consume();
            }

            @Override public void mouseReleased(MouseEvent e) {
                resizeDir = 0;
                startBounds = null;
            }

            @Override public void mouseMoved(MouseEvent e) {
                if (maximized) {
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                int dir = edgeAt(e.getX(), e.getY());
                setCursor(switch (dir) {
                    case N -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
                    case S -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
                    case W -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
                    case E -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
                    case N | W -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
                    case N | E -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
                    case S | W -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
                    case S | E -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
                    default -> Cursor.getDefaultCursor();
                });
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    private int edgeAt(int x, int y) {
        int edge = 9;
        int w = getWidth(), h = getHeight();
        int dir = 0;
        if (x <= edge) dir |= W;
        if (x >= w - edge) dir |= E;
        if (y <= edge) dir |= N;
        if (y >= h - edge) dir |= S;
        return dir;
    }
}

/** 自定义标题栏：品牌、拖动、双击最大化与窗口控制。 */
class TitleBar extends JPanel {
    public interface WindowActions {
        void minimizeWindow();
        void toggleMaximize();
        void closeWindow();
        boolean isWindowMaximized();
        void restoreWindowForDrag();
    }

    private final JFrame frame;
    private final WindowActions actions;
    private final IconButton maximizeBtn;
    private Point dragStart;
    private Point frameStart;

    public TitleBar(JFrame frame, WindowActions actions, String appName, String version) {
        this.frame = frame;
        this.actions = actions;
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 16, 2, 10));
        setPreferredSize(new Dimension(0, 48));

        Brand brand = new Brand(appName, version);
        add(brand, BorderLayout.WEST);

        JPanel drag = new JPanel();
        drag.setOpaque(false);
        drag.setCursor(Cursor.getDefaultCursor());
        add(drag, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        controls.setOpaque(false);
        IconButton min = new IconButton(Icons.MINIMIZE, "最小化", IconButton.Variant.TITLE);
        min.setPreferredSize(new Dimension(34, 28));
        min.setMinimumSize(min.getPreferredSize());
        min.setMaximumSize(min.getPreferredSize());
        min.addActionListener(e -> actions.minimizeWindow());

        maximizeBtn = new IconButton(Icons.MAXIMIZE, "最大化", IconButton.Variant.TITLE);
        maximizeBtn.setPreferredSize(new Dimension(34, 28));
        maximizeBtn.setMinimumSize(maximizeBtn.getPreferredSize());
        maximizeBtn.setMaximumSize(maximizeBtn.getPreferredSize());
        maximizeBtn.addActionListener(e -> {
            actions.toggleMaximize();
            updateMaximizeIcon();
        });

        IconButton close = new IconButton(Icons.CLOSE, "关闭", IconButton.Variant.TITLE);
        close.setPreferredSize(new Dimension(34, 28));
        close.setMinimumSize(close.getPreferredSize());
        close.setMaximumSize(close.getPreferredSize());
        close.setDangerHover(true);
        close.addActionListener(e -> actions.closeWindow());

        controls.add(min);
        // controls.add(maximizeBtn);
        controls.add(close);
        add(controls, BorderLayout.EAST);

        MouseAdapter dragHandler = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                // 彩蛋/锁定：禁止双击最大化
                if (e.getClickCount() == 2) {
                    return;
                }
                if (actions.isWindowMaximized()) {
                    actions.restoreWindowForDrag();
                }
                dragStart = e.getLocationOnScreen();
                frameStart = frame.getLocation();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null || frameStart == null) return;
                Point p = e.getLocationOnScreen();
                frame.setLocation(frameStart.x + (p.x - dragStart.x),
                        frameStart.y + (p.y - dragStart.y));
            }

            @Override public void mouseReleased(MouseEvent e) {
                dragStart = null;
                frameStart = null;
            }
        };
        addMouseListener(dragHandler);
        addMouseMotionListener(dragHandler);
        brand.addMouseListener(dragHandler);
        brand.addMouseMotionListener(dragHandler);
        drag.addMouseListener(dragHandler);
        drag.addMouseMotionListener(dragHandler);
    }

    public void updateMaximizeIcon() {
        maximizeBtn.setGlyph(actions.isWindowMaximized() ? Icons.RESTORE : Icons.MAXIMIZE);
    }

    private static final class Brand extends JComponent {
        private final String name;
        private final String version;

        private long lastClickTime = 0;
        private int clickCount = 0;

        Brand(String name, String version) {
            this.name = name;
            this.version = version;
            setPreferredSize(new Dimension(258, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // ================= 彩蛋：连点左上角 M 5次 =================
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    long now = System.currentTimeMillis();
                    if (now - lastClickTime < 500) {
                        clickCount++;
                    } else {
                        clickCount = 1;
                    }
                    lastClickTime = now;

                    if (clickCount >= 5) {
                        clickCount = 0;
                        Window w = SwingUtilities.getWindowAncestor(Brand.this);
                        if (w != null) w.setVisible(false);

                        new RetroLauncherFrame(() -> {
                            if (w != null) w.setVisible(true);
                        });
                    }
                }
            });
            // ========================================================
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int h = getHeight();
            int logo = 26;
            int ly = (h - logo) / 2;
            g2.setPaint(new GradientPaint(0, ly, new Color(0x5B, 0x9B, 0xFF),
                    logo, ly + logo, new Color(0x2C, 0x6B, 0xF0)));
            g2.fill(Theme.roundRect(0, ly, logo, logo, 8));
            g2.setColor(new Color(255, 255, 255, 240));
            g2.setFont(Theme.bold(15));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("M", (logo - fm.stringWidth("M")) / 2f,
                    ly + (logo - fm.getHeight()) / 2f + fm.getAscent());
            g2.setColor(Theme.TEXT);
            g2.setFont(Theme.bold(13.5f));
            fm = g2.getFontMetrics();
            int tx = logo + 9;
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(name, tx, baseline);
            if (version != null && !version.isBlank()) {
                int nameW = fm.stringWidth(name);
                Font vf = Theme.font(10f);
                g2.setFont(vf);
                FontMetrics vfm = g2.getFontMetrics();
                int vw = vfm.stringWidth(version) + 12;
                int vx = tx + nameW + 8;
                int vy = (h - 18) / 2;
                g2.setColor(Theme.isDark() ? new Color(0x2A, 0x3A, 0x52) : new Color(0xEE, 0xF3, 0xFA));
                g2.fill(Theme.roundRect(vx, vy, vw, 18, 6));
                g2.setColor(Theme.isDark() ? Theme.TEXT_SUB : Theme.TEXT_MUTED);
                g2.drawString(version, vx + 6, vy + (18 - vfm.getHeight()) / 2 + vfm.getAscent());
            }
            g2.dispose();
        }
    }
}

/** 左侧导航：滑动高亮、底部玩家卡片。 */
class SidebarNav extends JComponent {
    private static final class Item {
        final String key, label;
        final Glyph icon;
        Item(String key, String label, Glyph icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private String selectedKey = "";
    private String username = "Player";
    private String version = "";
    private Consumer<String> onSelect;
    private Runnable onSettings;

    private final Animator.AnimatedValue selY;
    private final Animator.AnimatedValue hoverAlpha;
    private final Animator.AnimatedValue profileHover;
    private int hoverIndex = -1;
    private boolean profileHovered;

    private static final int PANEL_X = 10;
    private static final int ITEM_H = 44;
    private static final int ITEM_GAP = 4;
    private static final int NAV_TOP = 46;

    public SidebarNav() {
        setOpaque(false);
        setPreferredSize(new Dimension(226, 0));
        setMinimumSize(new Dimension(226, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        selY = new Animator.AnimatedValue(NAV_TOP, v -> repaint());
        hoverAlpha = new Animator.AnimatedValue(0, v -> repaint());
        profileHover = new Animator.AnimatedValue(0, v -> repaint());
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int idx = indexAt(e.getY());
                if (idx != hoverIndex) {
                    hoverIndex = idx;
                    hoverAlpha.snap(0);
                    if (idx >= 0) hoverAlpha.set(1, 150);
                }
                boolean ph = profileRect().contains(e.getPoint());
                if (ph != profileHovered) {
                    profileHovered = ph;
                    profileHover.set(ph ? 1 : 0, 170);
                }
                setCursor((idx >= 0 || ph) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }

            @Override public void mouseExited(MouseEvent e) {
                hoverIndex = -1;
                hoverAlpha.set(0, 160);
                profileHovered = false;
                profileHover.set(0, 170);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int idx = indexAt(e.getY());
                if (idx >= 0) {
                    select(items.get(idx).key, true);
                } else if (profileRect().contains(e.getPoint()) && onSettings != null) {
                    onSettings.run();
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void addItem(String key, String label, Glyph icon) {
        items.add(new Item(key, label, icon));
    }

    public void setOnSelect(Consumer<String> c) {
        this.onSelect = c;
    }

    public void setOnSettings(Runnable r) {
        this.onSettings = r;
    }

    public void setSelectedKey(String key) {
        if (key == null || key.equals(selectedKey)) return;
        selectedKey = key;
        int idx = indexOf(key);
        if (idx >= 0) selY.set(itemY(idx), 300);
        repaint();
    }

    public void setUsername(String username) {
        this.username = (username == null || username.isBlank()) ? "Player" : username;
        repaint();
    }

    public void setVersion(String version) {
        this.version = version;
        repaint();
    }

    private void select(String key, boolean notify) {
        setSelectedKey(key);
        if (notify && onSelect != null) onSelect.accept(key);
    }

    private int indexOf(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private int itemY(int index) {
        return NAV_TOP + index * (ITEM_H + ITEM_GAP);
    }

    private int indexAt(int y) {
        for (int i = 0; i < items.size(); i++) {
            int iy = itemY(i);
            if (y >= iy && y <= iy + ITEM_H) return i;
        }
        return -1;
    }

    private Rectangle profileRect() {
        int h = getHeight();
        int ph = 70;
        int y = Math.max(NAV_TOP + items.size() * (ITEM_H + ITEM_GAP) + 24, h - ph - 38);
        return new Rectangle(PANEL_X + 10, y, getWidth() - PANEL_X * 2 - 20, ph);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        Shape panel = Theme.roundRect(PANEL_X, 4, w - PANEL_X * 2, h - 10, 18);
        g2.setColor(Theme.isDark() ? new Color(0x0F, 0x17, 0x24, 246)
                : new Color(0xF5, 0xF8, 0xFD, 246));
        g2.fill(panel);
        g2.setColor(Theme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(Theme.roundRect(PANEL_X + 0.5f, 4.5f, w - PANEL_X * 2 - 1, h - 11, 18));

        g2.setFont(Theme.bold(10.5f));
        g2.setColor(Theme.TEXT_MUTED);
        g2.drawString("导 航", PANEL_X + 20, 30);

        int ix = PANEL_X + 10;
        int iw = w - PANEL_X * 2 - 20;

        // 选中背景（滑动）
        int selIdx = indexOf(selectedKey);
        if (selIdx >= 0) {
            float sy = selY.value();
            g2.setColor(Theme.SIDEBAR_SEL);
            g2.fill(Theme.roundRect(ix, sy, iw, ITEM_H, 12));
            g2.setColor(Theme.alpha(Theme.PRIMARY, 18));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(Theme.roundRect(ix + 0.5f, sy + 0.5f, iw - 1, ITEM_H - 1, 12));
        }
        // 悬停背景
        if (hoverIndex >= 0 && hoverAlpha.value() > 0.01f) {
            g2.setColor(Theme.alpha(Theme.PRIMARY, (int) (16 * hoverAlpha.value())));
            g2.fill(Theme.roundRect(ix, itemY(hoverIndex), iw, ITEM_H, 12));
        }

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int y = itemY(i);
            boolean selected = it.key.equals(selectedKey);
            Color fg = selected
                    ? (Theme.isDark() ? new Color(0x9C, 0xC3, 0xFF) : Theme.PRIMARY_DARK)
                    : Theme.TEXT_SUB;
            int iconColorAlpha = selected ? 255 : 205;
            Icons.paint(g2, it.icon, ix + 14, y + (ITEM_H - 18) / 2, 18,
                    Theme.alpha(fg, iconColorAlpha));
            g2.setFont(selected ? Theme.bold(14) : Theme.font(14));
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(it.label, ix + 44, y + (ITEM_H - fm.getHeight()) / 2 + fm.getAscent());
            if (selected) {
                g2.setColor(Theme.PRIMARY);
                g2.fill(Theme.roundRect(ix + iw - 16, y + ITEM_H / 2f - 2, 4, 4, 2));
            }
        }

        // 底部玩家卡片
        Rectangle pr = profileRect();
        float ph = profileHover.value();
        Color profileBase = Theme.isDark() ? Theme.SURFACE_ALT : Color.WHITE;
        g2.setColor(Theme.mix(profileBase, Theme.PRIMARY_LIGHT, ph * 0.35f));
        g2.fill(Theme.roundRect(pr.x, pr.y, pr.width, pr.height, 14));
        g2.setColor(Theme.mix(Theme.BORDER, Theme.PRIMARY_SOFT, ph * 0.6f));
        g2.draw(Theme.roundRect(pr.x + 0.5f, pr.y + 0.5f, pr.width - 1, pr.height - 1, 14));

        int av = 38;
        int ax = pr.x + 12, ay = pr.y + (pr.height - av) / 2;
        Avatar.paint(g2, ax, ay, av, username);
        g2.setFont(Theme.bold(13));
        g2.setColor(Theme.TEXT);
        FontMetrics nfm = g2.getFontMetrics();
        String shown = Theme.ellipsize(nfm, username, pr.width - 90);
        g2.drawString(shown, ax + av + 10, ay + 15);
        g2.setColor(Theme.GREEN);
        g2.fill(new java.awt.geom.Ellipse2D.Float(ax + av + 10, ay + 24, 6, 6));
        g2.setFont(Theme.font(10.5f));
        g2.setColor(Theme.TEXT_MUTED);
        g2.drawString("离线模式 · 本地", ax + av + 20, ay + 31);
        Icons.paint(g2, Icons.SETTINGS, pr.x + pr.width - 27, pr.y + pr.height / 2 - 8, 16,
                Theme.TEXT_MUTED);

        g2.setFont(Theme.font(10f));
        g2.setColor(Theme.TEXT_MUTED);
        String v = (version == null || version.isBlank()) ? BuildInfo.APP_NAME
                : BuildInfo.APP_NAME + "  " + version;
        g2.drawString(v, (w - g2.getFontMetrics().stringWidth(v)) / 2f, h - 14);
        g2.dispose();
    }
}

/** 底部状态栏：状态、当前文件、任务入口与细进度线。 */
class StatusBar extends JPanel {
    private final JLabel statusLabel;
    private final JLabel fileLabel;
    private final AccentButton taskButton;
    private final Dot dot;
    private int progress;
    private boolean active;
    private int runningCount;
    private int totalCount;
    private Runnable onOpenTasks;

    public StatusBar() {
        setOpaque(false);
        setLayout(new BorderLayout(12, 0));
        setBorder(new EmptyBorder(4, 18, 4, 14));
        setPreferredSize(new Dimension(0, 40));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        left.setOpaque(false);
        dot = new Dot();
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(Theme.font(12));
        statusLabel.setForeground(Theme.TEXT_SUB);
        left.add(dot);
        left.add(statusLabel);
        add(left, BorderLayout.WEST);

        fileLabel = new JLabel("");
        fileLabel.setFont(Theme.font(11));
        fileLabel.setForeground(Theme.TEXT_MUTED);
        fileLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(fileLabel, BorderLayout.CENTER);

        taskButton = new AccentButton("任务中心", Icons.LOG, AccentButton.Variant.GHOST);
        taskButton.setFontSize(11.5f, true);
        taskButton.setPaddingX(11);
        taskButton.setButtonHeight(26);
        taskButton.setMinWidth(84);
        taskButton.addActionListener(e -> {
            if (onOpenTasks != null) onOpenTasks.run();
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(taskButton);
        add(right, BorderLayout.EAST);
    }

    public void setStatusText(String text) {
        statusLabel.setText(text == null || text.isBlank() ? "就绪" : text);
        statusLabel.setToolTipText(statusLabel.getText());
    }

    public String getStatusText() {
        return statusLabel.getText();
    }

    public void setFileText(String text) {
        fileLabel.setText(text == null || text.isBlank() ? "" : text);
        fileLabel.setToolTipText(fileLabel.getText());
    }

    public void setProgress(int percent, boolean active) {
        this.progress = Math.max(0, Math.min(100, percent));
        this.active = active;
        dot.setActive(active);
        repaint();
    }

    public void setTaskInfo(int running, int total, boolean active) {
        this.runningCount = running;
        this.totalCount = total;
        this.active = active;
        dot.setActive(active);
        String text;
        if (total == 0) text = "任务中心";
        else if (running > 0) text = "任务 · " + total;
        else text = "任务 · " + total;
        if (!text.equals(taskButton.getText())) {
            taskButton.setText(text);
            revalidate();
        }
        repaint();
    }

    public void setOnOpenTasks(Runnable r) {
        this.onOpenTasks = r;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        g2.setColor(Theme.BORDER);
        g2.drawLine(0, 0, w, 0);
        if (progress > 0 || active) {
            int pw = Math.round((w - 2) * (progress / 100f));
            if (pw > 0) {
                g2.setPaint(new GradientPaint(0, 0, new Color(0x6E, 0xA6, 0xFF),
                        Math.max(1, pw), 0, Theme.PRIMARY));
                g2.fill(Theme.roundRect(0, h - 3, pw, 3, 1.5f));
            }
            g2.setColor(Theme.alpha(Theme.PRIMARY, 30));
            g2.fillRect(0, h - 3, w, 3);
            if (pw > 0) {
                g2.setPaint(new GradientPaint(0, 0, new Color(0x6E, 0xA6, 0xFF),
                        Math.max(1, pw), 0, Theme.PRIMARY));
                g2.fill(Theme.roundRect(0, h - 3, pw, 3, 1.5f));
            }
        }
        g2.dispose();
    }

    private final class Dot extends JComponent {
        private final Timer pulse;
        private float phase;

        Dot() {
            setPreferredSize(new Dimension(10, 10));
            setOpaque(false);
            pulse = new Timer(40, e -> {
                phase += 0.09f;
                if (phase > (float) (Math.PI * 2)) phase -= (float) (Math.PI * 2);
                repaint();
            });
        }

        void setActive(boolean a) {
            if (a) {
                if (!pulse.isRunning()) pulse.start();
            } else {
                pulse.stop();
                phase = 0;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            Color base = active ? Theme.PRIMARY : Theme.GREEN;
            if (active) {
                float a = 0.5f + 0.5f * (float) Math.sin(phase);
                g2.setColor(Theme.alpha(base, (int) (50 + 60 * a)));
                g2.fill(new Ellipse2D.Float(cx - 5, cy - 5, 10, 10));
            }
            g2.setColor(base);
            g2.fill(new Ellipse2D.Float(cx - 3, cy - 3, 6, 6));
            g2.dispose();
        }
    }
}

/** 页面容器：切换页面时使用旧页面截图做交叉淡出 + 轻微位移。 */
class PageHost extends JLayeredPane {
    private JComponent page;
    private final FadeOverlay overlay;

    public PageHost() {
        setOpaque(false);
        overlay = new FadeOverlay();
    }

    public JComponent getPage() {
        return page;
    }

    public void setPage(JComponent next, boolean animate) {
        if (next == null || next == page) return;
        BufferedImage snapshot = null;
        if (animate && page != null && getWidth() > 2 && getHeight() > 2) {
            try {
                BufferedImage img = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = Theme.quality(img.getGraphics());
                page.printAll(g);
                g.dispose();
                snapshot = img;
            } catch (Throwable ignored) {}
        }
        removeAll();
        page = next;
        next.setOpaque(false);
        add(next, Integer.valueOf(DEFAULT_LAYER));
        if (snapshot != null) {
            overlay.setImage(snapshot);
            add(overlay, Integer.valueOf(PALETTE_LAYER));
            overlay.start(() -> {
                if (overlay.getParent() == this) {
                    remove(overlay);
                    repaint();
                }
            });
        }
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        if (page != null) page.setBounds(0, 0, getWidth(), getHeight());
        overlay.setBounds(0, 0, getWidth(), getHeight());
    }

    private final class FadeOverlay extends JComponent {
        private BufferedImage image;
        private final Animator.AnimatedValue alpha;

        FadeOverlay() {
            setOpaque(false);
            alpha = new Animator.AnimatedValue(1, v -> repaint());
        }

        void setImage(BufferedImage img) {
            this.image = img;
            alpha.snap(1);
        }

        void start(Runnable onDone) {
            alpha.set(0, 260);
            Timer check = new Timer(270, e -> {
                ((Timer) e.getSource()).stop();
                onDone.run();
            });
            check.setRepeats(false);
            check.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (image == null) return;
            Graphics2D g2 = Theme.quality(g);
            float a = alpha.value();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            int dx = Math.round((1 - a) * -10);
            g2.drawImage(image, dx, 0, null);
            g2.dispose();
        }
    }
}

/**
 * 覆盖层：半透明遮罩 + 居中/右侧滑入的圆角面板。
 * 用于模组详情、安装进度、任务中心等。
 */
class OverlayHost extends JPanel {
    public enum Anchor { CENTER, RIGHT }

    private final Animator.AnimatedValue open;
    private Card sheet;
    private Anchor anchor = Anchor.CENTER;
    private int preferredWidth = 600;
    private int preferredHeight = 500;
    private boolean closable = true;
    private boolean closing;
    private Runnable onClosed;
    private Timer closeTimer;

    public OverlayHost() {
        setOpaque(false);
        setVisible(false);
        setLayout(null);
        open = new Animator.AnimatedValue(0, v -> {
            layoutSheet();
            repaint();
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && closable) close();
            }
        });
        addMouseWheelListener(this::ignoreWheel);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (!isVisible() || closing) return false;
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                if (closable) {
                    close();
                    return true;
                }
            }
            return false;
        });
    }

    private void ignoreWheel(MouseWheelEvent e) {
        e.consume();
    }

    public boolean isOpen() {
        return isVisible() && !closing;
    }

    public void show(Component content, Anchor anchor, int width, int height,
                     boolean closable, Runnable onClosed) {
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }
        removeAll();
        this.anchor = anchor;
        this.preferredWidth = width;
        this.preferredHeight = height;
        this.closable = closable;
        this.onClosed = onClosed;
        this.closing = false;
        sheet = new Card(new BorderLayout(), 0, 20, true);
        sheet.withShadow();
        sheet.withRadius(20);
        sheet.add(content, BorderLayout.CENTER);
        add(sheet);
        setVisible(true);
        open.snap(0);
        layoutSheet();
        open.set(1, 300);
        revalidate();
        repaint();
        setFocusable(true);
        requestFocusInWindow();
    }

    public void setClosable(boolean closable) {
        this.closable = closable;
    }

    public Component getSheetContent() {
        return sheet == null ? null : sheet.getComponentCount() > 0 ? sheet.getComponent(0) : null;
    }

    public void close() {
        if (!isVisible() || closing) return;
        closing = true;
        open.set(0, 210);
        closeTimer = new Timer(230, e -> {
            ((Timer) e.getSource()).stop();
            finishClose();
        });
        closeTimer.setRepeats(false);
        closeTimer.start();
    }

    public void closeImmediately() {
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }
        if (!isVisible()) return;
        open.snap(0);
        finishClose();
    }

    private void finishClose() {
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }
        removeAll();
        setVisible(false);
        closing = false;
        Runnable cb = onClosed;
        onClosed = null;
        repaint();
        if (cb != null) SwingUtilities.invokeLater(cb);
    }

    private void layoutSheet() {
        if (sheet == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = open.value();
        int sw, sh, x, y;
        if (anchor == Anchor.RIGHT) {
            sw = preferredWidth > 0 ? preferredWidth : Math.min(440, w - 48);
            sw = Math.min(sw, w - 30);
            sh = preferredHeight > 0 && preferredHeight < h - 24 ? preferredHeight : h - 24;
            x = w - sw - 12 + Math.round((1 - t) * 38);
            y = (h - sh) / 2;
        } else {
            sw = Math.min(preferredWidth, w - 50);
            sh = Math.min(preferredHeight, h - 40);
            x = (w - sw) / 2;
            y = (h - sh) / 2 + Math.round((1 - t) * 24);
        }
        sheet.setBounds(x, y, sw, sh);
    }

    @Override
    public void doLayout() {
        layoutSheet();
    }

    @Override
    protected void paintComponent(Graphics g) {
        float t = open.value();
        if (t <= 0.001f) return;
        Graphics2D g2 = Theme.quality(g);
        g2.setColor(new Color(0x0A, 0x12, 0x20, Math.round(150 * t)));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }
}

/** 顶部居中的轻量提示条，自动消失，带滑入与淡出。 */
class ToastHost extends JPanel {
    public enum Type { INFO, SUCCESS, ERROR }

    private static final int CARD_W = 400;
    private static final int CARD_H = 48;
    private static final int GAP = 8;
    private final List<ToastCard> cards = new ArrayList<>();
    private Runnable onLayoutChanged;

    public ToastHost() {
        setOpaque(false);
        setLayout(null);
    }

    public void setOnLayoutChanged(Runnable r) {
        this.onLayoutChanged = r;
    }

    public void show(String message, Type type) {
        if (message == null || message.isBlank()) return;
        setVisible(true);
        ToastCard card = new ToastCard(message, type);
        cards.add(0, card);
        add(card, 0);
        while (cards.size() > 3) {
            ToastCard last = cards.remove(cards.size() - 1);
            remove(last);
        }
        card.playIn();
        relayout();
        repaint();
        if (onLayoutChanged != null) onLayoutChanged.run();
        Timer timer = new Timer(type == Type.ERROR ? 5200 : 3200, e -> {
            ((Timer) e.getSource()).stop();
            dismiss(card);
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void dismiss(ToastCard card) {
        if (!cards.contains(card)) return;
        card.playOut(() -> {
            cards.remove(card);
            remove(card);
            relayout();
            repaint();
            if (onLayoutChanged != null) onLayoutChanged.run();
            if (cards.isEmpty()) setVisible(false);
        });
    }

    private void relayout() {
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setBounds(0, i * (CARD_H + GAP), getWidth() > 0 ? getWidth() : CARD_W, CARD_H);
        }
    }

    @Override
    public void doLayout() {
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setBounds(0, i * (CARD_H + GAP), getWidth(), CARD_H);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int n = Math.max(1, cards.size());
        return new Dimension(CARD_W, n * CARD_H + (n - 1) * GAP);
    }

    private final class ToastCard extends JComponent {
        private final String message;
        private final Type type;
        private final Animator.AnimatedValue enter;
        private boolean exiting;

        ToastCard(String message, Type type) {
            this.message = message;
            this.type = type;
            setOpaque(false);
            setToolTipText(message);
            enter = new Animator.AnimatedValue(0, v -> repaint());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) {
                    if (!exiting) dismiss(ToastCard.this);
                }
            });
        }

        void playIn() {
            enter.snap(0);
            enter.set(1, 300);
            setVisible(true);
        }

        void playOut(Runnable done) {
            if (exiting) return;
            exiting = true;
            enter.set(0, 220);
            Timer t = new Timer(240, e -> {
                ((Timer) e.getSource()).stop();
                done.run();
            });
            t.setRepeats(false);
            t.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            float p = enter.value();
            int w = getWidth(), h = getHeight();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, p)));
            g2.translate(0, (1 - p) * -10);
            Color base = new Color(0x1C, 0x25, 0x33, 246);
            g2.setColor(new Color(0x08, 0x12, 0x22, 40));
            g2.fill(Theme.roundRect(0, 3, w, h - 2, 13));
            g2.setColor(base);
            g2.fill(Theme.roundRect(0, 0, w, h - 2, 13));
            g2.setColor(new Color(255, 255, 255, 18));
            g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 3, 13));

            Color accent = switch (type) {
                case SUCCESS -> Theme.GREEN;
                case ERROR -> new Color(0xFF, 0x7B, 0x72);
                default -> new Color(0x79, 0xC0, 0xFF);
            };
            g2.setColor(accent);
            g2.fill(Theme.roundRect(0, 10, 3, h - 22, 2));
            Glyph glyph = type == Type.SUCCESS ? Icons.CHECK
                    : type == Type.ERROR ? Icons.WARNING : Icons.INFO;
            Icons.paint(g2, glyph, 16, (h - 18) / 2, 18, accent);
            g2.setFont(Theme.font(12.5f));
            g2.setColor(new Color(0xE9, 0xEF, 0xF7));
            FontMetrics fm = g2.getFontMetrics();
            String shown = Theme.ellipsize(fm, message, w - 54);
            g2.drawString(shown, 44, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}

/** 应用内统一风格的对话框（错误 / 确认 / 提示）。 */
final class Dialogs {
    private Dialogs() {}

    private static final class Shell {
        JDialog dialog;
        JPanel content;
    }

    public static void error(Component owner, String title, Throwable t) {
        if (t == null) t = new Throwable("未知错误");
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();

        Shell shell = shell(owner, "错误 · " + title, 860, 580);
        JLabel desc = new JLabel("操作未能完成。详细信息如下，可复制后反馈给开发者。");
        desc.setFont(Theme.font(12.5f));
        desc.setForeground(Theme.TEXT_SUB);
        shell.content.add(desc, BorderLayout.NORTH);

        JTextArea area = new JTextArea(stack);
        area.setEditable(false);
        area.setFont(Theme.mono(12));
        area.setForeground(Theme.TEXT_SUB);
        area.setBackground(Theme.SURFACE_ALT);
        area.setBorder(new EmptyBorder(12, 12, 12, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.getVerticalScrollBar().setUnitIncrement(18);
        shell.content.add(sp, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        AccentButton copy = new AccentButton("复制详情", Icons.COPY, AccentButton.Variant.OUTLINE);
        copy.setButtonHeight(36);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(stack), null);
            copy.setText("已复制");
        });
        AccentButton ok = new AccentButton("关闭", null, AccentButton.Variant.PRIMARY);
        ok.setButtonHeight(36);
        ok.addActionListener(e -> shell.dialog.dispose());
        buttons.add(copy);
        buttons.add(ok);
        shell.content.add(buttons, BorderLayout.SOUTH);
        show(shell, owner);
    }

    public static void info(Component owner, String title, String message) {
        Shell shell = shell(owner, title, 560, 250);
        JLabel label = new JLabel(html(message, 420));
        label.setFont(Theme.font(13));
        label.setForeground(Theme.TEXT);
        label.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane sp = new JScrollPane(label);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        shell.content.add(sp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        AccentButton ok = new AccentButton("知道了", null, AccentButton.Variant.PRIMARY);
        ok.setButtonHeight(36);
        ok.addActionListener(e -> shell.dialog.dispose());
        buttons.add(ok);
        shell.content.add(buttons, BorderLayout.SOUTH);
        show(shell, owner);
    }

    public static boolean confirm(Component owner, String title, String message,
                                  String okText, String cancelText) {
        final boolean[] result = {false};
        Shell shell = shell(owner, title, 500, 280);
        JLabel label = new JLabel(html(message, 400));
        label.setFont(Theme.font(13));
        label.setForeground(Theme.TEXT_SUB);
        label.setVerticalAlignment(SwingConstants.TOP);
        shell.content.add(label, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        AccentButton cancel = new AccentButton(cancelText == null ? "取消" : cancelText,
                null, AccentButton.Variant.GHOST);
        cancel.setButtonHeight(36);
        cancel.addActionListener(e -> shell.dialog.dispose());
        AccentButton ok = new AccentButton(okText == null ? "确定" : okText,
                null, AccentButton.Variant.PRIMARY);
        ok.setButtonHeight(36);
        ok.addActionListener(e -> {
            result[0] = true;
            shell.dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        shell.content.add(buttons, BorderLayout.SOUTH);
        show(shell, owner);
        return result[0];
    }

    private static Shell shell(Component owner, String title, int width, int height) {
        Window ownerWindow = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        JDialog dlg = new JDialog(ownerWindow, title, Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setUndecorated(true);
        try {
            dlg.setBackground(new Color(0, 0, 0, 0));
        } catch (Throwable ignored) {}

        Card card = new Card(new BorderLayout(0, 14), 24, 20, true);
        card.withShadow();
        card.setOpaque(false);

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setOpaque(false);

        card.add(buildTitleRow(dlg, title), BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);

        JPanel transparent = new JPanel(new BorderLayout());
        transparent.setOpaque(false);
        transparent.add(card, BorderLayout.CENTER);
        dlg.setContentPane(transparent);
        dlg.setSize(width, height);
        dlg.setResizable(false);
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        dlg.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { dlg.dispose(); }
        });

        Shell s = new Shell();
        s.dialog = dlg;
        s.content = content;
        return s;
    }

    private static JPanel buildTitleRow(JDialog dlg, String title) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(Theme.bold(17));
        t.setForeground(Theme.TEXT);
        row.add(t, BorderLayout.WEST);
        IconButton close = new IconButton(Icons.CLOSE, "关闭");
        close.addActionListener(e -> dlg.dispose());
        row.add(close, BorderLayout.EAST);
        return row;
    }

    private static void show(Shell shell, Component owner) {
        Window ownerWindow = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        if (ownerWindow != null && ownerWindow.isShowing()) {
            shell.dialog.setLocationRelativeTo(ownerWindow);
        } else {
            shell.dialog.setLocationRelativeTo(null);
        }
        shell.dialog.setVisible(true);
    }

    private static String html(String message, int width) {
        String safe = message == null ? "" : message
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><body style='width:" + width + "px;line-height:160%'>" + safe + "</body></html>";
    }
}

/**
 * PCL / iOS 风格按钮：渐变主色、悬停过渡、按压缩放、点击涟漪与忙碌状态。
 */
class AccentButton extends JComponent {
    public enum Variant { PRIMARY, SECONDARY, GHOST, OUTLINE, WHITE, DANGER, GLASS }

    private String text;
    private Glyph glyph;
    private Variant variant = Variant.PRIMARY;
    private final List<ActionListener> listeners = new ArrayList<>();
    private final Animator.AnimatedValue hover;
    private final Animator.AnimatedValue press;
    private final Animator.AnimatedValue ripple;
    private float rippleX, rippleY;
    private boolean busy;
    private Timer busyTimer;
    private float busyAngle;
    private int paddingX = 20;
    private int minWidth = 0;
    private int fixedHeight = 40;
    private boolean showGlyphWhenBusy;
    private boolean progressMode;
    private float progress;
    private float progressTarget;
    private Timer progressTimer;
    private Timer progressAnimator;

    public AccentButton(String text) {
        this(text, null, Variant.PRIMARY);
    }

    public AccentButton(String text, Glyph glyph) {
        this(text, glyph, Variant.PRIMARY);
    }

    public AccentButton(String text, Glyph glyph, Variant variant) {
        this.text = text == null ? "" : text;
        this.glyph = glyph;
        this.variant = variant;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.bold(14));
        hover = new Animator.AnimatedValue(0, v -> repaint());
        press = new Animator.AnimatedValue(0, v -> repaint());
        ripple = new Animator.AnimatedValue(1, v -> repaint());
        installMouse();
        installKeys();
    }

    public void setText(String text) {
        String t = text == null ? "" : text;
        if (t.equals(this.text)) return;
        this.text = t;
        revalidate();
        repaint();
    }

    public String getText() {
        return text;
    }

    public void setGlyph(Glyph glyph) {
        this.glyph = glyph;
        revalidate();
        repaint();
    }

    public void setVariant(Variant variant) {
        this.variant = variant;
        repaint();
    }

    public Variant getVariant() {
        return variant;
    }

    public void setPaddingX(int paddingX) {
        this.paddingX = paddingX;
        revalidate();
    }

    public void setMinWidth(int w) {
        this.minWidth = w;
        revalidate();
    }

    public void setFontSize(float size, boolean bold) {
        setFont(bold ? Theme.bold(size) : Theme.font(size));
        revalidate();
    }

    public void setButtonHeight(int h) {
        this.fixedHeight = h;
        revalidate();
    }

    public void addActionListener(ActionListener l) {
        listeners.add(l);
    }

    public void removeActionListener(ActionListener l) {
        listeners.remove(l);
    }

    public boolean isBusy() {
        return busy;
    }

    /** 忙碌状态：显示旋转指示器并禁止点击。 */
    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        if (busy) {
            if (busyTimer == null) {
                busyTimer = new Timer(30, e -> {
                    busyAngle += 12;
                    if (busyAngle >= 360) busyAngle -= 360;
                    repaint();
                });
            }
            busyTimer.start();
        } else if (busyTimer != null) {
            busyTimer.stop();
        }
        setCursor(busy ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repaint();
    }

    /** 忙碌时显示当前 glyph（例如暂停图标），而不是旋转加载圈。 */
    public void setShowGlyphWhenBusy(boolean show) {
        this.showGlyphWhenBusy = show;
        repaint();
    }

    /** 启动按钮的“启动中”背景进度条。 */
    public void startBusyProgress() {
        progressMode = true;
        progress = 0f;
        progressTarget = 0f;
        if (progressAnimator != null) progressAnimator.stop();
        if (progressTimer == null) {
            progressTimer = new Timer(30, e -> {
                if (!progressMode) return;
                progress += 0.024f;
                if (progress > 1.02f) progress = 0f;
                repaint();
            });
        }
        progressTimer.start();
        repaint();
    }

    public void stopBusyProgress() {
        progressMode = false;
        progress = 0f;
        progressTarget = 0f;
        if (progressTimer != null) progressTimer.stop();
        if (progressAnimator != null) progressAnimator.stop();
        repaint();
    }

    /** 真实进度：平滑追赶目标百分比，避免阶段值瞬间跳满。 */
    public void setProgress(float percent) {
        progressMode = true;
        float target = Math.max(0f, Math.min(1f, percent));
        // 进度只前进不后退
        if (target < progress) target = progress;
        progressTarget = target;
        if (progressTimer != null) progressTimer.stop();
        if (progressAnimator == null) {
            progressAnimator = new Timer(30, e -> {
                if (!progressMode) {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                if (progress < progressTarget) {
                    progress = Math.min(progressTarget, progress + 0.014f);
                } else if (progress > progressTarget) {
                    progress = progressTarget;
                }
                if (Math.abs(progressTarget - progress) < 0.0005f) {
                    progress = progressTarget;
                    ((Timer) e.getSource()).stop();
                }
                repaint();
            });
        }
        progressAnimator.start();
        repaint();
    }

    @Override
    public void removeNotify() {
        if (busyTimer != null) busyTimer.stop();
        if (progressTimer != null) progressTimer.stop();
        if (progressAnimator != null) progressAnimator.stop();
        super.removeNotify();
    }

    private void installMouse() {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (isEnabled() && !busy) hover.set(1, 150);
            }

            @Override public void mouseExited(MouseEvent e) {
                hover.set(0, 180);
                press.set(0, 160);
            }

            @Override public void mousePressed(MouseEvent e) {
                if (!isEnabled() || busy || !SwingUtilities.isLeftMouseButton(e)) return;
                requestFocusInWindow();
                press.set(1, 90);
                rippleX = e.getX();
                rippleY = e.getY();
                ripple.snap(0);
                ripple.set(1, 460);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!isEnabled() || busy || !SwingUtilities.isLeftMouseButton(e)) return;
                press.set(0, 220);
                if (contains(e.getPoint())) fire();
            }
        };
        addMouseListener(ma);
    }

    private void installKeys() {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "fire");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "fire");
        getActionMap().put("fire", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (isEnabled() && !busy) fire();
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    protected void fire() {
        ActionEvent evt = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, text);
        for (ActionListener l : new ArrayList<>(listeners)) l.actionPerformed(evt);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        if (!enabled) {
            hover.snap(0);
            press.snap(0);
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int tw = text.isEmpty() ? 0 : fm.stringWidth(text);
        int iconW = (busy || glyph != null) ? 18 : 0;
        int gap = (iconW > 0 && tw > 0) ? 8 : 0;
        int w = Math.max(minWidth, paddingX * 2 + tw + iconW + gap);
        int h = fixedHeight > 0 ? fixedHeight : Math.max(34, fm.getHeight() + 16);
        return new Dimension(w, h);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        boolean en = isEnabled();
        // 进度条状态即使按钮被禁用也要保持清晰可见
        boolean active = en || progressMode;
        float hov = hover.value();
        float prs = press.value();
        int w = getWidth(), h = getHeight();
        float radius = Math.min(14, h / 2f);
        int alpha = active ? 255 : 90;

        if (prs > 0.001f) {
            double scale = 1 - 0.018 * prs;
            g2.translate(w / 2.0, h / 2.0);
            g2.scale(scale, scale);
            g2.translate(-w / 2.0, -h / 2.0);
        }

        Color fillTop, fillBottom, fg, borderCol = null;
        switch (variant) {
            case PRIMARY -> {
                fillTop = Theme.mix(new Color(0x5B, 0x9B, 0xFF), Color.WHITE, hov * 0.10f);
                fillBottom = Theme.mix(Theme.PRIMARY_DARK, new Color(0x3B, 0x82, 0xF6), hov * 0.22f);
                fg = Color.WHITE;
            }
            case SECONDARY -> {
                fillTop = Theme.mix(Theme.PRIMARY_LIGHT, new Color(0xDD, 0xEA, 0xFF), hov);
                fillBottom = fillTop;
                fg = Theme.PRIMARY_DARK;
            }
            case GHOST -> {
                fillTop = Theme.alpha(Theme.PRIMARY, (int) (8 + 8 * hov));
                fillBottom = fillTop;
                fg = Theme.mix(Theme.TEXT_SUB, Theme.TEXT, hov);
            }
            case OUTLINE -> {
                fillTop = Theme.isDark()
                        ? Theme.mix(Theme.SURFACE_ALT, Theme.SURFACE_HOVER, hov)
                        : Theme.mix(Color.WHITE, new Color(0xFA, 0xFC, 0xFF), hov);
                fillBottom = fillTop;
                fg = Theme.mix(Theme.TEXT, Theme.PRIMARY_DARK, hov);
                borderCol = Theme.mix(Theme.BORDER_STRONG, Theme.PRIMARY, hov * 0.8f);
            }
            case WHITE -> {
                // 蓝色启动卡片上的主按钮保持白底，深色模式下也不需要变黑
                fillTop = Theme.mix(Color.WHITE, new Color(0xF4, 0xF8, 0xFF), hov);
                fillBottom = fillTop;
                fg = Theme.PRIMARY_DARK;
            }
            case DANGER -> {
                fillTop = Theme.mix(new Color(0xF8, 0x6A, 0x6A), Color.WHITE, hov * 0.08f);
                fillBottom = Theme.mix(new Color(0xDC, 0x2F, 0x2F), Color.WHITE, hov * 0.10f);
                fg = Color.WHITE;
            }
            case GLASS -> {
                fillTop = new Color(255, 255, 255, (int) (28 + 18 * hov));
                fillBottom = new Color(255, 255, 255, (int) (20 + 14 * hov));
                fg = Color.WHITE;
                borderCol = new Color(255, 255, 255, 55);
            }
            default -> {
                fillTop = Theme.PRIMARY;
                fillBottom = Theme.PRIMARY_DARK;
                fg = Color.WHITE;
            }
        }

        Color fgA = Theme.alpha(fg, alpha);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, active ? 1f : 0.55f));

        Shape buttonShape = Theme.roundRect(0, 0, w, h, radius);
        if (progressMode) {
            // 进度轨道 + 前景进度
            if (Theme.isDark()) {
                g2.setPaint(new GradientPaint(0, 0, new Color(0x1A, 0x26, 0x36),
                        0, Math.max(1, h), new Color(0x22, 0x31, 0x46)));
                g2.fill(buttonShape);
                Shape oldClip = g2.getClip();
                g2.clip(buttonShape);
                int fillW = Math.round(w * Math.max(0f, Math.min(1f, progress)));
                if (fillW > 0) {
                    g2.setColor(Theme.PRIMARY);
                    g2.fillRect(0, 0, fillW, h);
                }
                g2.setClip(oldClip);
                g2.setColor(new Color(0x35, 0x45, 0x5C));
            } else {
                g2.setPaint(new GradientPaint(0, 0, new Color(0xE1, 0xE6, 0xEE),
                        0, Math.max(1, h), new Color(0xEC, 0xF0, 0xF6)));
                g2.fill(buttonShape);
                Shape oldClip = g2.getClip();
                g2.clip(buttonShape);
                int fillW = Math.round(w * Math.max(0f, Math.min(1f, progress)));
                if (fillW > 0) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(0, 0, fillW, h);
                }
                g2.setClip(oldClip);
                g2.setColor(new Color(0xD2, 0xDA, 0xE6));
            }
            g2.setStroke(new BasicStroke(1f));
            g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, radius));
        } else {
            g2.setPaint(new GradientPaint(0, 0, fillTop, 0, Math.max(1, h), fillBottom));
            g2.fill(buttonShape);
            if (variant == Variant.PRIMARY || variant == Variant.DANGER) {
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 45),
                        0, Math.max(1, h * 0.55f), new Color(255, 255, 255, 0)));
                g2.fill(Theme.roundRect(0.8f, 0.8f, w - 1.6f, h - 1.6f, radius));
            }
            if (borderCol != null) {
                g2.setColor(Theme.alpha(borderCol, en ? 255 : 90));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, radius));
            }
        }

        float rp = ripple.value();
        if (!progressMode && rp < 1f && (variant == Variant.PRIMARY || variant == Variant.WHITE
                || variant == Variant.DANGER || variant == Variant.SECONDARY)) {
            float maxR = (float) Math.hypot(w, h) * 0.75f;
            float r = maxR * Animator.easeOutCubic(rp);
            g2.setColor(new Color(255, 255, 255, (int) (55 * (1 - rp))));
            Shape old = g2.getClip();
            g2.clip(Theme.roundRect(0, 0, w, h, radius));
            g2.fill(new Ellipse2D.Float(rippleX - r, rippleY - r, r * 2, r * 2));
            g2.setClip(old);
        }

        FontMetrics fm = g2.getFontMetrics();
        String label = text == null ? "" : text;
        int tw = label.isEmpty() ? 0 : fm.stringWidth(label);
        int iconSize = 18;
        boolean showIcon = busy || glyph != null;
        int iconW = showIcon ? iconSize : 0;
        int gap = (showIcon && tw > 0) ? 8 : 0;
        int contentW = iconW + gap + tw;
        int startX = (w - contentW) / 2;
        int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();

        if (showIcon) {
            int iy = (h - iconSize) / 2;
            if (busy && !showGlyphWhenBusy) {
                Graphics2D sg = (Graphics2D) g2.create();
                sg.translate(startX + iconSize / 2.0, h / 2.0);
                sg.rotate(Math.toRadians(busyAngle));
                sg.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                sg.setColor(Theme.alpha(fg, 70));
                sg.draw(new Ellipse2D.Float(-7, -7, 14, 14));
                sg.setColor(fgA);
                sg.draw(new Arc2D.Float(-7, -7, 14, 14, 90, 110, Arc2D.OPEN));
                sg.dispose();
            } else {
                Icons.paint(g2, glyph, startX, iy, iconSize, fgA);
            }
        }
        if (tw > 0) {
            g2.setColor(fgA);
            g2.drawString(label, startX + iconW + gap, baseline);
        }

        g2.dispose();

        if (hasFocus() && isEnabled()) {
            Graphics2D fg2 = Theme.quality(g);
            fg2.setColor(Theme.alpha(Theme.PRIMARY, 110));
            fg2.setStroke(new BasicStroke(2f));
            fg2.draw(Theme.roundRect(1.5f, 1.5f, getWidth() - 3, getHeight() - 3, radius));
            fg2.dispose();
        }
    }
}

/** 圆形 / 圆角小图标按钮，用于标题栏、列表操作与工具按钮。 */
class IconButton extends JComponent {
    public enum Variant { NORMAL, TITLE, DANGER, DARK, SOLID }

    private Glyph glyph;
    private Variant variant;
    private final List<ActionListener> listeners = new ArrayList<>();
    private final Animator.AnimatedValue hover;
    private final Animator.AnimatedValue press;
    private int glyphSize = 16;
    private int radius = 10;
    private String tooltip;
    private boolean dangerHover;

    public IconButton(Glyph glyph, String tooltip) {
        this(glyph, tooltip, Variant.NORMAL);
    }

    public IconButton(Glyph glyph, String tooltip, Variant variant) {
        this.glyph = glyph;
        this.variant = variant;
        this.tooltip = tooltip;
        setOpaque(false);
        setFocusable(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (variant == Variant.TITLE) {
            glyphSize = 13;
            radius = 8;
        }
        hover = new Animator.AnimatedValue(0, v -> repaint());
        press = new Animator.AnimatedValue(0, v -> repaint());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover.set(1, 140); }
            @Override public void mouseExited(MouseEvent e) { hover.set(0, 180); press.set(0, 150); }
            @Override public void mousePressed(MouseEvent e) {
                if (!isEnabled()) return;
                press.set(1, 90);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!isEnabled()) return;
                press.set(0, 200);
                if (contains(e.getPoint())) fire();
            }
        });
        if (tooltip != null && !tooltip.isBlank()) setToolTipText(tooltip);
    }

    public void setGlyph(Glyph glyph) {
        this.glyph = glyph;
        repaint();
    }

    public void setGlyphSize(int size) {
        this.glyphSize = size;
        repaint();
    }

    public void setTooltip(String tooltip) {
        this.tooltip = tooltip;
        setToolTipText(tooltip);
    }

    public void setRadius(int radius) {
        this.radius = radius;
        repaint();
    }

    public void setVariant(Variant v) {
        this.variant = v;
        repaint();
    }

    /** 标题栏关闭按钮：悬停时变红。 */
    public void setDangerHover(boolean dangerHover) {
        this.dangerHover = dangerHover;
        repaint();
    }

    public void addActionListener(ActionListener l) {
        listeners.add(l);
    }

    private void fire() {
        ActionEvent e = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, tooltip);
        for (ActionListener l : new ArrayList<>(listeners)) l.actionPerformed(e);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(32, 30);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        float hov = hover.value();
        float prs = press.value();
        if (prs > 0.001f) {
            double scale = 1 - 0.06 * prs;
            g2.translate(w / 2.0, h / 2.0);
            g2.scale(scale, scale);
            g2.translate(-w / 2.0, -h / 2.0);
        }
        if (variant == Variant.TITLE) {
            if (hov > 0.001f) {
                g2.setColor(dangerHover
                        ? new Color(0xE8, 0x3B, 0x3B, (int) (255 * hov))
                        : new Color(0x1B, 0x24, 0x30, (int) (16 * hov)));
                g2.fill(Theme.roundRect(0, 0, w, h, radius));
            }
            Icons.paint(g2, glyph, (w - glyphSize) / 2, (h - glyphSize) / 2, glyphSize,
                    dangerHover ? Theme.mix(Theme.TEXT_SUB, Color.WHITE, hov)
                            : Theme.mix(Theme.TEXT_SUB, Theme.TEXT, hov));
        } else if (variant == Variant.DANGER) {
            g2.setColor(new Color(0xEF, 0x44, 0x44, (int) (30 + 20 * hov)));
            g2.fill(Theme.roundRect(0, 0, w, h, radius));
            Icons.paint(g2, glyph, (w - glyphSize) / 2, (h - glyphSize) / 2, glyphSize, Theme.RED);
        } else if (variant == Variant.DARK) {
            g2.setColor(new Color(255, 255, 255, (int) (14 + 26 * hov)));
            g2.fill(Theme.roundRect(0, 0, w, h, radius));
            Icons.paint(g2, glyph, (w - glyphSize) / 2, (h - glyphSize) / 2, glyphSize,
                    Theme.mix(new Color(0xB8, 0xC6, 0xD9), Color.WHITE, hov));
        } else if (variant == Variant.SOLID) {
            g2.setColor(Theme.PRIMARY);
            g2.fill(Theme.roundRect(0, 0, w, h, radius));
            Icons.paint(g2, glyph, (w - glyphSize) / 2, (h - glyphSize) / 2, glyphSize, Color.WHITE);
        } else {
            if (hov > 0.001f) {
                g2.setColor(new Color(0x3B, 0x82, 0xF6, (int) (20 * hov)));
                g2.fill(Theme.roundRect(0, 0, w, h, radius));
            }
            Icons.paint(g2, glyph, (w - glyphSize) / 2, (h - glyphSize) / 2, glyphSize,
                    Theme.mix(Theme.TEXT_SUB, Theme.PRIMARY_DARK, hov));
        }
        g2.dispose();
    }
}

/** 细长的圆角进度条，数值带缓动；支持不确定状态的流光。 */
class ModernProgressBar extends JComponent {
    private final Animator.AnimatedValue anim;
    private boolean indeterminate;
    private Timer shimmer;
    private float phase;
    private int barHeight = 7;
    private Color fillStart = new Color(0x6E, 0xA6, 0xFF);
    private Color fillEnd = Theme.PRIMARY;
    private Color trackColor = Theme.SURFACE_SUNK;

    public ModernProgressBar() {
        setOpaque(false);
        setPreferredSize(new Dimension(200, barHeight));
        anim = new Animator.AnimatedValue(0, v -> repaint());
    }

    public void setProgress(float p) {
        p = Math.max(0, Math.min(1, p));
        anim.set(p, 320);
    }

    public void setProgress(int percent) {
        setProgress(percent / 100f);
    }

    public void setProgressImmediate(int percent) {
        anim.snap(Math.max(0, Math.min(1, percent / 100f)));
    }

    public float getProgress() {
        return anim.value();
    }

    public void setColors(Color start, Color end, Color track) {
        this.fillStart = start;
        this.fillEnd = end;
        if (track != null) this.trackColor = track;
        repaint();
    }

    public void setBarHeight(int h) {
        this.barHeight = h;
        setPreferredSize(new Dimension(getWidth(), h));
        revalidate();
        repaint();
    }

    public void setIndeterminate(boolean value) {
        if (indeterminate == value) return;
        indeterminate = value;
        if (value) {
            if (shimmer == null) {
                shimmer = new Timer(24, e -> {
                    phase += 0.02f;
                    if (phase > 1.6f) phase = -0.6f;
                    repaint();
                });
            }
            shimmer.start();
        } else if (shimmer != null) {
            shimmer.stop();
            phase = 0;
        }
        repaint();
    }

    public boolean isIndeterminate() {
        return indeterminate;
    }

    @Override
    public void removeNotify() {
        if (shimmer != null) shimmer.stop();
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, barHeight);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, barHeight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        float bh = Math.min(barHeight, h);
        float y = (h - bh) / 2f;
        g2.setColor(trackColor);
        g2.fill(Theme.roundRect(0, y, w, bh, bh / 2f));

        if (indeterminate) {
            float segW = Math.max(60, w * 0.32f);
            float x = (w + segW) * phase - segW;
            Shape clip = Theme.roundRect(0, y, w, bh, bh / 2f);
            Shape old = g2.getClip();
            g2.clip(clip);
            g2.setPaint(new GradientPaint(x, 0, Theme.alpha(Theme.PRIMARY, 20),
                    x + segW, 0, Theme.alpha(Theme.PRIMARY, 235)));
            g2.fill(Theme.roundRect(x, y, segW, bh, bh / 2f));
            g2.setClip(old);
        } else {
            float p = anim.value();
            if (p > 0.001f) {
                float fw = Math.max(bh, w * p);
                g2.setPaint(new GradientPaint(0, 0, fillStart, fw, 0, fillEnd));
                g2.fill(Theme.roundRect(0, y, fw, bh, bh / 2f));
                if (p < 0.98f) {
                    g2.setColor(new Color(255, 255, 255, 70));
                    g2.fill(new Ellipse2D.Float(fw - bh * 0.7f, y + 1, bh * 0.4f, bh * 0.4f));
                }
            }
        }
        g2.dispose();
    }
}

/** 日志页：深色终端卡片、按内容着色、自动滚动。 */
class LogPanel extends JPanel {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MainWindow window;
    private final JTextPane textPane;
    private final StyledDocument doc;
    private final SmoothScrollPane scroll;
    private final ToggleSwitch autoScroll;
    private int lines;

    private final AttributeSet stylePlain;
    private final AttributeSet styleInfo;
    private final AttributeSet styleSuccess;
    private final AttributeSet styleError;
    private final AttributeSet styleTime;

    public LogPanel(MainWindow window) {
        this.window = window;
        setOpaque(false);
        setLayout(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(20, 26, 22, 26));

        JPanel headerRight = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        AccentButtonCompat.addHeaderButton(headerRight, "打开目录", Icons.FOLDER,
                () -> window.openFolder(window.getGameDir()));
        add(UiKit.header("日志", "运行输出与任务进度 · 仅保留最近 3000 行", headerRight), BorderLayout.NORTH);

        Card card = new Card(new BorderLayout(), 0, 18, true);
        card.withFill(Theme.LOG_BG);
        card.withBorder(new Color(0x24, 0x30, 0x44));
        card.setBorder(new EmptyBorder(9, 9, 9, 9));

        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setForeground(Theme.LOG_TEXT);
        textPane.setCaretColor(new Color(0x9E, 0xC1, 0xFF));
        textPane.setSelectionColor(new Color(0x2B, 0x4A, 0x7A));
        textPane.setFont(Theme.mono(12));
        textPane.setBorder(new EmptyBorder(4, 10, 10, 10));
        doc = textPane.getStyledDocument();

        stylePlain = style(Theme.LOG_TEXT, false);
        styleInfo = style(new Color(0x79, 0xC0, 0xFF), false);
        styleSuccess = style(new Color(0x7E, 0xE7, 0x87), false);
        styleError = style(new Color(0xFF, 0x7B, 0x72), false);
        styleTime = style(Theme.LOG_COMMENT, false);

        scroll = new SmoothScrollPane(textPane);
        scroll.setWheelStep(58);
        autoScroll = new ToggleSwitch(true);

        JPanel body = UiKit.transparent(new BorderLayout());
        body.add(buildTerminalBar(), BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        for (String s : window.getLogHistory()) append(s);
        scroll.scrollToTop();
        SwingUtilities.invokeLater(scroll::snapToBottom);
    }

    private JPanel buildTerminalBar() {
        JPanel bar = UiKit.transparent(new BorderLayout(12, 0));
        bar.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel left = UiKit.transparent(new FlowLayout(FlowLayout.LEFT, 7, 0));
        left.add(new Dot(new Color(0xFF, 0x5F, 0x57)));
        left.add(new Dot(new Color(0xFE, 0xBC, 0x2E)));
        left.add(new Dot(new Color(0x28, 0xC8, 0x40)));
        JLabel title = new JLabel("  运行日志");
        title.setFont(Theme.bold(11.5f));
        title.setForeground(Theme.LOG_MUTED);
        left.add(title);
        bar.add(left, BorderLayout.WEST);

        JPanel right = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JLabel auto = new JLabel("自动滚动");
        auto.setFont(Theme.font(11));
        auto.setForeground(Theme.LOG_MUTED);
        right.add(auto);
        right.add(autoScroll);

        IconButton copy = new IconButton(Icons.COPY, "复制全部日志", IconButton.Variant.DARK);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(textPane.getText()), null);
            window.toastSuccess("日志已复制到剪贴板");
        });
        IconButton clear = new IconButton(Icons.TRASH, "清空日志", IconButton.Variant.DARK);
        clear.addActionListener(e -> {
            textPane.setText("");
            lines = 0;
            window.toast("日志已清空");
        });
        right.add(copy);
        right.add(clear);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private AttributeSet style(Color color, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, color);
        StyleConstants.setFontFamily(a, Theme.MONO_FAMILY);
        StyleConstants.setFontSize(a, 12);
        StyleConstants.setBold(a, bold);
        return a;
    }

    public void append(String s) {
        if (s == null) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(s));
            return;
        }
        boolean atBottom = scroll.isNearBottom();
        String[] parts = s.split("\\R", -1);
        for (String line : parts) {
            if (line.isEmpty() && parts.length > 1 && line.equals(parts[parts.length - 1])) continue;
            try {
                doc.insertString(doc.getLength(), "[" + LocalTime.now().format(TS) + "] ",
                        styleTime);
                doc.insertString(doc.getLength(), line + "\n", styleFor(line));
                lines++;
            } catch (BadLocationException ignored) {
                return;
            }
        }
        trim();
        if (atBottom && autoScroll.isSelected()) {
            SwingUtilities.invokeLater(scroll::snapToBottom);
        }
    }

    private AttributeSet styleFor(String line) {
        String l = line.toLowerCase();
        if (line.contains("失败") || line.contains("错误") || l.contains("exception")
                || l.contains("error") || line.contains("✗")) return styleError;
        if (line.contains("完成") || line.contains("成功") || line.contains("已启动")) return styleSuccess;
        if (line.contains("[任务") || line.contains("[下载") || line.contains("[启动")
                || line.contains("[设置") || line.contains("[Java")) return styleInfo;
        return stylePlain;
    }

    private void trim() {
        if (lines <= 3000) return;
        Element root = doc.getDefaultRootElement();
        int removeLines = 500;
        if (root.getElementCount() <= removeLines) return;
        int end = root.getElement(removeLines - 1).getEndOffset();
        try {
            doc.remove(0, end);
            lines -= removeLines;
        } catch (BadLocationException ignored) {}
    }

    public void scrollToBottom() {
        SwingUtilities.invokeLater(scroll::snapToBottom);
    }

    /** 用现有按钮组件快速创建头部操作。 */
    private static final class AccentButtonCompat {
        static void addHeaderButton(JPanel parent, String text,
                                    Glyph icon, Runnable action) {
            AccentButton b = new AccentButton(
                    text, icon, AccentButton.Variant.OUTLINE);
            b.setButtonHeight(36);
            b.setPaddingX(14);
            b.addActionListener(e -> action.run());
            parent.add(b);
        }
    }

    private static final class Dot extends JComponent {
        private final Color color;
        Dot(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(9, 9));
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            g2.setColor(color);
            g2.fill(new java.awt.geom.Ellipse2D.Float(0, 0, 9, 9));
            g2.dispose();
        }
    }
}

/** 右侧任务抽屉：任务进度、状态、剩余文件数与清理操作。 */
class TaskSheet extends JPanel {
    private final MainWindow window;
    private final Runnable close;
    private final JPanel listBox;
    private final JLabel subtitle;
    private final Badge runningBadge;
    private final SmoothScrollPane scroll;
    private final Map<InstallTask, TaskRow> rows = new IdentityHashMap<>();
    private final Timer timer;
    private int lastSignature;

    public TaskSheet(MainWindow window, Runnable close) {
        this.window = window;
        this.close = close;
        setOpaque(false);
        setLayout(new BorderLayout(0, 14));
        setBorder(new EmptyBorder(22, 22, 18, 22));

        JPanel header = UiKit.transparent(new BorderLayout(0, 4));
        JPanel titleRow = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("任务中心");
        title.setFont(Theme.bold(19));
        title.setForeground(Theme.TEXT);
        titleRow.add(title, BorderLayout.WEST);
        JPanel right = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        runningBadge = new Badge("空闲", Theme.GREEN_SOFT, new Color(0x14, 0x8A, 0x48))
                .withDot(Theme.GREEN);
        right.add(runningBadge);
        IconButton closeBtn = new IconButton(Icons.CLOSE, "关闭");
        closeBtn.addActionListener(e -> close.run());
        right.add(closeBtn);
        titleRow.add(right, BorderLayout.EAST);
        header.add(titleRow, BorderLayout.NORTH);

        subtitle = new JLabel("串行执行 · 共 0 个任务");
        subtitle.setFont(Theme.font(11.5f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        header.add(subtitle, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        listBox = UiKit.transparent(null);
        listBox.setLayout(new BoxLayout(listBox, BoxLayout.Y_AXIS));
        listBox.setBorder(new EmptyBorder(2, 2, 10, 2));
        scroll = new SmoothScrollPane(listBox);
        scroll.setWheelStep(54);
        add(scroll, BorderLayout.CENTER);

        JPanel footer = UiKit.transparent(new BorderLayout(8, 0));
        AccentButton clear = new AccentButton(
                "清除已完成", Icons.TRASH, AccentButton.Variant.GHOST);
        clear.setButtonHeight(34);
        clear.setFontSize(12f, true);
        clear.addActionListener(e -> {
            TaskManager.get().clearFinished();
            refresh(true);
            window.toast("已清除完成/失败的任务");
        });
        footer.add(clear, BorderLayout.WEST);
        JLabel hint = UiKit.caption("任务串行执行");
        hint.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(hint, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        refresh(true);
        timer = new Timer(220, e -> refresh(false));
        timer.start();
    }

    @Override
    public void removeNotify() {
        if (timer != null) timer.stop();
        super.removeNotify();
    }

    private void refresh(boolean force) {
        List<InstallTask> tasks = TaskManager.get().getTasks();
        int signature = tasks.size() * 131 + (tasks.isEmpty() ? 0 : tasks.get(tasks.size() - 1).hashCode());
        boolean rebuild = force || signature != lastSignature;
        lastSignature = signature;

        if (rebuild) {
            listBox.removeAll();
            rows.clear();
            if (tasks.isEmpty()) {
                listBox.add(buildEmpty());
                listBox.add(Box.createVerticalGlue());
            } else {
                for (InstallTask t : tasks) {
                    TaskRow row = new TaskRow(t);
                    rows.put(t, row);
                    row.setAlignmentX(LEFT_ALIGNMENT);
                    listBox.add(row);
                    listBox.add(Box.createVerticalStrut(9));
                }
            }
            listBox.revalidate();
            listBox.repaint();
        } else {
            List<InstallTask> gone = new ArrayList<>();
            for (InstallTask t : rows.keySet()) {
                if (!tasks.contains(t)) gone.add(t);
            }
            for (InstallTask t : gone) rows.remove(t);
            for (InstallTask t : tasks) {
                TaskRow r = rows.get(t);
                if (r == null) {
                    rebuild = true;
                    break;
                }
            }
            if (rebuild) {
                refresh(true);
                return;
            }
            for (TaskRow r : rows.values()) r.update();
        }

        int running = 0;
        int done = 0;
        for (InstallTask t : tasks) {
            if (t.state == InstallTask.State.RUNNING || t.state == InstallTask.State.QUEUED) running++;
            if (t.state == InstallTask.State.DONE) done++;
        }
        subtitle.setText("串行执行 · 共 " + tasks.size() + " 个任务"
                + (tasks.isEmpty() ? "" : " · 已完成 " + done));
        if (running > 0) {
            runningBadge.setText("进行中 " + running);
            runningBadge.setColors(Theme.PRIMARY_LIGHT, Theme.PRIMARY_DARK);
        } else {
            runningBadge.setText(tasks.isEmpty() ? "空闲" : "已完成");
            runningBadge.setColors(Theme.GREEN_SOFT, new Color(0x14, 0x8A, 0x48));
        }
    }

    private JComponent buildEmpty() {
        JPanel empty = UiKit.transparent(null);
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        empty.setBorder(new EmptyBorder(60, 10, 0, 10));
        empty.add(new EmptyIcon(52, Theme.TEXT_MUTED));
        empty.add(Box.createVerticalStrut(14));
        JLabel t = new JLabel("暂时没有任务");
        t.setFont(Theme.bold(14));
        t.setForeground(Theme.TEXT_SUB);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.add(t);
        empty.add(Box.createVerticalStrut(4));
        JLabel s = new JLabel("下载或安装任务会出现在这里");
        s.setFont(Theme.font(11.5f));
        s.setForeground(Theme.TEXT_MUTED);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.add(s);
        empty.setAlignmentX(Component.CENTER_ALIGNMENT);
        return empty;
    }

    private static final class EmptyIcon extends JComponent {
        private final int size;
        private final Color color;

        EmptyIcon(int size, Color color) {
            this.size = size;
            this.color = color;
            setOpaque(false);
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setAlignmentX(Component.CENTER_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            Icons.paint(g2, Icons.CUBE, 0, 0, size, Theme.alpha(color, 120));
            g2.dispose();
        }
    }

    private final class TaskRow extends JComponent {
        private final InstallTask task;
        private final Animator.AnimatedValue fileAnim;
        private final Animator.AnimatedValue totalAnim;

        TaskRow(InstallTask task) {
            this.task = task;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 142));
            setPreferredSize(new Dimension(320, 142));
            setToolTipText(task.displayName + " · " + task.subtitle());
            fileAnim = new Animator.AnimatedValue(task.filePercent / 100f, v -> repaint());
            totalAnim = new Animator.AnimatedValue(task.totalPercent / 100f, v -> repaint());
            update();
        }

        void update() {
            fileAnim.set(task.filePercent / 100f, 260);
            totalAnim.set(task.totalPercent / 100f, 300);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int w = getWidth(), h = getHeight();
            int pad = 13;

            g2.setColor(Theme.SURFACE_ALT);
            g2.fill(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, 12));
            g2.setColor(Theme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, 12));

            String badge;
            Color badgeBg, badgeFg;
            switch (task.state) {
                case RUNNING -> {
                    badge = "进行中";
                    badgeBg = Theme.PRIMARY_LIGHT;
                    badgeFg = Theme.PRIMARY_DARK;
                }
                case DONE -> {
                    badge = "已完成";
                    badgeBg = Theme.GREEN_SOFT;
                    badgeFg = new Color(0x14, 0x8A, 0x48);
                }
                case FAILED -> {
                    badge = "失败";
                    badgeBg = Theme.RED_SOFT;
                    badgeFg = Theme.RED;
                }
                default -> {
                    badge = "排队中";
                    badgeBg = Theme.SURFACE_SUNK;
                    badgeFg = Theme.TEXT_SUB;
                }
            }

            Font badgeFont = Theme.font(9.5f);
            g2.setFont(badgeFont);
            FontMetrics bfm = g2.getFontMetrics();
            int bw = bfm.stringWidth(badge) + 14;
            int bh = 18;
            int bx = w - pad - bw;
            int by = 11;
            g2.setColor(badgeBg);
            g2.fill(Theme.roundRect(bx, by, bw, bh, bh / 2f));
            g2.setColor(badgeFg);
            g2.drawString(badge, bx + 7,
                    by + (bh - bfm.getHeight()) / 2 + bfm.getAscent());

            Font titleFont = Theme.bold(13.5f);
            g2.setFont(titleFont);
            FontMetrics tfm = g2.getFontMetrics();
            g2.setColor(Theme.TEXT);
            g2.drawString(ellipsize(tfm, task.displayName, Math.max(40, bx - pad - 8)),
                    pad, 27);

            g2.setFont(Theme.font(10.5f));
            FontMetrics sfm = g2.getFontMetrics();
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(ellipsize(sfm, task.subtitle(), w - pad * 2), pad, 45);

            float fp = clamp01(fileAnim.value());
            float tp = clamp01(totalAnim.value());
            String fileName = task.currentFileName == null || task.currentFileName.isEmpty()
                    ? "—" : task.currentFileName;
            String action = switch (task.state) {
                case QUEUED -> "等待开始：";
                case DONE -> "已完成：";
                case FAILED -> "已中断：";
                default -> "正在下载：";
            };

            g2.setFont(Theme.font(10f));
            FontMetrics lfm = g2.getFontMetrics();
            g2.setColor(Theme.TEXT_SUB);
            String pct = Math.round(fp * 100) + "%";
            int pctW = lfm.stringWidth(pct);
            g2.drawString(ellipsize(lfm, action + fileName,
                    w - pad * 2 - pctW - 10), pad, 63);
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(pct, w - pad - pctW, 63);
            drawBar(g2, pad, 68, w - pad * 2, 6, fp);

            g2.setFont(Theme.font(10f));
            FontMetrics lfm2 = g2.getFontMetrics();
            String totalText = "总进度 " + Math.round(tp * 100) + "%";
            String remain = task.remainingFiles >= 0
                    ? "剩余 " + task.remainingFiles + " 个文件" : "";
            int remainW = remain.isEmpty() ? 0 : lfm2.stringWidth(remain);
            g2.setColor(Theme.TEXT_SUB);
            g2.drawString(totalText, pad, 89);
            if (!remain.isEmpty()) {
                g2.setColor(Theme.TEXT_MUTED);
                g2.drawString(remain, w - pad - remainW, 89);
            }
            drawBar(g2, pad, 94, w - pad * 2, 6, tp);

            Color statusColor = switch (task.state) {
                case DONE -> new Color(0x14, 0x8A, 0x48);
                case FAILED -> Theme.RED;
                case RUNNING -> Theme.PRIMARY;
                default -> Theme.TEXT_MUTED;
            };
            g2.setColor(statusColor);
            g2.fill(new Ellipse2D.Float(pad, 114, 5, 5));
            g2.setFont(Theme.font(10.5f));
            g2.setColor(Theme.TEXT_SUB);
            FontMetrics stfm = g2.getFontMetrics();
            g2.drawString(ellipsize(stfm, task.status, w - pad * 2 - 12),
                    pad + 10, 121);

            g2.dispose();
        }

        private void drawBar(Graphics2D g2, int x, int y, int w, int h, float p) {
            g2.setColor(Theme.SURFACE_SUNK);
            g2.fill(Theme.roundRect(x, y, w, h, h / 2f));
            int fill = Math.round(w * p);
            if (fill > 0) {
                g2.setPaint(new GradientPaint(x, 0, new Color(0x6E, 0xA6, 0xFF),
                        x + Math.max(1, fill), 0, Theme.PRIMARY));
                g2.fill(Theme.roundRect(x, y, Math.max(h, fill), h, h / 2f));
            }
        }

        private float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        private String ellipsize(FontMetrics fm, String text, int maxWidth) {
            if (text == null) return "";
            if (maxWidth <= 0) return "";
            if (fm.stringWidth(text) <= maxWidth) return text;
            String ell = "…";
            int lo = 0, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (fm.stringWidth(text.substring(0, mid) + ell) <= maxWidth) lo = mid;
                else hi = mid - 1;
            }
            return text.substring(0, lo) + ell;
        }
    }
}
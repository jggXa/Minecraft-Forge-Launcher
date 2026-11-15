package com.mclauncher;


import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.function.Consumer;

/** 设置页：内存、下载、Java 环境、实例隔离与关于。 */
public class SettingsPanel extends JPanel {
    private static final int MEMORY_MIN_MB = 1024;
    private static final int MEMORY_MAX_MB = 32768;

    private final MainWindow window;
    private final Settings settings;
    private boolean updating = false;

    private SegmentedControl memoryMode;
    private ModernSlider memorySlider;
    private JLabel memoryValue;
    private JLabel memoryHint;
    private JPanel memoryScalePanel;
    private JLabel scaleRightLabel;
    private Timer memoryMonitor;
    private ToggleSwitch optimizeToggle;
    private ToggleSwitch isolationToggle;
    private ToggleSwitch darkModeToggle;

    private SelectBox<String> mirrorCombo;
    private SelectBox<String> loaderMirrorCombo;
    private TextField bufferField;
    private ModernSlider threadsSlider;
    private JLabel threadsValueLabel;

    private TextField java25Field, java21Field, java17Field, java8Field, javaGlobalField;
    private AccentButton javaDetectBtn;
    private JLabel savedLabel;
    private Timer savedTimer;

    public SettingsPanel(MainWindow window) {
        this.window = window;
        this.settings = window.getSettings();
        setOpaque(false);
        setLayout(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(20, 26, 22, 26));

        JPanel right = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 12));
        savedLabel = new JLabel("已自动保存");
        savedLabel.setFont(Theme.font(11));
        savedLabel.setForeground(new Color(0x14, 0x8A, 0x48));
        savedLabel.setVisible(false);
        right.add(savedLabel);
        add(UiKit.header("设置", "所有修改即时生效并自动保存", right), BorderLayout.NORTH);

        add(buildContent(), BorderLayout.CENTER);
    }

    private JComponent buildContent() {
        JPanel left = UiKit.transparent(null);
        left.setLayout(new GridBagLayout());
        JComponent memoryCard = buildMemoryCard();
        JComponent downloadCard = buildDownloadCard();
        JComponent communityCard = buildQqCommunityCard();
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTH;
        gc.insets = new Insets(0, 0, 14, 0);
        left.add(memoryCard, gc);
        left.add(downloadCard, gc);
        left.add(communityCard, gc);
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 0, 0);
        left.add(Box.createGlue(), gc);

        JPanel right = UiKit.transparent(null);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(buildJavaCard());
        right.add(Box.createVerticalStrut(14));
        right.add(buildAppearanceCard());
        right.add(Box.createVerticalStrut(14));
        right.add(buildGameCard());

        SplitPanel columns =
                new SplitPanel(left, right, 0.5, 16);
        columns.setAlignmentX(LEFT_ALIGNMENT);
        JPanel wrap = UiKit.transparent(null);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(columns);
        wrap.add(Box.createVerticalStrut(14));
        wrap.add(buildAboutFooter());
        SmoothScrollPane scroll = new SmoothScrollPane(wrap);
        scroll.setWheelStep(58);
        return scroll;
    }

    // ==================== 内存 ====================

    private JComponent buildMemoryCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("内存分配");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        memoryValue = new JLabel("8 GB");
        memoryValue.setFont(Theme.bold(20));
        memoryValue.setForeground(Theme.PRIMARY);
        head.add(memoryValue, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(null);
        body.setLayout(new StackLayout());
        memoryMode = new SegmentedControl("自动配置", "自定义");
        memoryMode.setAlignmentX(LEFT_ALIGNMENT);
        memoryMode.setPreferredSize(new Dimension(300, 40));
        memoryMode.selectSilently(settings.autoMemory ? 0 : 1);
        memoryMode.addListener(index -> {
            if (updating) return;
            settings.autoMemory = index == 0;
            boolean custom = !settings.autoMemory;
            memorySlider.setEnabled(custom);
            memorySlider.setVisible(custom);
            if (memoryScalePanel != null) memoryScalePanel.setVisible(custom);
            if (custom) {
                refreshMemorySliderBounds();
                memorySlider.setValueSilently(settings.customMemoryMb);
                if (memorySlider.getValue() != settings.customMemoryMb) {
                    settings.customMemoryMb = memorySlider.getValue();
                }
            }
            refreshMemoryLabel();
            autoSave();
            revalidate();
            repaint();
        });
        JPanel modeWrap = UiKit.transparent(new BorderLayout());
        modeWrap.add(memoryMode, BorderLayout.WEST);
        modeWrap.setPreferredSize(new Dimension(0, 40));
        body.add(modeWrap);
        body.add(Box.createVerticalStrut(12));

        int initial = settings.customMemoryMb;
        initial = Math.max(MEMORY_MIN_MB, Math.min(MEMORY_MAX_MB, initial));
        memorySlider = new ModernSlider(MEMORY_MIN_MB, MEMORY_MAX_MB, initial);
        memorySlider.setStep(256);
        memorySlider.setEnabled(!settings.autoMemory);
        memorySlider.setVisible(!settings.autoMemory);
        memorySlider.setAlignmentX(LEFT_ALIGNMENT);
        memorySlider.setOnChange(v -> refreshMemoryLabel());
        memorySlider.setOnRelease(() -> {
            if (!updating && !settings.autoMemory) {
                settings.customMemoryMb = memorySlider.getValue();
                autoSave();
            }
        });
        body.add(memorySlider);

        memoryScalePanel = UiKit.transparent(new BorderLayout());
        memoryScalePanel.add(UiKit.caption("1 GB"), BorderLayout.WEST);
        scaleRightLabel = UiKit.caption("32 GB");
        memoryScalePanel.add(scaleRightLabel, BorderLayout.EAST);
        memoryScalePanel.setVisible(!settings.autoMemory);
        body.add(memoryScalePanel);
        body.add(Box.createVerticalStrut(6));
        memoryHint = UiKit.caption(" ");
        memoryHint.setAlignmentX(LEFT_ALIGNMENT);
        body.add(memoryHint);
        body.add(Box.createVerticalStrut(12));
        body.add(UiKit.hline());
        body.add(Box.createVerticalStrut(10));

        JPanel optRow = UiKit.transparent(new BorderLayout());
        JPanel optText = UiKit.transparent(null);
        optText.setLayout(new BoxLayout(optText, BoxLayout.Y_AXIS));
        JLabel optTitle = UiKit.label("启动游戏前进行内存优化", Theme.bold(12), Theme.TEXT);
        optTitle.setAlignmentX(LEFT_ALIGNMENT);
        JLabel optHint = UiKit.caption("释放不必要的内存占用，适用于内存较小的设备");
        optHint.setAlignmentX(LEFT_ALIGNMENT);
        optText.add(optTitle);
        optText.add(Box.createVerticalStrut(2));
        optText.add(optHint);
        optRow.add(optText, BorderLayout.CENTER);
        optimizeToggle = new ToggleSwitch(settings.optimizeMemoryBeforeLaunch);
        optimizeToggle.addListener(v -> {
            if (!updating) autoSave();
        });
        JPanel optWrap = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        optWrap.add(optimizeToggle);
        optRow.add(optWrap, BorderLayout.EAST);
        body.add(optRow);
        card.add(body, BorderLayout.CENTER);

        if (!settings.autoMemory) {
            refreshMemorySliderBounds();
            memorySlider.setValueSilently(settings.customMemoryMb);
        }
        refreshMemoryLabel();

        memoryMonitor = new Timer(1000, e -> {
            if (settings.autoMemory) return;
            // 只刷新灰色“已占用”提示；绝不修改用户设置，也不自动保存
            refreshMemorySliderBounds();
        });
        memoryMonitor.start();

        return card;
    }

    private void refreshMemoryLabel() {
        if (memoryValue == null) return;
        int mb = currentMemoryMb();
        memoryValue.setText(Theme.formatMemory(mb));
        if (settings.autoMemory) {
            memoryHint.setText("自动配置为系统物理内存的一半，限制在 1–8 GB 之间");
        } else {
            int usable = memorySlider != null
                    ? memorySlider.getOccupiedStart() : Integer.MAX_VALUE;
            if (usable != Integer.MAX_VALUE && mb > usable) {
                memoryHint.setText("系统当前可用内存约 " + Theme.formatMemory(usable)
                        + "，分配值偏高（不会自动修改设置），启动可能失败");
            } else {
                memoryHint.setText("拖动滑块调整分配给 Minecraft 的最大堆内存");
            }
        }
        if (memoryValue.getParent() != null) memoryValue.getParent().revalidate();
    }

    private int currentMemoryMb() {
        if (settings.autoMemory) {
            long total = detectTotalMemoryMb();
            return (int) Math.max(1024, Math.min(total / 2, 8192));
        }
        return memorySlider.getValue();
    }

    private static long detectTotalMemoryMb() {
        try {
            Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
            Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long bytes = (long) clazz.getMethod("getTotalMemorySize").invoke(bean);
            return bytes / (1024 * 1024);
        } catch (Throwable t) {
            return 8192;
        }
    }

    private void refreshMemorySliderBounds() {
        if (memorySlider == null) return;
        long totalMb = detectTotalMemoryMb();
        int maxMb = (int) Math.min(totalMb, MEMORY_MAX_MB);
        maxMb = Math.max(MEMORY_MIN_MB, maxMb);
        maxMb = (maxMb / 256) * 256;
        if (maxMb < MEMORY_MIN_MB) maxMb = MEMORY_MIN_MB;

        long freeMb = detectFreeMemoryMb();
        int usable = (int) Math.min(freeMb, maxMb);
        usable = Math.max(MEMORY_MIN_MB, usable);

        memorySlider.setRange(MEMORY_MIN_MB, maxMb);
        memorySlider.setOccupiedStart(usable);
        if (scaleRightLabel != null) {
            scaleRightLabel.setText(Theme.formatMemory(maxMb));
        }
    }

    private static long detectFreeMemoryMb() {
        try {
            Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
            Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long bytes = (long) clazz.getMethod("getFreeMemorySize").invoke(bean);
            return bytes / (1024 * 1024);
        } catch (Throwable t) {
            return detectTotalMemoryMb() / 2;
        }
    }

    // ==================== 下载 ====================

    private JComponent buildDownloadCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("下载设置");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("镜像加速", Theme.CYAN_SOFT, Theme.CYAN).fontSize(9.5f), BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(null);
        body.setLayout(new StackLayout());

        body.add(fieldLabel("原版镜像"));
        mirrorCombo = new SelectBox<>(String::valueOf);
        for (Mirror.Type t : Mirror.Type.values()) mirrorCombo.addItem(t.label);
        Mirror.Type cur = Mirror.Type.fromId(settings.mirror);
        for (int i = 0; i < Mirror.Type.values().length; i++) {
            if (Mirror.Type.values()[i] == cur) mirrorCombo.setSelectedIndex(i);
        }
        mirrorCombo.setAlignmentX(LEFT_ALIGNMENT);
        mirrorCombo.addActionListener(e -> { if (!updating) autoSave(); });
        body.add(mirrorCombo);
        body.add(Box.createVerticalStrut(9));

        body.add(fieldLabel("加载器镜像"));
        loaderMirrorCombo = new SelectBox<>(String::valueOf);
        for (Mirror.LoaderType t : Mirror.LoaderType.values()) loaderMirrorCombo.addItem(t.label);
        Mirror.LoaderType curL = Mirror.LoaderType.fromId(settings.loaderMirror);
        for (int i = 0; i < Mirror.LoaderType.values().length; i++) {
            if (Mirror.LoaderType.values()[i] == curL) loaderMirrorCombo.setSelectedIndex(i);
        }
        loaderMirrorCombo.setAlignmentX(LEFT_ALIGNMENT);
        loaderMirrorCombo.addActionListener(e -> { if (!updating) autoSave(); });
        body.add(loaderMirrorCombo);
        body.add(Box.createVerticalStrut(9));

        JPanel numeric = UiKit.transparent(new GridLayout(1, 2, 10, 0));
        JPanel bufferBox = UiKit.transparent(null);
        bufferBox.setLayout(new BoxLayout(bufferBox, BoxLayout.Y_AXIS));
        bufferBox.add(fieldLabel("缓冲区 (KB, 8-1024)"));
        bufferField = new TextField(String.valueOf(settings.bufferSizeKb));
        bufferField.setFieldHeight(38);
        bufferField.setAlignmentX(LEFT_ALIGNMENT);
        bufferField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { if (!updating) autoSave(); }
        });
        bufferBox.add(bufferField);
        JPanel threadBox = UiKit.transparent(null);
        threadBox.setLayout(new BoxLayout(threadBox, BoxLayout.Y_AXIS));
        threadBox.add(fieldLabel("下载线程 (1-16)"));
        int threads = Math.max(1, Math.min(16, settings.downloadThreads));
        JPanel threadSliderRow = UiKit.transparent(new BorderLayout(8, 0));
        threadsSlider = new ModernSlider(1, 16, threads);
        threadsSlider.setStep(1);
        threadsSlider.setBarHeight(6);
        threadsSlider.setOnChange(v -> {
            if (threadsValueLabel != null) threadsValueLabel.setText(v + " 线程");
        });
        threadsSlider.setOnRelease(() -> { if (!updating) autoSave(); });
        threadsValueLabel = UiKit.caption(threads + " 线程");
        threadsValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        threadsValueLabel.setPreferredSize(new Dimension(54, 18));
        threadSliderRow.add(threadsSlider, BorderLayout.CENTER);
        threadSliderRow.add(threadsValueLabel, BorderLayout.EAST);
        threadSliderRow.setAlignmentX(LEFT_ALIGNMENT);
        threadSliderRow.setPreferredSize(new Dimension(0, 38));
        threadSliderRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        threadBox.add(threadSliderRow);
        numeric.add(bufferBox);
        numeric.add(threadBox);
        body.add(numeric);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ==================== QQ 社区 ====================

    private static final String QQ_GROUP_URL = BuildInfo.FEEDBACK_URL;

    private JComponent buildQqCommunityCard() {
        Card card = new Card(new BorderLayout(0, 10), 14, 18, false);

        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("QQ 玩家社区");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("官方群", Theme.CYAN_SOFT, Theme.CYAN).fontSize(9.5f),
                BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(new BorderLayout(12, 0));
        JPanel text = UiKit.transparent(null);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel hint = UiKit.label("点击链接加入群聊", Theme.bold(12.5f), Theme.TEXT);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        hint.setToolTipText("和其他玩家交流、反馈问题、获取最新版本");
        JLabel url = new JLabel(QQ_GROUP_URL);
        url.setFont(Theme.font(10.5f));
        url.setForeground(Theme.PRIMARY);
        url.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        url.setToolTipText("点击加入 QQ 群");
        url.setAlignmentX(LEFT_ALIGNMENT);
        url.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                openQqGroup();
            }
        });

        text.add(hint);
        text.add(Box.createVerticalStrut(4));
        text.add(url);
        body.add(text, BorderLayout.CENTER);

        AccentButton join = new AccentButton("加入群聊", Icons.USER);
        join.setButtonHeight(34);
        join.setFontSize(12f, true);
        join.addActionListener(e -> openQqGroup());
        JPanel rightWrap = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        rightWrap.add(join);
        body.add(rightWrap, BorderLayout.EAST);

        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private void openQqGroup() {
        openUrl(QQ_GROUP_URL);
    }

    // ==================== Java ====================

    private JComponent buildJavaCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("Java 环境");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("自动匹配版本", Theme.PURPLE_SOFT, Theme.PURPLE).fontSize(9.5f),
                BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        java25Field = new TextField(settings.javaPath25);
        java21Field = new TextField(settings.javaPath21);
        java17Field = new TextField(settings.javaPath17);
        java8Field = new TextField(settings.javaPath8);
        javaGlobalField = new TextField(settings.javaPath);
        addJavaRow(body, "Java 25", java25Field);
        addJavaRow(body, "Java 21", java21Field);
        addJavaRow(body, "Java 17", java17Field);
        addJavaRow(body, "Java 8", java8Field);
        addJavaRow(body, "默认路径", javaGlobalField);
        body.add(Box.createVerticalStrut(4));
        javaDetectBtn = new AccentButton("自动获取计算机中的 Java", Icons.SPARKLE,
                AccentButton.Variant.SECONDARY);
        javaDetectBtn.setButtonHeight(38);
        javaDetectBtn.setAlignmentX(LEFT_ALIGNMENT);
        javaDetectBtn.addActionListener(e -> autoDetectJava());
        body.add(javaDetectBtn);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private void addJavaRow(JPanel parent, String label, TextField field) {
        JPanel row = UiKit.transparent(new BorderLayout(8, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setFont(Theme.font(11));
        l.setForeground(Theme.TEXT_SUB);
        l.setPreferredSize(new Dimension(72, 34));
        row.add(l, BorderLayout.WEST);
        field.setFieldHeight(34);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { if (!updating) autoSave(); }
        });
        row.add(field, BorderLayout.CENTER);
        IconButton browse = new IconButton(Icons.FOLDER, "浏览");
        browse.setPreferredSize(new Dimension(34, 34));
        browse.setMinimumSize(browse.getPreferredSize());
        browse.setMaximumSize(browse.getPreferredSize());
        browse.addActionListener(e -> browseJava(field));
        row.add(browse, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        parent.add(row);
        parent.add(Box.createVerticalStrut(5));
    }

    private void browseJava(TextField field) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setDialogTitle("选择 java 可执行文件");
        String cur = field.getText().trim();
        if (!cur.isEmpty()) {
            File parent = new File(cur).getParentFile();
            if (parent != null && parent.exists()) fc.setCurrentDirectory(parent);
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(fc.getSelectedFile().getAbsolutePath());
            if (!updating) autoSave();
        }
    }

    // ==================== 游戏与关于 ====================

    private JComponent buildAppearanceCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);
        JLabel title = new JLabel("外观");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        card.add(title, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(new BorderLayout(10, 0));
        JPanel text = UiKit.transparent(null);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel modeTitle = UiKit.label("深色模式", Theme.bold(12), Theme.TEXT);
        modeTitle.setAlignmentX(LEFT_ALIGNMENT);
        JLabel modeHint = UiKit.caption("将页面的白色背景切换为深色");
        modeHint.setAlignmentX(LEFT_ALIGNMENT);
        text.add(modeTitle);
        text.add(Box.createVerticalStrut(2));
        text.add(modeHint);
        body.add(text, BorderLayout.CENTER);

        darkModeToggle = new ToggleSwitch(settings.darkMode);
        darkModeToggle.addListener(v -> {
            if (updating) return;
            boolean old = settings.darkMode;
            if (v == old) return;

            boolean restart = Dialogs.confirm(window, "切换外观",
                    "切换外观将重启启动器，是否重启？", "重启", "取消");
            if (!restart) {
                // 选择“否”：还原开关，不写入 launcher-config.json
                darkModeToggle.setSelectedSilently(old);
                return;
            }

            settings.darkMode = v;
            window.applyTheme(v);
            if (!autoSave()) {
                settings.darkMode = old;
                darkModeToggle.setSelectedSilently(old);
                window.applyTheme(old);
                window.toastError("设置保存失败，已取消重启");
                return;
            }
            window.restartApplication();
        });
        JPanel wrap = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        wrap.add(darkModeToggle);
        body.add(wrap, BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildGameCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);
        JLabel title = new JLabel("游戏与实例");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        card.add(title, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JPanel isoRow = UiKit.transparent(new BorderLayout(10, 0));
        JPanel isoText = UiKit.transparent(null);
        isoText.setLayout(new BoxLayout(isoText, BoxLayout.Y_AXIS));
        JLabel isoTitle = UiKit.label("启用版本隔离", Theme.bold(12), Theme.TEXT);
        isoTitle.setAlignmentX(LEFT_ALIGNMENT);
        JLabel isoHint = UiKit.caption("每个版本使用独立的存档与模组目录");
        isoHint.setAlignmentX(LEFT_ALIGNMENT);
        isoText.add(isoTitle);
        isoText.add(Box.createVerticalStrut(2));
        isoText.add(isoHint);
        isoRow.add(isoText, BorderLayout.CENTER);
        isolationToggle = new ToggleSwitch(settings.versionIsolation);
        isolationToggle.addListener(v -> { if (!updating) autoSave(); });
        JPanel isoWrap = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        isoWrap.add(isolationToggle);
        isoRow.add(isoWrap, BorderLayout.EAST);
        body.add(isoRow);
        body.add(Box.createVerticalStrut(10));
        body.add(UiKit.hline());
        body.add(Box.createVerticalStrut(10));
        JPanel dirRow = UiKit.transparent(new BorderLayout(8, 0));
        JLabel dirLabel = UiKit.caption("游戏目录");
        dirRow.add(dirLabel, BorderLayout.WEST);
        JPanel dirButtons = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        AccentButton openGame = new AccentButton("游戏目录", Icons.FOLDER, AccentButton.Variant.GHOST);
        openGame.setButtonHeight(30);
        openGame.setFontSize(11, true);
        openGame.addActionListener(e -> window.openFolder(window.getGameDir()));
        AccentButton openData = new AccentButton("启动器目录", Icons.BOX, AccentButton.Variant.GHOST);
        openData.setButtonHeight(30);
        openData.setFontSize(11, true);
        openData.addActionListener(e -> window.openFolder(window.getAppDir()));
        dirButtons.add(openGame);
        dirButtons.add(openData);
        dirRow.add(dirButtons, BorderLayout.EAST);
        body.add(dirRow);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ==================== 关于 ====================

    /** 设置页最底部的“关于”入口：展示版本摘要，点击打开详情。 */
    private JComponent buildAboutFooter() {
        Card card = new Card(new BorderLayout(16, 0), 16, 18, false);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setToolTipText("查看启动器版本、构建信息与开源协议");

        JPanel left = UiKit.transparent(new BorderLayout(12, 0));
        JComponent logo = brandMark(44);
        left.add(logo, BorderLayout.WEST);

        JPanel text = UiKit.transparent(null);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel name = UiKit.label("关于 " + BuildInfo.APP_NAME, Theme.bold(14), Theme.TEXT);
        name.setAlignmentX(LEFT_ALIGNMENT);
        JLabel meta = UiKit.caption("版本 " + BuildInfo.VERSION + " · "
                + BuildInfo.LICENSE_ID + " · " + BuildInfo.INSTALL_TYPE);
        meta.setAlignmentX(LEFT_ALIGNMENT);
        text.add(name);
        text.add(Box.createVerticalStrut(3));
        text.add(meta);
        left.add(text, BorderLayout.CENTER);
        card.add(left, BorderLayout.WEST);

        JPanel actions = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        AccentButton license = new AccentButton("项目开源协议", Icons.LOG, AccentButton.Variant.GHOST);
        license.setButtonHeight(32);
        license.setFontSize(10.5f, true);
        license.setPaddingX(10);
        license.addActionListener(e -> showLicense());
        AccentButton about = new AccentButton("关于", Icons.INFO, AccentButton.Variant.SECONDARY);
        about.setButtonHeight(32);
        about.setFontSize(11.5f, true);
        about.setPaddingX(14);
        about.addActionListener(e -> showAbout());
        actions.add(license);
        actions.add(about);
        JPanel actionWrap = UiKit.transparent(new BorderLayout());
        actionWrap.add(actions, BorderLayout.CENTER);
        card.add(actionWrap, BorderLayout.EAST);

        // 卡片任意空白处都可以打开，避免只有右侧按钮可点
        installAboutClick(card, left, logo, text, name, meta);
        return card;
    }

    private void installAboutClick(JComponent... targets) {
        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        for (JComponent target : targets) {
            target.setCursor(hand);
            target.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) showAbout();
                }
            });
        }
    }

    /** 启动器图标：蓝色圆角渐变 + 白色 M，尺寸可复用。 */
    private JComponent brandMark(int size) {
        JComponent logo = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.quality(g);
                g2.setPaint(new GradientPaint(0, 0, new Color(0x5B, 0x9B, 0xFF),
                        getWidth(), getHeight(), new Color(0x2C, 0x6B, 0xF0)));
                g2.fill(Theme.roundRect(0, 0, getWidth(), getHeight(),
                        Math.max(8, Math.round(getWidth() * 0.26f))));
                g2.setColor(Color.WHITE);
                g2.setFont(Theme.bold(getWidth() * 0.52f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("M", (getWidth() - fm.stringWidth("M")) / 2f,
                        (getHeight() - fm.getHeight()) / 2f + fm.getAscent());
                g2.dispose();
            }
        };
        logo.setOpaque(false);
        logo.setPreferredSize(new Dimension(size, size));
        logo.setMinimumSize(new Dimension(size, size));
        logo.setMaximumSize(new Dimension(size, size));
        return logo;
    }

    /** “关于”详情面板：版本、发布渠道、构建信息、运行环境与许可证。 */
    private void showAbout() {
        JPanel panel = UiKit.transparent(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(20, 22, 18, 22));

        JPanel head = UiKit.transparent(new BorderLayout());
        JPanel titles = UiKit.transparent(null);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("关于");
        title.setFont(Theme.bold(18));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        JLabel subtitle = UiKit.caption(BuildInfo.APP_NAME + " · " + BuildInfo.LICENSE_ID);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        titles.add(title);
        titles.add(Box.createVerticalStrut(3));
        titles.add(subtitle);
        head.add(titles, BorderLayout.WEST);
        IconButton close = new IconButton(Icons.CLOSE, "关闭");
        close.addActionListener(e -> window.closeSheet());
        head.add(close, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        JPanel body = UiKit.transparent(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(buildAboutHero());
        body.add(Box.createVerticalStrut(12));
        body.add(buildAboutInfo());
        body.add(Box.createVerticalStrut(10));
        JLabel footnote = UiKit.caption(BuildInfo.APP_NAME + " 是自由软件，按 "
                + BuildInfo.LICENSE_ID + " 分发；使用前请阅读许可证全文。");
        footnote.setAlignmentX(LEFT_ALIGNMENT);
        body.add(footnote);
        body.add(Box.createVerticalGlue());
        panel.add(body, BorderLayout.CENTER);

        window.showSheet(panel, OverlayHost.Anchor.CENTER, 680, 540, true, null);
    }

    private JComponent buildAboutHero() {
        JPanel hero = UiKit.transparent(new BorderLayout(14, 0));
        hero.setAlignmentX(LEFT_ALIGNMENT);
        hero.add(brandMark(56), BorderLayout.WEST);

        JPanel text = UiKit.transparent(null);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel name = UiKit.label(BuildInfo.APP_NAME, Theme.bold(17), Theme.TEXT);
        name.setAlignmentX(LEFT_ALIGNMENT);
        JLabel desc = UiKit.label("Minecraft 启动器 · 开源、轻量、可扩展",
                Theme.font(11.5f), Theme.TEXT_MUTED);
        desc.setAlignmentX(LEFT_ALIGNMENT);
        JPanel badges = UiKit.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        badges.setAlignmentX(LEFT_ALIGNMENT);
        badges.add(new Badge("v" + BuildInfo.VERSION, Theme.PRIMARY_LIGHT, Theme.PRIMARY).fontSize(10f));
        badges.add(new Badge(BuildInfo.LICENSE_ID, Theme.GREEN_SOFT, Theme.GREEN).fontSize(10f));
        badges.add(Badge.soft(BuildInfo.INSTALL_TYPE, Theme.TEXT_SUB).fontSize(10f));
        text.add(name);
        text.add(Box.createVerticalStrut(2));
        text.add(desc);
        text.add(Box.createVerticalStrut(6));
        text.add(badges);
        hero.add(text, BorderLayout.CENTER);
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        return hero;
    }

    private JComponent buildAboutInfo() {
        Card card = new Card(new BorderLayout(), 0, 12, false);
        card.withFill(Theme.SURFACE_ALT);
        card.setAlignmentX(LEFT_ALIGNMENT);

        JPanel rows = UiKit.transparent(null);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(new EmptyBorder(5, 14, 5, 14));

        JLabel version = UiKit.label(BuildInfo.VERSION, Theme.bold(13), Theme.TEXT);
        version.setToolTipText("语义化版本号（SemVer 2.0.0）");
        rows.add(aboutRow("启动器版本", valueRow(version, UiKit.caption("SemVer 2.0.0"))));

        rows.add(aboutSeparator());
        rows.add(aboutRow("发布渠道", valueRow(
                linkLabel(BuildInfo.RELEASE_URL, () -> openUrl(BuildInfo.RELEASE_URL)),
                UiKit.caption(BuildInfo.RELEASE_CHANNEL_NOTE))));

        rows.add(aboutSeparator());
        JLabel buildDate = UiKit.label(BuildInfo.BUILD_DATE, Theme.mono(11.5f), Theme.TEXT_SUB);
        if (!BuildInfo.BUILD_DATE_TEXT.isEmpty()) {
            buildDate.setToolTipText("构建于 " + BuildInfo.BUILD_DATE_TEXT);
        }
        rows.add(aboutRow("构建日期", valueRow(buildDate, UiKit.caption(BuildInfo.BUILD_DATE_TEXT))));

        rows.add(aboutSeparator());
        JLabel installType = UiKit.body(BuildInfo.INSTALL_TYPE);
        installType.setToolTipText("jpackage app-image 为便携版，源码/JAR 直接运行为开发运行");
        rows.add(aboutRow("安装类型", valueRow(installType)));

        rows.add(aboutSeparator());
        JLabel platform = UiKit.body(BuildInfo.PLATFORM);
        platform.setToolTipText("当前运行：" + BuildInfo.PLATFORM_CURRENT);
        rows.add(aboutRow("运行平台", valueRow(platform, UiKit.caption("（" + BuildInfo.PLATFORM_NOTE + "）"))));

        rows.add(aboutSeparator());
        JLabel runtime = UiKit.body(BuildInfo.RUNTIME);
        runtime.setToolTipText("当前运行：" + BuildInfo.RUNTIME_CURRENT);
        rows.add(aboutRow("运行环境", valueRow(runtime, UiKit.caption("（当前 " + BuildInfo.RUNTIME_CURRENT + "）"))));

        rows.add(aboutSeparator());
        rows.add(aboutRow("问题反馈", valueRow(
                linkLabel(BuildInfo.FEEDBACK_URL, () -> openUrl(BuildInfo.FEEDBACK_URL)))));

        rows.add(aboutSeparator());
        AccentButton license = new AccentButton("点击查看 " + BuildInfo.LICENSE_NAME,
                Icons.SHIELD, AccentButton.Variant.GHOST);
        license.setButtonHeight(26);
        license.setFontSize(10.5f, true);
        license.setPaddingX(8);
        license.setToolTipText("在启动器内阅读 GNU GPL v3 许可证全文");
        license.addActionListener(e -> showLicense(this::showAbout));
        rows.add(aboutRow("许可证", valueRow(compact(license))));

        card.add(rows, BorderLayout.CENTER);
        Dimension preferred = card.getPreferredSize();
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        return card;
    }

    private JComponent aboutRow(String label, JComponent value) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setPreferredSize(new Dimension(0, 34));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setMinimumSize(new Dimension(0, 34));

        JLabel name = UiKit.caption(label);
        name.setPreferredSize(new Dimension(76, 16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 0, 12);
        row.add(name, gc);

        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 0);
        row.add(value, gc);
        return row;
    }

    private JComponent valueRow(JComponent... parts) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        for (int i = 0; i < parts.length; i++) {
            JComponent part = parts[i];
            Dimension pref = part.getPreferredSize();
            part.setMaximumSize(new Dimension(pref.width, pref.height));
            part.setAlignmentY(Component.CENTER_ALIGNMENT);
            row.add(part);
            if (i < parts.length - 1) row.add(Box.createHorizontalStrut(8));
        }
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** AccentButton 的 getMaximumSize 会返回无限宽，放进 BoxLayout 会横向拉伸；用包装层锁住宽度。 */
    private JComponent compact(JComponent component) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(component, BorderLayout.CENTER);
        Dimension pref = component.getPreferredSize();
        wrap.setPreferredSize(pref);
        wrap.setMinimumSize(pref);
        wrap.setMaximumSize(pref);
        return wrap;
    }

    private JComponent aboutSeparator() {
        JPanel wrap = UiKit.transparent(new BorderLayout());
        wrap.add(UiKit.hline(), BorderLayout.CENTER);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return wrap;
    }

    private JLabel linkLabel(String text, Runnable action) {
        JLabel link = new JLabel(text);
        link.setFont(Theme.font(12));
        link.setForeground(Theme.PRIMARY);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("在浏览器中打开：" + text);
        Color hoverColor = Theme.isDark()
                ? Theme.mix(Theme.PRIMARY, Color.WHITE, 0.4f)
                : Theme.PRIMARY_DARK;
        link.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                action.run();
            }
            @Override public void mouseEntered(MouseEvent e) {
                link.setForeground(hoverColor);
            }
            @Override public void mouseExited(MouseEvent e) {
                link.setForeground(Theme.PRIMARY);
            }
        });
        return link;
    }

    private void openUrl(String url) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                window.toastError("无法打开浏览器，请手动访问: " + url);
                return;
            }
            Desktop.getDesktop().browse(java.net.URI.create(url));
            window.appendLog("[链接] 已打开: " + url);
        } catch (Throwable t) {
            window.toastError("打开链接失败: " + t.getMessage());
        }
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(10.5f));
        l.setForeground(Theme.TEXT_MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(0, 2, 4, 0));
        return l;
    }

    // ==================== 自动保存 ====================

    private boolean autoSave() {
        if (updating) return false;
        try {
            settings.autoMemory = memoryMode.getSelectedIndex() == 0;
            settings.customMemoryMb = memorySlider.getValue();
            settings.optimizeMemoryBeforeLaunch = optimizeToggle.isSelected();
            settings.versionIsolation = isolationToggle.isSelected();
            settings.darkMode = darkModeToggle != null && darkModeToggle.isSelected();
            if (settings.autoMemory) {
                long totalMb = detectTotalMemoryMb();
                int alloc = (int) Math.max(1024, Math.min(totalMb / 2, 8192));
                settings.maxMemory = alloc + "M";
            } else {
                settings.maxMemory = settings.customMemoryMb + "M";
            }

            if (mirrorCombo.getSelectedIndex() >= 0) {
                settings.mirror = Mirror.Type.values()[mirrorCombo.getSelectedIndex()].name();
            }
            if (loaderMirrorCombo.getSelectedIndex() >= 0) {
                settings.loaderMirror = Mirror.LoaderType.values()[loaderMirrorCombo.getSelectedIndex()].name();
            }
            try {
                int kb = Integer.parseInt(bufferField.getText().trim());
                if (kb >= 8 && kb <= 1024) settings.bufferSizeKb = kb;
            } catch (NumberFormatException ignored) {}
            if (threadsSlider != null) {
                settings.downloadThreads = Math.max(1, Math.min(16, threadsSlider.getValue()));
            }

            settings.javaPath25 = java25Field.getText().trim();
            settings.javaPath21 = java21Field.getText().trim();
            settings.javaPath17 = java17Field.getText().trim();
            settings.javaPath8 = java8Field.getText().trim();
            settings.javaPath = javaGlobalField.getText().trim();

            boolean saved = settings.save(window.getConfigFile());
            window.applyTransferSettings();
            if (!saved) {
                String reason = Settings.lastSaveError;
                window.setStatus("设置保存失败");
                window.appendLog("[设置] 保存失败: " + window.getConfigFile()
                        + (reason == null || reason.isEmpty() ? "" : " -> " + reason));
                window.toastError("设置保存失败："
                        + (reason == null || reason.isEmpty() ? "无法写入配置" : reason));
                return false;
            }
            window.setStatus("设置已自动保存");
            showSaved();
            window.appendLog("[设置] 已自动保存（镜像: " + Mirror.get().label
                    + " / 内存: " + settings.maxMemory
                    + " / 版本隔离: " + (settings.versionIsolation ? "开" : "关") + "）");
            return true;
        } catch (Throwable t) {
            MainGui.showError("自动保存失败", t);
            return false;
        }
    }

    private void showSaved() {
        savedLabel.setVisible(true);
        if (savedTimer != null) savedTimer.stop();
        savedTimer = new Timer(1600, e -> {
            savedLabel.setVisible(false);
            ((Timer) e.getSource()).stop();
        });
        savedTimer.setRepeats(false);
        savedTimer.start();
    }

    // ==================== 自动检测 Java ====================

    private void autoDetectJava() {
        javaDetectBtn.setEnabled(false);
        javaDetectBtn.setText("正在扫描…");
        window.setStatus("正在扫描 Java…");
        new Thread(() -> {
            List<String> raw = new ArrayList<>();
            try {
                if (isWindows()) raw.addAll(detectJavaFromRegistry());
                raw.addAll(detectJavaFromPath());
                raw.addAll(detectJavaFromCommonDirs());
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    javaDetectBtn.setEnabled(true);
                    javaDetectBtn.setText("自动获取计算机中的 Java");
                    Dialogs.error(this, "自动获取 Java 失败", t);
                });
                return;
            }
            List<String> candidates = dedup(raw);
            java.util.Map<String, Integer> pathToMajor = new java.util.LinkedHashMap<>();
            for (String exe : candidates) {
                int major = readJavaMajor(exe);
                if (major > 0) pathToMajor.put(exe, major);
            }
            String java8 = null, java17 = null, java21 = null, java25 = null;
            for (var e : pathToMajor.entrySet()) {
                int major = e.getValue();
                String path = e.getKey();
                if (major == 8) { if (java8 == null) java8 = path; }
                else if (major == 17) { if (java17 == null) java17 = path; }
                else if (major == 21) { if (java21 == null) java21 = path; }
                else if (major >= 25) { if (java25 == null) java25 = path; }
            }
            String globalDefault = detectSystemDefaultJava(candidates);
            final String f8 = java8, f17 = java17, f21 = java21, f25 = java25, fGlobal = globalDefault;
            final java.util.Map<String, Integer> fp = pathToMajor;

            SwingUtilities.invokeLater(() -> {
                javaDetectBtn.setEnabled(true);
                javaDetectBtn.setText("自动获取计算机中的 Java");
                if (fp.isEmpty()) {
                    window.toastError("未找到可用的 Java");
                    return;
                }
                updating = true;
                try {
                    if (f25 != null) java25Field.setText(f25);
                    if (f21 != null) java21Field.setText(f21);
                    if (f17 != null) java17Field.setText(f17);
                    if (f8 != null) java8Field.setText(f8);
                    if (fGlobal != null) javaGlobalField.setText(fGlobal);
                } finally {
                    updating = false;
                }
                autoSave();
                StringBuilder sb = new StringBuilder("扫描到 ").append(fp.size()).append(" 个 Java：\n\n");
                for (var e : fp.entrySet()) {
                    sb.append("Java ").append(e.getValue()).append("  →  ")
                            .append(e.getKey()).append("\n");
                }
                sb.append("\n已自动填入并保存。");
                window.toastSuccess("已扫描到 " + fp.size() + " 个 Java");
                Dialogs.info(this, "自动获取 Java", sb.toString());
            });
        }, "Java-Detector").start();
    }

    private static int readJavaMajor(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-version")
                    .redirectErrorStream(true).start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                while (reader.readLine() != null) { /* drain */ }
                if (line != null && line.contains("version")) {
                    String[] q = line.split("\"");
                    if (q.length >= 2) {
                        String[] parts = q[1].split("\\.");
                        int major = Integer.parseInt(parts[0]);
                        if (major == 1 && parts.length > 1) major = Integer.parseInt(parts[1]);
                        return major;
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return -1;
    }

    private String detectSystemDefaultJava(List<String> candidates) {
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            String exe = home + File.separator + "bin" + File.separator
                    + (isWindows() ? "java.exe" : "java");
            if (Files.exists(Path.of(exe))) return exe;
        }
        try {
            String cmd = isWindows() ? "where" : "which";
            Process p = new ProcessBuilder(cmd, "java").redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && Files.exists(Path.of(line))) return line;
            }
        } catch (Exception ignored) {}
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private List<String> detectJavaFromRegistry() {
        List<String> result = new ArrayList<>();
        String[] bases = {
                "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
                "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit",
                "HKLM\\SOFTWARE\\JavaSoft\\JDK",
                "HKLM\\SOFTWARE\\Wow6432Node\\JavaSoft\\Java Runtime Environment",
                "HKLM\\SOFTWARE\\Wow6432Node\\JavaSoft\\Java Development Kit",
                "HKLM\\SOFTWARE\\Wow6432Node\\JavaSoft\\JDK"
        };
        for (String base : bases) {
            try {
                String current = regQueryValue(base, "CurrentVersion");
                if (current != null) addJavaHome(result, regQueryValue(base + "\\" + current, "JavaHome"));
                for (String sub : regQuerySubKeys(base)) {
                    addJavaHome(result, regQueryValue(base + "\\" + sub, "JavaHome"));
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private String regQueryValue(String keyPath, String valueName) {
        try {
            Process p = new ProcessBuilder("reg", "query", keyPath, "/v", valueName)
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            Pattern pattern = Pattern.compile(Pattern.quote(valueName) + "\\s+REG_\\w+\\s+(.+)");
            Matcher m = pattern.matcher(out);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> regQuerySubKeys(String keyPath) {
        List<String> keys = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("reg", "query", keyPath)
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            String prefix = keyPath + "\\";
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (line.startsWith(prefix)) {
                    String sub = line.substring(prefix.length());
                    if (!sub.isBlank() && !sub.contains("\\")) keys.add(sub);
                }
            }
        } catch (Exception ignored) {}
        return keys;
    }

    private void addJavaHome(List<String> list, String javaHome) {
        if (javaHome == null || javaHome.isBlank()) return;
        String exeName = isWindows() ? "java.exe" : "java";
        String exe = javaHome.trim() + File.separator + "bin" + File.separator + exeName;
        if (Files.exists(Path.of(exe))) list.add(exe);
    }

    private List<String> detectJavaFromPath() {
        List<String> result = new ArrayList<>();
        try {
            String cmd = isWindows() ? "where" : "which";
            Process p = new ProcessBuilder(cmd, "java").redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && Files.exists(Path.of(line))) result.add(line);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private List<String> detectJavaFromCommonDirs() {
        List<String> result = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        if (isWindows()) {
            dirs.add("C:\\Program Files\\Java");
            dirs.add("C:\\Program Files\\Eclipse Adoptium");
            dirs.add("C:\\Program Files\\AdoptOpenJDK");
            dirs.add("C:\\Program Files\\Microsoft");
            dirs.add("C:\\Program Files\\Amazon Corretto");
            dirs.add("C:\\Program Files\\Zulu");
            dirs.add("C:\\Program Files\\BellSoft");
            String home = System.getProperty("user.home", "");
            dirs.add(home + "\\.jdks");
        } else if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            dirs.add("/Library/Java/JavaVirtualMachines");
            dirs.add(System.getProperty("user.home", "") + "/Library/Java/JavaVirtualMachines");
        } else {
            dirs.add("/usr/lib/jvm");
            dirs.add("/usr/java");
            dirs.add("/opt/java");
            dirs.add("/opt/jdk");
        }
        String exeName = isWindows() ? "java.exe" : "java";
        for (String dir : dirs) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] subs = d.listFiles();
            if (subs == null) continue;
            for (File sub : subs) {
                File exe1 = new File(sub, "bin" + File.separator + exeName);
                if (exe1.exists()) result.add(exe1.getAbsolutePath());
                File exe2 = new File(sub, "Contents" + File.separator + "Home" + File.separator
                        + "bin" + File.separator + exeName);
                if (exe2.exists()) result.add(exe2.getAbsolutePath());
            }
        }
        return result;
    }

    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        Charset charset;
        try {
            charset = Charset.forName(System.getProperty("sun.jnu.encoding", ""));
        } catch (Exception e) {
            charset = Charset.defaultCharset();
        }
        try (var reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), charset))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> dedup(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) if (!out.contains(s)) out.add(s);
        return out;
    }

    // ==================== 开源协议 ====================

    private void showLicense() {
        showLicense(null);
    }

    private void showLicense(Runnable onClosed) {
        String gpl = loadGpl3Text();
        StringBuilder sb = new StringBuilder();
        sb.append(BuildInfo.APP_FULL_NAME).append('\n');
        sb.append("Copyright (C) 2026 jggXa, cheng, and PrismGlow Studio group contributors\n\n");
        sb.append("SPDX-License-Identifier: GPL-3.0-only\n\n");
        sb.append("This file is part of ").append(BuildInfo.APP_FULL_NAME).append(".\n\n");
        sb.append("This program is free software: you can redistribute it and/or modify\n");
        sb.append("it under the terms of the GNU General Public License as published by\n");
        sb.append("the Free Software Foundation, version 3 of the License.\n\n");
        sb.append("This program is distributed in the hope that it will be useful,\n");
        sb.append("but WITHOUT ANY WARRANTY; without even the implied warranty of\n");
        sb.append("MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\n");
        sb.append("GNU General Public License for more details.\n\n");
        sb.append("============================================================\n\n");
        if (gpl == null) {
            sb.append("[错误] 找不到 GPL v3 协议文件 gpl-3.0.txt\n")
                    .append("可从 https://www.gnu.org/licenses/gpl-3.0.txt 获取。\n");
        } else {
            sb.append(gpl);
        }

        JPanel panel = UiKit.transparent(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(22, 22, 20, 22));
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("项目开源协议");
        title.setFont(Theme.bold(18));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        IconButton close = new IconButton(Icons.CLOSE, "关闭");
        close.addActionListener(e -> window.closeSheet());
        head.add(close, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(Theme.mono(11.5f));
        area.setForeground(Theme.TEXT_SUB);
        area.setBackground(Theme.SURFACE_ALT);
        area.setBorder(new EmptyBorder(12, 12, 12, 12));
        area.setCaretPosition(0);
        Card card = new Card(new BorderLayout(), 0, 12, false);
        card.withFill(Theme.SURFACE_ALT);
        card.add(new SmoothScrollPane(area), BorderLayout.CENTER);
        panel.add(card, BorderLayout.CENTER);

        window.showSheet(panel, OverlayHost.Anchor.CENTER, 760, 620, true, onClosed);
    }

    private String loadGpl3Text() {
        try {
            Path local = LauncherPaths.root().resolve("gpl-3.0.txt");
            if (Files.exists(local)) return Files.readString(local);
        } catch (Exception ignored) {}
        try {
            Path local = Path.of(System.getProperty("user.dir"), "gpl-3.0.txt");
            if (Files.exists(local)) return Files.readString(local);
        } catch (Exception ignored) {}
        try (var in = SettingsPanel.class.getResourceAsStream("/gpl-3.0.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public void removeNotify() {
        if (memoryMonitor != null) memoryMonitor.stop();
        super.removeNotify();
    }
}

/** iOS 风格开关。 */
class ToggleSwitch extends JComponent {
    private boolean selected;
    private final Animator.AnimatedValue anim;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();
    private int w = 46, h = 26;

    public ToggleSwitch(boolean selected) {
        this.selected = selected;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(w, h));
        setFocusable(true);
        anim = new Animator.AnimatedValue(selected ? 1 : 0, v -> repaint());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (!isEnabled()) return;
                if (contains(e.getPoint())) toggle();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggle(); }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        if (this.selected == value) return;
        this.selected = value;
        anim.set(value ? 1 : 0, 220);
        repaint();
    }

    public void setSelectedSilently(boolean value) {
        this.selected = value;
        anim.snap(value ? 1 : 0);
        repaint();
    }

    public void addListener(Consumer<Boolean> l) {
        listeners.add(l);
    }

    public void toggle() {
        selected = !selected;
        anim.set(selected ? 1 : 0, 230);
        for (Consumer<Boolean> l : new ArrayList<>(listeners)) l.accept(selected);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(w, h);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        float t = anim.value();
        int height = getHeight();
        int width = getWidth();
        int trackH = Math.min(26, height);
        int trackW = Math.min(46, width);
        float y = (height - trackH) / 2f;
        float x = (width - trackW) / 2f;
        Color off = new Color(0xD9, 0xE0, 0xEA);
        Color on = Theme.PRIMARY;
        Color track = Theme.mix(off, on, t);
        if (!isEnabled()) track = Theme.mix(track, new Color(0xF0, 0xF2, 0xF5), 0.5f);
        g2.setColor(track);
        g2.fill(Theme.roundRect(x, y, trackW, trackH, trackH / 2f));
        if (t > 0.05f) {
            g2.setColor(Theme.alpha(Theme.PRIMARY, (int) (40 * t)));
            g2.fill(Theme.roundRect(x, y, trackW, trackH, trackH / 2f));
        }
        int knob = trackH - 6;
        float kx = x + 3 + t * (trackW - knob - 6);
        g2.setColor(new Color(0x10, 0x20, 0x38, 28));
        g2.fill(new Ellipse2D.Float(kx, y + 3.6f, knob, knob));
        g2.setColor(Color.WHITE);
        g2.fill(new Ellipse2D.Float(kx, y + 3, knob, knob));
        if (hasFocus()) {
            g2.setColor(Theme.alpha(Theme.PRIMARY, 90));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(Theme.roundRect(1, 1, width - 2, height - 2, height / 2f));
        }
        g2.dispose();
    }
}

/** 极简滑条，带柔和的填充色、拖动放大反馈与“已占用”灰色区域。 */
class ModernSlider extends JComponent {
    private int min = 0;
    private int max = 100;
    private int step = 1;
    private int value;
    private int occupiedStart = Integer.MAX_VALUE;
    private boolean dragging;
    private final Animator.AnimatedValue hover;
    private final Animator.AnimatedValue grow;
    private Consumer<Integer> onChange;
    private Runnable onRelease;
    private int barHeight = 6;

    public ModernSlider(int min, int max, int value) {
        this.min = min;
        this.max = max;
        this.value = clamp(value);
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hover = new Animator.AnimatedValue(0, v -> repaint());
        grow = new Animator.AnimatedValue(0, v -> repaint());
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!isEnabled()) return;
                dragging = true;
                grow.set(1, 160);
                setFromMouse(e.getX());
                requestFocusInWindow();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging) setFromMouse(e.getX());
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!dragging) return;
                dragging = false;
                grow.set(0, 260);
                if (onRelease != null) onRelease.run();
            }
            @Override public void mouseEntered(MouseEvent e) {
                if (isEnabled()) hover.set(1, 150);
            }
            @Override public void mouseExited(MouseEvent e) { hover.set(0, 180); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        MouseWheelListener wheel = e -> {
            if (!isEnabled()) return;
            setValue(value - e.getWheelRotation() * step, true);
            if (onRelease != null) onRelease.run();
        };
        addMouseWheelListener(wheel);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "left");
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "right");
        getActionMap().put("left", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                setValue(value - step, true);
                if (onRelease != null) onRelease.run();
            }
        });
        getActionMap().put("right", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                setValue(value + step, true);
                if (onRelease != null) onRelease.run();
            }
        });
    }

    public void setRange(int min, int max) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        if (occupiedStart > this.max) occupiedStart = this.max;
        if (occupiedStart < this.min) occupiedStart = this.min;
        value = clamp(value);
        repaint();
    }

    public void setStep(int step) {
        this.step = Math.max(1, step);
    }

    public int getValue() {
        return value;
    }

    public void setValueSilently(int v) {
        this.value = clamp(v);
        repaint();
    }

    public void setValue(int v, boolean notify) {
        int nv = clamp(v);
        if (nv == value) return;
        value = nv;
        repaint();
        if (notify && onChange != null) onChange.accept(value);
    }

    /** 设置灰色“已占用”区域起点（单位与 min/max 一致）；仅用于绘制提示，不限制取值。 */
    public void setOccupiedStart(int mb) {
        this.occupiedStart = Math.max(min, Math.min(max, mb));
        repaint();
    }

    /** 灰色“已占用”区域起点，供界面显示“可用内存不足”提示。 */
    public int getOccupiedStart() {
        return occupiedStart;
    }

    public void setOnChange(Consumer<Integer> c) {
        this.onChange = c;
    }

    public void setOnRelease(Runnable r) {
        this.onRelease = r;
    }

    public void setBarHeight(int h) {
        this.barHeight = h;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, 32);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, 32);
    }

    private int clamp(int v) {
        int c = Math.max(min, Math.min(max, v));
        if (step > 1) {
            c = min + Math.round((c - min) / (float) step) * step;
        }
        return Math.max(min, Math.min(max, c));
    }

    private void setFromMouse(int x) {
        int pad = 10;
        int w = Math.max(1, getWidth() - pad * 2);
        float p = Math.max(0, Math.min(1, (x - pad) / (float) w));
        int v = min + Math.round(p * (max - min));
        setValue(v, true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        int pad = 10;
        int trackW = Math.max(1, w - pad * 2);
        float p = (max == min) ? 0 : (value - min) / (float) (max - min);
        float trackY = (h - barHeight) / 2f;
        boolean en = isEnabled();

        Color track = en ? Theme.SURFACE_SUNK
                : (Theme.isDark() ? Theme.SURFACE_SUNK : new Color(0xF0, 0xF2, 0xF5));
        g2.setColor(track);
        g2.fill(Theme.roundRect(pad, trackY, trackW, barHeight, barHeight / 2f));

        if (occupiedStart < max) {
            float gp = (occupiedStart - min) / (float) (max - min);
            int gx = pad + Math.round(trackW * gp);
            if (gx < pad + trackW) {
                g2.setColor(Theme.mix(Theme.SURFACE_SUNK, Theme.TEXT_MUTED, 0.35f));
                g2.fill(Theme.roundRect(gx, trackY, pad + trackW - gx,
                        barHeight, barHeight / 2f));
            }
        }

        if (p > 0) {
            int fillW = Math.round(trackW * p);
            g2.setPaint(new GradientPaint(pad, 0, new Color(0x6E, 0xA6, 0xFF),
                    pad + Math.max(1, fillW), 0, Theme.PRIMARY));
            g2.fill(Theme.roundRect(pad, trackY,
                    Math.max(barHeight, fillW), barHeight, barHeight / 2f));
        }

        float gv = grow.value();
        float knob = 18 + gv * 3;
        float kx = pad + trackW * p - knob / 2f;
        float ky = (h - knob) / 2f;
        g2.setColor(new Color(0x10, 0x20, 0x38, 34));
        g2.fill(new java.awt.geom.Ellipse2D.Float(kx, ky + 1.5f, knob, knob));
        g2.setColor(Color.WHITE);
        g2.fill(new java.awt.geom.Ellipse2D.Float(kx, ky, knob, knob));
        g2.setColor(en ? Theme.PRIMARY : Theme.TEXT_MUTED);
        g2.setStroke(new BasicStroke(2f));
        g2.draw(new java.awt.geom.Ellipse2D.Float(kx + 1, ky + 1, knob - 2, knob - 2));

        if (hasFocus()) {
            g2.setColor(Theme.alpha(Theme.PRIMARY, 80));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(Theme.roundRect(1, 1, w - 2, h - 2, 10));
        }
        g2.dispose();
    }
}

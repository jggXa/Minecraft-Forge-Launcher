package com.mclauncher;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;
import java.awt.event.*;
import java.util.function.Consumer;

/**
 * 下载页：Minecraft 安装、模组链接下载与 Modrinth 搜索。
 * 业务逻辑复用原有实现，界面全部重绘。
 */
public class DownloadPanel extends JPanel {
    private static final Set<String> APRIL_FOOLS = new HashSet<>(Set.of(
            "2.0", "15w14a", "1.RV-Pre1", "3D Shareware v1.34",
            "20w14∞", "22w13oneBlockAtATime", "23w13a_or_b",
            "24w14potato", "25w14craftmine"
    ));
    private static final Pattern MC_VER_PATTERN = Pattern.compile("(1\\.[0-9]+(?:\\.[0-9]+)?)");
    private static final Map<String, BufferedImage> ICON_CACHE = new HashMap<>();

    private final MainWindow window;
    private final Settings settings;
    private final Path gameDir;

    private SegmentedControl tabs;
    private CardLayout cardLayout;
    private JPanel cards;

    // Minecraft
    private SelectBox<VersionItem> versionCombo;
    private FilterChip releaseChip, snapshotChip, aprilChip, oldChip, serverChip;
    private TextField nameField;
    private SelectBox<String> loaderCombo;
    private SelectBox<String> loaderVersionCombo;
    private JPanel loaderPanel;
    private AccentButton startBtn;
    private JLabel hintLabel;
    private final List<VersionManifest.VersionEntry> allVersions = new ArrayList<>();
    private volatile boolean versionsLoaded = false;
    private EllipsisLabel previewVersion;
    private Badge previewType;
    private JLabel previewDate;
    private JLabel previewLoader;
    private JLabel previewName;
    private volatile int loaderRequestSeq = 0;

    /** 勾选服务端前，其余四个 chip 的状态（取消勾选时恢复）。 */
    private boolean[] otherChipsBeforeServer = null;

    // Mods
    private TextField modUrlField;
    private SelectBox<String> modInstanceCombo;
    private JLabel modHintLabel;
    private TextField browseSearchField;
    private JPanel resultsBox;
    private JLabel browseStatusLabel;
    private AccentButton browseSearchBtn;
    private SmoothScrollPane resultsScroll;

    public DownloadPanel(MainWindow window) {
        this.window = window;
        this.settings = window.getSettings();
        this.gameDir = window.getGameDir();
        setOpaque(false);
        setLayout(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(20, 26, 22, 26));

        tabs = new SegmentedControl("Minecraft", "模组");
        tabs.setPreferredSize(new Dimension(250, 40));
        tabs.selectSilently(0);
        JPanel tabWrap = UiKit.transparent(new BorderLayout());
        tabWrap.setPreferredSize(new Dimension(250, 40));
        tabWrap.add(tabs, BorderLayout.CENTER);
        add(UiKit.header("下载", "安装游戏版本、加载器与社区模组", tabWrap), BorderLayout.NORTH);

        cardLayout = new CardLayout();
        cards = UiKit.transparent(cardLayout);
        cards.add(buildMinecraftPage(), "mc");
        cards.add(buildModPage(), "mod");
        add(cards, BorderLayout.CENTER);

        tabs.addListener(index -> {
            if (index == 0) {
                cardLayout.show(cards, "mc");
            } else {
                refreshModInstances();
                cardLayout.show(cards, "mod");
                if (resultsBox.getComponentCount() == 0) doBrowseSearch(true);
            }
        });

        loadVersionsAsync();
        refreshModInstances();
        doBrowseSearch(true);
    }

    // ==================== Minecraft 页 ====================

    private JComponent buildMinecraftPage() {
        JPanel page = UiKit.transparent(new BorderLayout(0, 14));
        page.add(new SplitPanel(buildInstallForm(), buildPreviewCard(), 0.56, 16),
                BorderLayout.CENTER);
        page.add(new DropHint(), BorderLayout.SOUTH);
        return page;
    }

    private JComponent buildInstallForm() {
        Card card = new Card(new BorderLayout(0, 12), 20, 18, false);

        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("安装 Minecraft");
        title.setFont(Theme.bold(16));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("官方源 · 支持多镜像", Theme.SURFACE_SUNK, Theme.TEXT_SUB)
                .fontSize(9.5f), BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel content = UiKit.transparent(null);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.add(sectionLabel("Minecraft 版本"));
        versionCombo = new SelectBox<>(Object::toString);
        versionCombo.addItem(new VersionItem("", "正在加载版本…", ""));
        versionCombo.setAlignmentX(LEFT_ALIGNMENT);
        content.add(versionCombo);
        content.add(Box.createVerticalStrut(8));

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        chips.setAlignmentX(LEFT_ALIGNMENT);
        releaseChip = new FilterChip("正式版", true);
        snapshotChip = new FilterChip("快照", true);
        aprilChip = new FilterChip("愚人节", true);
        oldChip = new FilterChip("远古版", true);
        serverChip = new FilterChip("服务器核心", false);

        releaseChip.addAction(this::onNormalChipToggled);
        snapshotChip.addAction(this::onNormalChipToggled);
        aprilChip.addAction(this::onNormalChipToggled);
        oldChip.addAction(this::onNormalChipToggled);
        // 服务端 chip 使用专用回调：自动取消其余四个
        serverChip.addAction(this::onServerChipToggled);

        chips.add(releaseChip);
        chips.add(snapshotChip);
        chips.add(aprilChip);
        chips.add(oldChip);
        chips.add(serverChip);

        chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        chips.setPreferredSize(new Dimension(0, 32));
        content.add(chips);
        content.add(Box.createVerticalStrut(14));

        nameField = new TextField("", null);
        nameField.setFloatingLabel("实例名称");
        nameField.setFloatingSuffix("*", "选填，不填则使用版本号作为实例名称");
        nameField.setAlignmentX(LEFT_ALIGNMENT);
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview(); }
        });
        content.add(nameField);
        content.add(Box.createVerticalStrut(14));

        // ================= 模组加载器区域封装（支持隐藏） =================
        loaderPanel = UiKit.transparent(null);
        loaderPanel.setLayout(new BoxLayout(loaderPanel, BoxLayout.Y_AXIS));
        loaderPanel.setAlignmentX(LEFT_ALIGNMENT);

        loaderPanel.add(sectionLabel("模组加载器"));
        JPanel loaderRow = UiKit.transparent(new BorderLayout(8, 0));
        loaderRow.setAlignmentX(LEFT_ALIGNMENT);
        loaderCombo = new SelectBox<>(String::valueOf);
        loaderCombo.addItem("无");
        loaderCombo.addItem("Forge");
        loaderCombo.addItem("NeoForge");
        loaderCombo.addItem("Fabric");
        loaderCombo.addItem("Quilt");
        loaderCombo.setPreferredSize(new Dimension(150, 40));
        loaderVersionCombo = new SelectBox<>(String::valueOf);
        loaderVersionCombo.setEnabled(false);
        loaderRow.add(loaderCombo, BorderLayout.WEST);
        loaderRow.add(loaderVersionCombo, BorderLayout.CENTER);
        loaderRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        loaderRow.setPreferredSize(new Dimension(0, 40));
        loaderPanel.add(loaderRow);
        loaderPanel.add(Box.createVerticalStrut(8));
        content.add(loaderPanel);
        // ==============================================================

        hintLabel = new JLabel("正在加载版本列表…");
        hintLabel.setFont(Theme.font(10.5f));
        hintLabel.setForeground(Theme.GREEN);
        hintLabel.setAlignmentX(LEFT_ALIGNMENT);
        content.add(hintLabel);
        content.add(Box.createVerticalStrut(12));

        Card sourceCard = new Card(new BorderLayout(10, 0), 10, 10, false);
        sourceCard.withFill(Theme.SURFACE_ALT);
        sourceCard.withBorder(Theme.SURFACE_ALT);
        JComponent globe = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.quality(g);
                Icons.paint(g2, Icons.GLOBE, 0, 0, 16, Theme.PRIMARY);
                g2.dispose();
            }
        };
        globe.setPreferredSize(new Dimension(16, 16));
        sourceCard.add(globe, BorderLayout.WEST);
        JLabel sourceText = new JLabel("<html>下载源：<b>"
                + Mirror.Type.fromId(settings.mirror).label + "</b> · "
                + settings.downloadThreads + " 线程<br>"
                + "<font color='#98A3B2'>镜像与线程可在设置中调整</font></html>");
        sourceText.setFont(Theme.font(10.5f));
        sourceText.setForeground(Theme.TEXT_SUB);
        sourceCard.add(sourceText, BorderLayout.CENTER);
        sourceCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        sourceCard.setPreferredSize(new Dimension(0, 50));
        content.add(sourceCard);
        content.add(Box.createVerticalGlue());

        SmoothScrollPane scroll = new SmoothScrollPane(content);
        scroll.setWheelStep(48);
        card.add(scroll, BorderLayout.CENTER);

        startBtn = new AccentButton("提交安装任务", Icons.DOWNLOAD);
        startBtn.setButtonHeight(44);
        startBtn.setEnabled(false);
        startBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        startBtn.addActionListener(e -> startInstall());
        card.add(startBtn, BorderLayout.SOUTH);

        versionCombo.addActionListener(e -> {
            updatePreview();
            onLoaderChanged();
            checkStartBtnState();
        });
        loaderCombo.addActionListener(e -> {
            updatePreview();
            onLoaderChanged();
        });
        loaderVersionCombo.addActionListener(e -> {
            updatePreview();
            checkStartBtnState();
        });
        return card;
    }

    // ==================== 普通 chip 与服务器核心联动 ====================

    /**
     * 勾选任一普通类型 chip 时：若「服务器核心」处于勾选状态，则退出服务器模式。
     *
     * 这里直接 setSelected(false)，不会触发 onServerChipToggled，
     * 因此不会递归，也不会把 otherChipsBeforeServer 恢复出来覆盖用户刚点的选择。
     * otherChipsBeforeServer 置空，保证下次勾选服务器核心时重新记录当前状态。
     */
    private void onNormalChipToggled() {
        if (serverChip.isSelected()) {
            serverChip.setSelected(false);
            otherChipsBeforeServer = null;
        }
        applyFilter();
    }

    // ==================== 服务端 chip 联动 ====================

    /**
     * 勾选服务端：记录并取消其余四个 chip；
     * 取消服务端：恢复之前的 chip 状态（若从未勾选过则全部恢复选中）。
     */
    private void onServerChipToggled() {
        boolean on = serverChip.isSelected();
        if (on) {
            otherChipsBeforeServer = new boolean[] {
                    releaseChip.isSelected(), snapshotChip.isSelected(),
                    aprilChip.isSelected(), oldChip.isSelected()
            };
            releaseChip.setSelected(false);
            snapshotChip.setSelected(false);
            aprilChip.setSelected(false);
            oldChip.setSelected(false);
        } else {
            if (otherChipsBeforeServer != null) {
                releaseChip.setSelected(otherChipsBeforeServer[0]);
                snapshotChip.setSelected(otherChipsBeforeServer[1]);
                aprilChip.setSelected(otherChipsBeforeServer[2]);
                oldChip.setSelected(otherChipsBeforeServer[3]);
                otherChipsBeforeServer = null;
            } else {
                releaseChip.setSelected(true);
                snapshotChip.setSelected(true);
                aprilChip.setSelected(true);
                oldChip.setSelected(true);
            }
        }
        applyFilter();
    }

    private JComponent buildPreviewCard() {
        Card card = new Card(new BorderLayout(0, 14), 20, 18, false);
        // 使用主题默认背景；不要固定浅色渐变，否则深色模式会残留白卡

        JPanel top = UiKit.transparent(null);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel captionRow = UiKit.transparent(new BorderLayout());
        JLabel caption = new JLabel("当前选择");
        caption.setFont(Theme.bold(10.5f));
        caption.setForeground(Theme.TEXT_MUTED);
        captionRow.add(caption, BorderLayout.WEST);
        previewType = new Badge("正式版", Theme.PRIMARY_LIGHT, Theme.PRIMARY_DARK).fontSize(9.5f);
        captionRow.add(previewType, BorderLayout.EAST);
        captionRow.setAlignmentX(LEFT_ALIGNMENT);
        top.add(captionRow);
        top.add(Box.createVerticalStrut(10));
        previewVersion = new EllipsisLabel("—", Theme.bold(30), Theme.TEXT);
        previewVersion.setAlignmentX(LEFT_ALIGNMENT);
        top.add(previewVersion);
        top.add(Box.createVerticalStrut(6));
        previewDate = UiKit.caption("等待选择版本");
        previewDate.setAlignmentX(LEFT_ALIGNMENT);
        top.add(previewDate);
        card.add(top, BorderLayout.NORTH);

        JPanel info = UiKit.transparent(null);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        previewLoader = valueLabel("无");
        previewName = valueLabel("—");
        info.add(infoRow("加载器", previewLoader));
        info.add(Box.createVerticalStrut(8));
        info.add(infoRow("实例名称", previewName));
        info.add(Box.createVerticalStrut(8));
        info.add(infoRow("安装后", valueLabel("自动加入版本列表")));
        JPanel center = UiKit.transparent(new BorderLayout(0, 22));
        center.add(info, BorderLayout.NORTH);
        center.add(buildInstallSteps(), BorderLayout.CENTER);
        card.add(center, BorderLayout.CENTER);

        Card noteCard = new Card(new BorderLayout(10, 0), 12, 12, false);
        noteCard.withFill(Theme.SURFACE_ALT);
        JComponent icon = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.quality(g);
                Icons.paint(g2, Icons.INFO, 0, 0, 18, Theme.PRIMARY);
                g2.dispose();
            }
        };
        icon.setPreferredSize(new Dimension(18, 18));
        noteCard.add(icon, BorderLayout.WEST);
        JLabel noteText = new JLabel("<html><body style='width:230px'>提交后会加入串行任务队列，"
                + "可在右下角「任务中心」查看文件下载进度。</body></html>");
        noteText.setFont(Theme.font(10.5f));
        noteText.setForeground(Theme.TEXT_SUB);
        noteCard.add(noteText, BorderLayout.CENTER);
        card.add(noteCard, BorderLayout.SOUTH);
        return card;
    }

    private JPanel infoRow(String label, JLabel value) {
        JPanel row = UiKit.transparent(new BorderLayout());
        row.add(UiKit.caption(label), BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        row.setPreferredSize(new Dimension(0, 22));
        return row;
    }

    private JLabel valueLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.bold(11.5f));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private JComponent buildInstallSteps() {
        JPanel steps = UiKit.transparent(null);
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));
        steps.setAlignmentX(LEFT_ALIGNMENT);
        JLabel caption = UiKit.label("安装流程", Theme.bold(10.5f), Theme.TEXT_MUTED);
        caption.setAlignmentX(LEFT_ALIGNMENT);
        steps.add(caption);
        steps.add(Box.createVerticalStrut(8));
        String[] labels = {"获取版本清单", "下载并校验依赖文件", "安装模组加载器（可选）"};
        for (int i = 0; i < labels.length; i++) {
            final int number = i + 1;
            JPanel row = UiKit.transparent(new BorderLayout(9, 0));
            JComponent dot = new JComponent() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = Theme.quality(g);
                    g2.setColor(Theme.PRIMARY_LIGHT);
                    g2.fill(new java.awt.geom.Ellipse2D.Float(0, 0, 19, 19));
                    g2.setColor(Theme.PRIMARY_DARK);
                    g2.setFont(Theme.bold(9.5f));
                    String n = String.valueOf(number);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(n, (19 - fm.stringWidth(n)) / 2f,
                            (19 - fm.getHeight()) / 2f + fm.getAscent());
                    g2.dispose();
                }
            };
            dot.setPreferredSize(new Dimension(19, 19));
            dot.setMinimumSize(new Dimension(19, 19));
            dot.setMaximumSize(new Dimension(19, 19));
            row.add(dot, BorderLayout.WEST);
            JLabel text = UiKit.body(labels[i]);
            row.add(text, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            row.setPreferredSize(new Dimension(0, 24));
            steps.add(row);
            if (i < labels.length - 1) steps.add(Box.createVerticalStrut(5));
        }
        return steps;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.bold(11));
        l.setForeground(Theme.TEXT_SUB);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(0, 2, 6, 0));
        return l;
    }

    private static boolean isLoaderVersionReady(String sel) {
        if (sel == null) return false;
        return !sel.startsWith("(")
                && !sel.equals("加载中…")
                && !sel.equals("加载失败，重试中…");
    }

    private void checkStartBtnState() {
        if (startBtn == null) return;
        VersionItem item = (VersionItem) versionCombo.getSelectedItem();
        if (item == null || item.id.isEmpty()) {
            startBtn.setEnabled(false);
            return;
        }
        if ("服务器核心".equals(item.typeLabel)) {
            startBtn.setEnabled(true);
            return;
        }
        String loader = (String) loaderCombo.getSelectedItem();
        if (loader == null || "无".equals(loader)) {
            startBtn.setEnabled(true);
            return;
        }
        String sel = (String) loaderVersionCombo.getSelectedItem();
        startBtn.setEnabled(isLoaderVersionReady(sel));
    }

    private void updatePreview() {
        if (previewVersion == null) return;
        VersionItem item = (VersionItem) versionCombo.getSelectedItem();
        if (item == null || item.id.isEmpty()) {
            previewVersion.setText("—");
            previewType.setText("—");
            previewDate.setText("等待选择版本");
            previewLoader.setText("无");
            previewName.setText("—");
            return;
        }
        previewVersion.setText(item.id);
        previewType.setText(item.typeLabel);
        String date = item.releaseTime == null || item.releaseTime.isBlank()
                ? "发布时间未知"
                : "发布于 " + item.releaseTime.substring(0, Math.min(10, item.releaseTime.length()));
        previewDate.setText(date);

        boolean isServer = "服务器核心".equals(item.typeLabel);
        if (isServer) {
            previewLoader.setText("不适用");
        } else {
            String loader = (String) loaderCombo.getSelectedItem();
            if (loader == null || "无".equals(loader)) {
                previewLoader.setText("原版");
            } else {
                String ver = (String) loaderVersionCombo.getSelectedItem();
                previewLoader.setText(loader + (ver == null || ver.startsWith("(")
                        ? "" : " · " + ver.replace(" (稳定)", "")));
            }
        }
        if (loaderPanel != null) {
            loaderPanel.setVisible(!isServer);
            loaderPanel.revalidate();
        }
        if (isServer) loaderCombo.setSelectedItem("无");
        checkStartBtnState();

        String custom = nameField.getText().trim();
        previewName.setText(custom.isEmpty() ? item.id : custom);
    }

    // ==================== 模组页 ====================

    private JComponent buildModPage() {
        return new SplitPanel(buildLinkCard(), buildBrowseCard(), 0.40, 16);
    }

    private JComponent buildLinkCard() {
        Card card = new Card(new BorderLayout(0, 12), 20, 18, false);
        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("通过链接安装");
        title.setFont(Theme.bold(16));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("JAR", Theme.PURPLE_SOFT, Theme.PURPLE).fontSize(9.5f), BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel content = UiKit.transparent(null);
        content.setLayout(new StackLayout());
        content.add(sectionLabel("模组下载链接"));
        modUrlField = new TextField("", "https://.../example-mod.jar");
        modUrlField.setLeadingIcon(Icons.GLOBE);
        modUrlField.setAlignmentX(LEFT_ALIGNMENT);
        content.add(modUrlField);
        content.add(Box.createVerticalStrut(12));

        content.add(sectionLabel("目标实例"));
        JPanel instanceRow = UiKit.transparent(new BorderLayout(8, 0));
        instanceRow.setAlignmentX(LEFT_ALIGNMENT);
        modInstanceCombo = new SelectBox<>(String::valueOf);
        modInstanceCombo.addActionListener(e -> updateModHint());
        IconButton refresh = new IconButton(Icons.REFRESH, "刷新实例列表");
        refresh.setPreferredSize(new Dimension(40, 40));
        refresh.setMinimumSize(refresh.getPreferredSize());
        refresh.setMaximumSize(refresh.getPreferredSize());
        refresh.addActionListener(e -> {
            refreshModInstances();
            window.toast("实例列表已刷新");
        });
        instanceRow.add(modInstanceCombo, BorderLayout.CENTER);
        instanceRow.add(refresh, BorderLayout.EAST);
        instanceRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        instanceRow.setPreferredSize(new Dimension(0, 40));
        content.add(instanceRow);
        content.add(Box.createVerticalStrut(10));

        modHintLabel = new JLabel(" ");
        modHintLabel.setFont(Theme.font(10.5f));
        modHintLabel.setForeground(Theme.TEXT_MUTED);
        modHintLabel.setAlignmentX(LEFT_ALIGNMENT);
        content.add(modHintLabel);
        content.add(Box.createVerticalStrut(14));

        Card help = new Card(new BorderLayout(), 12, 12, false);
        help.withFill(Theme.SURFACE_ALT);
        help.withBorder(Theme.SURFACE_ALT);
        JLabel helpText = new JLabel("<html><body style='width:280px'>"
                + "· 支持 .jar / .zip 模组文件直链<br>"
                + "· 下载完成后自动放入目标实例的 mods 目录<br>"
                + "· 从右侧 Modrinth 下载会自动匹配版本与加载器</body></html>");
        helpText.setFont(Theme.font(10.5f));
        helpText.setForeground(Theme.TEXT_SUB);
        help.add(helpText, BorderLayout.CENTER);
        help.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));
        help.setPreferredSize(new Dimension(0, 112));
        content.add(help);
        content.add(Box.createVerticalGlue());

        SmoothScrollPane scroll = new SmoothScrollPane(content);
        scroll.setWheelStep(46);
        card.add(scroll, BorderLayout.CENTER);

        AccentButton download = new AccentButton("提交下载任务", Icons.DOWNLOAD);
        download.setButtonHeight(42);
        download.addActionListener(e -> startModDownload());
        card.add(download, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildBrowseCard() {
        Card card = new Card(new BorderLayout(0, 10), 16, 18, false);

        JPanel head = UiKit.transparent(new BorderLayout());
        JPanel titleBox = UiKit.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel title = new JLabel("Modrinth 模组");
        title.setFont(Theme.bold(16));
        title.setForeground(Theme.TEXT);
        titleBox.add(title);
        titleBox.add(new Badge("社区源", Theme.CYAN_SOFT, Theme.CYAN).fontSize(9.5f));
        head.add(titleBox, BorderLayout.WEST);
        browseStatusLabel = UiKit.caption(" ");
        browseStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        head.add(browseStatusLabel, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel searchRow = UiKit.transparent(new BorderLayout(8, 0));
        browseSearchField = new TextField("", "搜索模组，留空浏览热门…");
        browseSearchField.setLeadingIcon(Icons.SEARCH);
        browseSearchField.setFieldHeight(38);
        browseSearchField.addActionListener(e -> doBrowseSearch(false));
        searchRow.add(browseSearchField, BorderLayout.CENTER);
        JPanel buttons = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 1));
        browseSearchBtn = new AccentButton("搜索", Icons.SEARCH);
        browseSearchBtn.setButtonHeight(38);
        browseSearchBtn.setFontSize(12f, true);
        browseSearchBtn.setPaddingX(16);
        browseSearchBtn.addActionListener(e -> doBrowseSearch(false));
        AccentButton popular = new AccentButton("热门", Icons.SPARKLE, AccentButton.Variant.OUTLINE);
        popular.setButtonHeight(38);
        popular.setFontSize(12f, true);
        popular.setPaddingX(14);
        popular.addActionListener(e -> doBrowseSearch(true));
        buttons.add(browseSearchBtn);
        buttons.add(popular);
        searchRow.add(buttons, BorderLayout.EAST);

        JPanel top = UiKit.transparent(new BorderLayout(0, 10));
        top.add(head, BorderLayout.NORTH);
        top.add(searchRow, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        resultsBox = UiKit.transparent(null);
        resultsBox.setLayout(new BoxLayout(resultsBox, BoxLayout.Y_AXIS));
        resultsBox.setBorder(new EmptyBorder(1, 1, 6, 1));
        resultsScroll = new SmoothScrollPane(resultsBox);
        resultsScroll.setWheelStep(58);
        card.add(resultsScroll, BorderLayout.CENTER);
        return card;
    }

    private void doBrowseSearch(boolean popular) {
        if (browseStatusLabel == null) return;
        String query = popular ? "" : browseSearchField.getText().trim();
        browseSearchBtn.setEnabled(false);
        browseSearchBtn.setBusy(true);
        browseStatusLabel.setText(popular ? "正在加载热门模组…" : "正在搜索…");
        resultsBox.removeAll();
        resultsBox.add(loadingRow(popular ? "正在获取热门模组" : "正在搜索「" + query + "」"));
        resultsBox.revalidate();
        resultsBox.repaint();

        new Thread(() -> {
            try {
                List<ModrinthAPI.ModProject> results = ModrinthAPI.search(query, 0, 20);
                SwingUtilities.invokeLater(() -> {
                    browseSearchBtn.setEnabled(true);
                    browseSearchBtn.setBusy(false);
                    resultsBox.removeAll();
                    for (ModrinthAPI.ModProject m : results) {
                        ModRow row = new ModRow(m);
                        row.setAlignmentX(LEFT_ALIGNMENT);
                        resultsBox.add(row);
                        resultsBox.add(Box.createVerticalStrut(7));
                    }
                    if (results.isEmpty()) {
                        resultsBox.add(loadingRow("没有找到相关模组"));
                    }
                    resultsBox.revalidate();
                    resultsBox.repaint();
                    if (resultsScroll != null) {
                        SwingUtilities.invokeLater(resultsScroll::scrollToTop);
                    }
                    browseStatusLabel.setText((popular ? "热门 · " : "搜索 · ")
                            + "共 " + results.size() + " 个结果，点击查看详情");
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    browseSearchBtn.setEnabled(true);
                    browseSearchBtn.setBusy(false);
                    resultsBox.removeAll();
                    resultsBox.add(loadingRow("加载失败，请检查网络"));
                    resultsBox.revalidate();
                    resultsBox.repaint();
                    browseStatusLabel.setText("加载失败");
                    window.toastError("Modrinth 加载失败：" + ex.getMessage());
                });
            }
        }, "Modrinth-Search").start();
    }

    private JComponent loadingRow(String text) {
        JPanel row = UiKit.transparent(new BorderLayout());
        row.setBorder(new EmptyBorder(30, 10, 30, 10));
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(12));
        l.setForeground(Theme.TEXT_MUTED);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        row.add(l, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        return row;
    }

    // ==================== 模组结果行 ====================

    private final class ModRow extends JComponent {
        private final ModrinthAPI.ModProject project;
        private final Animator.AnimatedValue hover;
        private BufferedImage icon;

        ModRow(ModrinthAPI.ModProject project) {
            this.project = project;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(project.title);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));
            setPreferredSize(new Dimension(320, 86));
            hover = new Animator.AnimatedValue(0, v -> repaint());
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover.set(1, 150); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hover.set(0, 180); }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) openProjectDetail(project);
                }
            });
            loadIcon();
        }

        private void loadIcon() {
            String url = project.iconUrl;
            if (url == null || url.isBlank()) return;
            synchronized (ICON_CACHE) {
                if (ICON_CACHE.containsKey(url)) {
                    icon = ICON_CACHE.get(url);
                    return;
                }
            }
            new Thread(() -> {
                try {
                    BufferedImage img = ModrinthAPI.loadImage(url);
                    if (img != null) {
                        synchronized (ICON_CACHE) { ICON_CACHE.put(url, img); }
                        SwingUtilities.invokeLater(() -> {
                            icon = img;
                            repaint();
                        });
                    }
                } catch (Throwable ignored) {}
            }, "Modrinth-Icon").start();
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(320, 86);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 86);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int w = getWidth(), h = getHeight();
            float hv = hover.value();
            if (hv > 0.01f) {
                g2.setColor(Theme.alpha(Theme.PRIMARY, (int) (10 * hv)));
                g2.fill(Theme.roundRect(0, 0, w, h, 13));
            }
            int s = 52;
            int ix = 10, iy = (h - s) / 2;
            Shape clip = Theme.roundRect(ix, iy, s, s, 13);
            Shape old = g2.getClip();
            g2.clip(clip);
            if (icon != null) {
                g2.drawImage(icon, ix, iy, s, s, null);
            } else {
                g2.setColor(Theme.PRIMARY_LIGHT);
                g2.fillRect(ix, iy, s, s);
                g2.setFont(Theme.bold(20));
                g2.setColor(Theme.PRIMARY);
                String initial = project.title == null || project.title.isBlank()
                        ? "?" : project.title.substring(0, 1).toUpperCase(Locale.ROOT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(initial, ix + (s - fm.stringWidth(initial)) / 2f,
                        iy + (s - fm.getHeight()) / 2f + fm.getAscent());
            }
            g2.setClip(old);
            g2.setColor(Theme.BORDER);
            g2.draw(clip);

            int tx = ix + s + 12;
            int right = 26;
            g2.setFont(Theme.bold(13.5f));
            g2.setColor(Theme.TEXT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(Theme.ellipsize(fm, project.title == null ? "未命名" : project.title,
                    w - tx - right), tx, 30);
            g2.setFont(Theme.font(11));
            g2.setColor(Theme.TEXT_SUB);
            FontMetrics dfm = g2.getFontMetrics();
            g2.drawString(Theme.ellipsize(dfm, project.description == null ? "" : project.description,
                    w - tx - right), tx, 48);
            g2.setFont(Theme.font(10f));
            g2.setColor(Theme.TEXT_MUTED);
            String loaders = project.loaders == null || project.loaders.isEmpty()
                    ? "" : " · " + String.join(" / ", project.loaders);
            g2.drawString(Theme.formatCount(project.downloads) + " 次下载" + loaders, tx, 65);
            if (hv > 0.25f) {
                Icons.paint(g2, Icons.CHEVRON_RIGHT, w - 22, h / 2 - 7, 14,
                        Theme.alpha(Theme.TEXT_MUTED, (int) (200 * hv)));
            }
            g2.dispose();
        }
    }

    // ==================== 模组详情 ====================

    private void openProjectDetail(ModrinthAPI.ModProject project) {
        DetailSheet sheet = new DetailSheet(project);
        window.showSheet(sheet, OverlayHost.Anchor.CENTER, 660, 580, true, null);
    }

    private final class DetailSheet extends JPanel {
        private final ModrinthAPI.ModProject project;
        private SelectBox<String> instanceCombo;
        private SelectBox<ModrinthAPI.ModVersion> versionCombo;
        private AccentButton downloadBtn;
        private JLabel statusLabel;
        private InstanceInfo instanceInfo;
        private boolean closed;

        DetailSheet(ModrinthAPI.ModProject project) {
            this.project = project;
            setOpaque(false);
            setLayout(new BorderLayout(0, 14));
            setBorder(new EmptyBorder(22, 22, 20, 22));

            JPanel header = UiKit.transparent(new BorderLayout(14, 0));
            header.add(new ModIcon(project), BorderLayout.WEST);

            JPanel titleBox = UiKit.transparent(null);
            titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
            EllipsisLabel title = new EllipsisLabel(project.title, Theme.bold(20), Theme.TEXT);
            title.setAlignmentX(LEFT_ALIGNMENT);
            titleBox.add(title);
            titleBox.add(Box.createVerticalStrut(5));
            JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            badges.setOpaque(false);
            badges.setAlignmentX(LEFT_ALIGNMENT);
            badges.add(new Badge(Theme.formatCount(project.downloads) + " 次下载",
                    Theme.SURFACE_SUNK, Theme.TEXT_SUB).fontSize(9.5f));
            for (String l : project.loaders) {
                badges.add(new Badge(l, Theme.PRIMARY_LIGHT, Theme.PRIMARY_DARK).fontSize(9.5f));
            }
            titleBox.add(badges);
            titleBox.add(Box.createVerticalStrut(6));
            JLabel intro = UiKit.caption("来自 Modrinth 社区");
            intro.setAlignmentX(LEFT_ALIGNMENT);
            titleBox.add(intro);
            header.add(titleBox, BorderLayout.CENTER);

            IconButton close = new IconButton(Icons.CLOSE, "关闭");
            close.addActionListener(e -> window.closeSheet());
            JPanel closeWrap = UiKit.transparent(new BorderLayout());
            closeWrap.add(close, BorderLayout.NORTH);
            header.add(closeWrap, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            JPanel body = UiKit.transparent(null);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

            JTextArea desc = new JTextArea(project.description == null ? "" : project.description);
            desc.setEditable(false);
            desc.setLineWrap(true);
            desc.setWrapStyleWord(true);
            desc.setOpaque(false);
            desc.setFont(Theme.font(11.5f));
            desc.setForeground(Theme.TEXT_SUB);
            desc.setBorder(new EmptyBorder(10, 12, 10, 12));
            Card descCard = new Card(new BorderLayout(), 0, 12, false);
            descCard.withFill(Theme.SURFACE_ALT);
            descCard.setPreferredSize(new Dimension(0, 86));
            descCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));
            descCard.add(new SmoothScrollPane(desc), BorderLayout.CENTER);
            body.add(descCard);
            body.add(Box.createVerticalStrut(12));

            Card config = new Card(new BorderLayout(0, 10), 16, 14, false);
            JLabel configTitle = new JLabel("下载配置");
            configTitle.setFont(Theme.bold(13));
            configTitle.setForeground(Theme.TEXT);
            config.add(configTitle, BorderLayout.NORTH);

            JPanel fields = UiKit.transparent(null);
            fields.setLayout(new StackLayout());
            fields.add(labelSmall("目标实例"));
            JPanel row1 = UiKit.transparent(new BorderLayout(8, 0));
            instanceCombo = new SelectBox<>(String::valueOf);
            instanceCombo.addActionListener(e -> onInstanceChanged());
            IconButton refresh = new IconButton(Icons.REFRESH, "刷新实例");
            refresh.setPreferredSize(new Dimension(40, 40));
            refresh.setMinimumSize(refresh.getPreferredSize());
            refresh.setMaximumSize(refresh.getPreferredSize());
            refresh.addActionListener(e -> refreshInstances());
            row1.add(instanceCombo, BorderLayout.CENTER);
            row1.add(refresh, BorderLayout.EAST);
            row1.setPreferredSize(new Dimension(0, 40));
            row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            fields.add(row1);
            fields.add(Box.createVerticalStrut(9));
            fields.add(labelSmall("模组版本"));
            versionCombo = new SelectBox<>(ModrinthAPI.ModVersion::toString);
            versionCombo.setEnabled(false);
            fields.add(versionCombo);
            fields.add(Box.createVerticalStrut(8));
            statusLabel = UiKit.caption("请选择目标实例");
            statusLabel.setAlignmentX(LEFT_ALIGNMENT);
            fields.add(statusLabel);
            config.add(fields, BorderLayout.CENTER);

            downloadBtn = new AccentButton("下载到实例", Icons.DOWNLOAD);
            downloadBtn.setButtonHeight(40);
            downloadBtn.setEnabled(false);
            downloadBtn.addActionListener(e -> doDownload());
            config.add(downloadBtn, BorderLayout.SOUTH);
            body.add(config);
            add(body, BorderLayout.CENTER);

            refreshInstances();
        }

        private JLabel labelSmall(String text) {
            JLabel l = new JLabel(text);
            l.setFont(Theme.font(10.5f));
            l.setForeground(Theme.TEXT_MUTED);
            l.setBorder(new EmptyBorder(0, 2, 4, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            return l;
        }

        private void refreshInstances() {
            String prev = (String) instanceCombo.getSelectedItem();
            instanceCombo.removeAllItems();
            List<String> list = scanLoaderInstances();
            if (list.isEmpty()) {
                instanceCombo.addItem("(没有匹配的实例)");
                instanceCombo.setEnabled(false);
                onInstanceChanged();
                return;
            }
            instanceCombo.setEnabled(true);
            for (String s : list) instanceCombo.addItem(s);
            if (prev != null) {
                for (int i = 0; i < instanceCombo.getItemCount(); i++) {
                    if (prev.equals(instanceCombo.getItemAt(i))) {
                        instanceCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            if (instanceCombo.getSelectedIndex() >= 0) onInstanceChanged();
        }

        private void onInstanceChanged() {
            String instance = (String) instanceCombo.getSelectedItem();
            versionCombo.removeAllItems();
            downloadBtn.setEnabled(false);
            if (instance == null || instance.startsWith("(")) {
                versionCombo.setEnabled(false);
                statusLabel.setText("请先安装带加载器的实例");
                return;
            }
            instanceInfo = getInstanceInfo(instance);
            versionCombo.setEnabled(false);
            statusLabel.setText("正在加载适配 " + instance + " 的版本…");
            new Thread(() -> {
                try {
                    List<ModrinthAPI.ModVersion> all = ModrinthAPI.getVersions(project.projectId);
                    List<ModrinthAPI.ModVersion> filtered = new ArrayList<>();
                    for (ModrinthAPI.ModVersion v : all) {
                        if (matchesInstance(v, instanceInfo)) filtered.add(v);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (closed) return;
                        versionCombo.removeAllItems();
                        if (filtered.isEmpty()) {
                            statusLabel.setText("该模组没有适配此实例的版本");
                            versionCombo.setEnabled(false);
                            downloadBtn.setEnabled(false);
                        } else {
                            for (ModrinthAPI.ModVersion v : filtered) versionCombo.addItem(v);
                            versionCombo.setEnabled(true);
                            downloadBtn.setEnabled(true);
                            statusLabel.setText("适配此实例的版本共 " + filtered.size() + " 个");
                        }
                    });
                } catch (Throwable ex) {
                    SwingUtilities.invokeLater(() -> {
                        if (closed) return;
                        statusLabel.setText("版本加载失败：" + ex.getMessage());
                        versionCombo.setEnabled(false);
                        downloadBtn.setEnabled(false);
                    });
                }
            }, "Modrinth-Versions").start();
        }

        private void doDownload() {
            Object v = versionCombo.getSelectedItem();
            if (!(v instanceof ModrinthAPI.ModVersion ver) || ver.downloadUrl == null) {
                window.toastError("请选择一个可下载的版本");
                return;
            }
            String instance = (String) instanceCombo.getSelectedItem();
            if (instance == null || instance.startsWith("(")) {
                window.toastError("没有匹配的实例");
                return;
            }
            Path modsDir = computeModsDir(instance);
            String fileName = ver.filename != null ? ver.filename : inferFileName(ver.downloadUrl);
            Path target = modsDir.resolve(fileName);
            TaskManager.get().submitModDownload(fileName, ver.downloadUrl, instance, target);
            window.appendLog("[Modrinth] 已提交下载任务: " + fileName + " → " + instance);
            window.toastSuccess("已加入下载队列：" + fileName);
            window.closeSheet();
            window.showTasks();
        }

        @Override public void removeNotify() {
            closed = true;
            super.removeNotify();
        }
    }

    private final class ModIcon extends JComponent {
        private BufferedImage image;

        ModIcon(ModrinthAPI.ModProject project) {
            setOpaque(false);
            setPreferredSize(new Dimension(76, 76));
            setMinimumSize(new Dimension(76, 76));
            setMaximumSize(new Dimension(76, 76));
            String url = project.iconUrl;
            if (url != null && !url.isBlank()) {
                synchronized (ICON_CACHE) {
                    if (ICON_CACHE.containsKey(url)) {
                        image = ICON_CACHE.get(url);
                        return;
                    }
                }
                new Thread(() -> {
                    try {
                        BufferedImage img = ModrinthAPI.loadImage(url);
                        if (img != null) {
                            synchronized (ICON_CACHE) { ICON_CACHE.put(url, img); }
                            SwingUtilities.invokeLater(() -> {
                                image = img;
                                repaint();
                            });
                        }
                    } catch (Throwable ignored) {}
                }, "Modrinth-Icon").start();
            }
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int s = Math.min(getWidth(), getHeight());
            Shape clip = Theme.roundRect(0, 0, s, s, 18);
            Shape old = g2.getClip();
            g2.clip(clip);
            if (image != null) {
                g2.drawImage(image, 0, 0, s, s, null);
            } else {
                g2.setColor(Theme.PRIMARY_LIGHT);
                g2.fillRect(0, 0, s, s);
                g2.setColor(Theme.PRIMARY);
                g2.setFont(Theme.bold(26));
                g2.drawString("?", s / 2f - 8, s / 2f + 9);
            }
            g2.setClip(old);
            g2.setColor(Theme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(clip);
            g2.dispose();
        }
    }

    // ==================== 实例信息解析 ====================

    private static final class InstanceInfo {
        String mcVersion;
        String loaderType;
    }

    private InstanceInfo getInstanceInfo(String instanceId) {
        InstanceInfo info = new InstanceInfo();
        parseFromId(instanceId, info);
        Path dir = gameDir.resolve("versions").resolve(instanceId);
        Path json = dir.resolve(instanceId + ".json");
        try {
            if (Files.exists(json)) {
                JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()) {
                    String parent = obj.get("inheritsFrom").getAsString();
                    parseFromId(parent, info);
                }
                if (info.mcVersion == null && info.loaderType != null) {
                    String parent = obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()
                            ? obj.get("inheritsFrom").getAsString() : null;
                    if (parent != null) {
                        Path pj = gameDir.resolve("versions").resolve(parent).resolve(parent + ".json");
                        if (Files.exists(pj)) {
                            JsonObject po = JsonParser.parseString(Files.readString(pj)).getAsJsonObject();
                            String pid = po.has("id") ? po.get("id").getAsString() : parent;
                            Matcher m = MC_VER_PATTERN.matcher(pid);
                            if (m.find()) info.mcVersion = m.group(1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return info;
    }

    private static void parseFromId(String id, InstanceInfo info) {
        if (id == null) return;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric-loader-")) {
            info.loaderType = "fabric";
            Matcher m = MC_VER_PATTERN.matcher(id.substring("fabric-loader-".length()));
            if (m.find()) info.mcVersion = m.group(1);
        } else if (lower.startsWith("quilt-loader-")) {
            info.loaderType = "quilt";
            Matcher m = MC_VER_PATTERN.matcher(id.substring("quilt-loader-".length()));
            if (m.find()) info.mcVersion = m.group(1);
        } else if (lower.contains("-forge-")) {
            info.loaderType = "forge";
            int idx = id.indexOf("-forge-");
            if (idx > 0) {
                Matcher m = MC_VER_PATTERN.matcher(id.substring(0, idx));
                if (m.find()) info.mcVersion = m.group(1);
            }
        } else if (lower.startsWith("neoforge-")) {
            info.loaderType = "neoforge";
            String rest = id.substring("neoforge-".length());
            String[] parts = rest.split("\\.");
            if (parts.length >= 2) {
                try {
                    info.mcVersion = "1." + Integer.parseInt(parts[0])
                            + (Integer.parseInt(parts[1]) == 0 ? "" : "." + Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {}
            }
        } else {
            Matcher m = MC_VER_PATTERN.matcher(id);
            if (m.find()) info.mcVersion = m.group(1);
        }
    }

    private boolean matchesInstance(ModrinthAPI.ModVersion v, InstanceInfo info) {
        if (info == null) return false;
        if (info.mcVersion != null && !v.gameVersions.contains(info.mcVersion)) return false;
        if (info.loaderType != null) {
            boolean match = false;
            for (String l : v.loaders) {
                if (l.equalsIgnoreCase(info.loaderType)) { match = true; break; }
            }
            if (!match) return false;
        }
        return true;
    }

    private List<String> scanLoaderInstances() {
        List<String> list = new ArrayList<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return list;
        try (var stream = Files.newDirectoryStream(versionsDir, Files::isDirectory)) {
            for (Path p : stream) {
                String id = p.getFileName().toString();
                try {
                    if (VersionInstaller.isInstalling(p)) continue;
                } catch (Throwable ignored) {}
                if (!Files.exists(p.resolve(id + ".json"))) continue;
                if (isLoaderInstance(p, id)) list.add(id);
            }
        } catch (Exception ignored) {}
        Collections.sort(list);
        return list;
    }

    private boolean isLoaderInstance(Path instanceDir, String id) {
        if (looksLikeLoader(id)) return true;
        try {
            String json = Files.readString(instanceDir.resolve(id + ".json"));
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()) {
                if (looksLikeLoader(obj.get("inheritsFrom").getAsString())) return true;
            }
            if (obj.has("mainClass") && !obj.get("mainClass").isJsonNull()) {
                String mc = obj.get("mainClass").getAsString().toLowerCase(Locale.ROOT);
                if (mc.contains("fabric") || mc.contains("forge") || mc.contains("quilt")
                        || mc.contains("neoforge") || mc.contains("launchwrapper")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean looksLikeLoader(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")
                || lower.contains("-forge-") || lower.startsWith("neoforge-");
    }

    private Path computeModsDir(String instance) {
        if (settings.versionIsolation) {
            return gameDir.resolve("versions").resolve(instance).resolve("game").resolve("mods");
        }
        return gameDir.resolve("mods");
    }

    private static String inferFileName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
        if (name.isEmpty()) return "mod-" + System.currentTimeMillis() + ".jar";
        if (!name.contains(".")) return name + ".jar";
        return name;
    }

    private void refreshModInstances() {
        if (modInstanceCombo == null) return;
        String prev = (String) modInstanceCombo.getSelectedItem();
        modInstanceCombo.removeAllItems();
        List<String> list = scanLoaderInstances();
        if (list.isEmpty()) {
            modInstanceCombo.addItem("(没有匹配的实例)");
            modInstanceCombo.setEnabled(false);
            updateModHint();
            return;
        }
        modInstanceCombo.setEnabled(true);
        for (String s : list) modInstanceCombo.addItem(s);
        if (prev != null) {
            for (int i = 0; i < modInstanceCombo.getItemCount(); i++) {
                if (prev.equals(modInstanceCombo.getItemAt(i))) {
                    modInstanceCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        updateModHint();
    }

    private void updateModHint() {
        if (modHintLabel == null) return;
        String instance = (String) modInstanceCombo.getSelectedItem();
        if (instance == null || instance.startsWith("(")) {
            modHintLabel.setText("没有匹配的实例，请先在「Minecraft」中安装带加载器的版本");
            modHintLabel.setForeground(Theme.AMBER);
            return;
        }
        Path target = computeModsDir(instance);
        String isoHint = settings.versionIsolation ? "版本隔离已开启" : "版本隔离未开启";
        modHintLabel.setText("<html><body style='width:290px'>保存到：" + target
                + "<br>" + isoHint + "，模组只对目标实例生效。</body></html>");
        modHintLabel.setForeground(Theme.TEXT_MUTED);
    }

    private void startModDownload() {
        String url = modUrlField.getText().trim();
        if (url.isEmpty()) {
            window.toastError("请输入模组下载链接");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            window.toastError("链接必须以 http:// 或 https:// 开头");
            return;
        }
        String instance = (String) modInstanceCombo.getSelectedItem();
        if (instance == null || instance.startsWith("(")) {
            window.toastError("没有匹配的实例，请先安装带加载器的版本");
            return;
        }
        String fileName = inferFileName(url);
        Path target = computeModsDir(instance).resolve(fileName);
        TaskManager.get().submitModDownload(fileName, url, instance, target);
        window.appendLog("[模组] 已提交下载任务: " + fileName + " → " + instance + " (" + target + ")");
        window.toastSuccess("已加入下载队列：" + fileName);
        modUrlField.setText("");
        window.showTasks();
    }

    // ==================== Minecraft 版本加载 ====================

    private void loadVersionsAsync() {
        new Thread(() -> {
            try {
                List<VersionManifest.VersionEntry> entries = VersionInstaller.fetchVersions();
                SwingUtilities.invokeLater(() -> {
                    allVersions.clear();
                    allVersions.addAll(entries);
                    versionsLoaded = true;
                    applyFilter();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    versionCombo.removeAllItems();
                    versionCombo.addItem(new VersionItem("", "版本列表加载失败", ""));
                    hintLabel.setText("加载失败：" + ex.getMessage());
                    hintLabel.setForeground(Theme.RED);
                    startBtn.setEnabled(false);
                    window.appendLog("[下载] 版本列表加载失败: " + ex.getMessage());
                });
            }
        }, "Version-List-Loader").start();
    }

    // ==================== 版本筛选 ====================

    private void applyFilter() {
        if (!versionsLoaded) return;
        VersionItem current = (VersionItem) versionCombo.getSelectedItem();
        String currentId = current != null ? current.id : null;

        boolean showRelease = releaseChip.isSelected();
        boolean showSnapshot = snapshotChip.isSelected();
        boolean showApril = aprilChip.isSelected();
        boolean showOld = oldChip.isSelected();
        boolean showServer = serverChip.isSelected();

        versionCombo.removeAllItems();
        int restored = -1;
        int count = 0;

        for (VersionManifest.VersionEntry e : allVersions) {
            String rawType = e.type == null ? "" : e.type.toLowerCase(Locale.ROOT);
            String displayType;
            boolean keep;

            if (showServer) {
                // 服务端模式：Mojang 清单 type 字段只有 release/snapshot/old_beta/old_alpha，
                // 不存在 "server"。折中方案：勾选服务端后把正式版+快照都当服务端候选列出来，
                // 真正是否有 downloads.server 在 startInstall 解析时给出明确错误。
                if ("release".equals(rawType) || "snapshot".equals(rawType)) {
                    keep = true;
                    displayType = "服务器核心";
                } else {
                    keep = false;
                    displayType = null;
                }
            } else {
                displayType = typeLabel(e.id, e.type);
                keep = switch (displayType) {
                    case "正式版" -> showRelease;
                    case "快照" -> showSnapshot;
                    case "愚人节" -> showApril;
                    case "远古 Beta", "远古 Alpha" -> showOld;
                    default -> true;
                };
            }

            if (!keep) continue;
            versionCombo.addItem(new VersionItem(e.id, displayType, e.releaseTime));
            if (currentId != null && currentId.equals(e.id)) restored = count;
            count++;
        }

        if (count == 0) {
            versionCombo.addItem(new VersionItem("", "无匹配版本", ""));
            startBtn.setEnabled(false);
            hintLabel.setText(showServer
                    ? "没有可用的服务端核心版本（需要联网获取版本清单）"
                    : "当前筛选条件下没有版本，请至少选择一种类型");
            hintLabel.setForeground(Theme.AMBER);
        } else {
            if (restored >= 0) versionCombo.setSelectedIndex(restored);
            else {
                for (int i = 0; i < versionCombo.getItemCount(); i++) {
                    VersionItem vi = (VersionItem) versionCombo.getItemAt(i);
                    if ("正式版".equals(vi.typeLabel) || "服务器核心".equals(vi.typeLabel)) {
                        versionCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            if (showServer) {
                hintLabel.setText("服务器核心模式 · 共 " + count
                        + " 个可选版本（正式版 + 快照）");
            } else {
                hintLabel.setText("显示 " + count + " / " + allVersions.size() + " 个版本");
            }
            hintLabel.setForeground(Theme.GREEN);
        }
        updatePreview();
        checkStartBtnState();
    }

    private static String typeLabel(String id, String type) {
        if (APRIL_FOOLS.contains(id)) return "愚人节";
        return switch (type == null ? "" : type) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_beta" -> "远古 Beta";
            case "old_alpha" -> "远古 Alpha";
            case "server" -> "服务器核心";
            default -> type == null || type.isBlank() ? "未知" : type;
        };
    }

    private void onLoaderChanged() {
        if (loaderCombo == null) return;
        final int seq = ++loaderRequestSeq;
        String loader = (String) loaderCombo.getSelectedItem();
        if (loader == null || "无".equals(loader)) {
            loaderVersionCombo.removeAllItems();
            loaderVersionCombo.setEnabled(false);
            updatePreview();
            checkStartBtnState();
            return;
        }
        VersionItem item = (VersionItem) versionCombo.getSelectedItem();
        if (item == null || item.id.isEmpty()) {
            loaderVersionCombo.removeAllItems();
            loaderVersionCombo.setEnabled(false);
            updatePreview();
            checkStartBtnState();
            return;
        }
        String loaderId = loaderId(loader);
        if (loaderId == null) return;
        String mcVersion = item.id;

        loaderVersionCombo.removeAllItems();
        loaderVersionCombo.addItem("加载中…");
        loaderVersionCombo.setEnabled(false);
        checkStartBtnState();

        new Thread(() -> {
            int attempt = 0;
            while (true) {
                if (seq != loaderRequestSeq) return;
                attempt++;
                try {
                    List<LoaderResolver.LoaderVersion> versions =
                            LoaderResolver.list(loaderId, mcVersion);
                    SwingUtilities.invokeLater(() -> {
                        if (seq != loaderRequestSeq) return;
                        loaderVersionCombo.removeAllItems();
                        if (versions.isEmpty()) {
                            loaderVersionCombo.addItem("(无可用版本)");
                            loaderVersionCombo.setEnabled(false);
                        } else {
                            for (var v : versions) {
                                loaderVersionCombo.addItem(v.version()
                                        + (v.stable() ? " (稳定)" : ""));
                            }
                            loaderVersionCombo.setEnabled(true);
                        }
                        updatePreview();
                        checkStartBtnState();
                    });
                    return;
                } catch (Exception ex) {
                    if (seq != loaderRequestSeq) return;
                    if (attempt == 1) {
                        SwingUtilities.invokeLater(() -> {
                            if (seq != loaderRequestSeq) return;
                            loaderVersionCombo.removeAllItems();
                            loaderVersionCombo.addItem("加载失败，重试中…");
                            loaderVersionCombo.setEnabled(false);
                        });
                    }
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "Loader-List").start();
    }

    private static String loaderId(String label) {
        return switch (label) {
            case "Forge" -> "forge";
            case "NeoForge" -> "neoforge";
            case "Fabric" -> "fabric";
            case "Quilt" -> "quilt";
            default -> null;
        };
    }

    private void startInstall() {
        if (!versionsLoaded) {
            window.toastError("版本列表尚未加载完成");
            return;
        }
        VersionItem item = (VersionItem) versionCombo.getSelectedItem();
        if (item == null || item.id.isEmpty()) {
            window.toastError("请选择 Minecraft 版本");
            return;
        }
        String mcVersion = item.id;
        String typeLabel = item.typeLabel;
        String customName = nameField.getText().trim();

        // ================= 服务端核心特殊处理 =================
        if ("服务器核心".equals(typeLabel)) {
            String displayName = customName.isEmpty() ? mcVersion : customName;

            startBtn.setEnabled(false);
            hintLabel.setText("正在解析 " + mcVersion + " 的服务端下载链接…");
            hintLabel.setForeground(Theme.TEXT_SUB);
            window.appendLog("[下载] 正在解析服务端链接: " + mcVersion);

            new Thread(() -> {
                try {
                    String manifestUrl =
                            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
                    String manifestJson = DownloadUtil.downloadString(manifestUrl);
                    JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();

                    JsonArray versions = root.getAsJsonArray("versions");
                    if (versions == null) {
                        throw new Exception("版本清单格式异常：缺少 versions 字段");
                    }

                    String versionUrl = null;
                    for (JsonElement e : versions) {
                        if (!e.isJsonObject()) continue;
                        JsonObject obj = e.getAsJsonObject();
                        if (obj.has("id") && !obj.get("id").isJsonNull()
                                && mcVersion.equals(obj.get("id").getAsString())) {
                            if (obj.has("url") && !obj.get("url").isJsonNull()) {
                                versionUrl = obj.get("url").getAsString();
                            }
                            break;
                        }
                    }
                    if (versionUrl == null) {
                        throw new Exception("版本清单中找不到 " + mcVersion + " 的元数据地址");
                    }

                    String versionJson = DownloadUtil.downloadString(versionUrl);
                    JsonObject vObj = JsonParser.parseString(versionJson).getAsJsonObject();

                    if (!vObj.has("downloads") || !vObj.get("downloads").isJsonObject()) {
                        throw new Exception("版本 " + mcVersion
                                + " 缺少 downloads 信息，可能该版本不支持官方服务端核心");
                    }
                    JsonObject downloads = vObj.getAsJsonObject("downloads");

                    String serverUrl = null;
                    String serverSha1 = null;
                    long serverSize = -1L;

                    if (downloads.has("server") && downloads.get("server").isJsonObject()) {
                        JsonObject serverObj = downloads.getAsJsonObject("server");
                        if (serverObj.has("url") && !serverObj.get("url").isJsonNull()) {
                            serverUrl = serverObj.get("url").getAsString();
                        }
                        if (serverObj.has("sha1") && !serverObj.get("sha1").isJsonNull()) {
                            serverSha1 = serverObj.get("sha1").getAsString();
                        }
                        if (serverObj.has("size") && !serverObj.get("size").isJsonNull()) {
                            try { serverSize = serverObj.get("size").getAsLong(); }
                            catch (Exception ignored) {}
                        }
                    }
                    if ((serverUrl == null || serverUrl.isBlank())
                            && downloads.has("windows_server")
                            && downloads.get("windows_server").isJsonObject()) {
                        JsonObject win = downloads.getAsJsonObject("windows_server");
                        if (win.has("url") && !win.get("url").isJsonNull()) {
                            serverUrl = win.get("url").getAsString();
                        }
                        if (win.has("sha1") && !win.get("sha1").isJsonNull()) {
                            serverSha1 = win.get("sha1").getAsString();
                        }
                        if (win.has("size") && !win.get("size").isJsonNull()) {
                            try { serverSize = win.get("size").getAsLong(); }
                            catch (Exception ignored) {}
                        }
                    }
                    if (serverUrl == null || serverUrl.isBlank()) {
                        throw new Exception("版本 " + mcVersion
                                + " 没有官方服务端核心（可能仅有客户端，或是远古版本 / 快照）");
                    }

                    final String fServerUrl = serverUrl;
                    final String fServerSha1 = serverSha1;
                    final long fServerSize = serverSize;

                    SwingUtilities.invokeLater(() -> {
                        TaskManager.get().submitServer(displayName, mcVersion, fServerUrl, customName);

                        StringBuilder hint = new StringBuilder();
                        hint.append("已提交服务端任务（").append(mcVersion);
                        if (fServerSize > 0) {
                            hint.append(String.format(" · %.1f MB",
                                    fServerSize / (1024.0 * 1024.0)));
                        }
                        if (fServerSha1 != null && fServerSha1.length() >= 8) {
                            hint.append(" · SHA-1 ").append(fServerSha1, 0, 8);
                        }
                        hint.append("），队列共 ")
                                .append(TaskManager.get().getTasks().size()).append(" 个");

                        hintLabel.setText(hint.toString());
                        hintLabel.setForeground(Theme.GREEN);
                        startBtn.setEnabled(true);
                        window.appendLog("[下载] 已提交服务端任务: " + displayName
                                + " ← " + fServerUrl);
                        window.toastSuccess("已加入服务端安装队列：" + displayName);
                        nameField.setText("");
                        window.showTasks();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        startBtn.setEnabled(true);
                        hintLabel.setText("获取服务端链接失败：" + ex.getMessage());
                        hintLabel.setForeground(Theme.RED);
                        window.toastError("获取服务端链接失败：" + ex.getMessage());
                        window.appendLog("[下载] 服务端链接解析失败: " + ex.getMessage());
                    });
                }
            }, "Server-Url-Fetcher").start();
            return;
        }
        // ========================================================

        String loader = (String) loaderCombo.getSelectedItem();
        String loaderId = null, loaderVer = null;

        if (loader != null && !"无".equals(loader)) {
            loaderId = loaderId(loader);
            String sel = (String) loaderVersionCombo.getSelectedItem();
            if (!isLoaderVersionReady(sel)) {
                window.toastError("请等待加载器版本列表加载完成或重试成功");
                return;
            }
            loaderVer = sel.replace(" (稳定)", "");
        }

        String displayName = customName.isEmpty() ? mcVersion : customName;
        TaskManager.get().submit(displayName, typeLabel, mcVersion, loaderId, loaderVer, customName);
        hintLabel.setText("已提交任务，队列共 " + TaskManager.get().getTasks().size() + " 个");
        hintLabel.setForeground(Theme.GREEN);
        window.appendLog("[下载] 已提交任务: " + displayName
                + (loaderId != null ? " + " + loaderId + " " + loaderVer : ""));
        window.toastSuccess("已加入安装队列：" + displayName);
        nameField.setText("");
        window.showTasks();
    }

    static class VersionItem {
        final String id;
        final String typeLabel;
        final String releaseTime;

        VersionItem(String id, String typeLabel, String releaseTime) {
            this.id = id;
            this.typeLabel = typeLabel;
            this.releaseTime = releaseTime;
        }

        @Override public String toString() {
            if (id.isEmpty()) return typeLabel;
            return "[" + typeLabel + "] " + id;
        }
    }

    // ==================== 拖放提示 ====================

    private static final class DropHint extends JComponent {
        private final Timer shimmer;
        private float phase;

        DropHint() {
            setOpaque(false);
            setPreferredSize(new Dimension(0, 46));
            shimmer = new Timer(40, e -> {
                if (!isShowing()) return;
                phase += 0.03f;
                if (phase > (float) (Math.PI * 2)) phase -= (float) (Math.PI * 2);
                repaint();
            });
        }

        @Override public void addNotify() {
            super.addNotify();
            shimmer.start();
        }

        @Override public void removeNotify() {
            shimmer.stop();
            super.removeNotify();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.isDark() ? Theme.SURFACE_ALT : new Color(0xF7, 0xF9, 0xFD));
            g2.fill(Theme.roundRect(0, 0, w, h, 13));
            g2.setColor(Theme.isDark() ? Theme.BORDER_STRONG : new Color(0xC9, 0xD5, 0xE4));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 10, new float[]{5f, 5f}, 0));
            g2.draw(Theme.roundRect(1, 1, w - 2, h - 2, 13));
            float pulse = 0.6f + 0.4f * (float) Math.sin(phase);
            Icons.paint(g2, Icons.BOX, 18, (h - 18) / 2, 18,
                    Theme.alpha(Theme.PRIMARY, (int) (150 + 80 * pulse)));
            g2.setFont(Theme.font(11.5f));
            g2.setColor(Theme.TEXT_SUB);
            String text = "拖入 .mrpack / .zip 整合包到窗口任意位置，即可快速安装";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 44, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}

/** 统一风格的圆角下拉框。 */
class SelectBox<T> extends JComboBox<T> {
    private final Animator.AnimatedValue hover;
    private final Function<T, String> labeler;
    private int radius = 11;

    public SelectBox() {
        this(String::valueOf);
    }

    public SelectBox(Function<T, String> labeler) {
        super();
        this.labeler = labeler == null ? String::valueOf : labeler;
        setOpaque(false);
        setFocusable(false);
        setFont(Theme.font(14));
        setForeground(Theme.TEXT);
        setBackground(Theme.SURFACE_ALT);
        setBorder(new EmptyBorder(0, 14, 0, 36));
        setPreferredSize(new Dimension(220, 40));

        setMaximumRowCount(6);

        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRenderer(new Renderer());
        setUI(new StyleUI());
        hover = new Animator.AnimatedValue(0, v -> repaint());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (isEnabled()) hover.set(1, 150);
            }
            @Override public void mouseExited(MouseEvent e) { hover.set(0, 180); }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        SwingUtilities.invokeLater(this::stylePopup);
    }

    public void setRadius(int radius) {
        this.radius = radius;
        repaint();
    }

    public void setPlaceholder(String placeholder) {
        setToolTipText(placeholder);
    }

    /**
     * 每次弹窗显示前重新应用主题色。
     * 否则切换深色模式后，BasicComboPopup 里的 JList 仍停留在创建时的浅色背景，
     * 而文字色已变为白色，会出现白底白字。
     */
    @Override
    public void setPopupVisible(boolean visible) {
        if (visible) stylePopup();
        super.setPopupVisible(visible);
        // 某些 LAF（尤其 Windows）会在 show 过程中重置弹层背景，
        // 或此时滚动面板才挂进弹层，所以显示后再刷一次。
        if (visible) SwingUtilities.invokeLater(this::stylePopup);
    }

    private void stylePopup() {
        try {
            ComboPopup cp = ((StyleUI) getUI()).popup();
            if (cp == null) return;

            Color bg = Theme.SURFACE;
            Color fg = Theme.TEXT;
            Color selBg = Theme.PRIMARY_LIGHT;
            Color selFg = Theme.TEXT;
            Color border = Theme.BORDER_STRONG;

            JList<?> list = cp.getList();
            list.setOpaque(true);
            list.setBackground(bg);
            list.setForeground(fg);
            list.setSelectionBackground(selBg);
            list.setSelectionForeground(selFg);
            list.setFont(Theme.font(14));
            list.setBorder(new EmptyBorder(5, 5, 5, 5));
            list.setFixedCellHeight(34);

            if (cp instanceof JComponent jc) {
                jc.setOpaque(true);
                jc.setBackground(bg);
                // 不要给弹层加 EmptyBorder：Windows LAF 下那圈内边距会露出白色底。
                jc.setBorder(BorderFactory.createLineBorder(border));
                stylePopupTree(jc, bg);
            }
            if (cp instanceof BasicComboPopup bp) {
                JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(
                        JScrollPane.class, bp.getList());
                if (sp != null) {
                    sp.setBorder(null);
                    sp.setOpaque(true);
                    sp.setBackground(bg);
                    // viewport 必须不透明，否则列表项不足 6 行时，
                    // 列表下方的空白区域会露出弹层窗口的白底。
                    sp.getViewport().setOpaque(true);
                    sp.getViewport().setBackground(bg);
                }
            }
            list.revalidate();
            list.repaint();
        } catch (Throwable ignored) {}
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(160, d.width), 40);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, 40);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        boolean en = isEnabled();
        float ho = hover.value();

        // 1. 先用父卡片背景色填满整个矩形，避免圆角外露出白色
        g2.setColor(Theme.SURFACE);
        g2.fillRect(0, 0, w, h);

        // 2. 再画圆角背景
        g2.setColor(en ? Theme.SURFACE_ALT
                : (Theme.isDark() ? Theme.SURFACE_SUNK : new Color(0xF2, 0xF4, 0xF7)));
        g2.fill(Theme.roundRect(0, 0, w, h, radius));

        // 3. 边框
        Color border = en ? Theme.mix(Theme.BORDER_STRONG, Theme.PRIMARY, ho * 0.6f)
                : Theme.BORDER;
        if (isPopupVisible()) border = Theme.PRIMARY;
        g2.setColor(border);
        g2.setStroke(new BasicStroke(isPopupVisible() ? 1.8f : 1f));
        g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, radius));
        g2.dispose();

        // 4. 手动让 UI 画当前值文字，不调用 super.paintComponent
        if (ui != null) {
            ui.paint(g, this);
        }
    }

    /** 递归给弹层内部的列表/滚动面板/内容面板上深色背景，防止 LAF 残留白底。 */
    private static void stylePopupTree(Component c, Color bg) {
        if (c == null) return;
        if (c instanceof JList<?> list) {
            list.setOpaque(true);
            list.setBackground(bg);
        } else if (c instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(bg);
        } else if (c instanceof JScrollPane sp) {
            sp.setBorder(null);
            sp.setOpaque(true);
            sp.setBackground(bg);
        } else if (c instanceof JPanel p) {
            p.setOpaque(true);
            p.setBackground(bg);
        }
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) stylePopupTree(child, bg);
        }
    }

    private final class Renderer extends DefaultListCellRenderer {
        @Override
        @SuppressWarnings("unchecked")
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index,
                    isSelected, cellHasFocus);
            l.setFont(Theme.font(14));
            l.setBorder(new EmptyBorder(0, 6, 0, 6));
            if (index >= 0) {
                // 下拉列表项：自行绘制背景，确保深色/浅色都与主题一致
                l.setOpaque(true);
                l.setForeground(Theme.TEXT);
                l.setBackground(isSelected ? Theme.PRIMARY_LIGHT : Theme.SURFACE);
            } else {
                // 未展开时显示当前值的区域由 SelectBox.paintComponent 负责绘制
                l.setOpaque(false);
                l.setForeground(SelectBox.this.isEnabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            }
            if (value == null) {
                l.setText("");
            } else {
                String s;
                try {
                    s = labeler.apply((T) value);
                } catch (Throwable t) {
                    s = String.valueOf(value);
                }
                l.setText(s == null ? "" : s);
            }
            return l;
        }
    }

    private final class StyleUI extends BasicComboBoxUI {
        ComboPopup popup() {
            if (popup == null) popup = createPopup();
            return popup;
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup p = new BasicComboPopup(comboBox) {
                @Override
                protected int getPopupHeightForRowCount(int maxRowCount) {
                    return 34 * 6;
                }
            };
            return p;
        }

        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = Theme.quality(g);
                    Color c = SelectBox.this.isEnabled() ? Theme.TEXT_MUTED
                            : Theme.mix(Theme.TEXT_MUTED, Theme.SURFACE, 0.45f);
                    Icons.paint(g2, Icons.CHEVRON_DOWN, (getWidth() - 14) / 2,
                            (getHeight() - 14) / 2, 14, c);
                    g2.dispose();
                }
            };
            b.setOpaque(false);
            b.setContentAreaFilled(false);
            b.setBorder(BorderFactory.createEmptyBorder());
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(32, 40));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return b;
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            // background is painted by SelectBox.paintComponent
        }
    }
}

/** 可切换的筛选胶囊。 */
class FilterChip extends JComponent {
    private final String text;
    private boolean selected;
    private boolean hovered;
    private final List<Runnable> listeners = new ArrayList<>();
    private final Animator.AnimatedValue hover;
    private final Animator.AnimatedValue sel;
    private int height = 30;

    public FilterChip(String text, boolean selected) {
        this.text = text;
        this.selected = selected;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        hover = new Animator.AnimatedValue(0, v -> repaint());
        sel = new Animator.AnimatedValue(selected ? 1 : 0, v -> repaint());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; hover.set(1, 140); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; hover.set(0, 180); }
            @Override public void mouseReleased(MouseEvent e) {
                if (contains(e.getPoint())) toggle();
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
        getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggle(); }
        });
    }

    public String getText() {
        return text;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        if (selected == value) return;
        selected = value;
        sel.set(value ? 1 : 0, 200);
        repaint();
    }

    public void addAction(Runnable r) {
        listeners.add(r);
    }

    private void toggle() {
        selected = !selected;
        sel.set(selected ? 1 : 0, 200);
        for (Runnable r : new ArrayList<>(listeners)) r.run();
        repaint();
    }

    public void setChipHeight(int h) {
        this.height = h;
        revalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.font(12.5f));
        int w = fm.stringWidth(text) + 24 + (selected ? 14 : 0);
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
        float s = sel.value();
        float ho = hover.value();
        Color bg = Theme.mix(Theme.SURFACE, Theme.PRIMARY_LIGHT, s);
        if (s < 0.05f && ho > 0) bg = Theme.mix(bg, Theme.SURFACE_HOVER, ho);
        g2.setColor(bg);
        g2.fill(Theme.roundRect(0, 0, w, h, h / 2f));
        Color border = Theme.mix(Theme.BORDER, Theme.PRIMARY, s * 0.9f);
        if (s < 0.5f) border = Theme.mix(border, Theme.BORDER_STRONG, ho * 0.5f);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, h / 2f));

        Font f = Theme.bold(12.5f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        Color fg = Theme.mix(Theme.TEXT_SUB, Theme.PRIMARY_DARK, s);
        int textW = fm.stringWidth(text);
        int checkW = s > 0.5f ? 13 : 0;
        int gap = checkW > 0 ? 5 : 0;
        int startX = (w - textW - checkW - gap) / 2;
        if (checkW > 0) {
            Icons.paint(g2, Icons.CHECK, startX, (h - 12) / 2, 12,
                    Theme.alpha(Theme.PRIMARY_DARK, (int) (255 * s)));
        }
        g2.setColor(fg);
        int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, startX + checkW + gap, baseline);
        g2.dispose();
        if (hasFocus()) {
            g2 = Theme.quality(g);
            g2.setColor(Theme.alpha(Theme.PRIMARY, 90));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(Theme.roundRect(1, 1, w - 2, h - 2, h / 2f));
            g2.dispose();
        }
    }
}

/** iOS 风格分段选择器，选中背景会平滑滑动。 */
class SegmentedControl extends JComponent {
    private final List<String> items = new ArrayList<>();
    private int selectedIndex;
    private final Animator.AnimatedValue pos;
    private final List<Consumer<Integer>> listeners = new ArrayList<>();
    private int height = 40;
    private int padding = 4;

    public SegmentedControl(String... items) {
        for (String s : items) this.items.add(s);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pos = new Animator.AnimatedValue(0, v -> repaint());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int idx = indexAt(e.getX());
                if (idx >= 0) select(idx, true);
            }
        });
    }

    public void addListener(Consumer<Integer> c) {
        listeners.add(c);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public String getSelectedItem() {
        return selectedIndex >= 0 && selectedIndex < items.size() ? items.get(selectedIndex) : null;
    }

    public void select(int index, boolean notify) {
        if (index < 0 || index >= items.size()) return;
        if (selectedIndex == index && notify) return;
        selectedIndex = index;
        pos.set(index, 260);
        if (notify) {
            for (Consumer<Integer> l : new ArrayList<>(listeners)) l.accept(index);
        }
        repaint();
    }

    public void selectSilently(int index) {
        selectedIndex = Math.max(0, Math.min(items.size() - 1, index));
        pos.snap(selectedIndex);
        repaint();
    }

    public void setControlHeight(int h) {
        this.height = h;
        revalidate();
    }

    private int indexAt(int x) {
        int segW = segmentWidth();
        int start = padding;
        int idx = (x - start) / Math.max(1, segW);
        return (idx >= 0 && idx < items.size()) ? idx : -1;
    }

    private int segmentWidth() {
        int w = getWidth() - padding * 2;
        return Math.max(1, w / Math.max(1, items.size()));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.bold(13));
        int textW = 0;
        for (String s : items) textW = Math.max(textW, fm.stringWidth(s));
        return new Dimension(Math.max(180, (textW + 38) * items.size() + padding * 2), height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        g2.setColor(Theme.SURFACE_SUNK);
        g2.fill(Theme.roundRect(0, 0, w, h, 12));
        g2.setColor(Theme.BORDER);
        g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, 12));

        int segW = segmentWidth();
        int start = padding;
        int pillY = padding;
        int pillH = h - padding * 2;
        float x = start + pos.value() * segW;
        g2.setColor(new Color(0x1B, 0x2F, 0x52, 18));
        g2.fill(Theme.roundRect(x + 1, pillY + 1.5f, segW - 2, pillH, 9));
        g2.setColor(Theme.isDark() ? Theme.SURFACE_ALT : Color.WHITE);
        g2.fill(Theme.roundRect(x, pillY, segW - 2, pillH, 9));
        g2.setColor(Theme.BORDER);
        g2.draw(Theme.roundRect(x + 0.5f, pillY + 0.5f, segW - 3, pillH - 1, 9));

        for (int i = 0; i < items.size(); i++) {
            Rectangle r = new Rectangle(start + i * segW, 0, segW, h);
            boolean sel = i == selectedIndex;
            Font f = sel ? Theme.bold(13) : Theme.font(13);
            Color color = sel ? (Theme.isDark() ? Theme.TEXT : Theme.PRIMARY_DARK) : Theme.TEXT_SUB;
            Theme.drawCentered(g2, items.get(i), r, f, color);
        }
        g2.dispose();
    }
}

/**
 * 简单的纵向堆叠布局：每个子组件按首选高度排列，占满整行宽度。
 * 适合表单卡片，避免 BoxLayout 在横轴上把控件居中/压缩。
 */
class StackLayout implements LayoutManager2 {
    private final List<Component> components = new ArrayList<>();

    @Override
    public void addLayoutComponent(Component comp, Object constraints) {
        if (!components.contains(comp)) components.add(comp);
    }

    @Override public void addLayoutComponent(String name, Component comp) {
        if (!components.contains(comp)) components.add(comp);
    }

    @Override
    public void removeLayoutComponent(Component comp) {
        components.remove(comp);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        int w = 0, h = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            w = Math.max(w, d.width);
            h += d.height;
        }
        return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Dimension d = preferredLayoutSize(parent);
        return new Dimension(0, d.height);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int x = in.left;
        int y = in.top;
        int w = Math.max(0, parent.getWidth() - in.left - in.right);
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            int h = c.getPreferredSize().height;
            c.setBounds(x, y, w, h);
            y += h;
        }
    }

    @Override public Dimension maximumLayoutSize(Container parent) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
    @Override public float getLayoutAlignmentX(Container target) { return 0f; }
    @Override public float getLayoutAlignmentY(Container target) { return 0f; }
    @Override public void invalidateLayout(Container target) {}
}

/** 小工具：快速创建透明容器、标签与组合行。 */
final class UiKit {
    private UiKit() {}

    public static JPanel transparent(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    public static JPanel column(int gap) {
        JPanel p = transparent(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        if (gap > 0) p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        return p;
    }

    public static JPanel row(int gap, Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        for (Component c : comps) p.add(c);
        return p;
    }

    public static JPanel rowRight(int gap, Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, gap, 0));
        p.setOpaque(false);
        for (Component c : comps) p.add(c);
        return p;
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    public static JLabel title(String text) {
        return label(text, Theme.bold(20), Theme.TEXT);
    }

    public static JLabel h2(String text) {
        return label(text, Theme.bold(15), Theme.TEXT);
    }

    public static JLabel body(String text) {
        return label(text, Theme.font(13), Theme.TEXT_SUB);
    }

    public static JLabel caption(String text) {
        return label(text, Theme.font(11.5f), Theme.TEXT_MUTED);
    }

    public static Component strut(int h) {
        return Box.createVerticalStrut(h);
    }

    public static Component glue() {
        return Box.createVerticalGlue();
    }

    public static Component hglue() {
        return Box.createHorizontalGlue();
    }

    public static JPanel header(String title, String subtitle, Component right) {
        JPanel header = transparent(new BorderLayout(16, 0));
        JPanel left = transparent(null);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(Theme.bold(25));
        t.setForeground(Theme.TEXT);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel s = new JLabel(subtitle);
        s.setFont(Theme.font(12));
        s.setForeground(Theme.TEXT_MUTED);
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(t);
        left.add(Box.createVerticalStrut(4));
        left.add(s);
        header.add(left, BorderLayout.WEST);
        if (right != null) {
            JPanel r = transparent(new BorderLayout());
            r.add(right, BorderLayout.EAST);
            header.add(r, BorderLayout.CENTER);
        }
        return header;
    }

    public static JComponent hline() {
        JComponent line = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Theme.quality(g);
                g2.setColor(Theme.BORDER);
                g2.drawLine(0, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        line.setPreferredSize(new Dimension(0, 1));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setMinimumSize(new Dimension(0, 1));
        return line;
    }
}
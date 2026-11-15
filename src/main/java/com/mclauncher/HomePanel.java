package com.mclauncher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 主页：问候语、渐变启动卡片、玩家档案与版本列表。
 */
public class HomePanel extends JPanel {
    private static final Pattern MC_VER_PATTERN = Pattern.compile("(1\\.[0-9]+(?:\\.[0-9]+)?)");

    private final MainWindow window;
    private final Settings settings;
    private final Path gameDir;

    private final List<VersionInfo> installed = new ArrayList<>();
    private final List<VersionInfo> filtered = new ArrayList<>();
    private String selectedId;

    private JLabel greetingLabel;
    private Avatar avatar;
    private TextField userField;
    private TextField uuidField;

    private EllipsisLabel versionLabel;
    private Badge typeBadge;
    private Badge loaderBadge;
    private Badge readyBadge;
    private AccentButton launchBtn;
    private EllipsisLabel heroHint;

    private Badge countBadge;
    private TextField searchField;
    private JPanel listBox;
    private SmoothScrollPane listScroll;

    private HeroCard heroCard;
    private Glyph heroLoaderGlyph;
    private Image heroLoaderImage;
    private static final Map<String, Image> LOADER_IMAGES = new HashMap<>();

    private volatile boolean launching;
    private volatile int launchSession;
    private volatile int launchPercent;
    private volatile Process gameProcess;
    private volatile Thread launchThread;

    private static volatile IntConsumer launchProgressSink;

    public static void reportLaunchProgress(int percent) {
        IntConsumer sink = launchProgressSink;
        if (sink == null) return;
        int p = Math.max(0, Math.min(100, percent));
        SwingUtilities.invokeLater(() -> {
            if (launchProgressSink == sink) sink.accept(p);
        });
    }

    public HomePanel(MainWindow window) {
        this.window = window;
        this.settings = window.getSettings();
        this.gameDir = window.getGameDir();
        setOpaque(false);
        setLayout(new BorderLayout(0, 18));
        setBorder(new EmptyBorder(20, 26, 22, 26));
        add(buildHeader(), BorderLayout.NORTH);
        JComponent grid = buildGrid();
        JPanel view = UiKit.transparent(new BorderLayout());
        view.add(grid, BorderLayout.CENTER);
        view.setPreferredSize(new Dimension(0, 560));
        SmoothScrollPane pageScroll = new SmoothScrollPane(view);
        pageScroll.setWheelStep(64);
        add(pageScroll, BorderLayout.CENTER);
        updateGreeting();
        rebuildList();
    }

    // ==================== 头部 ====================

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        greetingLabel = new JLabel();
        greetingLabel.setFont(Theme.bold(25));
        greetingLabel.setForeground(Theme.TEXT);
        greetingLabel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel sub = new JLabel("选择一个版本，开始今天的冒险");
        sub.setFont(Theme.font(12));
        sub.setForeground(Theme.TEXT_MUTED);
        sub.setAlignmentX(LEFT_ALIGNMENT);
        left.add(greetingLabel);
        left.add(Box.createVerticalStrut(4));
        left.add(sub);
        header.add(left, BorderLayout.WEST);

        JPanel actions = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        AccentButton open = new AccentButton("打开游戏目录", Icons.FOLDER, AccentButton.Variant.OUTLINE);
        open.setButtonHeight(36);
        open.setFontSize(12f, true);
        open.addActionListener(e -> window.openFolder(gameDir));

        IconButton logs = new IconButton(Icons.LOG, "查看日志");
        logs.setPreferredSize(new Dimension(36, 36));
        logs.setMinimumSize(logs.getPreferredSize());
        logs.setMaximumSize(logs.getPreferredSize());
        logs.addActionListener(e -> window.navigate("log"));

        IconButton refresh = new IconButton(Icons.REFRESH, "刷新版本列表");
        refresh.setPreferredSize(new Dimension(36, 36));
        refresh.setMinimumSize(refresh.getPreferredSize());
        refresh.setMaximumSize(refresh.getPreferredSize());
        refresh.addActionListener(e -> {
            refreshVersions();
            window.toast("版本列表已刷新");
        });

        actions.add(open);
        actions.add(logs);
        actions.add(refresh);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private void updateGreeting() {
        if (greetingLabel == null) return;
        greetingLabel.setText("开始");
    }

    private JPanel buildGrid() {
        JPanel leftCol = new JPanel();
        leftCol.setOpaque(false);
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        heroCard = new HeroCard();
        heroCard.setAlignmentX(LEFT_ALIGNMENT);
        leftCol.add(heroCard);
        leftCol.add(Box.createVerticalStrut(16));
        JComponent player = buildPlayerCard();
        player.setAlignmentX(LEFT_ALIGNMENT);
        leftCol.add(player);
        return new SplitPanel(leftCol, buildVersionCard(), 0.44, 16);
    }

    // ==================== 启动卡片 ====================

    private final class HeroCard extends Card {
        private final Timer bobTimer;
        private float phase;

        HeroCard() {
            super(new BorderLayout(), 0, 24, true);
            withGradient(new Color(0x3E, 0x7D, 0xF7), new Color(0x5B, 0x58, 0xD8));
            setPreferredSize(new Dimension(0, 278));
            setMinimumSize(new Dimension(0, 238));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 278));
            add(buildHeroContent(), BorderLayout.CENTER);
            bobTimer = new Timer(55, e -> {
                if (!isShowing()) return;
                phase += 0.055f;
                repaint();
            });
        }

        @Override public void addNotify() {
            super.addNotify();
            bobTimer.start();
        }

        @Override public void removeNotify() {
            bobTimer.stop();
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = Theme.quality(g);
            Shape clip = cardShape();
            Shape old = g2.getClip();
            g2.clip(clip);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                g2.dispose();
                return;
            }
            g2.setPaint(new RadialGradientPaint(
                    new Point(w - 40, 40), Math.max(w, h) * 0.75f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 255, 255, 26), new Color(255, 255, 255, 0)}));
            g2.fillRect(0, 0, w, h);

            float bob = (float) Math.sin(phase) * 3f;
            float r1 = 130;
            g2.setColor(new Color(255, 255, 255, 12));
            g2.fill(new Ellipse2D.Float(-r1 * 0.45f + bob, h - r1 * 1.1f - bob * 0.6f, r1, r1));
            float r2 = 80;
            g2.setColor(new Color(255, 255, 255, 9));
            g2.fill(new Ellipse2D.Float(w * 0.60f + bob * 0.8f, -r2 * 0.55f, r2, r2));

            // 右侧空白区域的大号 loader 图标；按版本文字宽度自动留出间距
            if (heroLoaderImage != null || heroLoaderGlyph != null) {
                int textW = 0;
                if (versionLabel != null) {
                    FontMetrics fm = versionLabel.getFontMetrics(versionLabel.getFont());
                    textW = fm.stringWidth(versionLabel.getText());
                }
                int available = (w - 28) - (24 + textW + 18);
                int size = Math.min(142, Math.max(86, available));
                int x = w - size - 28;
                int y = (h - size) / 2 - 24;
                if (heroLoaderImage != null) {
                    Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(heroLoaderImage, x, y, size, size, null);
                    if (oldInterp != null) {
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
                    }
                } else {
                    Icons.paint(g2, heroLoaderGlyph, x, y, size, Color.WHITE);
                }
            }

            g2.setClip(old);
            g2.dispose();
        }
    }

    private JComponent buildHeroContent() {
        JPanel content = UiKit.transparent(new BorderLayout(0, 14));
        content.setBorder(new EmptyBorder(20, 24, 20, 24));

        JPanel top = UiKit.transparent(null);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        badges.setOpaque(false);
        badges.setAlignmentX(LEFT_ALIGNMENT);
        typeBadge = new Badge("正式版", new Color(255, 255, 255, 55), Color.WHITE).fontSize(10f);
        loaderBadge = new Badge("原版", new Color(255, 255, 255, 45), Color.WHITE).fontSize(10f);
        readyBadge = new Badge("未选择", new Color(255, 255, 255, 45), Color.WHITE).fontSize(10f);
        badges.add(typeBadge);
        badges.add(loaderBadge);
        badges.add(readyBadge);
        top.add(badges);
        top.add(Box.createVerticalStrut(14));

        versionLabel = new EllipsisLabel("—", Theme.bold(28), Color.WHITE);
        versionLabel.setAlignmentX(LEFT_ALIGNMENT);
        top.add(versionLabel);
        top.add(Box.createVerticalStrut(8));

        heroHint = new EllipsisLabel("在右侧选择一个版本", Theme.font(12f), new Color(255, 255, 255, 200));
        heroHint.setAlignmentX(LEFT_ALIGNMENT);
        top.add(heroHint);
        content.add(top, BorderLayout.CENTER);

        launchBtn = new AccentButton("启动游戏", Icons.PLAY, AccentButton.Variant.WHITE);
        launchBtn.setButtonHeight(46);
        launchBtn.setPaddingX(28);
        launchBtn.setFontSize(15f, true);
        launchBtn.setEnabled(false);
        launchBtn.addActionListener(e -> launch());
        JPanel btnRow = UiKit.transparent(new BorderLayout());
        btnRow.add(launchBtn, BorderLayout.CENTER);
        content.add(btnRow, BorderLayout.SOUTH);

        return content;
    }

    // ==================== 玩家档案 ====================

    private JComponent buildPlayerCard() {
        Card card = new Card(new BorderLayout(0, 12), 18, 18, false);

        JPanel head = UiKit.transparent(new BorderLayout());
        JLabel title = new JLabel("玩家信息");
        title.setFont(Theme.bold(15));
        title.setForeground(Theme.TEXT);
        head.add(title, BorderLayout.WEST);
        head.add(new Badge("离线模式", Theme.SURFACE_SUNK, Theme.TEXT_SUB).fontSize(9.5f),
                BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JPanel content = UiKit.transparent(null);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel userRow = UiKit.transparent(new BorderLayout(12, 0));
        userRow.setAlignmentX(LEFT_ALIGNMENT);
        avatar = new Avatar(settings.username, 54);
        userRow.add(avatar, BorderLayout.WEST);

        JPanel userInfo = UiKit.transparent(null);
        userInfo.setLayout(new BoxLayout(userInfo, BoxLayout.Y_AXIS));
        userInfo.add(Box.createVerticalGlue());

        userField = new TextField(settings.username == null ? "Player" : settings.username, null);
        userField.setFloatingLabel("用户名");
        userField.setFieldHeight(40);
        userField.setAlignmentX(LEFT_ALIGNMENT);
        userField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { savePlayerSettings(); }
        });
        userInfo.add(userField);
        userInfo.add(Box.createVerticalGlue());
        userRow.add(userInfo, BorderLayout.CENTER);
        content.add(userRow);
        content.add(Box.createVerticalStrut(12));

        uuidField = new TextField(settings.uuid == null ? "" : settings.uuid, null);
        uuidField.setFloatingLabel("UUID");
        uuidField.setFloatingSuffix("*", "留空自动生成UUID");
        uuidField.setFieldHeight(40);
        uuidField.setAlignmentX(LEFT_ALIGNMENT);
        uuidField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { savePlayerSettings(); }
        });
        content.add(uuidField);

        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void savePlayerSettings() {
        if (userField == null || uuidField == null) return;
        String name = userField.getText().trim();
        if (name.isEmpty()) name = "Player";
        String uuid = uuidField.getText().trim();
        boolean nameChanged = !name.equals(settings.username);
        boolean uuidChanged = !uuid.equals(settings.uuid);
        if (!nameChanged && !uuidChanged) return;
        settings.username = name;
        settings.uuid = uuid;
        settings.save(window.getConfigFile());
        if (avatar != null) avatar.setName(name);
        if (nameChanged) window.updateUsername(name);
        updateGreeting();
    }

    // ==================== 版本列表 ====================

    private JComponent buildVersionCard() {
        Card card = new Card(new BorderLayout(0, 10), 16, 18, false);

        JPanel head = UiKit.transparent(new BorderLayout());
        JPanel titleBox = UiKit.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel title = new JLabel("版本列表");
        title.setFont(Theme.bold(16));
        title.setForeground(Theme.TEXT);
        titleBox.add(title);
        countBadge = new Badge("0", Theme.SURFACE_SUNK, Theme.TEXT_SUB).fontSize(9.5f);
        titleBox.add(countBadge);
        head.add(titleBox, BorderLayout.WEST);

        IconButton refresh = new IconButton(Icons.REFRESH, "刷新");
        refresh.setPreferredSize(new Dimension(32, 30));
        refresh.setMinimumSize(refresh.getPreferredSize());
        refresh.setMaximumSize(refresh.getPreferredSize());
        refresh.addActionListener(e -> {
            refreshVersions();
            window.toast("版本列表已刷新");
        });
        JPanel right = UiKit.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        right.add(refresh);
        head.add(right, BorderLayout.EAST);

        searchField = new TextField("", "搜索版本…");
        searchField.setLeadingIcon(Icons.SEARCH);
        searchField.setFieldHeight(36);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuildList(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuildList(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuildList(); }
        });

        JPanel top = UiKit.transparent(new BorderLayout(0, 10));
        top.add(head, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        listBox = UiKit.transparent(null);
        listBox.setLayout(new BoxLayout(listBox, BoxLayout.Y_AXIS));
        listBox.setBorder(new EmptyBorder(1, 1, 6, 1));
        listScroll = new SmoothScrollPane(listBox);
        listScroll.setWheelStep(54);
        card.add(listScroll, BorderLayout.CENTER);

        return card;
    }

    public void refreshVersions() {
        new Thread(() -> {
            List<VersionInfo> list;
            try {
                list = scanInstalled();
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() ->
                        window.appendLog("[主页] 扫描版本失败: " + t.getMessage()));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                installed.clear();
                installed.addAll(list);
                if (selectedId == null || findById(selectedId) == null) {
                    selectedId = null;
                    for (VersionInfo v : installed) {
                        if (!v.installing) { selectedId = v.id; break; }
                    }
                    if (selectedId == null && !installed.isEmpty()) {
                        selectedId = installed.get(0).id;
                    }
                }
                rebuildList();
                updateHero();
            });
        }, "Home-VersionScan").start();
    }

    private List<VersionInfo> scanInstalled() {
        List<VersionInfo> result = new ArrayList<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return result;

        try (var stream = Files.newDirectoryStream(versionsDir, Files::isDirectory)) {
            for (Path dir : stream) {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                if (!Files.exists(json)) continue;

                boolean installing = false;
                try { installing = VersionInstaller.isInstalling(dir); }
                catch (Throwable ignored) {}

                String type = "release";
                boolean serverCore = false;
                String inheritsFrom = null;
                String customLoaderVersion = null;
                try {
                    JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    if (obj.has("type") && !obj.get("type").isJsonNull()) {
                        type = obj.get("type").getAsString();
                    }
                    if (obj.has("serverCore") && !obj.get("serverCore").isJsonNull()) {
                        serverCore = obj.get("serverCore").getAsBoolean();
                    }
                    if (obj.has("inheritsFrom") && !obj.get("inheritsFrom").isJsonNull()) {
                        inheritsFrom = obj.get("inheritsFrom").getAsString();
                    }
                    if (obj.has("mcliLoaderVersion") && !obj.get("mcliLoaderVersion").isJsonNull()) {
                        customLoaderVersion = obj.get("mcliLoaderVersion").getAsString();
                    }
                } catch (Exception ignored) {}

                String loaderType = detectLoaderType(id, inheritsFrom);
                String loaderVersion = customLoaderVersion != null
                        ? customLoaderVersion : extractLoaderVersion(id, loaderType);
                String mcVersion = extractMcVersion(id, inheritsFrom);
                String custom = settings.versionNames != null
                        ? settings.versionNames.getOrDefault(id, id) : id;

                result.add(new VersionInfo(id, custom, mcVersion, inheritsFrom,
                        loaderType, loaderVersion, serverCore, installing, type));
            }
        } catch (Exception ignored) {}

        result.sort((a, b) -> versionCompare(b.id, a.id));
        // 有加载器版本时，隐藏它继承的原版基础版本，界面更干净
        Set<String> loaderParents = new HashSet<>();
        for (VersionInfo v : result) {
            if (!v.installing && v.loaderType != null && v.inheritsFrom != null) {
                loaderParents.add(v.inheritsFrom);
            }
        }
        result.removeIf(v -> v.loaderType == null && loaderParents.contains(v.id));
        return result;
    }

    private VersionInfo findById(String id) {
        if (id == null) return null;
        for (VersionInfo v : installed) {
            if (v.id.equals(id)) return v;
        }
        return null;
    }

    private void rebuildList() {
        if (listBox == null) return;
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);

        filtered.clear();
        for (VersionInfo v : installed) {
            if (query.isEmpty()
                    || v.id.toLowerCase(Locale.ROOT).contains(query)
                    || v.displayName.toLowerCase(Locale.ROOT).contains(query)
                    || (v.mcVersion != null && v.mcVersion.toLowerCase(Locale.ROOT).contains(query))) {
                filtered.add(v);
            }
        }

        if (countBadge != null) {
            countBadge.setText(String.valueOf(filtered.size()));
        }

        listBox.removeAll();
        if (filtered.isEmpty()) {
            listBox.add(buildEmpty());
            listBox.add(Box.createVerticalGlue());
        } else {
            for (VersionInfo v : filtered) {
                VersionRow row = new VersionRow(v);
                row.setAlignmentX(LEFT_ALIGNMENT);
                listBox.add(row);
                listBox.add(Box.createVerticalStrut(6));
            }
        }
        listBox.revalidate();
        listBox.repaint();

        if (selectedId != null && filtered.stream().noneMatch(v -> v.id.equals(selectedId))) {
            selectedId = filtered.isEmpty() ? null : filtered.get(0).id;
            updateHero();
        }
    }

    private JComponent buildEmpty() {
        JPanel empty = UiKit.transparent(null);
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        empty.setBorder(new EmptyBorder(60, 10, 40, 10));
        JLabel t = new JLabel("还没有安装任何版本");
        t.setFont(Theme.bold(14));
        t.setForeground(Theme.TEXT_SUB);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.add(t);
        empty.add(Box.createVerticalStrut(4));
        JLabel s = new JLabel("前往「下载」页安装 Minecraft");
        s.setFont(Theme.font(11.5f));
        s.setForeground(Theme.TEXT_MUTED);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);
        empty.add(s);
        return empty;
    }

    private void selectVersion(String id) {
        if (id == null || id.equals(selectedId)) return;
        selectedId = id;
        if (listBox != null) {
            for (Component c : listBox.getComponents()) {
                if (c instanceof VersionRow) c.repaint();
            }
        }
        updateHero();
    }

    private void updateHero() {
        if (versionLabel == null) return;
        VersionInfo v = findById(selectedId);

        if (v == null) {
            versionLabel.setText("—");
            heroLoaderGlyph = null;
            heroLoaderImage = null;
            if (heroCard != null) heroCard.repaint();
            typeBadge.setText("无");
            loaderBadge.setText("原版");
            readyBadge.setText("未选择");
            readyBadge.setColors(new Color(255, 255, 255, 45), Color.WHITE);
            heroHint.setText("在右侧选择一个版本");
            launchBtn.setEnabled(false);
            launchBtn.setText("启动游戏");
            return;
        }

        versionLabel.setText(heroVersionTitle(v));
        heroLoaderImage = loaderImage(v);
        heroLoaderGlyph = heroLoaderImage != null ? null : loaderGlyph(v);
        if (heroCard != null) heroCard.repaint();
        typeBadge.setText(v.serverCore ? "服务端" : typeLabel(v.type));

        if (v.serverCore) {
            loaderBadge.setText("核心");
        } else if (v.loaderType == null) {
            loaderBadge.setText("原版");
        } else {
            loaderBadge.setText(v.loaderType
                    + (v.loaderVersion != null ? " " + v.loaderVersion : ""));
        }

        if (v.installing) {
            readyBadge.setText("安装中");
            readyBadge.setColors(new Color(0xFF, 0xD0, 0x70, 110), Color.WHITE);
        } else {
            readyBadge.setText("就绪");
            readyBadge.setColors(new Color(255, 255, 255, 55), Color.WHITE);
        }

        if (launching) {
            heroHint.setText("正在启动，请稍候…");
        } else if (v.installing) {
            heroHint.setText("版本正在安装，请稍候…");
        } else if (v.serverCore) {
            heroHint.setText("点击启动服务器（局域网/公网）");
        } else {
            heroHint.setText("点击启动游戏");
        }

        // 启动中允许再次点击取消，所以按钮保持可点击
        launchBtn.setEnabled(!v.installing);
        if (launching) {
            launchBtn.setText(launchPercent > 0 ? "启动中 " + launchPercent + "%" : "启动中…");
            launchBtn.setGlyph(Icons.PAUSE);
            launchBtn.setToolTipText("再次点击取消启动");
        } else {
            launchBtn.setText(v.serverCore ? "启动服务端" : "启动游戏");
            launchBtn.setGlyph(Icons.PLAY);
            launchBtn.setToolTipText(null);
        }
    }

    /** 蓝卡大标题：1.20.1 / 1.20.1-forge / 1.20.1-fabric / 1.20.1-server。 */
    private static String heroVersionTitle(VersionInfo v) {
        String base = v.mcVersion;
        if (base == null || base.isBlank()) base = v.id;
        if (base == null || base.isBlank()) base = v.displayName;
        if (base == null || base.isBlank()) return "—";
        base = base.trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if (v.serverCore) {
            if (v.loaderType != null) {
                String suffix = "-" + v.loaderType.toLowerCase(Locale.ROOT);
                if (lower.endsWith(suffix)) return base;
                return base + suffix;
            }
            return base;
        }
        if (v.loaderType == null) return base;
        String suffix = "-" + v.loaderType.toLowerCase(Locale.ROOT);
        if (lower.endsWith(suffix)) return base;
        return base + suffix;
    }

    private static Glyph loaderGlyph(VersionInfo v) {
        if (v == null) return null;
        if (v.serverCore) return Icons.GLOBE;
        if (v.loaderType == null) return Icons.CUBE;
        return switch (v.loaderType) {
            case "Fabric", "Quilt" -> Icons.FABRIC;
            case "Forge", "NeoForge" -> Icons.ANVIL;
            default -> Icons.CUBE;
        };
    }

    /** 使用 PCL 的方块图标：grass / fabric / anvil / neoforge。 */
    private static Image loaderImage(VersionInfo v) {
        if (v == null || v.serverCore) return null;
        String name;
        if (v.loaderType == null) name = "grass";
        else name = switch (v.loaderType) {
            case "Fabric", "Quilt" -> "fabric";
            case "Forge" -> "anvil";
            case "NeoForge" -> "neoforge";
            default -> null;
        };
        if (name == null) return null;
        if (LOADER_IMAGES.containsKey(name)) return LOADER_IMAGES.get(name);
        Image img = null;
        try (var in = HomePanel.class.getResourceAsStream("/icons/" + name + ".png")) {
            if (in != null) img = ImageIO.read(in);
        } catch (Exception ignored) {}
        LOADER_IMAGES.put(name, img);
        return img;
    }

    private static String typeLabel(String type) {
        if (type == null || type.isBlank()) return "正式版";
        return switch (type) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_beta" -> "远古 Beta";
            case "old_alpha" -> "远古 Alpha";
            case "server" -> "服务端";
            default -> type;
        };
    }

    private static String buildSubtitle(VersionInfo v) {
        if (v.serverCore) {
            return "服务端核心" + (v.mcVersion != null ? " · MC " + v.mcVersion : "");
        }
        StringBuilder sb = new StringBuilder();
        if (v.mcVersion != null) sb.append("MC ").append(v.mcVersion);
        if (v.loaderType != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(v.loaderType);
            if (v.loaderVersion != null) sb.append(" ").append(v.loaderVersion);
        }
        if (v.installing) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("安装中…");
        }
        if (sb.length() == 0) sb.append(v.id);
        return sb.toString();
    }

    // ==================== 启动 ====================

    private void launch() {
        if (launching) {
            cancelLaunch();
            return;
        }
        VersionInfo v = findById(selectedId);
        if (v == null || v.installing) return;
        savePlayerSettings();
        if (v.serverCore) launchServer(v);
        else launchClient(v);
    }

    private void launchClient(VersionInfo v) {
        launching = true;
        final int session = ++launchSession;
        updateHero();
        launchPercent = 0;
        launchBtn.setText("启动中…");
        launchBtn.setProgress(0f);
        launchProgressSink = p -> {
            launchPercent = p;
            launchBtn.setProgress(p / 100f);
            launchBtn.setText("启动中 " + p + "%");
        };
        window.setStatus("正在启动：" + v.displayName + "…");

        launchThread = new Thread(() -> {
            try {
                Thread.sleep(80);
                if (session != launchSession) return;

                CountDownLatch windowReady = new CountDownLatch(1);
                Process p = Launcher.launch(v.id, gameDir, settings,
                        s -> {
                            window.appendLog("[游戏] " + s);
                            if (isGameWindowReady(s)) windowReady.countDown();
                        },
                        HomePanel::reportLaunchProgress);

                if (session != launchSession) {
                    killProcess(p);
                    return;
                }
                gameProcess = p;

                // 启动阶段很快就到 30%；接下来等游戏窗口/日志就绪，
                // 不再在进程刚创建时就显示“已启动”。
                long deadline = System.currentTimeMillis() + 20_000L;
                while (p.isAlive() && windowReady.getCount() > 0
                        && System.currentTimeMillis() < deadline) {
                    windowReady.await(100L, TimeUnit.MILLISECONDS);
                }
                if (session != launchSession) return;

                if (!p.isAlive()) {
                    int code;
                    try { code = p.exitValue(); } catch (Throwable t) { code = -1; }
                    final int exit = code;
                    SwingUtilities.invokeLater(() -> {
                        if (session != launchSession) return;
                        launching = false;
                        launchPercent = 0;
                        gameProcess = null;
                        launchProgressSink = null;
                        launchBtn.stopBusyProgress();
                        updateHero();
                        window.setStatus("游戏进程已退出（退出码 " + exit + "）");
                        window.appendLog("[游戏] 进程已退出，退出码 " + exit);
                        if (exit != 0) window.toastError("启动失败，退出码 " + exit);
                    });
                    return;
                }

                boolean ready = windowReady.getCount() == 0;
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    gameProcess = p;
                    launching = false;
                    launchPercent = 0;
                    launchProgressSink = null;
                    launchBtn.setProgress(1f);
                    launchBtn.stopBusyProgress();
                    updateHero();
                    window.setStatus("游戏已启动：" + v.displayName);
                    window.toastSuccess("已启动：" + v.displayName);
                    window.appendLog("[游戏] " + v.displayName + " 已启动"
                            + (ready ? "" : "（等待窗口超时，进程仍在运行）"));
                });

                int exit = p.waitFor();
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    gameProcess = null;
                    launching = false;
                    launchPercent = 0;
                    launchProgressSink = null;
                    updateHero();
                    window.setStatus("游戏已退出（退出码 " + exit + "）");
                    window.appendLog("[游戏] 已退出，退出码 " + exit);
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    launching = false;
                    launchPercent = 0;
                    gameProcess = null;
                    launchProgressSink = null;
                    launchBtn.stopBusyProgress();
                    updateHero();
                    window.toastError("启动失败：" + ex.getMessage());
                    window.appendLog("[游戏] 启动失败：" + ex.getMessage());
                    MainGui.showError("启动失败", ex);
                });
            }
        }, "Game-Launcher");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    /** 日志出现这些行时，说明游戏窗口已经创建/开始渲染。 */
    private static boolean isGameWindowReady(String line) {
        if (line == null) return false;
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("created window")
                || s.contains("openal initialized")
                || s.contains("sound engine started")
                || s.contains("reloading resourcemanager")
                || s.contains("created: ");
    }

    /** 启动中再次点击：立即取消，并结束已经拉起的 Java 进程。 */
    private void cancelLaunch() {
        if (!launching) return;
        launchSession++;
        launching = false;
        launchPercent = 0;
        launchProgressSink = null;
        Process p = gameProcess;
        gameProcess = null;
        Thread t = launchThread;
        launchThread = null;
        if (p != null) killProcess(p);
        if (t != null) t.interrupt();
        launchBtn.stopBusyProgress();
        updateHero();
        window.setStatus("已取消启动");
        window.toast("已取消启动");
    }

    private static void killProcess(Process process) {
        if (process == null) return;
        try { process.destroy(); } catch (Throwable ignored) {}
        try { process.destroyForcibly(); } catch (Throwable ignored) {}
    }

    private void launchServer(VersionInfo v) {
        launching = true;
        final int session = ++launchSession;
        launchPercent = 0;
        updateHero();
        launchBtn.setText("启动中…");
        launchBtn.setProgress(0f);
        launchProgressSink = p -> {
            launchPercent = p;
            launchBtn.setProgress(p / 100f);
            launchBtn.setText("启动中 " + p + "%");
        };
        window.setStatus("正在启动服务端：" + v.displayName + "…");

        launchThread = new Thread(() -> {
            try {
                CountDownLatch serverReady = new CountDownLatch(1);
                Process p = Launcher.launchServer(v.id, gameDir, settings,
                        s -> {
                            window.appendLog(s);
                            if (isServerReady(s)) serverReady.countDown();
                        },
                        HomePanel::reportLaunchProgress);

                if (session != launchSession) {
                    killProcess(p);
                    return;
                }
                gameProcess = p;

                long deadline = System.currentTimeMillis() + 30_000L;
                while (p.isAlive() && serverReady.getCount() > 0
                        && System.currentTimeMillis() < deadline) {
                    serverReady.await(100L, TimeUnit.MILLISECONDS);
                }
                if (session != launchSession) {
                    killProcess(p);
                    return;
                }

                if (!p.isAlive()) {
                    int code;
                    try { code = p.exitValue(); } catch (Throwable t) { code = -1; }
                    final int exit = code;
                    SwingUtilities.invokeLater(() -> {
                        if (session != launchSession) return;
                        launching = false;
                        launchPercent = 0;
                        gameProcess = null;
                        launchProgressSink = null;
                        launchBtn.stopBusyProgress();
                        updateHero();
                        window.setStatus("服务端进程已退出（退出码 " + exit + "）");
                        window.appendLog("[服务端] 进程已退出，退出码 " + exit);
                        if (exit != 0) window.toastError("服务端启动失败，退出码 " + exit);
                    });
                    return;
                }

                boolean readySeen = serverReady.getCount() == 0;
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    gameProcess = p;
                    launching = false;
                    launchPercent = 0;
                    launchProgressSink = null;
                    launchBtn.setProgress(1f);
                    launchBtn.stopBusyProgress();
                    updateHero();
                    // 带上显示名，关闭窗口时能显示「server.jar · 运行中」
                    ServerConsole.attach(p, v.displayName);
                    window.setStatus("服务端已启动：" + v.displayName);
                    window.toastSuccess("服务端已启动");
                    window.appendLog("[服务端] " + v.displayName + " 已启动"
                            + (readySeen ? "" : "（等待日志超时，进程仍在运行）"));
                });

                int exit = p.waitFor();
                ServerConsole.detachIf(p);
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    gameProcess = null;
                    launching = false;
                    launchPercent = 0;
                    launchProgressSink = null;
                    updateHero();
                    window.setStatus("服务端已退出（退出码 " + exit + "）");
                    window.appendLog("[服务端] 已退出，退出码 " + exit);
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    if (session != launchSession) return;
                    launching = false;
                    launchPercent = 0;
                    gameProcess = null;
                    launchProgressSink = null;
                    launchBtn.stopBusyProgress();
                    updateHero();
                    window.toastError("服务端启动失败：" + ex.getMessage());
                    window.appendLog("[服务端] 启动失败：" + ex.getMessage());
                    MainGui.showError("服务端启动失败", ex);
                });
            }
        }, "Server-Launcher");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    private static boolean isServerReady(String line) {
        if (line == null) return false;
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("done (") || s.contains("for help");
    }

    // ==================== 版本行 ====================

    private final class VersionRow extends JComponent {
        private final VersionInfo info;
        private final Animator.AnimatedValue hover;

        VersionRow(VersionInfo info) {
            this.info = info;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(info.displayName);
            setAlignmentX(LEFT_ALIGNMENT);
            hover = new Animator.AnimatedValue(0, v -> repaint());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover.set(1, 140); }
                @Override public void mouseExited(MouseEvent e) { hover.set(0, 180); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) selectVersion(info.id);
                }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(0, 54); }
        @Override public Dimension getMinimumSize() { return new Dimension(0, 54); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 54); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Theme.quality(g);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) { g2.dispose(); return; }

            boolean selected = info.id.equals(selectedId);
            float hv = hover.value();
            Color bg;
            if (selected) bg = Theme.PRIMARY_LIGHT;
            else if (hv > 0.01f) bg = Theme.alpha(Theme.PRIMARY, (int) (12 * hv));
            else bg = Theme.SURFACE;

            g2.setColor(bg);
            g2.fill(Theme.roundRect(0, 0, w, h, 11));
            g2.setColor(selected ? Theme.PRIMARY_SOFT : Theme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, 11));

            int s = 34;
            int ix = 10, iy = (h - s) / 2;
            g2.setColor(selected ? Theme.PRIMARY : Theme.PRIMARY_LIGHT);
            g2.fill(Theme.roundRect(ix, iy, s, s, 9));
            Icons.paint(g2, info.serverCore ? Icons.BOX : Icons.CUBE,
                    ix + (s - 18) / 2, iy + (s - 18) / 2, 18,
                    selected ? Color.WHITE : Theme.PRIMARY_DARK);

            int tx = ix + s + 12;
            int right = 16;

            g2.setFont(Theme.bold(13));
            g2.setColor(Theme.TEXT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(Theme.ellipsize(fm, info.displayName, w - tx - right), tx, 23);

            g2.setFont(Theme.font(10.5f));
            g2.setColor(Theme.TEXT_MUTED);
            FontMetrics dfm = g2.getFontMetrics();
            String sub = buildSubtitle(info);
            g2.drawString(Theme.ellipsize(dfm, sub, w - tx - right - 14), tx, 39);

            if (info.installing) {
                g2.setColor(Theme.AMBER);
                g2.fill(new Ellipse2D.Float(w - 18, h / 2f - 3, 6, 6));
            } else if (selected) {
                Icons.paint(g2, Icons.CHEVRON_RIGHT, w - 22, h / 2 - 7, 14, Theme.PRIMARY);
            }
            g2.dispose();
        }
    }

    // ==================== 版本信息 ====================

    private static final class VersionInfo {
        final String id;
        final String displayName;
        final String mcVersion;
        final String inheritsFrom;
        final String loaderType;
        final String loaderVersion;
        final boolean serverCore;
        final boolean installing;
        final String type;

        VersionInfo(String id, String displayName, String mcVersion,
                    String inheritsFrom, String loaderType, String loaderVersion,
                    boolean serverCore, boolean installing, String type) {
            this.id = id;
            this.displayName = displayName;
            this.mcVersion = mcVersion;
            this.inheritsFrom = inheritsFrom;
            this.loaderType = loaderType;
            this.loaderVersion = loaderVersion;
            this.serverCore = serverCore;
            this.installing = installing;
            this.type = type;
        }
    }

    // ==================== 版本号工具 ====================

    private static String detectLoaderType(String id, String inheritsFrom) {
        // 先看目录名本身：自定义目录名是“游戏版本-加载器”
        if (id != null) {
            String lid = id.toLowerCase(Locale.ROOT);
            if (lid.endsWith("-neoforge")) return "NeoForge";
            if (lid.endsWith("-forge")) return "Forge";
            if (lid.endsWith("-fabric")) return "Fabric";
            if (lid.endsWith("-quilt")) return "Quilt";
            if (lid.startsWith("fabric-loader-")) return "Fabric";
            if (lid.startsWith("quilt-loader-")) return "Quilt";
            if (lid.startsWith("neoforge-")) return "NeoForge";
            if (lid.contains("-forge-") || lid.startsWith("forge-")) return "Forge";
        }
        String source = (inheritsFrom != null ? inheritsFrom : id);
        if (source == null) return null;
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric-loader-")) return "Fabric";
        if (lower.startsWith("quilt-loader-")) return "Quilt";
        if (lower.contains("-forge-") || lower.startsWith("forge-")) return "Forge";
        if (lower.startsWith("neoforge-")) return "NeoForge";
        if (inheritsFrom != null && !inheritsFrom.equals(id)) {
            String in = inheritsFrom.toLowerCase(Locale.ROOT);
            if (in.startsWith("fabric-loader-")) return "Fabric";
            if (in.startsWith("quilt-loader-")) return "Quilt";
            if (in.contains("-forge-") || in.startsWith("forge-")) return "Forge";
            if (in.startsWith("neoforge-")) return "NeoForge";
        }
        return null;
    }

    private static String extractLoaderVersion(String id, String loaderType) {
        if (id == null || loaderType == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        try {
            return switch (loaderType) {
                case "Fabric" -> {
                    if (lower.startsWith("fabric-loader-")) {
                        String rest = id.substring("fabric-loader-".length());
                        int dash = rest.indexOf('-');
                        yield dash > 0 ? rest.substring(0, dash) : rest;
                    }
                    yield null;
                }
                case "Quilt" -> {
                    if (lower.startsWith("quilt-loader-")) {
                        String rest = id.substring("quilt-loader-".length());
                        int dash = rest.indexOf('-');
                        yield dash > 0 ? rest.substring(0, dash) : rest;
                    }
                    yield null;
                }
                case "Forge" -> {
                    int idx = lower.indexOf("-forge-");
                    if (idx > 0) yield id.substring(idx + "-forge-".length());
                    yield null;
                }
                case "NeoForge" -> {
                    if (lower.startsWith("neoforge-")) yield id.substring("neoforge-".length());
                    yield null;
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractMcVersion(String id, String inheritsFrom) {
        String source = (inheritsFrom != null ? inheritsFrom : id);
        if (source != null) {
            Matcher m = MC_VER_PATTERN.matcher(source);
            if (m.find()) return m.group(1);
        }
        if (id != null && !id.equals(source)) {
            Matcher m = MC_VER_PATTERN.matcher(id);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static int versionCompare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = pa[i], y = pb[i];
            try {
                int nx = Integer.parseInt(x);
                int ny = Integer.parseInt(y);
                if (nx != ny) return Integer.compare(nx, ny);
            } catch (NumberFormatException e) {
                int c = x.compareTo(y);
                if (c != 0) return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }
}

// ==================================================================
// 同包共享的辅助类（原本就在 HomePanel.java 里）
// ==================================================================

/**
 * 统一风格圆角输入框：placeholder、左侧图标、可调高度。
 * 支持 Material 风格浮动标签与“选填星号”提示。
 */
class TextField extends JTextField {
    private String placeholder;
    private String floatingLabel;
    private String floatingSuffix;
    private String floatingSuffixTooltip;
    private boolean floating;
    private Glyph leadingIcon;
    private int fieldHeight = 40;
    private int radius = 11;
    private final Animator.AnimatedValue focusAnim;
    private final Animator.AnimatedValue floatAnim;

    public TextField(String text) {
        this(text, null);
    }

    public TextField(String text, String placeholder) {
        super(text == null ? "" : text);
        this.placeholder = placeholder;
        setOpaque(false);
        setFont(Theme.font(13));
        setForeground(Theme.TEXT);
        setCaretColor(Theme.PRIMARY);
        setSelectionColor(Theme.PRIMARY_LIGHT);
        setSelectedTextColor(Theme.TEXT);
        setBorder(new EmptyBorder(0, 13, 0, 13));
        setPreferredSize(new Dimension(220, fieldHeight));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldHeight));
        setMinimumSize(new Dimension(80, fieldHeight));
        focusAnim = new Animator.AnimatedValue(0, v -> repaint());
        floatAnim = new Animator.AnimatedValue(0, v -> {
            updateBorder();
            repaint();
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                focusAnim.set(1, 150);
                updateFloatingState(true);
            }
            @Override public void focusLost(FocusEvent e) {
                focusAnim.set(0, 180);
                updateFloatingState(true);
            }
        });
        getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateFloatingState(true); repaint(); }
            @Override public void removeUpdate(DocumentEvent e) { updateFloatingState(true); repaint(); }
            @Override public void changedUpdate(DocumentEvent e) { updateFloatingState(true); repaint(); }
        });
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    public String getPlaceholder() { return placeholder; }

    /** 设置浮动标签。设置后 placeholder 不再显示。 */
    public void setFloatingLabel(String label) {
        this.floatingLabel = label;
        updateFloatingState(false);
        repaint();
    }

    /** 设置标签后的选填星号，例如 "*"，以及悬停星号时的提示。 */
    public void setFloatingSuffix(String suffix, String tooltip) {
        this.floatingSuffix = suffix;
        this.floatingSuffixTooltip = tooltip;
        repaint();
    }

    public void setLeadingIcon(Glyph icon) {
        this.leadingIcon = icon;
        updateBorder();
        repaint();
    }

    public void setFieldHeight(int h) {
        this.fieldHeight = Math.max(24, h);
        setPreferredSize(new Dimension(getPreferredSize().width, fieldHeight));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldHeight));
        setMinimumSize(new Dimension(80, fieldHeight));
        updateBorder();
        revalidate();
        repaint();
    }

    public int getFieldHeight() { return fieldHeight; }

    public void setRadius(int r) {
        this.radius = r;
        repaint();
    }

    private void updateFloatingState(boolean animate) {
        boolean shouldFloat = floatingLabel != null
                && (isFocusOwner() || !getText().isEmpty());
        floating = shouldFloat;
        if (floatAnim != null) {
            if (animate) {
                floatAnim.set(shouldFloat ? 1 : 0, 180);
            } else {
                floatAnim.snap(shouldFloat ? 1 : 0);
            }
        }
        updateBorder();
        repaint();
    }

    private void updateBorder() {
        int left = leadingIcon != null ? 38 : 13;
        float t = floatAnim == null ? (floating ? 1f : 0f) : floatAnim.value();
        int top = Math.round(14 * t);
        Insets old = getInsets();
        if (old.top != top || old.left != left) {
            setBorder(new EmptyBorder(top, left, 0, 13));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(80, d.width), fieldHeight);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, fieldHeight);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Theme.quality(g);
        int w = getWidth(), h = getHeight();
        boolean en = isEnabled();
        float fv = focusAnim.value();
        g2.setColor(en ? Theme.SURFACE_ALT
                : (Theme.isDark() ? Theme.SURFACE_SUNK : new Color(0xF2, 0xF4, 0xF7)));
        g2.fill(Theme.roundRect(0, 0, w, h, radius));
        Color border;
        if (isFocusOwner()) border = Theme.PRIMARY;
        else border = Theme.mix(Theme.BORDER_STRONG, Theme.PRIMARY, fv * 0.6f);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(isFocusOwner() ? 1.6f : 1f));
        g2.draw(Theme.roundRect(0.5f, 0.5f, w - 1, h - 1, radius));

        int topInset = Math.round(14 * (floatAnim == null ? 0f : floatAnim.value()));
        if (leadingIcon != null) {
            Icons.paint(g2, leadingIcon, 12, (h + topInset - 16) / 2, 16,
                    en ? Theme.TEXT_MUTED
                            : Theme.mix(Theme.TEXT_MUTED, Theme.SURFACE, 0.45f));
        }

        if (floatingLabel != null) {
            float t = floatAnim == null ? (floating ? 1f : 0f) : floatAnim.value();
            Font labelFont = Theme.font(13f - 3f * t);
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();
            Color labelColor = Theme.mix(
                    Theme.isDark() ? Theme.TEXT_MUTED : new Color(0xB0, 0xB8, 0xC4),
                    isFocusOwner() ? Theme.PRIMARY : Theme.TEXT_MUTED, t);
            int labelX = leadingIcon != null ? 38 : 13;
            float centerBaseline = (h - fm.getHeight()) / 2f + fm.getAscent();
            float floatBaseline = (14 - fm.getHeight()) / 2f + fm.getAscent();
            int labelY = Math.round(centerBaseline + (floatBaseline - centerBaseline) * t);
            g2.setColor(labelColor);
            g2.drawString(floatingLabel, labelX, labelY);
            if (floatingSuffix != null && !floatingSuffix.isEmpty()) {
                int sx = labelX + fm.stringWidth(floatingLabel) + 2;
                g2.setColor(Theme.RED);
                g2.drawString(floatingSuffix, sx, labelY);
            }
        }

        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (floatingLabel == null && placeholder != null && !placeholder.isEmpty()
                && getText().isEmpty() && !isFocusOwner()) {
            Graphics2D g2 = Theme.quality(g);
            g2.setFont(getFont());
            g2.setColor(Theme.isDark() ? Theme.TEXT_MUTED : new Color(0xB0, 0xB8, 0xC4));
            Insets ins = getInsets();
            FontMetrics fm = g2.getFontMetrics();
            int x = ins.left;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(placeholder, x, y);
            g2.dispose();
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        // JTextComponent.getToolTipText(MouseEvent) 会解引用 event，传 null 会 NPE
        if (event == null) return getToolTipText();
        if (floatingSuffix != null && !floatingSuffix.isEmpty() && floatingLabel != null
                && isOverSuffix(event.getX(), event.getY())) {
            return floatingSuffixTooltip;
        }
        return super.getToolTipText(event);
    }

    private boolean isOverSuffix(int x, int y) {
        if (floatingSuffix == null || floatingSuffix.isEmpty() || floatingLabel == null) return false;
        float t = floatAnim == null ? (floating ? 1f : 0f) : floatAnim.value();
        Font f = Theme.font(13f - 3f * t);
        FontMetrics fm = getFontMetrics(f);
        int labelX = leadingIcon != null ? 38 : 13;
        int labelWidth = fm.stringWidth(floatingLabel);
        int suffixX = labelX + labelWidth + 2;
        int suffixWidth = fm.stringWidth(floatingSuffix);

        float centerTop = (getHeight() - fm.getHeight()) / 2f;
        float floatTop = (14 - fm.getHeight()) / 2f;
        int suffixTop = Math.round(centerTop + (floatTop - centerTop) * t);
        int suffixBottom = suffixTop + fm.getHeight();

        return x >= suffixX && x <= suffixX + suffixWidth + 2
                && y >= suffixTop && y <= suffixBottom;
    }
}

/**
 * 平滑滚动面板：接管滚轮事件，按 step 缓存目标值，用 Timer 缓动追。
 */
class SmoothScrollPane extends JScrollPane {
    private int wheelStep = 48;
    private float target;
    private Timer timer;

    public SmoothScrollPane(Component view) {
        super(view);
        setOpaque(false);
        getViewport().setOpaque(false);
        setBorder(null);
        setViewportBorder(null);
        setWheelScrollingEnabled(false);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_AS_NEEDED);
        JScrollBar bar = getVerticalScrollBar();
        bar.setUnitIncrement(16);
        bar.setBlockIncrement(96);
        bar.setPreferredSize(new Dimension(9, 0));
        bar.setUI(new MinimalScrollBarUI());
        addMouseWheelListener(this::handleWheel);
    }

    public void setWheelStep(int step) {
        this.wheelStep = Math.max(8, step);
    }

    public void scrollToTop() {
        JScrollBar bar = getVerticalScrollBar();
        bar.setValue(0);
        target = 0;
    }

    public void snapToBottom() {
        JScrollBar bar = getVerticalScrollBar();
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        bar.setValue(max);
        target = max;
    }

    public boolean isNearBottom() {
        JScrollBar bar = getVerticalScrollBar();
        int max = bar.getMaximum() - bar.getVisibleAmount();
        return bar.getValue() >= max - 24;
    }

    private void handleWheel(MouseWheelEvent e) {
        if (e.isShiftDown()) return;
        JScrollBar bar = getVerticalScrollBar();
        int cur = bar.getValue();
        if (Math.abs(cur - target) > bar.getVisibleAmount()) {
            target = cur;
        }
        float delta = (float) e.getPreciseWheelRotation() * wheelStep;
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        target = Math.max(0, Math.min(max, target + delta));
        startAnim();
        e.consume();
    }

    private void startAnim() {
        if (timer == null) {
            timer = new Timer(15, e -> tick());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) timer.start();
    }

    private void tick() {
        JScrollBar bar = getVerticalScrollBar();
        int cur = bar.getValue();
        float diff = target - cur;
        if (Math.abs(diff) < 0.5f) {
            bar.setValue(Math.round(target));
            timer.stop();
            return;
        }
        int step = (int) (diff * 0.28f);
        if (step == 0) step = diff > 0 ? 1 : -1;
        bar.setValue(cur + step);
    }

    private static final class MinimalScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = Theme.BORDER_STRONG;
            trackColor = Theme.SURFACE;
        }
        @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
        private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !c.isEnabled()) return;
            Graphics2D g2 = Theme.quality(g);
            boolean hover = isThumbRollover();
            int alpha = hover ? 230 : 160;
            g2.setColor(Theme.alpha(Theme.BORDER_STRONG, alpha));
            int inset = 3;
            int rr = Math.max(1, (r.width - inset * 2) / 2);
            g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                    r.x + inset, r.y + 2, r.width - inset * 2, r.height - 4, rr * 2f, rr * 2f));
            g2.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            // 无轨道背景
        }
    }
}

/**
 * 按比例横向分割左右两列，无拖动条。
 */
class SplitPanel extends JPanel {
    private final Component left;
    private final Component right;
    private final double ratio;
    private final int gap;

    public SplitPanel(Component left, Component right, double ratio, int gap) {
        this.left = left;
        this.right = right;
        this.ratio = Math.max(0.05, Math.min(0.95, ratio));
        this.gap = Math.max(0, gap);
        setOpaque(false);
        setLayout(null);
        if (left != null) add(left);
        if (right != null) add(right);
    }

    @Override public void doLayout() {
        int w = getWidth(), h = getHeight();
        int avail = Math.max(0, w - gap);
        int lw = Math.round(avail * (float) ratio);
        int rw = Math.max(0, avail - lw);
        if (left != null) left.setBounds(0, 0, lw, h);
        if (right != null) right.setBounds(lw + gap, 0, rw, h);
    }

    @Override public Dimension getPreferredSize() {
        Dimension l = left == null ? new Dimension(0, 0) : left.getPreferredSize();
        Dimension r = right == null ? new Dimension(0, 0) : right.getPreferredSize();
        int h = Math.max(l.height, r.height);
        return new Dimension(l.width + gap + r.width, h);
    }

    @Override public Dimension getMinimumSize() {
        return getPreferredSize();
    }
}
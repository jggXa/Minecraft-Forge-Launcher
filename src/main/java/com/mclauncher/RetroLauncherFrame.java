package com.mclauncher;import javax.swing.*;import java.awt.*;import java.awt.event.ActionEvent;import java.awt.event.MouseAdapter;import java.awt.event.MouseEvent;import java.awt.event.WindowAdapter;import java.awt.event.WindowEvent;import java.io.File;
public class RetroLauncherFrame extends JFrame {
    private final Runnable onExit;
    private final LookAndFeel previousLaf;
    private long lastClickTime = 0;
    private int clickCount = 0;

    public RetroLauncherFrame(Runnable onExit) {
        this.onExit = onExit;
        this.previousLaf = UIManager.getLookAndFeel();

        // 临时使用 Metal 风格；关闭窗口时恢复原来的 LAF
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception ignored) {}

        setTitle(BuildInfo.APP_NAME + " - Classic");
        setSize(420, 320);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                closeAndReturn();
            }
        });
        setLayout(new BorderLayout());

        // 顶部区域：咖啡图标
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 15));
        topPanel.setBackground(new Color(0xEEEEEE));

        JComponent coffeeIcon = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 画一个棕色圆角矩形当杯子
                g2.setColor(new Color(0x8B, 0x45, 0x13));
                g2.fillRoundRect(5, 10, 30, 35, 10, 10);
                // 画个白色的杯把手
                g2.setStroke(new BasicStroke(4f));
                g2.setColor(new Color(0xEEEEEE));
                g2.drawOval(28, 18, 15, 15);
                // 画点热气
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(0xCCCCCC));
                g2.drawArc(10, -5, 10, 15, 90, 180);
                g2.drawArc(20, -5, 10, 15, 90, 180);
                g2.dispose();
            }
        };
        coffeeIcon.setPreferredSize(new Dimension(45, 45));
        coffeeIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        topPanel.add(coffeeIcon);
        JLabel titleLabel = new JLabel("Java Edition Launcher");
        titleLabel.setFont(new Font("Serif", Font.BOLD, 18));
        topPanel.add(titleLabel);
        add(topPanel, BorderLayout.NORTH);

        // 中间提示文字
        JTextArea textArea = new JTextArea();
        textArea.setText("\n  Welcome to the Classic Interface.\n\n" +
                "  [Hint] Click the coffee cup 5 times\n" +
                "  to enter the CLI mode.\n\n" +
                "  [Hint] Press Ctrl + O to go back.");
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setBackground(new Color(0xEEEEEE));
        add(textArea, BorderLayout.CENTER);

        // 绑定 Ctrl + O 返回上一级
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK), "exit");
        getRootPane().getActionMap().put("exit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeAndReturn();
            }
        });

        // 咖啡图标连点 5 次
        coffeeIcon.addMouseListener(new MouseAdapter() {
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
                    enterCliMode();
                }
            }
        });

        setVisible(true);
    }

    private void closeAndReturn() {
        dispose();
        if (previousLaf != null) {
            try {
                UIManager.setLookAndFeel(previousLaf);
            } catch (Exception ignored) {}
        }
        if (onExit != null) onExit.run();
    }

    private void enterCliMode() {
        // 在 jpackage 打包后，jar 包通常在本级目录的 app 文件夹下
        File jarFile = new File("app/mc-launcher.jar");
        String classpath = jarFile.exists() ? jarFile.getAbsolutePath() : "target/mc-launcher.jar";

        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";

        try {
            new ProcessBuilder(
                    "cmd.exe", "/c", "start", "",
                    javaExe, "-cp", classpath, "com.mclauncher.Main"
            ).start();

            // 弹窗提示
            JOptionPane.showMessageDialog(this,
                    "已启动 CLI 模式！\n请在弹出的黑色终端窗口中操作。\n\n在终端输入 ^O 即可退出并返回。",
                    "彩蛋触发", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "启动 CLI 失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
}
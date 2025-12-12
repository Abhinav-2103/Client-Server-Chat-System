import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

public class ChatClient {

    // ====== Networking ======
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private String serverHost;
    private String password;
    private Socket socket;

    private boolean loggedIn = false;
    private boolean typingSent = false;
    private Timer typingTimer;

    // ====== UI main ======
    private JFrame frame;
    private JPanel messagesPanel;
    private JScrollPane messagesScroll;
    private JTextField textField;
    private JButton sendBtn;
    private JButton sendFileBtn;
    private JButton emojiBtn;
    private JButton themeBtn;
    private JButton bgBtn;

    // Panels for theme
    private JPanel leftPanel;
    private GlassHeaderPanel headerPanel;
    private JPanel inputPanel;
    private JPanel messagesWrapper;
    private JScrollPane userScroll;

    private DefaultListModel<String> userListModel = new DefaultListModel<>();
    private JList<String> userList = new JList<>(userListModel);

    private JLabel typingLabel = new JLabel(" ");
    private JLabel chatTitleLabel = new JLabel("Group Chat");
    private JLabel chatStatusLabel = new JLabel("Offline");

    private boolean darkMode = false;

    // Client-side history
    private File historyFile;
    private PrintWriter historyOut;
    private boolean loadingHistory = false;

    // Emojis for picker
    private static final String[] EMOJIS = {
            "😀","😁","😂","🤣","😃","😄","😅","😆",
            "😉","😊","😋","😎","😍","😘","😗","😙",
            "😚","🙂","🤗","🤔","😐","😑","😶","🙄",
            "😏","😣","😥","😮","🤐","😯","😪","😫",
            "😴","😌","😛","😜","😝","🤤","😒","😓",
            "😔","😕","🙃","🤑","😲","☹","🙁","😖",
            "😞","😟","😤","😢","😭","😦","😧","😨",
            "😩","😰","😱","😳","😵","😡","😠","❤"
    };

    private static final Font EMOJI_FONT = new Font("Segoe UI Emoji", Font.PLAIN, 13);

    // ===== Light (Messenger style) =====
    private static final Color WA_GREEN = new Color(0, 132, 255);         // header blue
    private static final Color WA_BG = new Color(240, 242, 245);          // chat bg
    private static final Color WA_LIGHT_GREEN = new Color(0, 132, 255);   // my bubble (blue)
    private static final Color LIGHT_OTHER_BUBBLE = new Color(228, 230, 235); // others' bubble (grey)

    // ===== Dark palette (Messenger-ish) =====
    private static final Color DARK_FRAME_BG   = new Color(10, 14, 19);
    private static final Color DARK_CHAT_BG    = new Color(20, 27, 33);
    private static final Color DARK_SIDE_BG    = new Color(13, 20, 26);
    private static final Color DARK_INPUT_BG   = new Color(20, 27, 33);

    private static final Color DARK_MY_BUBBLE    = new Color(0, 132, 255); // blue
    private static final Color DARK_OTHER_BUBBLE = new Color(36, 37, 38);  // dark grey

    // ====== Constructor ======
    public ChatClient(String serverHost, String username, String password) throws Exception {
        this.serverHost = serverHost;
        this.username = username;
        this.password = password;

        buildGUI();
        connectToServer();

        typingTimer = new Timer(2000, e -> {
            if (typingSent && loggedIn) {
                out.println("/typing stop");
                typingSent = false;
            }
        });
        typingTimer.setRepeats(false);
    }

    // ====== BackgroundPanel (for tiled/scaled backgrounds) ======
    private class BackgroundPanel extends JPanel {
        private BufferedImage backgroundImage;
        private boolean tile = false;         // if true, tiles image
        private float overlayAlpha = 0.18f;   // translucent overlay (0 = none)
        private Color overlayColor = Color.WHITE;

        public BackgroundPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        public void setBackgroundImage(BufferedImage img, boolean tileMode) {
            this.backgroundImage = img;
            this.tile = tileMode;
            revalidate();
            repaint();
        }

        public void clearBackgroundImage() {
            this.backgroundImage = null;
            repaint();
        }

        public void setOverlay(float alpha, Color color) {
            this.overlayAlpha = alpha;
            this.overlayColor = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();
                if (backgroundImage != null) {
                    if (tile) {
                        for (int x = 0; x < w; x += backgroundImage.getWidth()) {
                            for (int y = 0; y < h; y += backgroundImage.getHeight()) {
                                g2.drawImage(backgroundImage, x, y, this);
                            }
                        }
                    } else {
                        double imgW = backgroundImage.getWidth();
                        double imgH = backgroundImage.getHeight();
                        double scale = Math.max((double) w / imgW, (double) h / imgH);
                        int nw = (int) Math.round(imgW * scale);
                        int nh = (int) Math.round(imgH * scale);
                        int x = (w - nw) / 2;
                        int y = (h - nh) / 2;
                        g2.drawImage(backgroundImage, x, y, nw, nh, this);
                    }
                }
                if (overlayAlpha > 0f) {
                    Composite old = g2.getComposite();
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlayAlpha));
                    g2.setColor(overlayColor);
                    g2.fillRect(0, 0, w, h);
                    g2.setComposite(old);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    // ====== Build GUI ======
    private void buildGUI() {
        frame = new JFrame("ChatBox");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(950, 600);
        frame.setLocationRelativeTo(null);

        UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TextField.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("List.font", new Font("Segoe UI", Font.PLAIN, 13));

        // LEFT
        leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(260, 0));

        GlassHeaderPanel leftHeader = new GlassHeaderPanel();
        leftHeader.setLayout(new BorderLayout());
        leftHeader.setBackground(WA_GREEN);
        leftHeader.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel appTitle = new JLabel(" ChatBox");
        appTitle.setForeground(Color.WHITE);
        appTitle.setFont(appTitle.getFont().deriveFont(Font.BOLD, 16f));
        leftHeader.add(appTitle, BorderLayout.WEST);
        leftPanel.add(leftHeader, BorderLayout.NORTH);

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setBorder(new EmptyBorder(5, 5, 5, 5));
        userScroll = new JScrollPane(userList);
        leftPanel.add(userScroll, BorderLayout.CENTER);

        JLabel usersLabel = new JLabel("  Online Users");
        usersLabel.setBorder(new EmptyBorder(4, 4, 4, 4));
        leftPanel.add(usersLabel, BorderLayout.SOUTH);

        // RIGHT
        JPanel rightPanel = new JPanel(new BorderLayout());

        headerPanel = new GlassHeaderPanel();
        headerPanel.setLayout(new BorderLayout());
        headerPanel.setBackground(WA_GREEN);
        headerPanel.setBorder(new EmptyBorder(6, 10, 6, 10));

        chatTitleLabel.setForeground(Color.WHITE);
        chatTitleLabel.setFont(chatTitleLabel.getFont().deriveFont(Font.BOLD, 15f));
        chatStatusLabel.setForeground(new Color(220, 240, 255));
        chatStatusLabel.setFont(chatStatusLabel.getFont().deriveFont(11f));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(chatTitleLabel);
        titleBox.add(chatStatusLabel);
        headerPanel.add(titleBox, BorderLayout.WEST);

        typingLabel.setForeground(new Color(220, 255, 220));
        typingLabel.setFont(typingLabel.getFont().deriveFont(11f));
        headerPanel.add(typingLabel, BorderLayout.SOUTH);

        rightPanel.add(headerPanel, BorderLayout.NORTH);

        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setOpaque(false);

        // Use BackgroundPanel instead of plain JPanel so we can set tiled background
        messagesWrapper = new BackgroundPanel();
        messagesWrapper.setBorder(new EmptyBorder(8, 8, 8, 8));
        messagesWrapper.setBackground(WA_BG);
        messagesWrapper.add(messagesPanel, BorderLayout.NORTH);

        // Load your Windows image path and use tiled background for messages area
        try {
            File bgFile = new File("D:\\Project 4\\istockphoto-2177401929-1024x1024.jpg");
            BufferedImage bg = loadBackgroundImage(bgFile, 1600); // limit max side to 1600 px
            if (bg != null && messagesWrapper instanceof BackgroundPanel) {
                BackgroundPanel bp = (BackgroundPanel) messagesWrapper;
                bp.setBackgroundImage(bg, true); // tiled pattern for messages area
                if (darkMode) bp.setOverlay(0.28f, Color.BLACK);
                else bp.setOverlay(0.14f, Color.WHITE);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        messagesScroll = new JScrollPane(messagesWrapper);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);
        messagesScroll.getViewport().setBackground(WA_BG);
        rightPanel.add(messagesScroll, BorderLayout.CENTER);

        // Input area
        inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
        inputPanel.setBackground(new Color(245, 245, 245));

        textField = new JTextField();
        textField.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        textField.setFont(EMOJI_FONT);

        emojiBtn = new JButton(":)");
        emojiBtn.setFocusPainted(false);
        emojiBtn.setMargin(new Insets(2, 8, 2, 8));

        sendFileBtn = new JButton("File");
        sendFileBtn.setFocusPainted(false);
        sendFileBtn.setMargin(new Insets(2, 8, 2, 8));

        sendBtn = new JButton("Send");
        sendBtn.setFocusPainted(false);

        themeBtn = new JButton("Dark");
        themeBtn.setFocusPainted(false);
        themeBtn.setMargin(new Insets(2, 6, 2, 6));

        bgBtn = new JButton("BG");
        bgBtn.setFocusPainted(false);
        bgBtn.setMargin(new Insets(2, 6, 2, 6));
        bgBtn.setToolTipText("Change chat background (right-click to clear)");

        JPanel leftInputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftInputButtons.setOpaque(false);
        leftInputButtons.add(emojiBtn);
        leftInputButtons.add(sendFileBtn);
        leftInputButtons.add(bgBtn);

        JPanel rightInputButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightInputButtons.setOpaque(false);
        rightInputButtons.add(themeBtn);
        rightInputButtons.add(sendBtn);

        inputPanel.add(leftInputButtons, BorderLayout.WEST);
        inputPanel.add(textField, BorderLayout.CENTER);
        inputPanel.add(rightInputButtons, BorderLayout.EAST);

        rightPanel.add(inputPanel, BorderLayout.SOUTH);

        // Split pane
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setDividerLocation(260);
        split.setResizeWeight(0);
        split.setBorder(null);

        // Put the split inside a frame-sized BackgroundPanel so the whole window shows the image
        BackgroundPanel frameBackground = new BackgroundPanel();
        frameBackground.setLayout(new BorderLayout());
        frameBackground.add(split, BorderLayout.CENTER);

        // load the full-window background image and scale it to fill the frame (false -> scale)
        try {
            File frameBgFile = new File("D:\\Project 4\\istockphoto-1739234430-1024x1024.jpg");
            BufferedImage frameBg = loadBackgroundImage(frameBgFile, 1600);
            if (frameBg != null) {
                frameBackground.setBackgroundImage(frameBg, false); // false => scale the image to fill
                // subtle overlay so controls remain readable
                frameBackground.setOverlay(darkMode ? 0.28f : 0.12f, darkMode ? Color.BLACK : Color.WHITE);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        frame.setContentPane(frameBackground);
        frame.getContentPane().setBackground(WA_BG);

        // Actions
        sendBtn.addActionListener(e -> sendMessage());
        textField.addActionListener(e -> sendMessage());
        emojiBtn.addActionListener(e -> openEmojiPicker());
        themeBtn.addActionListener(e -> toggleDarkMode());
        sendFileBtn.addActionListener(e -> sendFile());

        bgBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select chat background (image)");
            int res = chooser.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                BufferedImage img = loadBackgroundImage(f, 1600);
                if (img != null && messagesWrapper instanceof BackgroundPanel) {
                    BackgroundPanel bp = (BackgroundPanel) messagesWrapper;
                    bp.setBackgroundImage(img, true); // tile by default
                    if (darkMode) bp.setOverlay(0.28f, Color.BLACK);
                    else bp.setOverlay(0.14f, Color.WHITE);
                } else {
                    JOptionPane.showMessageDialog(frame, "Unable to load image. Choose another file.");
                }
            }
        });

        // right-click menu on BG button to clear background
        JPopupMenu bgMenu = new JPopupMenu();
        JMenuItem clear = new JMenuItem("Clear background");
        clear.addActionListener(ev -> {
            if (messagesWrapper instanceof BackgroundPanel) ((BackgroundPanel) messagesWrapper).clearBackgroundImage();
        });
        bgMenu.add(clear);
        bgBtn.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) bgMenu.show(bgBtn, e.getX(), e.getY());
            }
        });

        // Typing indicator listener
        textField.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!loggedIn) return;
                String txt = textField.getText().trim();
                if (!typingSent && txt.length() > 0) {
                    out.println("/typing start");
                    typingSent = true;
                    typingTimer.restart();
                } else if (typingSent && txt.isEmpty()) {
                    out.println("/typing stop");
                    typingSent = false;
                    typingTimer.stop();
                } else if (typingSent) {
                    typingTimer.restart();
                }
            }
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
        });

        // Double-click user for private message template
        userList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String user = userList.getSelectedValue();
                    if (user != null && !user.equals(username)) {
                        textField.setText("/msg " + user + " " + textField.getText());
                        textField.requestFocus();
                        textField.setCaretPosition(textField.getText().length());
                    }
                }
            }
        });

        frame.setVisible(true);
        applyTheme();
    }

    private void connectToServer() throws Exception {
        socket = new Socket(serverHost, 9001);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    private String currentTimeString() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String generateMessageId(String sender) {
        return System.currentTimeMillis() + "_" + sender;
    }

    // ====== History handling ======
    private void initHistory() {
        try {
            historyFile = new File("chat_history_" + username + ".txt");

            if (historyFile.exists()) {
                loadingHistory = true;
                List<String> lines = Files.readAllLines(historyFile.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    // Format: id|time|sender|msg
                    String[] parts = line.split("\\|", 4);
                    if (parts.length == 4) {
                        String id = parts[0];
                        String time = parts[1];
                        String sender = parts[2];
                        String msg = parts[3];
                        boolean isMe = sender.equals(username);
                        appendBubbleInternal(sender, msg, isMe, id, time, false);
                    } else {
                        // old / invalid line -> show as server text
                        appendServer(line);
                    }
                }
                loadingHistory = false;
            }

            historyOut = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(historyFile, true), StandardCharsets.UTF_8),
                    true
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveHistory(String id, String sender, String msg, String time) {
        if (loadingHistory || historyOut == null) return;
        // basic sanitization: replace newlines
        String safeMsg = msg.replace("\n", " ");
        historyOut.println(id + "|" + time + "|" + sender + "|" + safeMsg);
    }

    private void removeFromHistory(String msgId) {
        if (historyFile == null || !historyFile.exists()) return;
        try {
            List<String> lines = Files.readAllLines(historyFile.toPath(), StandardCharsets.UTF_8);
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(historyFile, false), StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    if (!line.startsWith(msgId + "|")) {
                        pw.println(line);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====== Main loop ======
    public void start() throws Exception {
        while (true) {
            String line = in.readLine();
            if (line == null) break;

            if (line.startsWith("SUBMITNAME")) {
                out.println(username);
            } else if (line.startsWith("SUBMITPASS")) {
                out.println(password);
            } else if (line.startsWith("AUTHFAILED")) {
                String msg = line.substring("AUTHFAILED".length()).trim();
                if (msg.isEmpty()) msg = "Authentication failed.";
                JOptionPane.showMessageDialog(frame, msg, "Login Failed", JOptionPane.ERROR_MESSAGE);
                frame.dispose();
                System.exit(0);
            } else if (line.startsWith("NAMEACCEPTED")) {
                loggedIn = true;
                frame.setTitle("ChatBox - " + username);
                chatStatusLabel.setText("Online");
                initHistory();
            } else if (line.startsWith("USERLIST ")) {
                updateUserList(line.substring("USERLIST ".length()));
            } else if (line.startsWith("TYPING ")) {
                String who = line.substring("TYPING ".length());
                if (!who.equals(username)) typingLabel.setText(who + " is typing...");
            } else if (line.startsWith("TYPINGSTOP ")) {
                typingLabel.setText(" ");
            } else if (line.startsWith("FILE ")) {
                handleIncomingFile(line);
            } else if (line.startsWith("DELETE ")) {
                // server asks clients to remove message id
                String id = line.substring("DELETE ".length()).trim();
                SwingUtilities.invokeLater(() -> removeBubbleById(id));
            } else if (line.startsWith("SERVER: ")) {
                String msg = line.substring("SERVER: ".length());
                appendServer(msg);
            } else if (line.contains(": ")) {
                int idx = line.indexOf(": ");
                String sender = line.substring(0, idx);
                String msg = line.substring(idx + 2);
                boolean isMe = sender.equals(username);
                appendBubbleLive(sender, msg, isMe);
            } else {
                appendServer(line);
            }
        }
    }

    private void updateUserList(String csv) {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            String[] users = csv.split(",");
            for (String u : users) {
                if (!u.trim().isEmpty()) userListModel.addElement(u.trim());
            }
        });
    }

    // ====== Bubble helpers ======
    private void appendBubbleLive(String sender, String msg, boolean isMe) {
        String id = generateMessageId(sender);
        String time = currentTimeString();
        appendBubbleInternal(sender, msg, isMe, id, time, true);
    }

    private void appendBubbleInternal(String sender, String msg, boolean isMe,
                                      String id, String time, boolean saveHistoryFlag) {
        SwingUtilities.invokeLater(() -> {
            Bubble bubble = new Bubble(msg, isMe, id, time);
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            int sideMargin = 60;
            if (isMe) {
                wrapper.setBorder(new EmptyBorder(2, sideMargin, 2, 0));
                wrapper.add(bubble, BorderLayout.EAST);
            } else {
                wrapper.setBorder(new EmptyBorder(2, 0, 2, sideMargin));
                wrapper.add(bubble, BorderLayout.WEST);
            }
            messagesPanel.add(wrapper);
            messagesPanel.add(Box.createVerticalStrut(2));
            messagesPanel.revalidate();
            scrollToBottom();
        });

        if (saveHistoryFlag) {
            saveHistory(id, sender, msg, time);
        }
    }

    // ====== Sending ======
    private void sendMessage() {
        String text = textField.getText().trim();
        if (!text.isEmpty()) {
            out.println(text);
            textField.setText("");
        }
    }

    // safer sendFile with size limit + SwingWorker
    private void sendFile() {
        final long MAX_BYTES = 8L * 1024L * 1024L; // 8 MB limit
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                long len = Files.size(f.toPath());
                if (len > MAX_BYTES) {
                    JOptionPane.showMessageDialog(frame,
                            "File too large. Please pick a file smaller than " + (MAX_BYTES / (1024*1024)) + " MB.",
                            "File too large", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                final JDialog dlg = new JDialog(frame, "Sending file...", true);
                dlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                dlg.setSize(280, 80);
                dlg.setLocationRelativeTo(frame);
                dlg.setLayout(new BorderLayout());
                JLabel lbl = new JLabel("Encoding and sending " + f.getName() + " ...");
                lbl.setBorder(new EmptyBorder(10,10,10,10));
                dlg.add(lbl, BorderLayout.CENTER);

                SwingWorker<Void, Void> worker = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        byte[] bytes = Files.readAllBytes(f.toPath());
                        String base64 = Base64.getEncoder().encodeToString(bytes);
                        out.println("/file " + f.getName() + " " + base64);
                        String msg = "You sent file: " + f.getName();
                        appendBubbleLive(username, msg, true);
                        return null;
                    }

                    @Override
                    protected void done() {
                        dlg.dispose();
                        try {
                            get();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(frame,
                                    "Could not send file: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                };
                worker.execute();
                dlg.setVisible(true);

            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame,
                        "Could not send file: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleIncomingFile(String line) {
        try {
            String[] parts = line.split(" ", 4);
            if (parts.length < 4) return;
            String sender = parts[1];
            if (sender.equals(username)) return; // ignore own echo

            String filename = parts[2];
            String base64 = parts[3];

            String msg = sender + " sent: " + filename;
            appendBubbleLive(sender, msg, false);

            int option = JOptionPane.showConfirmDialog(frame,
                    sender + " sent file \"" + filename + "\".\nDo you want to save it?",
                    "File Received", JOptionPane.YES_NO_OPTION);

            if (option == JOptionPane.YES_OPTION) {
                JFileChooser chooser = new JFileChooser();
                chooser.setSelectedFile(new File(filename));
                if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File saveFile = chooser.getSelectedFile();
                    byte[] bytes = Base64.getDecoder().decode(base64);
                    Files.write(saveFile.toPath(), bytes);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====== UI helpers ======
    private void appendServer(String msg) {
        SwingUtilities.invokeLater(() -> {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER));
            p.setOpaque(false);
            JLabel lbl = new JLabel(msg);
            lbl.setFont(lbl.getFont().deriveFont(Font.ITALIC, 11f));
            lbl.setForeground(new Color(140, 140, 140));
            p.add(lbl);
            messagesPanel.add(p);
            messagesPanel.add(Box.createVerticalStrut(4));
            messagesPanel.revalidate();
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = messagesScroll.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    private void openEmojiPicker() {
        JDialog d = new JDialog(frame, "Emoji Picker", true);
        d.setLayout(new GridLayout(0, 8, 4, 4));
        d.setSize(350, 260);
        d.setLocationRelativeTo(frame);

        for (String emoji : EMOJIS) {
            JButton b = new JButton(emoji);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
            b.setMargin(new Insets(2, 2, 2, 2));
            b.setFocusPainted(false);
            b.addActionListener(e -> {
                textField.setText(textField.getText() + emoji);
                textField.requestFocus();
                d.dispose();
            });
            d.add(b);
        }
        d.setVisible(true);
    }

    private void toggleDarkMode() {
        darkMode = !darkMode;
        themeBtn.setText(darkMode ? "Light" : "Dark");
        applyTheme();
        updateAllBubblesTheme(messagesPanel);
    }

    private void applyTheme() {
        Color frameBg;
        Color chatBg;
        Color headerBg;
        Color sideBg;
        Color inputBg;
        Color listBg;
        Color listFg;
        Color textBg;
        Color textFg;

        if (darkMode) {
            frameBg = DARK_FRAME_BG;
            chatBg = DARK_CHAT_BG;
            headerBg = WA_GREEN;
            sideBg = DARK_SIDE_BG;
            inputBg = DARK_INPUT_BG;
            listBg = DARK_SIDE_BG;
            listFg = Color.WHITE;
            textBg = new Color(32, 44, 51);
            textFg = Color.WHITE;
        } else {
            frameBg = WA_BG;
            chatBg = WA_BG;
            headerBg = WA_GREEN;
            sideBg = Color.WHITE;
            inputBg = new Color(245, 245, 245);
            listBg = Color.WHITE;
            listFg = Color.BLACK;
            textBg = Color.WHITE;
            textFg = Color.BLACK;
        }

        frame.getContentPane().setBackground(frameBg);
        messagesScroll.getViewport().setBackground(chatBg);
        messagesWrapper.setBackground(chatBg);
        messagesPanel.setBackground(chatBg);

        headerPanel.setBackground(headerBg);
        leftPanel.setBackground(sideBg);
        userScroll.getViewport().setBackground(listBg);
        userList.setBackground(listBg);
        userList.setForeground(listFg);

        inputPanel.setBackground(inputBg);
        textField.setBackground(textBg);
        textField.setForeground(textFg);
        textField.setCaretColor(textFg);

        // Update overlay in background panel if present
        Container cp = frame.getContentPane();
        if (cp instanceof BackgroundPanel) {
            BackgroundPanel fb = (BackgroundPanel) cp;
            if (darkMode) fb.setOverlay(0.28f, Color.BLACK);
            else fb.setOverlay(0.12f, Color.WHITE);
        }
        if (messagesWrapper instanceof BackgroundPanel) {
            BackgroundPanel bp = (BackgroundPanel) messagesWrapper;
            if (darkMode) bp.setOverlay(0.28f, Color.BLACK);
            else bp.setOverlay(0.14f, Color.WHITE);
        }

        frame.repaint();
    }

    // update existing bubbles when theme changes
    private void updateAllBubblesTheme(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof Bubble) {
                ((Bubble) c).updateForTheme();
            } else if (c instanceof Container) {
                updateAllBubblesTheme((Container) c);
            }
        }
    }

    // ====== Glass / frosted header panel ======
    private class GlassHeaderPanel extends JPanel {
        public GlassHeaderPanel() {
            super();
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            Color base = getBackground();
            if (base == null) base = new Color(0, 132, 255);

            // base fill
            g2.setColor(base);
            g2.fillRect(0, 0, w, h);

            // vertical white gradient
            GradientPaint gp = new GradientPaint(
                    0, 0, new Color(255, 255, 255, 90),
                    0, h, new Color(255, 255, 255, 10)
            );
            g2.setPaint(gp);
            g2.fillRect(0, 0, w, h);

            // top highlight
            g2.setColor(new Color(255, 255, 255, 120));
            g2.drawLine(0, 0, w, 0);

            // bottom shadow
            g2.setColor(new Color(0, 0, 0, 80));
            g2.drawLine(0, h - 1, w, h - 1);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ====== Bubble class (replacement) ======
    private class Bubble extends JPanel {
        private final boolean isMe;
        private final String messageId;
        private final String time;
        private final JTextPane textPane;
        private final JLabel timeLabel;

        public Bubble(String msg, boolean isMe, String messageId, String time) {
            this.isMe = isMe;
            this.messageId = messageId;
            this.time = time;

            setOpaque(false);
            setLayout(new BorderLayout());

            // sanitize & simple HTML wrapping (escape < and >)
            String safe = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            safe = safe.replace("\n", "<br/>");

            textPane = new JTextPane();
            textPane.setContentType("text/html");
            textPane.setEditable(false);
            textPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            String html = "<html><body style=\"font-family: 'Segoe UI', Arial, sans-serif; font-size:12px;\">"
                    + safe + "</body></html>";
            textPane.setText(html);
            textPane.setOpaque(false);
            textPane.setBorder(null);
            textPane.setFocusable(false);

            // wrap long messages by limiting preferred width
            textPane.setMaximumSize(new Dimension(500, Integer.MAX_VALUE));

            timeLabel = new JLabel(time);
            timeLabel.setFont(timeLabel.getFont().deriveFont(9f));

            JPanel inner = new JPanel(new BorderLayout());
            inner.setOpaque(false);
            inner.setBorder(new EmptyBorder(8, 12, 6, 12));

            inner.add(textPane, BorderLayout.CENTER);

            JPanel south = new JPanel(new BorderLayout());
            south.setOpaque(false);
            south.add(timeLabel, BorderLayout.EAST);
            inner.add(south, BorderLayout.SOUTH);

            add(inner, BorderLayout.CENTER);

            updateForTheme();

            // Right-click menu
            JPopupMenu menu = new JPopupMenu();
            JMenuItem deleteMe = new JMenuItem("Delete for Me");
            deleteMe.addActionListener(e -> deleteThisBubble());
            menu.add(deleteMe);

            if (isMe) {
                JMenuItem deleteEveryone = new JMenuItem("Delete for Everyone");
                deleteEveryone.addActionListener(e -> {
                    if (loggedIn) {
                        out.println("/delete " + messageId);
                    }
                    deleteThisBubble();
                });
                menu.add(deleteEveryone);
            }

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        menu.show(Bubble.this, e.getX(), e.getY());
                    }
                }
            };
            this.addMouseListener(ma);
            inner.addMouseListener(ma);
            textPane.addMouseListener(ma);
            timeLabel.addMouseListener(ma);
        }

        private void deleteThisBubble() {
            Container wrapper = this.getParent();      // wrapper panel
            Container chatPanel = wrapper.getParent(); // messagesPanel

            chatPanel.remove(wrapper);
            chatPanel.revalidate();
            chatPanel.repaint();

            removeFromHistory(messageId);
        }

        void updateForTheme() {
            if (darkMode) {
                textPane.setForeground(Color.WHITE);
                timeLabel.setForeground(new Color(220, 220, 220));
            } else {
                if (isMe) textPane.setForeground(Color.WHITE);
                else textPane.setForeground(Color.BLACK);
                timeLabel.setForeground(Color.DARK_GRAY);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            Dimension tp = textPane.getPreferredSize();
            int width = Math.max(d.width, tp.width + 40);
            int height = tp.height + 28;
            return new Dimension(width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int pad = 0;
            int w = getWidth() - pad * 2;
            int h = getHeight() - pad * 2;
            int arc = Math.max(20, h);

            Color fill;
            if (isMe) {
                fill = darkMode ? DARK_MY_BUBBLE : new Color(0, 132, 255, 230); // slightly translucent
            } else {
                fill = darkMode ? DARK_OTHER_BUBBLE : new Color(228, 230, 235, 230);
            }

            g2.setColor(fill);
            g2.fillRoundRect(pad, pad, w, h, arc, arc);
            g2.setColor(new Color(0, 0, 0, 30));
            g2.drawRoundRect(pad, pad, w - 1, h - 1, arc, arc);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // Remove bubble by message id (used for DELETE command)
    private void removeBubbleById(String id) {
        for (Component comp : messagesPanel.getComponents()) {
            if (comp instanceof JPanel) {
                Container wrapper = (Container) comp;
                for (Component inner : wrapper.getComponents()) {
                    if (inner instanceof Bubble) {
                        Bubble b = (Bubble) inner;
                        try {
                            java.lang.reflect.Field f = Bubble.class.getDeclaredField("messageId");
                            f.setAccessible(true);
                            Object val = f.get(b);
                            if (val != null && val.equals(id)) {
                                messagesPanel.remove(wrapper);
                                messagesPanel.revalidate();
                                messagesPanel.repaint();
                                removeFromHistory(id);
                                return;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    // ====== Login dialog ======
    private static class LoginData {
        String serverHost;
        String username;
        String password;
    }

    /**
     * Final showLoginDialog() — uses the provided image as dialog background
     * and fixes the "must be final" issue by using a final local reference.
     */
    private static LoginData showLoginDialog() {
        // Create dialog
        JDialog dialog = new JDialog((Frame) null, "Login to ChatBox", true);
        dialog.setSize(450, 300);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);

        // ====== Load Background Image ======
        BufferedImage bgImage = null;
        try {
            bgImage = ImageIO.read(new File("D:\\Project 4\\istockphoto-1739234430-1024x1024.jpg"));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        // final reference for inner class use
        final BufferedImage bgImageFinal = bgImage;

        // ====== Background panel ======
        JPanel background = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                // draw image scaled to fill + soft overlay for readability
                super.paintComponent(g);
                if (bgImageFinal != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g2.drawImage(bgImageFinal, 0, 0, getWidth(), getHeight(), null);

                        // Soft translucent overlay (white-ish) to keep form readable
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
                        g2.setColor(Color.WHITE);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    } finally {
                        g2.dispose();
                    }
                }
            }
        };
        background.setLayout(new GridBagLayout());
        dialog.setContentPane(background);

        // ====== UI FORM ======
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(10, 20, 10, 20));
        form.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        JLabel hostLabel = new JLabel("Server host:");
        hostLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JTextField hostField = new JTextField("localhost");
        hostField.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JLabel userLabel = new JLabel("Username:");
        userLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JTextField userField = new JTextField();
        userField.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JLabel passLabel = new JLabel("Password:");
        passLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JPasswordField passField = new JPasswordField();
        passField.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        // Add fields
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1;
        form.add(hostLabel, gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        form.add(hostField, gbc);

        gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 1;
        form.add(userLabel, gbc);
        gbc.gridx = 1; gbc.gridwidth = 2;
        form.add(userField, gbc);

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 1;
        form.add(passLabel, gbc);
        gbc.gridx = 1; gbc.gridwidth = 1;
        form.add(passField, gbc);

        // show/hide password toggle
        JToggleButton showPass = new JToggleButton("Show");
        showPass.setFocusable(false);
        showPass.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        gbc.gridx = 2; gbc.gridy = 2; gbc.gridwidth = 1;
        form.add(showPass, gbc);

        // Remember me checkbox
        JCheckBox remember = new JCheckBox("Remember me");
        remember.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        remember.setOpaque(false);
        gbc.gridx = 1; gbc.gridy = 3; gbc.gridwidth = 2;
        form.add(remember, gbc);

        background.add(form);

        // ====== Buttons ======
        JButton cancel = new JButton("Cancel");
        JButton connect = new JButton("Connect");
        connect.setPreferredSize(new Dimension(96, 28));
        cancel.setPreferredSize(new Dimension(96, 28));
        connect.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        cancel.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);
        btnPanel.setBorder(new EmptyBorder(8, 16, 8, 16));
        btnPanel.add(cancel);
        btnPanel.add(connect);

        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 3;
        form.add(btnPanel, gbc);

        // ====== Behaviors ======
        final LoginData[] result = {null};

        // placeholder-like hint in username (clears on focus)
        final String hintText = "enter username";
        final Color hintColor = Color.GRAY;
        final Color normalColor = Color.BLACK;
        userField.setForeground(hintColor);
        userField.setText(hintText);
        userField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (userField.getText().equals(hintText)) {
                    userField.setText("");
                    userField.setForeground(normalColor);
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (userField.getText().trim().isEmpty()) {
                    userField.setForeground(hintColor);
                    userField.setText(hintText);
                }
            }
        });

        // show/hide password
        showPass.addActionListener(ev -> {
            if (showPass.isSelected()) {
                passField.setEchoChar((char)0);
                showPass.setText("Hide");
            } else {
                passField.setEchoChar('\u2022'); // bullet
                showPass.setText("Show");
            }
        });

        // Connect action (validate)
        ActionListener doConnect = e -> {
            String host = hostField.getText().trim();
            String user = userField.getText().trim();
            String pass = new String(passField.getPassword());
            if (user.equals(hintText)) user = "";

            if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                        "Server host, username and password are required.",
                        "Missing fields", JOptionPane.WARNING_MESSAGE);
                return;
            }

            LoginData d = new LoginData();
            d.serverHost = host;
            d.username = user;
            d.password = pass;
            result[0] = d;
            // Note: Remember-me persistence not implemented here
            dialog.dispose();
        };

        connect.addActionListener(doConnect);

        // Cancel closes dialog
        cancel.addActionListener(e -> {
            result[0] = null;
            dialog.dispose();
        });

        // Enter triggers Connect
        dialog.getRootPane().setDefaultButton(connect);

        // Focus rules when dialog opens
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                userField.requestFocusInWindow();
                if (userField.getText().equals(hintText)) {
                    userField.setCaretPosition(0);
                }
            }
        });

        // Show and return result
        dialog.setVisible(true);
        return result[0];
    }

    // ====== Image loader helper ======
    private BufferedImage loadBackgroundImage(File file, int maxSide) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            int max = Math.max(w, h);
            if (maxSide > 0 && max > maxSide) {
                double scale = (double) maxSide / (double) max;
                int nw = (int) Math.round(w * scale);
                int nh = (int) Math.round(h * scale);
                Image tmp = img.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
                BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(tmp, 0, 0, null);
                g2.dispose();
                return scaled;
            }
            return img;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    // ====== main ======
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        LoginData data = showLoginDialog();
        if (data == null) return;

        SwingUtilities.invokeLater(() -> {
            try {
                ChatClient client = new ChatClient(data.serverHost, data.username, data.password);
                new Thread(() -> {
                    try {
                        client.start();
                    } catch (Exception e) {
                        e.printStackTrace();
                        JOptionPane.showMessageDialog(null,
                                "Connection closed: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        System.exit(0);
                    }
                }).start();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Could not connect: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}

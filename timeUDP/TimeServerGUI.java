package timeUDP;

import javax.swing.*;
import java.awt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.HttpURLConnection;

/** Server lấy giờ Internet (HTTP/NTP) và phản hồi client qua UDP */
public class TimeServerGUI extends JFrame {
    // ==== Màu & Nimbus
    private static final Color PRIMARY    = new Color(0x4F46E5);
    private static final Color PRIMARY_DK = new Color(0x4338CA);
    private static final Color SURFACE    = new Color(0xF7F7F9);
    static {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            UIManager.put("control", SURFACE);
            UIManager.put("nimbusBase", PRIMARY_DK);
            UIManager.put("nimbusFocus", PRIMARY);
        } catch (Exception ignored) {}
    }

    private JTextArea logArea;
    private JButton btnStart, btnStop;
    private JTextField txtPort, txtNtpHost, txtRefresh;
    private JComboBox<String> cbSource;
    private ServerWorker worker;

    // ==== thêm đồng hồ
    private JLabel lblClock;
    private javax.swing.Timer clockTimer;

    // ==== thêm đồng hồ analog (mới)
    private AnalogClockPanel analogPanel;

    public TimeServerGUI() {
        super("UDP Time Server (Internet Time)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(760, 520);
        setLocationRelativeTo(null);

     // ===== Header gradient
        JPanel header = new GradientPanel(PRIMARY, PRIMARY_DK);
        header.setLayout(new GridBagLayout());

        JLabel title = new JLabel("UDP Time Server");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        // ==== Các control
        txtPort    = field("5005", 6);
        cbSource   = new JComboBox<>(new String[]{"HTTP Date", "NTP"});
        txtNtpHost = field("time.google.com", 14);
        txtRefresh = field("5000", 6); // ms

        btnStart = pill("Start", new Color(0x16A34A));
        btnStop  = pill("Stop",  new Color(0xEF4444));
        btnStop.setEnabled(false);

        // Panel controls 1 hàng ngang
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(labelW("Port:"));        controls.add(txtPort);
        controls.add(Box.createRigidArea(new Dimension(10,0)));
        controls.add(labelW("Nguồn:"));       controls.add(cbSource);
        controls.add(Box.createRigidArea(new Dimension(10,0)));
        controls.add(labelW("NTP host:"));    controls.add(txtNtpHost);
        controls.add(Box.createRigidArea(new Dimension(10,0)));
        controls.add(labelW("Refresh(ms):")); controls.add(txtRefresh);
        controls.add(Box.createRigidArea(new Dimension(10,0)));
        controls.add(btnStart);
        controls.add(Box.createRigidArea(new Dimension(10,0)));
        controls.add(btnStop);

        // Bọc trong JScrollPane ngang để không mất nút khi thu nhỏ
        JScrollPane ctrlScroll = new JScrollPane(
                controls,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        ctrlScroll.setBorder(null);
        ctrlScroll.getViewport().setOpaque(false);
        ctrlScroll.setOpaque(false);
        ctrlScroll.getHorizontalScrollBar().setUnitIncrement(24);
        ctrlScroll.setPreferredSize(new Dimension(10, controls.getPreferredSize().height + 12));

        // Gắn vào header với GridBagConstraints
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0;
        gc.insets = new Insets(8,16,0,16);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        header.add(title, gc);

        gc.gridy = 1;
        gc.insets = new Insets(0,16,12,16);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        header.add(ctrlScroll, gc);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(logArea);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        // 🔸 Đồng hồ analog ở cạnh phải
        analogPanel = new AnalogClockPanel();
        analogPanel.setPreferredSize(new Dimension(260, 260));
        add(analogPanel, BorderLayout.EAST);

        // ==== đồng hồ digital ở dưới cùng
        lblClock = new JLabel("00:00:00", SwingConstants.CENTER);
        lblClock.setFont(new Font("Monospaced", Font.BOLD, 24));
        lblClock.setOpaque(true);
        lblClock.setBackground(Color.BLACK);
        lblClock.setForeground(Color.GREEN);
        add(lblClock, BorderLayout.SOUTH);

        // Timer cập nhật đồng hồ mỗi giây
        clockTimer = new javax.swing.Timer(1000, e -> updateClock());
        clockTimer.start();

        btnStart.addActionListener(e -> startServer());
        btnStop.addActionListener(e -> stopServer());
    }

    // ======== UI helpers
    private static JTextField field(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));
        return f;
    }
    private static JLabel labelW(String s) {
        JLabel l = new JLabel(s); l.setForeground(new Color(255,255,255,230));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f)); return l;
    }
    private static JButton pill(String text, Color base) {
        JButton b = new JButton(text);
        b.setForeground(Color.WHITE); b.setFocusPainted(false);
        b.setBackground(base); b.setBorder(BorderFactory.createEmptyBorder(8,16,8,16));
        return b;
    }
    private void appendLog(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ======== cập nhật đồng hồ
    private void updateClock() {
        long now = (worker != null && worker.engine != null)
                ? worker.engine.nowMs()
                : System.currentTimeMillis();
        java.time.LocalTime t = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime();
        lblClock.setText(t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());

        // cập nhật analog
        if (analogPanel != null) {
            analogPanel.setNow(now);
            analogPanel.repaint();
        }
    }

    // ======== Start/Stop
    private void startServer() {
        if (worker != null && !worker.isDone()) return;

        int port, refreshMs;
        try { port = Integer.parseInt(txtPort.getText().trim()); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Port không hợp lệ"); return; }
        try { refreshMs = Integer.parseInt(txtRefresh.getText().trim()); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Refresh(ms) không hợp lệ"); return; }

        String modeStr = (String) cbSource.getSelectedItem();
        TimeEngine.Mode mode = "NTP".equals(modeStr) ? TimeEngine.Mode.NTP : TimeEngine.Mode.HTTP;
        String ntpHost = txtNtpHost.getText().trim();

        TimeEngine engine = new TimeEngine(mode, ntpHost, refreshMs, this::appendLog);
        engine.syncOnce(); // đồng bộ lần đầu (log trạng thái)

        worker = new ServerWorker(port, engine, this::appendLog);
        worker.execute();
        btnStart.setEnabled(false); btnStop.setEnabled(true);

        appendLog("[SERVER] Lắng nghe UDP 0.0.0.0:" + port);
        appendLog("[SERVER] Nguồn thời gian: " + mode + (mode== TimeEngine.Mode.NTP? " ("+ntpHost+")": " (HTTP Date)"));
        appendLog("[SERVER] Hỗ trợ DISCOVER → HERE <ip> <port>");
    }

    private void stopServer() {
        if (worker != null) worker.shutdown();
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        appendLog("[SERVER] Đã dừng.");
    }

    // ======== Worker UDP: DISCOVER & TIME
    private static class ServerWorker extends SwingWorker<Void, Void> {
        private final int port;
        private final TimeEngine engine;
        private final java.util.function.Consumer<String> logger;
        private volatile boolean running = true;
        private DatagramSocket socket;

        ServerWorker(int port, TimeEngine engine, java.util.function.Consumer<String> logger) {
            this.port = port; this.engine = engine; this.logger = logger;
        }

        @Override protected Void doInBackground() {
            try {
                socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", port));
                byte[] buf = new byte[1024];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);

                while (running) {
                    socket.receive(pkt);
                    String req = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                    InetAddress cliAddr = pkt.getAddress();
                    int cliPort = pkt.getPort();

                    if (req.startsWith("DISCOVER")) {
                        String myIp = getBestLocalIp();
                        String resp = "HERE " + myIp + " " + port;
                        socket.send(new DatagramPacket(resp.getBytes(StandardCharsets.UTF_8), resp.length(), cliAddr, cliPort));
                        logger.accept(String.format("DISCOVER %s:%d → HERE %s %d", cliAddr.getHostAddress(), cliPort, myIp, port));
                        continue;
                    }

                    // === CÁCH 2: lấy giờ Internet qua TimeEngine (đã cache để trả lời nhanh)
                    long t1 = engine.nowMs();   // server receive (Internet time)
                    long t2 = engine.nowMs();   // server transmit (Internet time)
                    String resp = "RESP " + t1 + " " + t2;
                    socket.send(new DatagramPacket(resp.getBytes(StandardCharsets.UTF_8), resp.length(), cliAddr, cliPort));

                    logger.accept(String.format("REQ %s:%d | t1=%d t2=%d | raw=%s",
                            cliAddr.getHostAddress(), cliPort, t1, t2, req));
                }
            } catch (SocketException se) {
                if (running) logger.accept("[ERR] SocketException: " + se.getMessage());
            } catch (IOException ioe) {
                if (running) logger.accept("[ERR] IOException: " + ioe.getMessage());
            } finally {
                if (socket != null && !socket.isClosed()) socket.close();
            }
            return null;
        }

        void shutdown() { running = false; if (socket != null) socket.close(); cancel(true); }

        private static String getBestLocalIp() {
            try {
                for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (a instanceof Inet4Address) {
                            String s = a.getHostAddress();
                            if (s.startsWith("10.") || s.startsWith("172.") || s.startsWith("192.168.")) return s;
                        }
                    }
                }
            } catch (Exception ignored) {}
            try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; }
        }
    }

    // ======== TimeEngine: HTTP Date / NTP với cache + nội suy bằng nanoTime
    static class TimeEngine {
        enum Mode { HTTP, NTP }
        private final Mode mode;
        private final String ntpHost;
        private final long refreshIntervalMs;
        private final java.util.function.Consumer<String> logger;

        private volatile long baseNetMs = 0L;     // mốc thời gian Internet (epoch ms)
        private volatile long baseMonoNs = 0L;    // mốc nanoTime tương ứng
        private volatile long lastSyncWallMs = 0L;

        TimeEngine(Mode mode, String ntpHost, long refreshIntervalMs, java.util.function.Consumer<String> logger) {
            this.mode = mode; this.ntpHost = ntpHost; this.refreshIntervalMs = refreshIntervalMs; this.logger = logger;
        }

        /** gọi lúc start để có mốc ban đầu */
        void syncOnce() {
            try {
                long net = (mode == Mode.HTTP) ? fetchInternetTimeHttpMs() : fetchInternetTimeNtpMs(ntpHost);
                baseNetMs = net;
                baseMonoNs = System.nanoTime();
                lastSyncWallMs = System.currentTimeMillis();
                logger.accept("[SYNC] " + mode + " ok: " + net + " ms");
            } catch (Exception e) {
                logger.accept("[SYNC] " + mode + " lỗi: " + e.getMessage() + " → dùng mốc local tạm thời");
                baseNetMs = System.currentTimeMillis();
                baseMonoNs = System.nanoTime();
                lastSyncWallMs = System.currentTimeMillis();
            }
        }

        /** trả về “giờ Internet hiện tại” rất nhanh nhờ nội suy bằng nanoTime */
        long nowMs() {
            long wall = System.currentTimeMillis();
            if (baseNetMs == 0 || wall - lastSyncWallMs > refreshIntervalMs) {
                // cố gắng làm mới (không chặn quá lâu)
                new Thread(this::syncOnce).start();
            }
            long deltaMs = (System.nanoTime() - baseMonoNs) / 1_000_000L;
            return baseNetMs + deltaMs;
        }

        // === HTTP: đọc header Date
        static long fetchInternetTimeHttpMs() throws Exception {
            URL url = new URL("https://www.google.com"); // nhanh & ổn định
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.connect();
            long t = conn.getDate(); // epoch ms từ header Date
            conn.disconnect();
            if (t == 0) throw new RuntimeException("No Date header");
            return t;
        }

        // === NTP (SNTP đơn giản)
        static long fetchInternetTimeNtpMs(String host) throws Exception {
            final String ntpHost = (host == null || host.isEmpty()) ? "time.google.com" : host;
            final int NTP_PORT = 123;
            final long UNIX_EPOCH_DIFF = 2208988800L; // giây giữa 1900 và 1970

            byte[] buf = new byte[48];
            buf[0] = 0b00_100_011; // LI=0, VN=4, Mode=3 (client)

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1500);
                InetAddress addr = InetAddress.getByName(ntpHost);
                socket.send(new DatagramPacket(buf, buf.length, addr, NTP_PORT));

                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                socket.receive(resp);

                long seconds = ((buf[40] & 0xffL) << 24) | ((buf[41] & 0xffL) << 16) |
                               ((buf[42] & 0xffL) << 8)  |  (buf[43] & 0xffL);
                long fraction = ((buf[44] & 0xffL) << 24) | ((buf[45] & 0xffL) << 16) |
                                ((buf[46] & 0xffL) << 8)  |  (buf[47] & 0xffL);

                return (seconds - UNIX_EPOCH_DIFF) * 1000L + (fraction * 1000L) / 0x100000000L;
            }
        }
    }

    // ======== Header gradient panel
    private static class GradientPanel extends JPanel {
        private final Color c1, c2;
        GradientPanel(Color a, Color b) { c1=a; c2=b; }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight();
            GradientPaint gp = new GradientPaint(0,0,c1, 0,h,c2);
            g2.setPaint(gp); g2.fillRect(0,0,w,h); g2.dispose();
        }
    }

    // ======== Analog clock panel (có số & vạch phút/giờ)
    private static class AnalogClockPanel extends JPanel {
        private long nowMs = System.currentTimeMillis();

        void setNow(long ms) { this.nowMs = ms; }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int size = Math.min(w, h) - 16;
            int cx = w / 2, cy = h / 2;
            int r  = size / 2;

            // nền
            g2.setColor(new Color(0xFAFBFF));
            g2.fillOval(cx - r, cy - r, 2*r, 2*r);

            // viền
            g2.setColor(new Color(0xCBD5E1));
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(cx - r, cy - r, 2*r, 2*r);

            // vạch phút/giờ
            for (int i = 0; i < 60; i++) {
                double angle = Math.toRadians(i * 6 - 90);
                int inner = (i % 5 == 0) ? (int)(r * 0.82) : (int)(r * 0.88);
                int outer = (int)(r * 0.96);
                int x1 = cx + (int)(inner * Math.cos(angle));
                int y1 = cy + (int)(inner * Math.sin(angle));
                int x2 = cx + (int)(outer * Math.cos(angle));
                int y2 = cy + (int)(outer * Math.sin(angle));
                g2.setStroke(new BasicStroke(i % 5 == 0 ? 3f : 1.5f));
                g2.setColor(new Color(0xCBD5E1));
                g2.drawLine(x1, y1, x2, y2);
            }

            // số 1..12
            g2.setColor(new Color(0x334155));
            Font f = getFont().deriveFont(Font.BOLD, Math.max(12f, r * 0.12f));
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 1; i <= 12; i++) {
                double angle = Math.toRadians(i * 30 - 90);
                int nr = (int)(r * 0.70);
                int tx = cx + (int)(nr * Math.cos(angle));
                int ty = cy + (int)(nr * Math.sin(angle));
                String s = String.valueOf(i);
                int tw = fm.stringWidth(s);
                int th = fm.getAscent();
                g2.drawString(s, tx - tw/2, ty + th/3);
            }

            // thời gian
            java.time.ZonedDateTime zt = java.time.Instant.ofEpochMilli(nowMs)
                    .atZone(java.time.ZoneId.systemDefault());
            int hour = zt.getHour() % 12;
            int min  = zt.getMinute();
            int sec  = zt.getSecond();

            double ah = Math.toRadians((hour + min/60.0) * 30 - 90);
            double am = Math.toRadians((min + sec/60.0) * 6 - 90);
            double as = Math.toRadians(sec * 6 - 90);

            // kim giờ
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0x111827));
            drawHand(g2, cx, cy, ah, (int)(r * 0.52));

            // kim phút
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0x111827));
            drawHand(g2, cx, cy, am, (int)(r * 0.70));

            // kim giây (đỏ)
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0xEF4444));
            drawHand(g2, cx, cy, as, (int)(r * 0.78));

            // tâm đồng hồ
            g2.setColor(new Color(0x4F46E5));
            g2.fillOval(cx - 6, cy - 6, 12, 12);

            g2.dispose();
        }

        private static void drawHand(Graphics2D g2, int cx, int cy, double angle, int len) {
            int x = cx + (int)(len * Math.cos(angle));
            int y = cy + (int)(len * Math.sin(angle));
            g2.drawLine(cx, cy, x, y);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TimeServerGUI().setVisible(true));
    }
}

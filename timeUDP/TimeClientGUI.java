package timeUDP;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class TimeClientGUI extends JFrame {
    // ====== Palette ======
    private static final Color PRIMARY    = new Color(0x4F46E5);
    private static final Color PRIMARY_DK = new Color(0x4338CA);
    private static final Color ACCENT     = new Color(0x22C55E); // green
    private static final Color SURFACE    = new Color(0xF7F7F9);
    private static final Color PANEL      = Color.WHITE;
    private static final Color TEXT       = new Color(0x1F2937);
    private static final Color GRID       = new Color(220, 227, 235);

    static {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            UIManager.put("control", SURFACE);
            UIManager.put("text", TEXT);
            UIManager.put("nimbusBase", PRIMARY_DK);
            UIManager.put("nimbusBlueGrey", new Color(0x2B2D42));
            UIManager.put("nimbusFocus", PRIMARY);
            UIManager.put("Table.alternateRowColor", new Color(0xF0F2F7));
            UIManager.put("Button.font", new Font("SansSerif", Font.BOLD, 13));
        } catch (Exception ignored) {}
    }

    private JTextField txtIP, txtPort, txtSamples, txtInterval, txtTimeout;
    private PillButton btnRun, btnStop, btnExport, btnDiscover;
    private JTable table;
    private DefaultTableModel model;
    private JTextArea resultArea;
    private PlotPanel plotPanel;
    private AnalogClockPanel analogClockPanel;
    private ClientWorker worker;

    // Đồng hồ số
    private JLabel lblLocalClock, lblServerClock, lblOffsetDisplay;
    private javax.swing.Timer clockTimer;
    private volatile Double lastMedianOffsetMs = null;
    private static final DateTimeFormatter CLOCK_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public TimeClientGUI() {
        super("UDP Time Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 720);
        setLocationRelativeTo(null);

        // ==== Header gradient + controls
        GradientPanel header = new GradientPanel(PRIMARY, PRIMARY_DK);
        header.setLayout(new GridBagLayout());

        txtIP = fieldWhite("127.0.0.1", 10);
        txtPort = fieldWhite("5005", 5);
        txtSamples = fieldWhite("9", 5);
        txtInterval = fieldWhite("250", 5);
        txtTimeout = fieldWhite("1000", 5);

        btnRun = new PillButton("Run", PRIMARY);
        btnStop = new PillButton("Stop", new Color(0xEF4444));
        btnStop.setEnabled(false);
        btnExport = new PillButton("Export CSV", new Color(0x059669));
        btnDiscover = new PillButton("Tìm server", new Color(0x0EA5E9));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        controls.setOpaque(false);
        controls.add(lw("Server IP:")); controls.add(txtIP);
        controls.add(lw("Port:")); controls.add(txtPort);
        controls.add(lw("Samples:")); controls.add(txtSamples);
        controls.add(lw("Interval(ms):")); controls.add(txtInterval);
        controls.add(lw("Timeout(ms):")); controls.add(txtTimeout);
        controls.add(btnRun); controls.add(btnStop); controls.add(btnExport); controls.add(btnDiscover);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx=0; gc.gridy=0; gc.insets = new Insets(8,16,8,16);
        gc.anchor = GridBagConstraints.WEST;
        header.add(new Title("UDP Time Client"), gc);
        gc.gridy=1; gc.insets = new Insets(0,16,12,16);
        header.add(controls, gc);

        // ====== Status bar (đồng hồ số)
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        status.setBackground(new Color(0x111827));
        lblLocalClock = new JLabel("Local: --:--:--.---");
        lblServerClock = new JLabel("Server(est): --:--:--.---");
        lblOffsetDisplay = new JLabel("Offset(median): -- ms");
        for (JLabel l : new JLabel[]{lblLocalClock, lblServerClock, lblOffsetDisplay}) {
            l.setForeground(Color.WHITE); l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        }
        status.add(lblLocalClock); status.add(lblServerClock); status.add(lblOffsetDisplay);

        // ====== Bảng
        model = new DefaultTableModel(new Object[]{"#", "Delay (ms)", "Offset (ms)"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model) {
            // zebra-stripe
            @Override public Component prepareRenderer(javax.swing.table.TableCellRenderer r, int row, int col) {
                Component c = super.prepareRenderer(r, row, col);
                if (!isRowSelected(row)) {
                    c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(0xF7F9FC));
                }
                return c;
            }
        };
        table.setRowHeight(24);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 13));
        ((DefaultTableCellRenderer)table.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(SwingConstants.LEFT);

        JPanel tablePane = card(new JScrollPane(table), "Mẫu đo");

        // ====== Kết quả
        resultArea = new JTextArea(7, 20);
        resultArea.setEditable(false);
        resultArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JPanel resultPane = card(new JScrollPane(resultArea), "Kết quả");

        // ====== Biểu đồ + Đồng hồ analog (tab)
        plotPanel = new PlotPanel();
        analogClockPanel = new AnalogClockPanel();
        analogClockPanel.setPaused(true); // ban đầu dừng cho đến khi sync
        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("Biểu đồ", card(new JScrollPane(plotPanel), null));
        rightTabs.addTab("Đồng hồ", card(analogClockPanel, null));
        rightTabs.setBackground(SURFACE);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tablePane, rightTabs);
        centerSplit.setResizeWeight(0.52);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerSplit, resultPane);
        mainSplit.setResizeWeight(0.68);

        add(header, BorderLayout.NORTH);
        add(status, BorderLayout.SOUTH);
        add(mainSplit, BorderLayout.CENTER);

        // Actions
        btnRun.addActionListener(e -> startRun());
        btnStop.addActionListener(e -> stopRun());
        btnExport.addActionListener(e -> exportCSV());
        btnDiscover.addActionListener(e -> doDiscover());

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { plotPanel.repaint(); analogClockPanel.repaint(); }
        });

        // Timer đồng hồ số & analog
        clockTimer = new javax.swing.Timer(200, e -> updateClocks());
        clockTimer.start();
        updateClocks();
    }

    // ===== Helpers UI
    private static JLabel lw(String s) { JLabel l=new JLabel(s); l.setForeground(new Color(255,255,255,220)); l.setFont(l.getFont().deriveFont(Font.BOLD,13f)); return l; }
    private static JTextField fieldWhite(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255,255,255,120), 1, true),
                BorderFactory.createEmptyBorder(6,8,6,8)));
        f.setBackground(new Color(255,255,255,40));
        f.setForeground(Color.WHITE);
        return f;
    }
    private static JPanel card(Component inner, String title) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(SURFACE);
        if (title != null) {
            JLabel t = new JLabel(title);
            t.setBorder(BorderFactory.createEmptyBorder(10,12,4,12));
            t.setFont(new Font("SansSerif", Font.BOLD, 14));
            p.add(t, BorderLayout.NORTH);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(PANEL);
        wrap.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        wrap.add(inner, BorderLayout.CENTER);
        p.add(wrap, BorderLayout.CENTER);
        return p;
    }
    static class Title extends JLabel {
        Title(String s){ super(s); setForeground(Color.WHITE); setFont(getFont().deriveFont(Font.BOLD, 18f)); }
    }
    static class GradientPanel extends JPanel {
        private final Color c1,c2; GradientPanel(Color a, Color b){ c1=a; c2=b; }
        @Override protected void paintComponent(Graphics g){ Graphics2D g2=(Graphics2D)g.create(); int w=getWidth(),h=getHeight();
            GradientPaint gp=new GradientPaint(0,0,c1, 0,h,c2); g2.setPaint(gp); g2.fillRect(0,0,w,h); g2.dispose(); }
    }
    static class PillButton extends JButton {
        private final Color base;
        PillButton(String text, Color base) { super(text); this.base=base; setOpaque(false); setFocusPainted(false);
            setForeground(Color.WHITE); setBorder(BorderFactory.createEmptyBorder(8,16,8,16)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2=(Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight(); int arc=h;
            Color fill = getModel().isPressed()? base.darker() : (getModel().isRollover()? base.brighter() : base);
            g2.setColor(fill); g2.fillRoundRect(0,0,w,h,arc,arc); super.paintComponent(g); g2.dispose();
        }
        @Override public void updateUI(){ super.updateUI(); setContentAreaFilled(false); setBorderPainted(false); }
    }

    // ===== Đồng hồ số + analog
    private void updateClocks() {
        long localNow = System.currentTimeMillis();
        lblLocalClock.setText("Local: " + CLOCK_FMT.format(Instant.ofEpochMilli(localNow)));

        if (lastMedianOffsetMs == null) {
            lblServerClock.setText("Server(est): --:--:--.---");
            analogClockPanel.setPaused(true); // chưa sync thì dừng
        } else {
            long serverEst = localNow + lastMedianOffsetMs.longValue();
            lblServerClock.setText("Server(est): " + CLOCK_FMT.format(Instant.ofEpochMilli(serverEst)));
            analogClockPanel.setPaused(false);
            analogClockPanel.setCurrentTimeMillis(serverEst);
        }
    }
    private static String fmtMs(double v) { return String.format("%.2f", v); }

    // ===== Logic
    private void startRun() {
        if (worker != null && !worker.isDone()) return;
        String ip = txtIP.getText().trim();
        int port, samples, interval, timeout;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
            samples = Integer.parseInt(txtSamples.getText().trim());
            interval = Integer.parseInt(txtInterval.getText().trim());
            timeout = Integer.parseInt(txtTimeout.getText().trim());
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Tham số không hợp lệ"); return; }

        model.setRowCount(0); resultArea.setText(""); plotPanel.clearData();
        lastMedianOffsetMs = null;
        lblOffsetDisplay.setText("Offset(median): -- ms");
        analogClockPanel.setPaused(true); // bắt đầu thì dừng đồng hồ tới khi có offset
        updateClocks();

        worker = new ClientWorker(
                ip, port, samples, interval, timeout,
                this::appendRow, this::showResult, this::appendLog,
                plotPanel::addPoint, this::setMedianOffset
        );
        worker.execute(); btnRun.setEnabled(false); btnStop.setEnabled(true);
    }
    private void stopRun() {
        if (worker != null) worker.shutdown();
        btnRun.setEnabled(true); btnStop.setEnabled(false);
        analogClockPanel.setPaused(true); // dừng khi stop
        appendLog("Đã dừng.");
    }
    private void setMedianOffset(double offsetMs) {
        SwingUtilities.invokeLater(() -> {
            lastMedianOffsetMs = offsetMs;
            lblOffsetDisplay.setText("Offset(median): " + fmtMs(offsetMs) + " ms");
            analogClockPanel.setPaused(false); // có offset thì chạy
            updateClocks();
        });
    }
    private void appendRow(int idx, long delay, double offset) {
        SwingUtilities.invokeLater(() -> model.addRow(new Object[]{idx, delay, String.format("%.2f", offset)}));
    }
    private void showResult(String text) { SwingUtilities.invokeLater(() -> resultArea.setText(text)); }
    private void appendLog(String text) { SwingUtilities.invokeLater(() -> resultArea.append((resultArea.getText().isEmpty()? "" : "\n") + text)); }

    private void exportCSV() {
        if (model.getRowCount() == 0) { JOptionPane.showMessageDialog(this, "Chưa có dữ liệu để xuất."); return; }
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new File("udp_timesync.csv"));
        int ret = chooser.showSaveDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (FileWriter w = new FileWriter(f)) {
                w.write("index,delay_ms,offset_ms\n");
                for (int i=0;i<model.getRowCount();i++) {
                    w.write(model.getValueAt(i,0)+","+model.getValueAt(i,1)+","+model.getValueAt(i,2)+"\n");
                }
                JOptionPane.showMessageDialog(this, "Đã xuất: " + f.getAbsolutePath());
            } catch (IOException e) { JOptionPane.showMessageDialog(this, "Lỗi ghi file: " + e.getMessage()); }
        }
    }

    private void doDiscover() {
        int port; try { port = Integer.parseInt(txtPort.getText().trim()); } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Port không hợp lệ"); return; }
        appendLog("Đang DISCOVER server qua broadcast...");
        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setBroadcast(true); sock.setSoTimeout(1200);
                byte[] out = "DISCOVER".getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(out, out.length, InetAddress.getByName("255.255.255.255"), port));
                byte[] buf = new byte[256]; DatagramPacket resp = new DatagramPacket(buf, buf.length);
                sock.receive(resp);
                String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.startsWith("HERE")) {
                    String[] parts = msg.split("\\s+");
                    if (parts.length >= 3) {
                        String ip = parts[1]; String prt = parts[2];
                        SwingUtilities.invokeLater(() -> { txtIP.setText(ip); txtPort.setText(prt); });
                        appendLog("Tìm thấy server: " + ip + ":" + prt); return;
                    }
                }
                appendLog("DISCOVER trả về không hợp lệ: " + msg);
            } catch (SocketTimeoutException te) { appendLog("DISCOVER: TIMEOUT. Kiểm tra firewall/mạng."); }
            catch (Exception e) { appendLog("DISCOVER lỗi: " + e.getMessage()); }
        }).start();
    }

    /// ===== Worker
 // ===== Worker
    private static class ClientWorker extends SwingWorker<Void, Void> {
        private final String ip; 
        private final int port, samples, interval, timeout;
        private final java.util.function.Consumer<String> resultPrinter;
        private final TriConsumer<Integer, Long, Double> rowAppender;
        private final java.util.function.Consumer<String> logger;
        private final PlotPointConsumer plotConsumer; 
        private final DoubleConsumer offsetConsumer;
        private volatile boolean running = true;
        private final List<Double> offsets = new ArrayList<>(); 
        private final List<Long> delays = new ArrayList<>();

        // JDBC
        private Connection cn;
        private PreparedStatement psInsertRun;
        private PreparedStatement psInsertSample;
        private PreparedStatement psUpdateRun;
        private long runId = -1L;

        ClientWorker(String ip, int port, int samples, int interval, int timeout,
                     TriConsumer<Integer, Long, Double> rowAppender,
                     java.util.function.Consumer<String> resultPrinter,
                     java.util.function.Consumer<String> logger,
                     PlotPointConsumer plotConsumer,
                     DoubleConsumer offsetConsumer) {
            this.ip=ip; this.port=port; this.samples=samples; this.interval=interval; this.timeout=timeout;
            this.rowAppender=rowAppender; this.resultPrinter=resultPrinter; this.logger=logger;
            this.plotConsumer=plotConsumer; this.offsetConsumer=offsetConsumer;

            try {
                cn = DbHelper.open();                // <-- đảm bảo trả về Connection tới schema udp_time
                cn.setAutoCommit(false);

                // 1) Insert 1 dòng vào runs (để lấy runId). started_at có DEFAULT CURRENT_TIMESTAMP nên không cần set.
                psInsertRun = cn.prepareStatement(
                    "INSERT INTO runs(server_ip, port, samples, interval_ms, timeout_ms) VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                psInsertRun.setString(1, ip);
                psInsertRun.setInt(2, port);
                psInsertRun.setInt(3, samples);
                psInsertRun.setInt(4, interval);
                psInsertRun.setInt(5, timeout);
                psInsertRun.executeUpdate();
                try (ResultSet rs = psInsertRun.getGeneratedKeys()) {
                    if (rs.next()) runId = rs.getLong(1);
                }
                logger.accept("[DB] runs.id = " + runId);

                // 2) Chuẩn bị statement insert samples (batch)
                psInsertSample = cn.prepareStatement(
                    "INSERT INTO samples(run_id, sample_index, delay_ms, offset_ms, created_at) VALUES(?,?,?,?,NOW())"
                );

                // 3) Chuẩn bị update run khi kết thúc (đúng tên cột của bạn: median_offset)
                psUpdateRun = cn.prepareStatement(
                    "UPDATE runs SET finished_at = NOW(), median_offset = ?, avg_delay = ? WHERE id = ?"
                );

            } catch (Exception ex) {
                logger.accept("[DB] Không mở được DB: " + ex.getMessage());
            }
        }

        private static long nowMs(){ return System.currentTimeMillis(); }

        @Override protected Void doInBackground() {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(timeout); 
                InetAddress serverAddr = InetAddress.getByName(ip);

                for (int i=1; i<=samples && running; i++) {
                    try {
                        long t0 = nowMs();
                        byte[] out = ("REQ " + t0).getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(out, out.length, serverAddr, port));
                        byte[] buf = new byte[1024]; 
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        socket.receive(resp); 
                        long t3 = nowMs();

                        String data = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8).trim();
                        String[] parts = data.split("\\s+");
                        if (parts.length==3 && "RESP".equals(parts[0])) {
                            long t1 = Long.parseLong(parts[1]); 
                            long t2 = Long.parseLong(parts[2]);
                            long delay = (t3 - t0) - (t2 - t1);
                            double offset = ((t1 - t0) + (t2 - t3)) / 2.0;

                            delays.add(delay); 
                            offsets.add(offset);

                            rowAppender.accept(i, delay, offset); 
                            plotConsumer.accept(i, delay, offset);

                            // Ghi DB (nếu đã mở kết nối & có runId)
                            if (psInsertSample != null && runId > 0) {
                                psInsertSample.setLong(1, runId);
                                psInsertSample.setInt(2, i);
                                psInsertSample.setLong(3, delay);
                                psInsertSample.setDouble(4, offset);
                                psInsertSample.addBatch();
                            }
                        } else {
                            logger.accept("Gói phản hồi không hợp lệ: " + data);
                        }
                    } catch (SocketTimeoutException te) { 
                        logger.accept("["+i+"/"+samples+"] TIMEOUT"); 
                    } catch (IOException ioe) { 
                        logger.accept("["+i+"/"+samples+"] Lỗi IO: " + ioe.getMessage()); 
                    }
                    try { Thread.sleep(Math.max(0, interval)); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) { 
                logger.accept("Lỗi: " + e.getMessage()); 
            }

            // Flush batch & commit lần 1 (để chắc chắn dữ liệu runs + samples đã lưu)
            try {
                if (psInsertSample != null) {
                    psInsertSample.executeBatch();
                }
                if (cn != null) cn.commit();
                logger.accept("[DB] Đã commit samples.");
            } catch (Exception e) {
                logger.accept("[DB] Batch insert lỗi: " + e.getMessage());
                try { if (cn != null) cn.rollback(); } catch (Exception ignore) {}
            }

            if (offsets.isEmpty() || delays.isEmpty()) { 
                resultPrinter.accept("Không có mẫu hợp lệ. Kiểm tra IP/cổng/firewall/mạng."); 
                return null; 
            }

            // Tính tổng kết
            Collections.sort(offsets);
            double medianOffset = offsets.get(offsets.size()/2);
            double avgDelay = delays.stream().mapToLong(Long::longValue).average().orElse(Double.NaN);
            long localNow = nowMs(); 
            long serverNowEst = (long) (localNow + medianOffset);
            offsetConsumer.accept(medianOffset);

            // Update run summary & commit lần 2
            try {
                if (psUpdateRun != null && runId > 0) {
                    psUpdateRun.setDouble(1, medianOffset);
                    psUpdateRun.setDouble(2, avgDelay);
                    psUpdateRun.setLong(3, runId);
                    psUpdateRun.executeUpdate();
                }
                if (cn != null) cn.commit();
                logger.accept("[DB] Đã cập nhật runs(median_offset, avg_delay).");
            } catch (Exception e) {
                logger.accept("[DB] UPDATE runs lỗi: " + e.getMessage());
                // Không rollback lần 2 để giữ dữ liệu samples đã commit ở trên
            }

            String summary = new StringBuilder()
                    .append("KẾT QUẢ ĐỒNG BỘ (logic)\n")
                    .append("- Số mẫu hợp lệ: ").append(offsets.size()).append("\n")
                    .append(String.format("- Độ trễ trung bình: %.2f ms%n", avgDelay))
                    .append(String.format("- Offset (median): %.2f ms  (client ≈ server + %.2f ms)%n", medianOffset, -medianOffset))
                    .append("- Local now (ms):  ").append(localNow).append("\n")
                    .append("- Server est (ms): ").append(serverNowEst).toString();

            resultPrinter.accept(summary); 
            return null;
        }

        @Override protected void done() {
            try { if (psInsertSample != null) psInsertSample.close(); } catch (Exception ignore) {}
            try { if (psInsertRun != null) psInsertRun.close(); } catch (Exception ignore) {}
            try { if (psUpdateRun != null) psUpdateRun.close(); } catch (Exception ignore) {}
            try { if (cn != null) cn.close(); } catch (Exception ignore) {}
        }

        void shutdown(){ running=false; cancel(true); }
    }




    // ===== Biểu đồ (có grid & stroke dày)
    private static class PlotPanel extends JPanel {
        private final java.util.List<Integer> xs = new ArrayList<>();
        private final java.util.List<Long> delays = new ArrayList<>();
        private final java.util.List<Double> offsets = new ArrayList<>();
        void addPoint(int x, long delay, double offset){ SwingUtilities.invokeLater(()->{ xs.add(x); delays.add(delay); offsets.add(offset); repaint(); }); }
        void clearData(){ xs.clear(); delays.clear(); offsets.clear(); repaint(); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight(); int pad=42;
            int x0=pad, y0=h-pad, x1=w-pad, y1=pad;

            // card bg
            g2.setColor(Color.WHITE); g2.fillRect(x0-10, y1-10, (x1-x0)+20, (y0-y1)+20);

            // grid
            g2.setColor(GRID);
            for (int i=0;i<6;i++){
                int y = y1 + i*(y0-y1)/5; g2.drawLine(x0,y,x1,y);
            }
            for (int i=0;i<6;i++){
                int x = x0 + i*(x1-x0)/5; g2.drawLine(x,y1,x,y0);
            }

            // axes frame
            g2.setColor(new Color(200,205,215));
            g2.drawRect(x0, y1, x1-x0, y0-y1);
            g2.setColor(new Color(120,130,140));
            g2.drawString("Index", (x0+x1)/2-10, h-12);
            g2.drawString("ms", 14, (y0+y1)/2);

            if (xs.isEmpty()) return;
            int n=xs.size(); int maxIdx=xs.get(n-1);

            long maxDelay = Math.max(1, delays.stream().mapToLong(v->v).max().orElse(1));
            long minDelay = delays.stream().mapToLong(v->v).min().orElse(0);
            double maxOffset = Math.max(1, offsets.stream().mapToDouble(v->v).max().orElse(1));
            double minOffset = offsets.stream().mapToDouble(v->v).min().orElse(0);

            double vMax = Math.max(maxDelay, Math.max(Math.abs(maxOffset), Math.abs(minOffset)));
            double vMin = Math.min(minDelay, Math.min(-Math.abs(maxOffset), minOffset));
            if (vMax==vMin){ vMax+=1; vMin-=1; }

            int yZero = y0 - (int)((0 - vMin)/(vMax - vMin)*(y0-y1));
            g2.setColor(new Color(210,210,210));
            g2.drawLine(x0,yZero,x1,yZero);

            // Delay series
            g2.setColor(PRIMARY);
            g2.setStroke(new BasicStroke(2.5f));
            int px=-1, py=-1;
            for (int i=0;i<n;i++){
                int xi = x0 + (int)((xs.get(i)-1)/ (double)Math.max(1, maxIdx-1) * (x1-x0));
                int yi = y0 - (int)((delays.get(i)-vMin)/(vMax - vMin)*(y0-y1));
                g2.fillOval(xi-3, yi-3, 6, 6);
                if (px>=0) g2.drawLine(px,py,xi,yi); px=xi; py=yi;
            }
            g2.drawString("Delay", x1-70, y1+18);

            // Offset series
            g2.setColor(ACCENT);
            g2.setStroke(new BasicStroke(2.5f));
            px=-1; py=-1;
            for (int i=0;i<n;i++){
                int xi = x0 + (int)((xs.get(i)-1)/ (double)Math.max(1, maxIdx-1) * (x1-x0));
                int yi = y0 - (int)((offsets.get(i)-vMin)/(vMax - vMin)*(y0-y1));
                g2.fillRect(xi-3, yi-3, 6, 6);
                if (px>=0) g2.drawLine(px,py,xi,yi); px=xi; py=yi;
            }
            g2.drawString("Offset", x1-70, y1+34);
        }
    }

    // ===== Đồng hồ analog (có số 1–12, hỗ trợ paused)
    private static class AnalogClockPanel extends JPanel {
        private volatile long currentTimeMs = System.currentTimeMillis();
        private volatile boolean paused = true;

        AnalogClockPanel(){ setPreferredSize(new Dimension(460, 360)); setBackground(SURFACE); }
        void setCurrentTimeMillis(long t){ currentTimeMs=t; SwingUtilities.invokeLater(this::repaint); }
        void setPaused(boolean p){ paused=p; SwingUtilities.invokeLater(this::repaint); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(), h=getHeight();
            int size=Math.min(w, h)-40;
            int cx=w/2, cy=h/2;
            int r=size/2;

            // mặt đồng hồ
            g2.setColor(Color.WHITE);
            g2.fillOval(cx-r, cy-r, 2*r, 2*r);
            g2.setColor(new Color(220,225,235));
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(cx-r, cy-r, 2*r, 2*r);

            // vạch phút/giờ
            for (int i=0;i<60;i++){
                double ang=Math.toRadians(i*6-90);
                int r1=r-(i%5==0? 14:7);
                int x1=cx+(int)(Math.cos(ang)*r1), y1=cy+(int)(Math.sin(ang)*r1);
                int x2=cx+(int)(Math.cos(ang)*r),  y2=cy+(int)(Math.sin(ang)*r);
                g2.setColor(i%5==0? new Color(180,186,198): new Color(210,214,222));
                g2.drawLine(x1,y1,x2,y2);
            }

            // số 1–12
            g2.setColor(new Color(0x334155));
            float fontSize = Math.max(12f, r * 0.12f);
            Font f = getFont().deriveFont(Font.BOLD, fontSize);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            int numRadius = (int)(r * 0.72);

            for (int i=1; i<=12; i++) {
                double ang = Math.toRadians(i*30 - 90);
                int tx = cx + (int)(numRadius * Math.cos(ang));
                int ty = cy + (int)(numRadius * Math.sin(ang));
                String s = String.valueOf(i);
                g2.drawString(s, tx - fm.stringWidth(s)/2, ty + fm.getAscent()/3);
            }

            if (paused) {
                g2.setColor(new Color(255,255,255,180));
                g2.fillOval(cx-r, cy-r, 2*r, 2*r);
                g2.setColor(new Color(120,120,120));
                g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
                String txt="Waiting…";
                g2.drawString(txt, cx - g2.getFontMetrics().stringWidth(txt)/2, cy+5);
                return;
            }

            // kim
            java.util.Calendar cal=java.util.Calendar.getInstance();
            cal.setTimeInMillis(currentTimeMs);
            int ms = cal.get(java.util.Calendar.MILLISECOND);
            int sec= cal.get(java.util.Calendar.SECOND);
            int min= cal.get(java.util.Calendar.MINUTE);
            int hr = cal.get(java.util.Calendar.HOUR_OF_DAY)%12;

            double secAng=Math.toRadians((sec + ms/1000.0)*6 - 90);
            double minAng=Math.toRadians((min + sec/60.0)*6 - 90);
            double hrAng =Math.toRadians((hr  + min/60.0)*30 - 90);

            g2.setColor(TEXT);
            g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawHand(g2,cx,cy,hrAng,(int)(r*0.55));
            g2.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawHand(g2,cx,cy,minAng,(int)(r*0.75));
            g2.setColor(new Color(0xEF4444));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawHand(g2,cx,cy,secAng,(int)(r*0.85));

            g2.setColor(PRIMARY);
            g2.fillOval(cx-5, cy-5, 10, 10);
        }
        private static void drawHand(Graphics2D g2,int cx,int cy,double ang,int len){
            int x=cx+(int)(Math.cos(ang)*len); int y=cy+(int)(Math.sin(ang)*len); g2.drawLine(cx,cy,x,y);
        }
    }

    @FunctionalInterface private interface TriConsumer<A,B,C>{ void accept(A a,B b,C c); }
    @FunctionalInterface private interface PlotPointConsumer{ void accept(int idx,long delay,double offset); }
    

    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new TimeClientGUI().setVisible(true)); }
}

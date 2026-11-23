package br.com.wirelessbattery;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

public final class Main {
    private final JFrame window = new JFrame("Wireless Battery - Java");
    private final JLabel status = new JLabel("Procurando receptor Fallen / Areson...");
    private final JTextArea log = new JTextArea(8, 44);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "battery-poll"); t.setDaemon(true); return t;
    });
    private AresonReader reader;
    private TrayIcon tray;
    private volatile boolean closed;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().start());
    }
    private void start() {
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        window.setLayout(new BorderLayout(12, 12));
        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(BorderFactory.createEmptyBorder(16, 16, 4, 16));
        status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
        north.add(status, BorderLayout.CENTER);
        window.add(north, BorderLayout.NORTH);
        log.setEditable(false);
        window.add(new JScrollPane(log), BorderLayout.CENTER);
        JButton refresh = new JButton("Atualizar agora");
        JButton exit = new JButton("Sair");
        refresh.addActionListener(e -> worker.execute(this::poll));
        exit.addActionListener(e -> shutdown());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(refresh); south.add(exit);
        window.add(south, BorderLayout.SOUTH);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        setupTray();
        worker.execute(() -> {
            try { reader = new AresonReader(); }
            catch (Exception e) { update("Erro inicializando HID: " + e.getMessage(), -1); return; }
            worker.scheduleWithFixedDelay(this::poll, 0, 20, TimeUnit.SECONDS);
        });
    }
    private void poll() {
        if (closed || reader == null) return;
        try {
            List<BatteryStatus> devices = reader.poll();
            if (devices.isEmpty()) {
                update("Nenhuma leitura: mouse desconectado, dormindo ou acesso HID indisponivel.", -1);
            } else {
                BatteryStatus d = devices.get(0);
                update(d.name() + " - " + d.percent() + "%" + (d.charging() ? " (carregando)" : ""), d.percent());
            }
        } catch (Exception e) { update("Erro consultando dispositivo: " + e.getMessage(), -1); }
    }
    private void update(String message, int percent) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            log.append(java.time.LocalTime.now().withNano(0) + "  " + message + "\n");
            if (log.getLineCount() > 250) log.setText("");
            if (tray != null) {
                tray.setImage(icon(percent));
                tray.setToolTip(message.length() > 120 ? message.substring(0, 120) : message);
            }
        });
    }
    private static Image icon(int level) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(40, 44, 52)); g.fillRoundRect(3, 3, 58, 58, 14, 14);
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3));
        g.drawRoundRect(9, 14, 42, 34, 5, 5); g.fillRoundRect(52, 24, 5, 13, 2, 2);
        g.setColor(level < 0 ? Color.GRAY : level < 20 ? new Color(240, 80, 80) : new Color(90, 210, 130));
        g.fillRect(14, 19, level < 0 ? 0 : (int)(32 * level / 100.0), 24);
        g.dispose(); return img;
    }
    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem show = new MenuItem("Abrir monitor");
        show.addActionListener(e -> SwingUtilities.invokeLater(() -> window.setVisible(true)));
        MenuItem quit = new MenuItem("Sair");
        quit.addActionListener(e -> shutdown());
        menu.add(show); menu.add(quit);
        tray = new TrayIcon(icon(-1), "Wireless Battery Java", menu);
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> SwingUtilities.invokeLater(() -> window.setVisible(true)));
        try { SystemTray.getSystemTray().add(tray); }
        catch (AWTException e) { tray = null; }
    }
    private void shutdown() {
        closed = true;
        worker.shutdownNow();
        if (reader != null) reader.close();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        window.dispose();
        System.exit(0);
    }
}

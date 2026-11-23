package br.com.wirelessbattery.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Garante instância única do aplicativo por usuário no Windows utilizando {@link FileLock}
 * no diretório de configurações ({@code ~/.wireless-battery-java/instance.lock}).
 *
 * Quando uma segunda instância é iniciada, ela sinaliza a instância principal (via arquivo
 * {@code show.signal}) para restaurar/trazer a janela principal à frente e encerra imediatamente.
 */
public final class SingleInstanceGuard implements AutoCloseable {
    private static final Path BASE_DIR = Path.of(
            System.getProperty("user.home"), ".wireless-battery-java"
    );
    private static final Path LOCK_PATH = BASE_DIR.resolve("instance.lock");
    private static final Path SIGNAL_PATH = BASE_DIR.resolve("show.signal");

    private final Path lockPath;
    private final Path signalPath;
    private FileChannel channel;
    private FileLock lock;
    private Timer signalWatchTimer;
    private String lastDiagnosticMessage = "";

    public SingleInstanceGuard() {
        this(LOCK_PATH, SIGNAL_PATH);
    }

    public SingleInstanceGuard(Path lockPath, Path signalPath) {
        this.lockPath = lockPath;
        this.signalPath = signalPath;
    }

    public Path getLockPath() {
        return lockPath;
    }

    public String getLastDiagnosticMessage() {
        return lastDiagnosticMessage;
    }

    /**
     * Tenta adquirir a trava de instância única e sinaliza a instância existente caso já esteja em execução.
     *
     * @return {@code true} se esta é a instância principal; {@code false} se outra instância já estiver ativa.
     */
    public boolean tryAcquireOrSignalExisting() {
        return tryAcquireOrSignalExisting(true);
    }

    /**
     * Tenta adquirir a trava de instância única.
     *
     * @param signalRestoreIfLocked se {@code true}, grava {@code show.signal} para restaurar a janela da instância ativa
     * @return {@code true} se esta é a instância principal; {@code false} se outra instância já estiver ativa.
     */
    public boolean tryAcquireOrSignalExisting(boolean signalRestoreIfLocked) {
        try {
            Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String previousRecordedPid = readRecordedPidQuietly();
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException overlap) {
                lock = null;
            }
            if (lock == null) {
                closeChannelQuietly();
                if (signalRestoreIfLocked) {
                    signalExistingInstance();
                }
                lastDiagnosticMessage = "Outra instância ativa detém a trava em " + lockPath
                        + (previousRecordedPid.isEmpty() ? "" : " (PID=" + previousRecordedPid + ")");
                return false;
            }
            clearSignalFileQuietly();
            long currentPid = ProcessHandle.current().pid();
            writePidToLockFile(currentPid);
            if (!previousRecordedPid.isEmpty() && !previousRecordedPid.equals(Long.toString(currentPid))) {
                lastDiagnosticMessage = "Trava de instância única adquirida (PID=" + currentPid
                        + ", arquivo anterior de PID=" + previousRecordedPid + " reutilizado sem bloqueio)";
            } else {
                lastDiagnosticMessage = "Trava de instância única adquirida (PID=" + currentPid + ")";
            }
            return true;
        } catch (IOException e) {
            closeChannelQuietly();
            lastDiagnosticMessage = "Aviso ao abrir trava de instância única (" + e.getMessage()
                    + ") — prosseguindo inicialização";
            return true;
        }
    }

    /**
     * Inicia o monitoramento leve na EDT para restaurar a janela principal quando outra
     * tentativa de abertura do executável for detectada.
     */
    public void startActivateListener(Runnable onActivateRequested) {
        if (onActivateRequested == null || signalWatchTimer != null) {
            return;
        }
        signalWatchTimer = new Timer(800, e -> {
            if (Files.isRegularFile(signalPath)) {
                clearSignalFileQuietly();
                SwingUtilities.invokeLater(onActivateRequested);
            }
        });
        signalWatchTimer.setRepeats(true);
        signalWatchTimer.start();
    }

    private void signalExistingInstance() {
        try {
            Path parent = signalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    signalPath,
                    Long.toString(System.currentTimeMillis()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException ignored) {
            // Melhor esforço para sinalizar instância existente
        }
    }

    private String readRecordedPidQuietly() {
        try {
            if (Files.isRegularFile(lockPath)) {
                return Files.readString(lockPath, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            // Ignorado
        }
        return "";
    }

    private void writePidToLockFile(long currentPid) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.truncate(0);
                byte[] pidBytes = Long.toString(currentPid).getBytes(StandardCharsets.UTF_8);
                channel.write(ByteBuffer.wrap(pidBytes), 0);
                channel.force(false);
            }
        } catch (IOException ignored) {
            // Metadado informativo apenas
        }
    }

    private void clearSignalFileQuietly() {
        try {
            Files.deleteIfExists(signalPath);
        } catch (IOException ignored) {
            // Ignorado
        }
    }

    private void closeChannelQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Ignorado
            }
            channel = null;
        }
    }

    @Override
    public void close() {
        if (signalWatchTimer != null) {
            signalWatchTimer.stop();
            signalWatchTimer = null;
        }
        if (lock != null) {
            clearSignalFileQuietly();
            try {
                lock.release();
            } catch (IOException ignored) {
                // Ignorado
            }
            lock = null;
        }
        closeChannelQuietly();
    }
}

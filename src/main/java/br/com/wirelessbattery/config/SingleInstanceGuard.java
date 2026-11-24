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
    private static final Path SHUTDOWN_SIGNAL_PATH = BASE_DIR.resolve("shutdown.signal");

    private final Path lockPath;
    private final Path signalPath;
    private final Path shutdownSignalPath;
    private FileChannel channel;
    private FileLock lock;
    private Timer signalWatchTimer;
    private String lastDiagnosticMessage = "";

    public SingleInstanceGuard() {
        this(LOCK_PATH, SIGNAL_PATH, SHUTDOWN_SIGNAL_PATH);
    }

    public SingleInstanceGuard(Path lockPath, Path signalPath) {
        this(
                lockPath,
                signalPath,
                (lockPath.getParent() != null ? lockPath.getParent() : BASE_DIR).resolve("shutdown.signal")
        );
    }

    public SingleInstanceGuard(Path lockPath, Path signalPath, Path shutdownSignalPath) {
        this.lockPath = lockPath;
        this.signalPath = signalPath;
        this.shutdownSignalPath = shutdownSignalPath;
    }

    public Path getShutdownSignalPath() {
        return shutdownSignalPath;
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
            clearShutdownSignalFileQuietly();
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
        startLifecycleListeners(onActivateRequested, null);
    }

    /**
     * Inicia o monitoramento leve na EDT tanto para restauração de janela ({@code show.signal})
     * quanto para encerramento seguro solicitado por atualizador/instalador ({@code shutdown.signal}).
     */
    public void startLifecycleListeners(Runnable onActivateRequested, Runnable onShutdownRequested) {
        if ((onActivateRequested == null && onShutdownRequested == null) || signalWatchTimer != null) {
            return;
        }
        signalWatchTimer = new Timer(500, e -> {
            if (onShutdownRequested != null && Files.isRegularFile(shutdownSignalPath)) {
                clearShutdownSignalFileQuietly();
                clearSignalFileQuietly();
                SwingUtilities.invokeLater(onShutdownRequested);
                return;
            }
            if (onActivateRequested != null && Files.isRegularFile(signalPath)) {
                clearSignalFileQuietly();
                SwingUtilities.invokeLater(onActivateRequested);
            }
        });
        signalWatchTimer.setRepeats(true);
        signalWatchTimer.start();
    }

    /**
     * Solicita o encerramento seguro de uma instância em execução (via {@code shutdown.signal})
     * e aguarda até que a trava {@code instance.lock} seja liberada.
     *
     * @param timeoutMillis tempo máximo de espera em milissegundos
     * @return {@code true} se nenhuma instância estiver ativa ao final do prazo
     */
    public boolean requestRunningInstanceShutdown(long timeoutMillis) {
        if (isLockCurrentlyFree()) {
            clearShutdownSignalFileQuietly();
            return true;
        }
        try {
            Path parent = shutdownSignalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    shutdownSignalPath,
                    Long.toString(System.currentTimeMillis()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException ignored) {
            // Melhor esforço
        }
        long deadline = System.currentTimeMillis() + Math.max(200L, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (isLockCurrentlyFree()) {
                clearShutdownSignalFileQuietly();
                return true;
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isLockCurrentlyFree();
    }

    private boolean isLockCurrentlyFree() {
        if (!Files.exists(lockPath)) {
            return true;
        }
        try (FileChannel probe = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            try (FileLock probeLock = probe.tryLock()) {
                return probeLock != null;
            } catch (OverlappingFileLockException overlap) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
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

    private void clearShutdownSignalFileQuietly() {
        try {
            Files.deleteIfExists(shutdownSignalPath);
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
            clearShutdownSignalFileQuietly();
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

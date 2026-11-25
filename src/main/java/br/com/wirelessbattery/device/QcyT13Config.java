package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuração de identificação Bluetooth do headset QCY T13.
 *
 * <p>Permite personalizar o padrão de nome ({@code wirelessbattery.qcyt13.namePattern}) via
 * propriedade do sistema ({@code -D}) ou arquivo {@code devices.properties} local.
 * Por padrão, reconhece variações contendo os tokens {@code qcy} e {@code t13}
 * (ex.: {@code "QCY T13"}, {@code "QCY-T13"}, {@code "QCY_T13"}, {@code "QCY T13 ANC"}).
 */
public final class QcyT13Config {

    public static final String PROPERTY_NAME_PATTERN = "wirelessbattery.qcyt13.namePattern";
    public static final String DEFAULT_NAME_PATTERN = "qcy.*t13(?!\\d)";

    private final String namePattern;
    private final Pattern compiledPattern;

    public QcyT13Config(String namePattern) {
        String effective = (namePattern == null || namePattern.isBlank())
                ? DEFAULT_NAME_PATTERN
                : namePattern.trim();
        this.namePattern = effective;
        this.compiledPattern = compileQuietly(effective);
    }

    public static QcyT13Config defaultReference() {
        return new QcyT13Config(DEFAULT_NAME_PATTERN);
    }

    /**
     * Carrega overrides opcionais a partir de propriedades do sistema ou de um arquivo
     * {@code devices.properties} na pasta de execução, caso exista.
     */
    public static QcyT13Config loadFromEnvironment() {
        Properties props = new Properties();
        Path localConfig = Path.of("devices.properties");
        if (Files.isRegularFile(localConfig)) {
            try (InputStream in = Files.newInputStream(localConfig)) {
                props.load(in);
            } catch (IOException ignored) {
                // Usa padrões de referência se o arquivo não puder ser lido.
            }
        }
        String pattern = resolve(PROPERTY_NAME_PATTERN, props);
        return new QcyT13Config(pattern);
    }

    private static String resolve(String key, Properties fileProps) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fileVal = fileProps.getProperty(key);
        return (fileVal != null && !fileVal.isBlank()) ? fileVal.trim() : null;
    }

    private static Pattern compileQuietly(String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException ignored) {
            return null;
        }
    }

    public String getNamePattern() {
        return namePattern;
    }

    /**
     * Verifica se o nome amigável do dispositivo Bluetooth corresponde ao QCY T13 configurado.
     */
    public boolean matchesDeviceName(String friendlyName) {
        if (friendlyName == null || friendlyName.isBlank()) {
            return false;
        }
        if (compiledPattern != null) {
            return compiledPattern.matcher(friendlyName).find();
        }
        return friendlyName.toLowerCase(Locale.ROOT).contains(namePattern.toLowerCase(Locale.ROOT));
    }
}

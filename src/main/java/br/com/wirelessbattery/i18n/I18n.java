package br.com.wirelessbattery.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Serviço centralizado e thread-safe de internacionalização baseado em {@link ResourceBundle}.
 */
public final class I18n {
    public static final String BUNDLE_BASE = "i18n.messages";
    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        public List<Locale> getCandidateLocales(String baseName, Locale locale) {
            if (locale != null && "pt".equalsIgnoreCase(locale.getLanguage())) {
                return List.of(Locale.forLanguageTag("pt-BR"));
            }
            return List.of(Locale.US);
        }

        @Override
        public ResourceBundle newBundle(
                String baseName,
                Locale locale,
                String format,
                ClassLoader loader,
                boolean reload
        ) throws IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    return null;
                }
                return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
    };

    private volatile AppLanguage configuredLanguage;
    private volatile Locale effectiveLocale;
    private volatile ResourceBundle bundle;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public I18n() {
        this(AppLanguage.SYSTEM);
    }

    public I18n(AppLanguage initialLanguage) {
        setLanguage(initialLanguage != null ? initialLanguage : AppLanguage.SYSTEM);
    }

    public synchronized void setLanguage(AppLanguage language) {
        AppLanguage target = language != null ? language : AppLanguage.SYSTEM;
        Locale resolved = target.resolveLocale();
        ResourceBundle loaded = ResourceBundle.getBundle(BUNDLE_BASE, resolved, UTF8_CONTROL);
        this.configuredLanguage = target;
        this.effectiveLocale = resolved;
        this.bundle = loaded;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public AppLanguage getConfiguredLanguage() {
        return configuredLanguage;
    }

    public Locale getEffectiveLocale() {
        return effectiveLocale;
    }

    public boolean isPortuguese() {
        return effectiveLocale != null && "pt".equalsIgnoreCase(effectiveLocale.getLanguage());
    }

    public String t(String key, Object... args) {
        Objects.requireNonNull(key, "key não pode ser nulo");
        ResourceBundle active = this.bundle;
        String pattern;
        try {
            pattern = active.getString(key);
        } catch (MissingResourceException ex) {
            return "!" + key + "!";
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        MessageFormat fmt = new MessageFormat(pattern, effectiveLocale);
        return fmt.format(args);
    }

    public void addLanguageListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeLanguageListener(Runnable listener) {
        listeners.remove(listener);
    }
}

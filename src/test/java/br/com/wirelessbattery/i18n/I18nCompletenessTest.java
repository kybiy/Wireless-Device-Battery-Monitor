package br.com.wirelessbattery.i18n;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.ReadingAgeFormatter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class I18nCompletenessTest {

    @Test
    void ptBrAndEnUsBundlesShareExactSameNonBlankKeysAndPreserveAccents() throws Exception {
        Properties pt = loadProps("i18n/messages_pt_BR.properties");
        Properties en = loadProps("i18n/messages_en_US.properties");

        Set<String> ptKeys = new TreeSet<>(pt.stringPropertyNames());
        Set<String> enKeys = new TreeSet<>(en.stringPropertyNames());

        Set<String> missingInEn = new TreeSet<>(ptKeys);
        missingInEn.removeAll(enKeys);
        Set<String> missingInPt = new TreeSet<>(enKeys);
        missingInPt.removeAll(ptKeys);

        assertTrue(missingInEn.isEmpty(), "Chaves ausentes em messages_en_US.properties: " + missingInEn);
        assertTrue(missingInPt.isEmpty(), "Chaves ausentes em messages_pt_BR.properties: " + missingInPt);
        assertFalse(ptKeys.isEmpty());

        for (String key : ptKeys) {
            assertFalse(pt.getProperty(key, "").isBlank(), "Valor vazio em pt-BR para chave: " + key);
            assertFalse(en.getProperty(key, "").isBlank(), "Valor vazio em en-US para chave: " + key);
        }

        // Verifica preservação de acentos UTF-8
        assertTrue(pt.getProperty("app.simulationBanner").contains("simulação"));
        assertTrue(pt.getProperty("app.historyLink").contains("Histórico"));
    }

    @Test
    void translatesStatusesReadingAgesAndSwitchesLanguagesDynamically() {
        I18n i18n = new I18n(AppLanguage.PT_BR);
        assertEquals("Português (BR)", i18n.isPortuguese() ? "Português (BR)" : "EN");
        assertEquals("Conectado", ConnectionStatus.CONNECTED.getLocalizedLabel(i18n));
        assertEquals("Bloqueado (modo somente leitura)", ConnectionStatus.READ_ONLY_BLOCKED.getLocalizedLabel(i18n));

        Instant now = Instant.parse("2026-11-26T15:00:00Z");
        assertEquals("Atualizado agora", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(5), now, i18n, false));
        assertEquals("Atualizado há 30 segundos", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(30), now, i18n, false));
        assertEquals("última leitura há 12 min", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(12 * 60), now, i18n, true));
        assertEquals("última leitura há 2 h", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(2 * 3600), now, i18n, true));

        i18n.setLanguage(AppLanguage.EN_US);
        assertFalse(i18n.isPortuguese());
        assertEquals("Connected", ConnectionStatus.CONNECTED.getLocalizedLabel(i18n));
        assertEquals("Blocked (read-only mode)", ConnectionStatus.READ_ONLY_BLOCKED.getLocalizedLabel(i18n));
        assertEquals("Updated just now", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(5), now, i18n, false));
        assertEquals("Updated 30 seconds ago", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(30), now, i18n, false));
        assertEquals("last reading 12 min ago", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(12 * 60), now, i18n, true));
        assertEquals("last reading 2 h ago", ReadingAgeFormatter.formatRelativeAge(now.minusSeconds(2 * 3600), now, i18n, true));
    }

    private static Properties loadProps(String resourcePath) throws Exception {
        Properties props = new Properties();
        try (InputStream in = I18nCompletenessTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Resource não encontrado: " + resourcePath);
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }
}

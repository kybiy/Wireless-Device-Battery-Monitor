package br.com.wirelessbattery.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;

/**
 * Sistema de design e gerenciador de temas (Escuro / Claro) inspirado nos utilitários
 * nativos do Windows 11, com paleta discreta e harmoniosa.
 */
public final class ThemeManager {
    public enum Theme {
        DARK("Escuro"),
        LIGHT("Claro");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // Paleta Escura (padrão)
    public static final Color DARK_BG_PRIMARY = new Color(0x10, 0x11, 0x14);      // #101114
    public static final Color DARK_BG_CARD = new Color(0x1A, 0x1C, 0x20);         // #1A1C20
    public static final Color DARK_BG_CARD_HOVER = new Color(0x1F, 0x22, 0x28);   // #1F2228
    public static final Color DARK_BG_ELEVATED = new Color(0x22, 0x25, 0x2B);     // #22252B
    public static final Color DARK_BG_TRACK = new Color(0x2B, 0x2F, 0x37);        // #2B2F37
    public static final Color DARK_TEXT_PRIMARY = new Color(0xF1, 0xF3, 0xF5);    // #F1F3F5
    public static final Color DARK_TEXT_SECONDARY = new Color(0x9C, 0xA3, 0xAF);  // #9CA3AF
    public static final Color DARK_TEXT_MUTED = new Color(0x6B, 0x72, 0x80);      // #6B7280
    public static final Color DARK_BORDER_SUBTLE = new Color(0x28, 0x2C, 0x34);   // #282C34
    public static final Color DARK_BORDER_CARD = new Color(0x24, 0x27, 0x2E);     // #24272E
    public static final Color DARK_ACCENT = new Color(0x7C, 0x9E, 0xFF);          // #7C9EFF

    // Paleta Clara equivalente
    public static final Color LIGHT_BG_PRIMARY = new Color(0xF4, 0xF5, 0xF7);     // #F4F5F7
    public static final Color LIGHT_BG_CARD = new Color(0xFF, 0xFF, 0xFF);        // #FFFFFF
    public static final Color LIGHT_BG_CARD_HOVER = new Color(0xFA, 0xFB, 0xFC);  // #FAFBFC
    public static final Color LIGHT_BG_ELEVATED = new Color(0xED, 0xF0, 0xF5);    // #EDF0F5
    public static final Color LIGHT_BG_TRACK = new Color(0xE2, 0xE6, 0xEE);       // #E2E6EE
    public static final Color LIGHT_TEXT_PRIMARY = new Color(0x11, 0x18, 0x27);   // #111827
    public static final Color LIGHT_TEXT_SECONDARY = new Color(0x6B, 0x72, 0x80); // #6B7280
    public static final Color LIGHT_TEXT_MUTED = new Color(0x9C, 0xA3, 0xAF);     // #9CA3AF
    public static final Color LIGHT_BORDER_SUBTLE = new Color(0xE2, 0xE5, 0xEC);  // #E2E5EC
    public static final Color LIGHT_BORDER_CARD = new Color(0xE4, 0xE7, 0xEE);    // #E4E7EE
    public static final Color LIGHT_ACCENT = new Color(0x4F, 0x6B, 0xED);         // #4F6BED

    private Theme currentTheme = Theme.DARK;

    public void installInitialTheme(Theme theme) {
        this.currentTheme = theme;
        applyLookAndFeel(theme);
    }

    public Theme toggleTheme() {
        Theme next = (currentTheme == Theme.DARK) ? Theme.LIGHT : Theme.DARK;
        applyTheme(next);
        return next;
    }

    public void applyTheme(Theme theme) {
        this.currentTheme = theme;
        applyLookAndFeel(theme);
        FlatLaf.updateUI();
    }

    public Theme getCurrentTheme() {
        return currentTheme;
    }

    public boolean isDark() {
        return currentTheme == Theme.DARK;
    }

    public Color windowBackground() {
        return isDark() ? DARK_BG_PRIMARY : LIGHT_BG_PRIMARY;
    }

    public Color cardBackground(boolean simulated) {
        return isDark() ? DARK_BG_CARD : LIGHT_BG_CARD;
    }

    public Color cardHoverBackground() {
        return isDark() ? DARK_BG_CARD_HOVER : LIGHT_BG_CARD_HOVER;
    }

    public Color elevatedBackground() {
        return isDark() ? DARK_BG_ELEVATED : LIGHT_BG_ELEVATED;
    }

    public Color trackBackground() {
        return isDark() ? DARK_BG_TRACK : LIGHT_BG_TRACK;
    }

    public Color cardBorder(boolean simulated) {
        return isDark() ? DARK_BORDER_CARD : LIGHT_BORDER_CARD;
    }

    public Color subtleBorder() {
        return isDark() ? DARK_BORDER_SUBTLE : LIGHT_BORDER_SUBTLE;
    }

    public Color primaryText() {
        return isDark() ? DARK_TEXT_PRIMARY : LIGHT_TEXT_PRIMARY;
    }

    public Color secondaryText() {
        return isDark() ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY;
    }

    public Color mutedText() {
        return isDark() ? DARK_TEXT_MUTED : LIGHT_TEXT_MUTED;
    }

    public Color accentColor() {
        return isDark() ? DARK_ACCENT : LIGHT_ACCENT;
    }

    public static Font fontRegular(float size) {
        return new Font("Segoe UI", Font.PLAIN, Math.round(size)).deriveFont(Font.PLAIN, size);
    }

    public static Font fontBold(float size) {
        return new Font("Segoe UI", Font.BOLD, Math.round(size)).deriveFont(Font.BOLD, size);
    }

    private static void applyLookAndFeel(Theme theme) {
        try {
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 8);
            if (theme == Theme.DARK) {
                FlatDarkLaf.setup();
                UIManager.put("Panel.background", DARK_BG_PRIMARY);
                UIManager.put("Viewport.background", DARK_BG_PRIMARY);
            } else {
                FlatLightLaf.setup();
                UIManager.put("Panel.background", LIGHT_BG_PRIMARY);
                UIManager.put("Viewport.background", LIGHT_BG_PRIMARY);
            }
        } catch (Exception e) {
            System.err.println("Não foi possível aplicar tema FlatLaf: " + e.getMessage());
        }
    }
}

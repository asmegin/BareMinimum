package com.bareminimum.ui;

import javax.swing.AbstractButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Stops Swing from interpreting untrusted text as HTML.
 *
 * <p>Swing labels, buttons, menu items, list cells and table cells render any text that starts
 * with {@code <html>} as HTML, which can load remote images. BareMinimum displays cookie, header
 * and parameter names and values taken straight from HTTP messages, so every component that can
 * show them has HTML rendering switched off.
 *
 * <p>Note that the {@code html.disable} property is read from the component that actually paints
 * the text. Setting it on a {@link JTable} or {@link JComboBox} has no effect on their cell
 * renderers, which is why this class installs its own renderers.
 */
final class SafeRendering {
    private static final String HTML_DISABLE = "html.disable";

    private SafeRendering() {
    }

    /**
     * Disables HTML rendering on a label, button or menu item and returns it.
     *
     * <p>A component built with its text already set (e.g. {@code new JMenuItem(text)}) has parsed
     * that text as HTML before the property could be applied, and not every component type
     * re-evaluates it afterwards, so the cached HTML view is rebuilt here explicitly.
     */
    static <T extends JComponent> T plainText(T component) {
        component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
        if (component instanceof JLabel label) {
            BasicHTML.updateRenderer(label, label.getText());
        } else if (component instanceof AbstractButton button) {
            BasicHTML.updateRenderer(button, button.getText());
        }
        return component;
    }

    /** Makes every text cell of the table render its value as plain text. */
    static void harden(JTable table) {
        plainText(table);
        table.setDefaultRenderer(Object.class, new PlainTextTableCellRenderer());
        table.setDefaultRenderer(String.class, new PlainTextTableCellRenderer());
        table.setDefaultRenderer(Number.class, new PlainTextTableCellRenderer());
    }

    /** Makes the combo box render its items as plain text. */
    static void harden(JComboBox<?> comboBox) {
        plainText(comboBox);
        comboBox.setRenderer(new PlainTextListCellRenderer());
    }

    private static final class PlainTextTableCellRenderer extends DefaultTableCellRenderer {
        PlainTextTableCellRenderer() {
            putClientProperty(HTML_DISABLE, Boolean.TRUE);
        }
    }

    private static final class PlainTextListCellRenderer extends DefaultListCellRenderer {
        PlainTextListCellRenderer() {
            putClientProperty(HTML_DISABLE, Boolean.TRUE);
        }
    }
}

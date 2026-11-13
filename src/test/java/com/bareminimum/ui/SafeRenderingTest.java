package com.bareminimum.ui;

import org.junit.jupiter.api.Test;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JTable;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that text taken from HTTP messages is never laid out as HTML. Swing stores the parsed
 * HTML view of a component under {@link BasicHTML#propertyKey}; it is null exactly when the text
 * is painted as plain text.
 */
class SafeRenderingTest {
    private static final String HOSTILE = "<html><img src=\"http://attacker.example/x.png\">";

    private static Object htmlView(Component component) {
        return ((JComponent) component).getClientProperty(BasicHTML.propertyKey);
    }

    private static JTable tableWith(Object... row) {
        Object[] columns = new Object[row.length];
        for (int i = 0; i < row.length; i++) {
            columns[i] = "c" + i;
        }
        return new JTable(new DefaultTableModel(new Object[][]{row}, columns));
    }

    private static Component cell(JTable table, int column) {
        return table.prepareRenderer(table.getCellRenderer(0, column), 0, column);
    }

    @Test
    void unprotectedSwingComponentsDoRenderHtml() {
        // Control case: proves the assertions below would fail without the hardening.
        assertNotNull(htmlView(new JLabel(HOSTILE)));
        assertNotNull(htmlView(cell(tableWith(HOSTILE), 0)));
    }

    @Test
    void settingThePropertyOnTheTableAloneIsNotEnough() {
        JTable table = tableWith(HOSTILE);
        table.putClientProperty("html.disable", Boolean.TRUE);
        assertNotNull(htmlView(cell(table, 0)));
    }

    @Test
    void hardenedTableCellsArePlainText() {
        JTable table = tableWith(HOSTILE, 42);
        SafeRendering.harden(table);

        assertNull(htmlView(cell(table, 0)));
        assertNull(htmlView(cell(table, 1)));

        // The hardening must survive a look-and-feel refresh such as a Burp theme change.
        table.updateUI();
        assertNull(htmlView(cell(table, 0)));
    }

    @Test
    void hardenedComboBoxItemsArePlainText() {
        JComboBox<String> combo = new JComboBox<>(new DefaultComboBoxModel<>(new String[]{HOSTILE}));
        SafeRendering.harden(combo);
        combo.updateUI();

        Component rendered = combo.getRenderer()
                .getListCellRendererComponent(new JList<>(), HOSTILE, 0, false, false);
        assertNull(htmlView(rendered));
    }

    @Test
    void menuItemBuiltWithHostileTextKeepsItsHtmlViewUnlessRebuilt() {
        // Control case: setting the property after construction is not enough for menu items.
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(HOSTILE, true);
        item.putClientProperty("html.disable", Boolean.TRUE);
        assertNotNull(htmlView(item));
    }

    @Test
    void plainTextLabelsButtonsAndMenuItemsDoNotRenderHtml() {
        assertNull(htmlView(SafeRendering.plainText(new JLabel(HOSTILE))));
        assertNull(htmlView(SafeRendering.plainText(new JButton(HOSTILE))));
        assertNull(htmlView(SafeRendering.plainText(new JCheckBoxMenuItem(HOSTILE, true))));
        assertNull(htmlView(SafeRendering.plainText(new JMenuItem(HOSTILE))));

        JLabel label = SafeRendering.plainText(new JLabel("status"));
        label.setText(HOSTILE);
        assertNull(htmlView(label));
    }
}

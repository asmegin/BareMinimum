package com.bareminimum.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import com.bareminimum.core.DeltaDebugMinimizer;
import com.bareminimum.core.DeltaDebugMinimizer.MinimizationResult;
import com.bareminimum.core.DeltaDebugMinimizer.ProgressListener;
import com.bareminimum.core.DeltaDebugMinimizer.StepLog;
import com.bareminimum.core.MinimizationJobManager;
import com.bareminimum.core.ProfileStore;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main top-level Burp Suite tab for BareMinimum.
 * Provides:
 * 1. Saved Profiles & Inspector (manage "GET - ALL 1", "POST - COOKIES 3", set active profile, send to Repeater)
 * 2. Live Binary-Search Execution Log (step-by-step progress of the O(k log N) delta debugger)
 * 3. Settings (baseline equivalence rules, body length tolerance %, pinned headers)
 */
public final class BareMinimumSuiteTab extends JPanel implements ProgressListener {
    private static final String IDLE_STATUS =
            "Idle — right-click any request and choose Extensions > BareMinimum > Find required ...";

    private final MontoyaApi api;
    private final MinimizationConfig config;
    private final ProfileStore profileStore;

    private final ProfilesTableModel profilesTableModel;
    private final JTable profilesTable;
    private final JTextArea profileDetailsArea;
    private final RawEditor minimizedPreviewEditor;

    private final JLabel jobStatusLabel;
    private final JProgressBar jobProgressBar;
    private final JButton cancelJobButton;
    private final StepLogTableModel stepLogTableModel;
    private final JTable stepTable;

    public BareMinimumSuiteTab(
            MontoyaApi api,
            MinimizationConfig config,
            ProfileStore profileStore,
            MinimizationJobManager jobManager) {
        super(new BorderLayout());
        this.api = api;
        this.config = config;
        this.profileStore = profileStore;

        this.profilesTableModel = new ProfilesTableModel(profileStore);
        this.profilesTable = new JTable(profilesTableModel);
        SafeRendering.harden(profilesTable);
        this.profilesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        this.profileDetailsArea = new JTextArea();
        this.profileDetailsArea.setEditable(false);
        this.profileDetailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        this.minimizedPreviewEditor = api.userInterface().createRawEditor(EditorOptions.READ_ONLY);

        this.jobStatusLabel = SafeRendering.plainText(new JLabel(IDLE_STATUS));
        this.jobProgressBar = new JProgressBar();
        this.jobProgressBar.setIndeterminate(false);
        this.cancelJobButton = new JButton("Cancel Run");
        this.cancelJobButton.setEnabled(false);
        this.cancelJobButton.addActionListener(e -> jobManager.cancelCurrentJob());
        this.stepLogTableModel = new StepLogTableModel();
        this.stepTable = new JTable(stepLogTableModel);
        SafeRendering.harden(stepTable);

        JTabbedPane innerTabs = new JTabbedPane();
        innerTabs.addTab("Saved Profiles & Live Progress", buildMainWorkspacePanel());
        innerTabs.addTab("Settings & Baseline Rules", buildSettingsPanel());
        add(innerTabs, BorderLayout.CENTER);

        this.profilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedProfilePreview();
            }
        });

        profileStore.addListener(this::onProfilesChanged);
        jobManager.addProgressListener(this);
        updateSelectedProfilePreview();
    }

    private void onProfilesChanged() {
        MinimizationProfile previouslySelected = getSelectedProfile();
        profilesTableModel.fireTableDataChanged();
        int row = profileStore.getProfiles().indexOf(previouslySelected);
        if (row >= 0) {
            profilesTable.setRowSelectionInterval(row, row);
        }
        updateSelectedProfilePreview();
    }

    private JPanel buildMainWorkspacePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top section: Saved Profiles table + actions
        JPanel profilesSection = new JPanel(new BorderLayout(4, 4));
        profilesSection.setBorder(BorderFactory.createTitledBorder(
                "Saved BareMinimum Profiles (Available in Right-Click Menu & Repeater)"));

        JPanel profileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JButton setActiveBtn = new JButton("Set as Active for Repeater");
        setActiveBtn.addActionListener(e -> {
            MinimizationProfile sel = getSelectedProfile();
            if (sel != null) {
                profileStore.setActiveProfile(sel);
            }
        });
        profileButtons.add(setActiveBtn);

        JButton sendToRepeaterBtn = new JButton("Send Minimized Request to Repeater");
        sendToRepeaterBtn.addActionListener(e -> {
            MinimizationProfile sel = getSelectedProfile();
            if (sel != null && sel.getMinimizedRequest() != null) {
                api.repeater().sendToRepeater(sel.getMinimizedRequest(), sel.getName());
            }
        });
        profileButtons.add(sendToRepeaterBtn);

        JButton deleteBtn = new JButton("Delete Selected");
        deleteBtn.addActionListener(e -> {
            stopEditingProfileName();
            MinimizationProfile sel = getSelectedProfile();
            if (sel != null) {
                profileStore.removeProfile(sel);
            }
        });
        profileButtons.add(deleteBtn);

        JButton clearAllBtn = new JButton("Clear All");
        clearAllBtn.addActionListener(e -> {
            stopEditingProfileName();
            profileStore.clearAll();
        });
        profileButtons.add(clearAllBtn);

        profilesSection.add(profileButtons, BorderLayout.NORTH);
        profilesSection.add(new JScrollPane(profilesTable), BorderLayout.CENTER);

        // Middle section: Selected Profile required items breakdown + Minimized HTTP Request preview
        JPanel breakdownPanel = new JPanel(new BorderLayout());
        breakdownPanel.setBorder(BorderFactory.createTitledBorder("Required Items in Selected Profile"));
        breakdownPanel.add(new JScrollPane(profileDetailsArea), BorderLayout.CENTER);

        JPanel rawPanel = new JPanel(new BorderLayout());
        rawPanel.setBorder(BorderFactory.createTitledBorder("Minimized Request Preview (Ready for Screenshot)"));
        rawPanel.add(minimizedPreviewEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane inspectSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, breakdownPanel, rawPanel);
        inspectSplit.setResizeWeight(0.4);

        // Bottom section: Live Binary Search Step Log
        JPanel logSection = new JPanel(new BorderLayout(4, 4));
        logSection.setBorder(BorderFactory.createTitledBorder("Binary-Search Minimization Step Log"));

        JPanel statusRow = new JPanel(new BorderLayout(8, 2));
        statusRow.add(jobStatusLabel, BorderLayout.CENTER);
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightControls.add(jobProgressBar);
        rightControls.add(cancelJobButton);
        statusRow.add(rightControls, BorderLayout.EAST);

        logSection.add(statusRow, BorderLayout.NORTH);
        logSection.add(new JScrollPane(stepTable), BorderLayout.CENTER);

        JSplitPane topMiddleSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, profilesSection, inspectSplit);
        topMiddleSplit.setResizeWeight(0.35);

        JSplitPane fullVerticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topMiddleSplit, logSection);
        fullVerticalSplit.setResizeWeight(0.68);

        panel.add(fullVerticalSplit, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSettingsPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // 0. How responses are compared
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        form.add(new JLabel("Response matching:"), gbc);

        gbc.gridx = 1;
        JComboBox<MinimizationConfig.ResponseMatching> matchingCombo =
                new JComboBox<>(MinimizationConfig.ResponseMatching.values());
        SafeRendering.harden(matchingCombo);
        matchingCombo.setSelectedItem(config.getResponseMatching());
        form.add(matchingCombo, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        form.add(new JLabel("Burp's response analysis sends the original request " + DeltaDebugMinimizer.ANALYSIS_SAMPLES
                + " times and then requires every response attribute that stayed the same (status, content type,"
                + " title, word count, visible text ...) to stay the same."), gbc);

        // 1. Match Status Code
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        JCheckBox statusCheck = new JCheckBox(
                "Require HTTP status code to match baseline response", config.isMatchStatusCode());
        statusCheck.addActionListener(e -> config.setMatchStatusCode(statusCheck.isSelected()));
        form.add(statusCheck, gbc);

        // 2. Match Body Length within tolerance %
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        JCheckBox bodyLenCheck = new JCheckBox(
                "Require response body length within tolerance (%):", config.isMatchBodyLength());
        bodyLenCheck.addActionListener(e -> config.setMatchBodyLength(bodyLenCheck.isSelected()));
        form.add(bodyLenCheck, gbc);

        gbc.gridx = 1;
        JSpinner toleranceSpinner = new JSpinner(
                new SpinnerNumberModel(config.getBodyLengthTolerancePercent(), 0.0, 100.0, 1.0));
        toleranceSpinner.addChangeListener(e ->
                config.setBodyLengthTolerancePercent(((Number) toleranceSpinner.getValue()).doubleValue()));
        form.add(toleranceSpinner, gbc);

        // 3. Check JSON structure
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        JCheckBox jsonCheck = new JCheckBox(
                "Verify top-level JSON keys match when baseline response is a JSON object",
                config.isCheckJsonStructure());
        jsonCheck.addActionListener(e -> config.setCheckJsonStructure(jsonCheck.isSelected()));
        form.add(jsonCheck, gbc);

        // 4. Baseline stability check
        row++;
        gbc.gridy = row;
        JCheckBox stabilityCheck = new JCheckBox(
                "Send the original request a second time first, and stop if its response is not stable",
                config.isVerifyBaselineStability());
        stabilityCheck.addActionListener(e -> config.setVerifyBaselineStability(stabilityCheck.isSelected()));
        form.add(stabilityCheck, gbc);

        // The length, JSON-key and stability settings only apply to the rules.
        Runnable updateRuleControls = () -> {
            boolean rules = config.getResponseMatching() == MinimizationConfig.ResponseMatching.RULES;
            bodyLenCheck.setEnabled(rules);
            toleranceSpinner.setEnabled(rules);
            jsonCheck.setEnabled(rules);
            stabilityCheck.setEnabled(rules);
        };
        matchingCombo.addActionListener(e -> {
            Object selected = matchingCombo.getSelectedItem();
            if (selected instanceof MinimizationConfig.ResponseMatching matching) {
                config.setResponseMatching(matching);
                updateRuleControls.run();
            }
        });
        updateRuleControls.run();

        // 5. Required Substring
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        form.add(new JLabel("Required response substring (optional):"), gbc);

        gbc.gridx = 1;
        JTextField reqSubField = new JTextField(config.getRequiredSubstring(), 30);
        onTextChange(reqSubField, config::setRequiredSubstring);
        form.add(reqSubField, gbc);

        // 6. Forbidden Substring
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("Forbidden response substring (optional, e.g. 'Unauthorized'):"), gbc);

        gbc.gridx = 1;
        JTextField forbSubField = new JTextField(config.getForbiddenSubstring(), 30);
        onTextChange(forbSubField, config::setForbiddenSubstring);
        form.add(forbSubField, gbc);

        // 7. Pinned Headers
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("Always-pinned headers (comma-separated, never removed):"), gbc);

        gbc.gridx = 1;
        JTextField pinnedField = new JTextField(config.getPinnedHeadersCsv(), 30);
        onTextChange(pinnedField, config::setPinnedHeadersFromCsv);
        form.add(pinnedField, gbc);

        // 8. Request Delay
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("Delay between test requests (ms):"), gbc);

        gbc.gridx = 1;
        JSpinner delaySpinner = new JSpinner(new SpinnerNumberModel(
                config.getRequestDelayMs(), 0, MinimizationConfig.MAX_REQUEST_DELAY_MS, 50));
        delaySpinner.addChangeListener(e ->
                config.setRequestDelayMs(((Number) delaySpinner.getValue()).intValue()));
        form.add(delaySpinner, gbc);

        // 9. Parallel requests
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        form.add(new JLabel("Parallel requests (1 = one at a time; a delay above forces 1):"), gbc);

        gbc.gridx = 1;
        JSpinner parallelSpinner = new JSpinner(new SpinnerNumberModel(
                config.getParallelism(), 1, MinimizationConfig.MAX_PARALLELISM, 1));
        parallelSpinner.addChangeListener(e ->
                config.setParallelism(((Number) parallelSpinner.getValue()).intValue()));
        form.add(parallelSpinner, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        form.add(new JLabel("Settings apply to the next run and are remembered between Burp sessions."
                + " Host, Content-Length, and HTTP/2 pseudo-headers are always pinned."), gbc);

        outer.add(form, BorderLayout.NORTH);
        return outer;
    }

    private static void onTextChange(JTextField field, Consumer<String> setter) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setter.accept(field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setter.accept(field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                setter.accept(field.getText());
            }
        });
    }

    private void stopEditingProfileName() {
        if (profilesTable.isEditing()) {
            profilesTable.getCellEditor().cancelCellEditing();
        }
    }

    private MinimizationProfile getSelectedProfile() {
        int row = profilesTable.getSelectedRow();
        List<MinimizationProfile> list = profileStore.getProfiles();
        if (row >= 0 && row < list.size()) {
            return list.get(row);
        }
        return profileStore.getActiveProfile();
    }

    private void updateSelectedProfilePreview() {
        MinimizationProfile profile = getSelectedProfile();
        if (profile == null) {
            profileDetailsArea.setText("No profile selected.");
            minimizedPreviewEditor.setContents(ByteArray.byteArray(new byte[0]));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Profile Name : ").append(profile.getName()).append('\n');
        sb.append("Endpoint     : ").append(profile.getHttpMethod()).append(' ')
                .append(profile.getEndpointPath()).append('\n');
        sb.append("Mode         : ").append(profile.getTarget().menuLabel()).append('\n');
        sb.append("Efficiency   : Kept ").append(profile.getRequiredItemCount())
                .append(" of ").append(profile.getOriginalItemCount())
                .append(" items (found in ").append(profile.getRequestsSent()).append(" HTTP requests)\n\n");

        if (profile.getTarget().includesCookies()) {
            sb.append("=== Required Cookies (").append(profile.getRequiredCookies().size()).append(") ===\n");
            appendNames(sb, profile.getRequiredCookies(), "(None required — all cookies can be removed)");
            sb.append('\n');
        }

        if (profile.getTarget().includesHeaders()) {
            sb.append("=== Required Headers (").append(profile.getRequiredHeaders().size())
                    .append(" + pinned) ===\n");
            sb.append("  • [Pinned] ").append(config.getPinnedHeadersCsv()).append('\n');
            appendNames(sb, profile.getRequiredHeaders(), "(No additional headers required)");
            sb.append('\n');
        }

        if (profile.getTarget().includesParameters()) {
            sb.append("=== Required URL Parameters (").append(profile.getRequiredUrlParams().size())
                    .append(") ===\n");
            appendNames(sb, profile.getRequiredUrlParams(), "(None required)");
            sb.append("\n=== Required Body / JSON Parameters (").append(profile.getRequiredBodyParams().size())
                    .append(") ===\n");
            appendNames(sb, profile.getRequiredBodyParams(), "(None required)");
        }

        profileDetailsArea.setText(sb.toString());
        profileDetailsArea.setCaretPosition(0);

        HttpRequest minimized = profile.getMinimizedRequest();
        minimizedPreviewEditor.setContents(
                minimized != null ? minimized.toByteArray() : ByteArray.byteArray(new byte[0]));
    }

    private static void appendNames(StringBuilder sb, Iterable<String> names, String whenEmpty) {
        boolean any = false;
        for (String name : names) {
            sb.append("  • ").append(name).append('\n');
            any = true;
        }
        if (!any) {
            sb.append("  ").append(whenEmpty).append('\n');
        }
    }

    @Override
    public void onStarted(HttpRequest request, MinimizationTarget target, List<RemovableItem> candidates) {
        stepLogTableModel.clear();
        jobProgressBar.setIndeterminate(true);
        cancelJobButton.setEnabled(true);
        jobStatusLabel.setText("Running '" + target.menuLabel() + "' on " + request.method() + " "
                + request.path() + " (" + candidates.size() + " candidate items)...");
    }

    @Override
    public void onBaselineEstablished(short statusCode, int bodyLength) {
        jobStatusLabel.setText("Baseline established: HTTP " + statusCode + " (" + bodyLength
                + " bytes). Binary searching...");
    }

    @Override
    public void onStep(StepLog stepLog) {
        int row = stepLogTableModel.addStep(stepLog);
        stepTable.scrollRectToVisible(stepTable.getCellRect(row, 0, true));
    }

    @Override
    public void onCompleted(MinimizationResult result) {
        jobProgressBar.setIndeterminate(false);
        cancelJobButton.setEnabled(false);
        if (result.errorMessage() != null) {
            jobStatusLabel.setText("Failed: " + result.errorMessage());
        } else if (result.cancelled()) {
            jobStatusLabel.setText("Cancelled after " + result.requestsSent() + " requests.");
        } else if (result.profile() != null) {
            jobStatusLabel.setText("Saved '" + result.profile().getName() + "' — kept "
                    + result.requiredItems().size() + " / " + result.allCandidates().size()
                    + " items in " + result.requestsSent() + " requests (" + result.rounds() + " rounds).");
            int row = profileStore.getProfiles().indexOf(result.profile());
            if (row >= 0 && row < profilesTable.getRowCount()) {
                profilesTable.setRowSelectionInterval(row, row);
            }
        }
    }

    private static final class ProfilesTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Active", "Profile Name (Editable)", "Method", "Endpoint", "Mode", "Required / Total", "HTTP Calls"
        };
        private final ProfileStore store;

        ProfilesTableModel(ProfileStore store) {
            this.store = store;
        }

        @Override
        public int getRowCount() {
            return store.getProfiles().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            List<MinimizationProfile> list = store.getProfiles();
            if (rowIndex < 0 || rowIndex >= list.size()) {
                return "";
            }
            MinimizationProfile p = list.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> (p == store.getActiveProfile()) ? "★ Active" : "";
                case 1 -> p.getName();
                case 2 -> p.getHttpMethod();
                case 3 -> p.getEndpointPath();
                case 4 -> p.getTarget().shortCode();
                case 5 -> p.getRequiredItemCount() + " / " + p.getOriginalItemCount()
                        + " (" + p.getShortSummary() + ")";
                case 6 -> p.getRequestsSent();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 1 && aValue instanceof String newName) {
                List<MinimizationProfile> list = store.getProfiles();
                if (rowIndex >= 0 && rowIndex < list.size()) {
                    list.get(rowIndex).setName(newName);
                    store.notifyListeners();
                }
            }
        }
    }

    private static final class StepLogTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Step #", "Round", "Test Description", "Kept / Total", "HTTP Status", "Body Length", "Matches Baseline?"
        };
        private final List<StepLog> logs = new ArrayList<>();

        void clear() {
            logs.clear();
            fireTableDataChanged();
        }

        int addStep(StepLog step) {
            logs.add(step);
            int row = logs.size() - 1;
            fireTableRowsInserted(row, row);
            return row;
        }

        @Override
        public int getRowCount() {
            return logs.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            StepLog s = logs.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> s.stepNumber();
                case 1 -> s.round();
                case 2 -> s.description();
                case 3 -> s.keptCount() + " / " + s.totalCandidates();
                case 4 -> s.statusCode() == 0 ? "—" : String.valueOf(s.statusCode());
                case 5 -> s.bodyLength() + " B";
                case 6 -> s.matchedBaseline() ? "✓ PASS (Equivalent)" : "✗ FAIL (Different)";
                default -> "";
            };
        }
    }
}

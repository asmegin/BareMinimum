package com.bareminimum.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import com.bareminimum.core.MinimizationJobManager;
import com.bareminimum.core.ProfileStore;
import com.bareminimum.core.RequestMutator;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;
import com.bareminimum.model.RemovableItem;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides the "BareMinimum" tab inside Burp Repeater (and other request editors).
 * Features:
 * - "Minimize Request" button that enacts the currently selected saved profile (e.g., "GET - ALL 1")
 * - Profile selector dropdown synced with the right-click context menu
 * - One-click discovery buttons (Find Cookies, Find Headers, Find Params, Find ALL)
 * - Interactive item checklist + embedded Raw Request preview
 * - One-click "Restore Original" undo button
 */
public final class BareMinimumRepeaterTabProvider implements HttpRequestEditorProvider {
    private final MontoyaApi api;
    private final MinimizationConfig config;
    private final ProfileStore profileStore;
    private final MinimizationJobManager jobManager;

    public BareMinimumRepeaterTabProvider(
            MontoyaApi api,
            MinimizationConfig config,
            ProfileStore profileStore,
            MinimizationJobManager jobManager) {
        this.api = api;
        this.config = config;
        this.profileStore = profileStore;
        this.jobManager = jobManager;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        return new BareMinimumRequestEditor(creationContext);
    }

    private final class BareMinimumRequestEditor implements ExtensionProvidedHttpRequestEditor {
        private final boolean editable;
        private final JPanel mainPanel;
        private final DefaultComboBoxModel<MinimizationProfile> profileComboModel;
        private final JComboBox<MinimizationProfile> profileCombo;
        private final JButton minimizeButton;
        private final JButton restoreButton;
        private final JCheckBox autoApplyCheckBox;
        private final JLabel statusLabel;
        private final CandidateTableModel tableModel;
        private final RawEditor rawPreviewEditor;
        // Held in a field: ProfileStore only keeps a weak reference to it.
        private final Runnable profileListener = this::refreshProfileCombo;

        /** The request currently shown in this tab (after any minimization applied here). */
        private HttpRequest currentEditorRequest;
        /** The request as it was before this tab first changed it; null when nothing can be restored. */
        private HttpRequest backupOriginalRequest;
        /** Bytes of the last request this tab produced, to recognise Burp handing it back to us. */
        private byte[] lastProducedBytes;
        /** Incremented whenever Burp shows a different request, so stale job results are not applied. */
        private int requestGeneration;
        private boolean modified = false;
        private boolean updatingCombo = false;

        BareMinimumRequestEditor(EditorCreationContext creationContext) {
            this.editable = creationContext.editorMode() != EditorMode.READ_ONLY;
            this.mainPanel = new JPanel(new BorderLayout(6, 6));
            this.mainPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            // Top Toolbar: Profile Selector + Minimize Request button + Discovery actions
            JPanel topContainer = new JPanel();
            topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));

            JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            profileRow.add(new JLabel("Saved Profile:"));

            this.profileComboModel = new DefaultComboBoxModel<>();
            this.profileCombo = new JComboBox<>(profileComboModel);
            this.profileCombo.setPrototypeDisplayValue(new MinimizationProfile(
                    "POST - COOKIES 10", "POST", "/", MinimizationTarget.COOKIES,
                    Set.of("a", "b"), Set.of(), Set.of(), Set.of(), 10, 2, 5, null, null));
            this.profileCombo.addActionListener(e -> {
                if (!updatingCombo) {
                    MinimizationProfile selected = (MinimizationProfile) profileCombo.getSelectedItem();
                    if (selected != null && selected != profileStore.getActiveProfile()) {
                        profileStore.setActiveProfile(selected);
                    }
                    updateMinimizeButtonLabel();
                }
            });
            profileRow.add(profileCombo);

            this.minimizeButton = SafeRendering.plainText(new JButton("Minimize Request"));
            this.minimizeButton.setFont(minimizeButton.getFont().deriveFont(Font.BOLD));
            this.minimizeButton.setBackground(new Color(255, 102, 51));
            this.minimizeButton.setForeground(Color.WHITE);
            this.minimizeButton.setOpaque(true);
            this.minimizeButton.addActionListener(e -> applySelectedProfile());
            profileRow.add(minimizeButton);

            this.restoreButton = new JButton("Restore Original");
            this.restoreButton.setEnabled(false);
            this.restoreButton.addActionListener(e -> restoreBackupRequest());
            profileRow.add(restoreButton);

            topContainer.add(profileRow);

            // Second toolbar row: 4 Discovery Buttons laid out in a 1x4 grid so none ever clip off-screen
            JPanel discoveryRow = new JPanel(new BorderLayout(6, 2));
            discoveryRow.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            discoveryRow.add(new JLabel("Discover:"), BorderLayout.WEST);

            JPanel buttonsGrid = new JPanel(new GridLayout(1, 4, 6, 0));
            MinimizationTarget[] orderedTargets = {
                    MinimizationTarget.ALL,
                    MinimizationTarget.COOKIES,
                    MinimizationTarget.HEADERS,
                    MinimizationTarget.PARAMETERS
            };
            for (MinimizationTarget target : orderedTargets) {
                JButton btn = new JButton(target.menuLabel());
                if (target == MinimizationTarget.ALL) {
                    btn.setFont(btn.getFont().deriveFont(Font.BOLD));
                }
                btn.addActionListener(e -> runDiscovery(target));
                buttonsGrid.add(btn);
            }
            discoveryRow.add(buttonsGrid, BorderLayout.CENTER);
            topContainer.add(discoveryRow);

            // Third toolbar row: Auto-minimize toggle & manual checklist apply button
            JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            this.autoApplyCheckBox = new JCheckBox("Auto-minimize when finished", true);
            this.autoApplyCheckBox.setEnabled(editable);
            optionsRow.add(autoApplyCheckBox);

            JButton applyCheckedBtn = new JButton("Apply Checked Items");
            applyCheckedBtn.setEnabled(editable);
            applyCheckedBtn.addActionListener(e -> applyCheckedTableItems());
            optionsRow.add(applyCheckedBtn);

            topContainer.add(optionsRow);
            mainPanel.add(topContainer, BorderLayout.NORTH);

            // Center SplitPane: Left = checklist of items in request, Right = Raw HTTP request editor
            this.tableModel = new CandidateTableModel(editable);
            JTable table = new JTable(tableModel);
            table.getColumnModel().getColumn(0).setMaxWidth(65);
            table.getColumnModel().getColumn(1).setPreferredWidth(110);
            table.getColumnModel().getColumn(2).setPreferredWidth(160);
            table.getColumnModel().getColumn(3).setPreferredWidth(260);

            this.rawPreviewEditor = api.userInterface().createRawEditor(
                    editable ? new EditorOptions[0] : new EditorOptions[]{EditorOptions.READ_ONLY});

            JSplitPane splitPane = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    new JScrollPane(table),
                    rawPreviewEditor.uiComponent());
            splitPane.setResizeWeight(0.45);
            mainPanel.add(splitPane, BorderLayout.CENTER);

            // Bottom status bar
            this.statusLabel = SafeRendering.plainText(new JLabel(
                    "BareMinimum — Select a saved profile and click 'Minimize Request', or run a discovery scan."));
            mainPanel.add(statusLabel, BorderLayout.SOUTH);

            api.userInterface().applyThemeToComponent(mainPanel);

            // Installed after theming so the plain-text renderers are the ones left in place.
            SafeRendering.harden(table);
            SafeRendering.harden(profileCombo);

            // Sync with ProfileStore changes using a weak reference so closed Repeater tabs are GC'd cleanly
            profileStore.addWeakListener(profileListener);
            refreshProfileCombo();
        }

        private void refreshProfileCombo() {
            updatingCombo = true;
            try {
                profileComboModel.removeAllElements();
                List<MinimizationProfile> profiles = profileStore.getProfiles();
                for (MinimizationProfile p : profiles) {
                    profileComboModel.addElement(p);
                }
                MinimizationProfile active = profileStore.getActiveProfile();
                if (active != null) {
                    profileComboModel.setSelectedItem(active);
                }
                updateMinimizeButtonLabel();
            } finally {
                updatingCombo = false;
            }
        }

        private void updateMinimizeButtonLabel() {
            MinimizationProfile active = (MinimizationProfile) profileCombo.getSelectedItem();
            if (active != null) {
                minimizeButton.setText("Minimize Request [" + active.getName() + "]");
                minimizeButton.setEnabled(editable && currentEditorRequest != null);
            } else {
                minimizeButton.setText("Minimize Request (No Profile Saved)");
                minimizeButton.setEnabled(false);
            }
        }

        private void applySelectedProfile() {
            MinimizationProfile selected = (MinimizationProfile) profileCombo.getSelectedItem();
            HttpRequest base = getLatestRequestFromEditor();
            if (selected == null || base == null || !editable) {
                return;
            }
            HttpRequest minimized;
            try {
                minimized = RequestMutator.applyProfile(base, selected, config);
            } catch (RuntimeException e) {
                statusLabel.setText("Could not apply profile: " + e);
                return;
            }
            rememberBackup(base);
            showProducedRequest(minimized);
            statusLabel.setText("Applied profile '" + selected.getName() + "' (" + selected.getShortSummary() + ").");
        }

        private void restoreBackupRequest() {
            if (backupOriginalRequest != null && editable) {
                HttpRequest original = backupOriginalRequest;
                backupOriginalRequest = null;
                restoreButton.setEnabled(false);
                produce(original);
                statusLabel.setText("Restored original pre-minimized request.");
            }
        }

        private void applyCheckedTableItems() {
            HttpRequest base = getLatestRequestFromEditor();
            if (base == null || !editable) {
                return;
            }
            HttpRequest mutated;
            int keptCount;
            int totalCount;
            try {
                // Re-read the request in case it was edited by hand since the checklist was built,
                // and drop exactly those items the user unchecked that are still present.
                Set<RemovableItem> unchecked = tableModel.getUncheckedItems();
                List<RemovableItem> latestItems =
                        RequestMutator.extractCandidates(base, MinimizationTarget.ALL, config);
                Set<RemovableItem> kept = new LinkedHashSet<>(latestItems);
                kept.removeAll(unchecked);
                keptCount = kept.size();
                totalCount = latestItems.size();
                mutated = RequestMutator.buildRequestWithSubset(base, MinimizationTarget.ALL, kept, config);
            } catch (RuntimeException e) {
                statusLabel.setText("Could not apply selection: " + e);
                return;
            }
            rememberBackup(base);
            showProducedRequest(mutated);
            statusLabel.setText("Applied manual item selection (" + keptCount + " / " + totalCount + " items kept).");
        }

        private void runDiscovery(MinimizationTarget target) {
            HttpRequest base = getLatestRequestFromEditor();
            if (base == null) {
                return;
            }
            final int generationAtStart = requestGeneration;
            statusLabel.setText("Queued '" + target.menuLabel() + "' via binary search...");
            jobManager.startJob(base, null, target, result -> {
                if (generationAtStart != requestGeneration) {
                    return; // this tab is showing a different request now
                }
                if (result.profile() != null) {
                    statusLabel.setText("Saved profile '" + result.profile().getName() + "' — kept "
                            + result.requiredItems().size() + "/" + result.allCandidates().size()
                            + " items in " + result.requestsSent() + " requests (" + result.rounds() + " rounds).");
                    if (editable && autoApplyCheckBox.isSelected() && result.minimizedRequest() != null) {
                        rememberBackup(base);
                        showProducedRequest(result.minimizedRequest());
                    }
                } else if (result.errorMessage() != null) {
                    statusLabel.setText("Failed: " + result.errorMessage());
                } else if (result.cancelled()) {
                    statusLabel.setText("Cancelled after " + result.requestsSent() + " requests.");
                }
            });
        }

        /** Keeps the first pre-minimization request so "Restore Original" can bring it back. */
        private void rememberBackup(HttpRequest beforeChange) {
            if (backupOriginalRequest == null) {
                backupOriginalRequest = beforeChange;
            }
        }

        private void showProducedRequest(HttpRequest produced) {
            produce(produced);
            restoreButton.setEnabled(editable && backupOriginalRequest != null);
        }

        /** Shows a request this tab generated and marks the editor as modified so Burp picks it up. */
        private void produce(HttpRequest produced) {
            this.modified = true;
            this.lastProducedBytes = bytesOf(produced);
            showRequest(produced);
        }

        private HttpRequest getLatestRequestFromEditor() {
            if (currentEditorRequest != null && rawPreviewEditor.isModified()) {
                ByteArray contents = rawPreviewEditor.getContents();
                if (contents != null) {
                    try {
                        return currentEditorRequest.httpService() != null
                                ? HttpRequest.httpRequest(currentEditorRequest.httpService(), contents)
                                : HttpRequest.httpRequest(contents);
                    } catch (RuntimeException ignored) {
                        // Fall back to the last request we parsed successfully.
                    }
                }
            }
            return currentEditorRequest;
        }

        private void showRequest(HttpRequest req) {
            this.currentEditorRequest = req;
            if (req != null) {
                this.rawPreviewEditor.setContents(req.toByteArray());
                List<RemovableItem> candidates;
                try {
                    candidates = RequestMutator.extractCandidates(req, MinimizationTarget.ALL, config);
                } catch (RuntimeException e) {
                    candidates = List.of();
                }
                this.tableModel.setItems(candidates);
            } else {
                this.rawPreviewEditor.setContents(ByteArray.byteArray(new byte[0]));
                this.tableModel.setItems(List.of());
            }
            updateMinimizeButtonLabel();
        }

        private byte[] bytesOf(HttpRequest request) {
            try {
                return request == null ? null : request.toByteArray().getBytes();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public HttpRequest getRequest() {
            return getLatestRequestFromEditor();
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            HttpRequest incoming = requestResponse != null ? requestResponse.request() : null;

            // After we minimize, Burp takes our request and later hands the very same bytes back.
            // That is still "our" request, so the undo state must survive it.
            byte[] incomingBytes = bytesOf(incoming);
            boolean isOurOwnOutput = incomingBytes != null && Arrays.equals(incomingBytes, lastProducedBytes);
            if (!isOurOwnOutput) {
                backupOriginalRequest = null;
                lastProducedBytes = null;
                requestGeneration++;
            }

            this.modified = false;
            showRequest(incoming);
            this.restoreButton.setEnabled(editable && backupOriginalRequest != null);
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return requestResponse != null && requestResponse.request() != null;
        }

        @Override
        public String caption() {
            return "BareMinimum";
        }

        @Override
        public Component uiComponent() {
            return mainPanel;
        }

        @Override
        public Selection selectedData() {
            return rawPreviewEditor.selection().orElse(null);
        }

        @Override
        public boolean isModified() {
            return modified || rawPreviewEditor.isModified();
        }
    }

    private static final class CandidateTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Keep", "Category", "Name", "Value"};
        private final boolean editable;
        private final List<RemovableItem> items = new ArrayList<>();
        private final List<Boolean> keptFlags = new ArrayList<>();

        CandidateTableModel(boolean editable) {
            this.editable = editable;
        }

        void setItems(List<RemovableItem> newItems) {
            items.clear();
            keptFlags.clear();
            for (RemovableItem item : newItems) {
                items.add(item);
                keptFlags.add(Boolean.TRUE);
            }
            fireTableDataChanged();
        }

        Set<RemovableItem> getUncheckedItems() {
            Set<RemovableItem> set = new LinkedHashSet<>();
            for (int i = 0; i < items.size(); i++) {
                if (!Boolean.TRUE.equals(keptFlags.get(i))) {
                    set.add(items.get(i));
                }
            }
            return set;
        }

        @Override
        public int getRowCount() {
            return items.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return editable && columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RemovableItem item = items.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> keptFlags.get(rowIndex);
                case 1 -> item.category().displayName();
                case 2 -> item.name();
                case 3 -> item.value();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && aValue instanceof Boolean b && rowIndex < keptFlags.size()) {
                keptFlags.set(rowIndex, b);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}

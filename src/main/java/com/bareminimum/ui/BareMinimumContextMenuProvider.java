package com.bareminimum.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.InvocationType;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import com.bareminimum.core.MinimizationJobManager;
import com.bareminimum.core.ProfileStore;
import com.bareminimum.core.RequestMutator;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.model.MinimizationProfile;
import com.bareminimum.model.MinimizationTarget;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Provides the right-click context menu items under "BareMinimum":
 * - Find required cookies
 * - Find required headers
 * - Find required parameters
 * - Find required ALL
 * - Minimize Request (enacts active profile)
 * - Saved profile items (e.g., "GET - ALL 1", "POST - COOKIES 3") to select the active profile
 */
public final class BareMinimumContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final MinimizationConfig config;
    private final ProfileStore profileStore;
    private final MinimizationJobManager jobManager;

    public BareMinimumContextMenuProvider(
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
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        TargetRequestContext ctx = extractTargetRequest(event);
        if (ctx == null || ctx.request() == null) {
            return List.of();
        }

        List<Component> menuItems = new ArrayList<>();

        // 1. The four discovery actions
        menuItems.add(createDiscoveryMenuItem(MinimizationTarget.COOKIES, ctx));
        menuItems.add(createDiscoveryMenuItem(MinimizationTarget.HEADERS, ctx));
        menuItems.add(createDiscoveryMenuItem(MinimizationTarget.PARAMETERS, ctx));
        menuItems.add(createDiscoveryMenuItem(MinimizationTarget.ALL, ctx));

        // 2. Quick "Minimize Request" action using the currently selected profile
        MinimizationProfile activeProfile = profileStore.getActiveProfile();
        List<MinimizationProfile> allProfiles = profileStore.getProfiles();

        menuItems.add(new JSeparator());

        if (activeProfile != null) {
            JMenuItem minimizeNowItem = SafeRendering.plainText(
                    new JMenuItem("Minimize Request [" + activeProfile.getName() + "]"));
            minimizeNowItem.addActionListener(e -> applyProfileToContext(activeProfile, ctx));
            menuItems.add(minimizeNowItem);
        } else {
            JMenuItem noActiveItem = new JMenuItem("Minimize Request (No saved profile yet)");
            noActiveItem.setEnabled(false);
            menuItems.add(noActiveItem);
        }

        // 3. Saved Profiles list directly in the context menu (e.g., "GET - ALL 1", "POST - COOKIES 3")
        if (!allProfiles.isEmpty()) {
            menuItems.add(new JSeparator());
            JMenuItem headerLabel = new JMenuItem("Saved Results (Click to select for Repeater):");
            headerLabel.setEnabled(false);
            menuItems.add(headerLabel);

            for (MinimizationProfile profile : allProfiles) {
                boolean isSelected = (profile == activeProfile);
                String label = profile.getName() + "  (" + profile.getShortSummary() + ")";
                // No tooltip: tooltips render HTML and the endpoint text comes from the request.
                JCheckBoxMenuItem profileItem = SafeRendering.plainText(new JCheckBoxMenuItem(label, isSelected));
                profileItem.addActionListener(e -> profileStore.setActiveProfile(profile));
                menuItems.add(profileItem);
            }
        }

        return menuItems;
    }

    private JMenuItem createDiscoveryMenuItem(MinimizationTarget target, TargetRequestContext ctx) {
        JMenuItem item = new JMenuItem(target.menuLabel());
        // The stored response may be stale (e.g. an expired session), so the job always fetches a
        // fresh baseline itself. It runs on BareMinimum's worker thread, not on the Swing thread.
        item.addActionListener(e -> jobManager.startJob(ctx.request(), null, target, null));
        return item;
    }

    private void applyProfileToContext(MinimizationProfile profile, TargetRequestContext ctx) {
        HttpRequest minimized;
        try {
            minimized = RequestMutator.applyProfile(ctx.request(), profile, config);
        } catch (RuntimeException e) {
            api.logging().logToError("[BareMinimum] Could not apply profile: " + e);
            return;
        }
        if (ctx.editor() != null && ctx.editableRequest()) {
            try {
                ctx.editor().setRequest(minimized);
                return;
            } catch (RuntimeException ignored) {
                // Could not update the editor in place; fall through and send to Repeater instead.
            }
        }
        // Read-only views (HTTP history, site map, ...) cannot be edited, so open it in Repeater.
        api.repeater().sendToRepeater(minimized, profile.getName());
    }

    private record TargetRequestContext(
            HttpRequest request,
            MessageEditorHttpRequestResponse editor,
            boolean editableRequest
    ) {
    }

    private TargetRequestContext extractTargetRequest(ContextMenuEvent event) {
        Optional<MessageEditorHttpRequestResponse> editorOpt = event.messageEditorRequestResponse();
        if (editorOpt.isPresent()) {
            MessageEditorHttpRequestResponse editor = editorOpt.get();
            HttpRequestResponse rr = editor.requestResponse();
            if (rr != null && rr.request() != null) {
                // Only an editable request editor can be updated in place.
                boolean editableRequest = event.isFrom(InvocationType.MESSAGE_EDITOR_REQUEST);
                return new TargetRequestContext(rr.request(), editor, editableRequest);
            }
        }
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected != null && !selected.isEmpty()) {
            HttpRequestResponse first = selected.get(0);
            if (first != null && first.request() != null) {
                return new TargetRequestContext(first.request(), null, false);
            }
        }
        return null;
    }
}

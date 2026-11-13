package com.bareminimum;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Preferences;
import com.bareminimum.core.MinimizationJobManager;
import com.bareminimum.core.ProfileStore;
import com.bareminimum.core.SettingsPersistence;
import com.bareminimum.model.MinimizationConfig;
import com.bareminimum.ui.BareMinimumContextMenuProvider;
import com.bareminimum.ui.BareMinimumRepeaterTabProvider;
import com.bareminimum.ui.BareMinimumSuiteTab;

/**
 * Montoya API entry point for the BareMinimum extension.
 * "Does the absolute minimum required for the request to still work."
 */
public final class BareMinimumExtension implements BurpExtension {
    static final String NAME = "BareMinimum";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);

        MinimizationConfig config = new MinimizationConfig();
        Preferences preferences = api.persistence().preferences();
        SettingsPersistence.load(preferences, config);
        config.setChangeListener(() -> SettingsPersistence.save(preferences, config));

        ProfileStore profileStore = new ProfileStore();
        MinimizationJobManager jobManager = new MinimizationJobManager(api, config, profileStore);

        BareMinimumSuiteTab suiteTab = new BareMinimumSuiteTab(api, config, profileStore, jobManager);
        api.userInterface().applyThemeToComponent(suiteTab);
        api.userInterface().registerSuiteTab(NAME, suiteTab);

        api.userInterface().registerContextMenuItemsProvider(
                new BareMinimumContextMenuProvider(api, config, profileStore, jobManager));

        api.userInterface().registerHttpRequestEditorProvider(
                new BareMinimumRepeaterTabProvider(api, config, profileStore, jobManager));

        // Burp removes the registrations above itself; this stops the worker thread and any
        // in-flight job, and drops everything the extension was holding on to.
        api.extension().registerUnloadingHandler(() -> {
            config.setChangeListener(null);
            jobManager.shutdown();
            profileStore.shutdown();
            api.logging().logToOutput("BareMinimum unloaded.");
        });

        api.logging().logToOutput("BareMinimum loaded. Right-click a request and choose"
                + " Extensions > BareMinimum to find what it actually needs.");
    }
}

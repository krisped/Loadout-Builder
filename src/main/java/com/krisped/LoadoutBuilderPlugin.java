package com.krisped;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
        name = "Loadout Builder",
        description = "Simple equipment + inventory editor",
        tags = {"gear","equipment","inventory","loadout"},
        enabledByDefault = false
)
public class LoadoutBuilderPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ItemManager itemManager;
    @Inject private ClientThread clientThread;
    @Inject private LoadoutBuilderConfig config;

    private NavigationButton navButton;
    private LoadoutBuilderPanel panel;
    private LoadoutManager loadoutManager;

    @Provides
    LoadoutBuilderConfig provideConfig(ConfigManager cm) { return cm.getConfig(LoadoutBuilderConfig.class); }

    @Override
    protected void startUp()
    {
        log.info("Loadout Builder starting");
        loadoutManager = new LoadoutManager(config, itemManager);
        panel = new LoadoutBuilderPanel(itemManager, clientThread, client, loadoutManager, config);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "defenceicon.png");
        navButton = NavigationButton.builder()
                .tooltip("Loadout Builder")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown()
    {
        log.info("Loadout Builder stopping");
        clientToolbar.removeNavigation(navButton);
        navButton = null;
        panel = null;
        loadoutManager = null;
    }
}
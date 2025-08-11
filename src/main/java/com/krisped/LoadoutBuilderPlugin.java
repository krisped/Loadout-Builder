package com.krisped;

import com.google.inject.Provides;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
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

import javax.swing.SwingUtilities;

@Slf4j
@PluginDescriptor(
        name = "Loadout Builder",
        description = "Viser equipment-bokser i ønsket rekkefølge og et 28-slot inventory.",
        tags = {"gear", "loadout", "equipment", "inventory"}
)
public class LoadoutBuilderPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientToolbar clientToolbar;
    @Inject private LoadoutBuilderConfig config;
    @Inject private ItemManager itemManager;
    @Inject private ClientThread clientThread;

    private NavigationButton navButton;
    private LoadoutBuilderPanel panel;

    @Override
    protected void startUp()
    {
        SwingUtilities.invokeLater(() ->
        {
            panel = new LoadoutBuilderPanel(itemManager, clientThread);

            BufferedImage icon = ImageUtil.loadImageResource(LoadoutBuilderPlugin.class, "armour.png");
            if (icon == null)
            {
                icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            }

            navButton = NavigationButton.builder()
                    .tooltip("Loadout Builder")
                    .icon(icon)
                    .priority(5)
                    .panel(panel)
                    .build();

            clientToolbar.addNavigation(navButton);
            log.info("Loadout Builder plugin startet!");
        });
    }

    @Override
    protected void shutDown()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (navButton != null)
            {
                clientToolbar.removeNavigation(navButton);
                navButton = null;
            }
            panel = null;
            log.info("Loadout Builder plugin stoppet!");
        });
    }

    @Provides
    LoadoutBuilderConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LoadoutBuilderConfig.class);
    }
}
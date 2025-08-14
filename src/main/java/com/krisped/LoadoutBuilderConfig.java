package com.krisped;

import net.runelite.client.config.*;

@ConfigGroup(LoadoutBuilderConfig.GROUP)
public interface LoadoutBuilderConfig extends Config
{
    String GROUP = "loadoutbuilder";

    @ConfigItem(
            keyName = "loadouts",
            name = "Stored loadouts",
            description = "Internal storage (do not edit manually)",
            hidden = true
    )
    default String loadouts()
    {
        return "";
    }

    @ConfigItem(
            keyName = "loadouts",
            name = "",
            description = ""
    )
    void setLoadouts(String value);

    // Discord section
    @ConfigSection(
            name = "Discord",
            description = "Discord webhook settings",
            position = 1
    )
    String discordSection = "discordSection";

    @ConfigItem(
            keyName = "discordWebhook",
            name = "Webhook",
            description = "Discord webhook URL (https://discord.com/api/webhooks/..)",
            section = discordSection
    )
    default String discordWebhook() { return ""; }

    @ConfigItem(
            keyName = "discordWebhook",
            name = "",
            description = "",
            section = discordSection
    )
    void setDiscordWebhook(String url);
}
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
}
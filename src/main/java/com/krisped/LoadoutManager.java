package com.krisped;

import net.runelite.api.EquipmentInventorySlot;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages serialization of loadouts to config.
 * Not wired into UI yet.
 */
public class LoadoutManager
{
    private final LoadoutBuilderConfig config;
    private final List<Loadout> loadouts = new ArrayList<>();

    public LoadoutManager(LoadoutBuilderConfig config)
    {
        this.config = config;
        loadFromConfig();
    }

    public List<Loadout> getAll() { return loadouts; }

    public void add(Loadout l) { loadouts.add(l); persist(); }

    public void remove(Loadout l) { loadouts.remove(l); persist(); }

    public void update() { persist(); }

    public int equipmentSlotCount() { return EquipmentInventorySlot.values().length; }

    private void loadFromConfig()
    {
        loadouts.clear();
        String raw = config.loadouts();
        if (raw == null || raw.isEmpty()) return;
        String[] entries = raw.split(";;");
        for (String e : entries)
        {
            if (e.isEmpty()) continue;
            Loadout l = Loadout.fromStorageString(e, equipmentSlotCount(), 28);
            if (l != null) loadouts.add(l);
        }
    }

    private void persist()
    {
        String serialized = loadouts.stream()
                .map(Loadout::toStorageString)
                .collect(Collectors.joining(";;"));
        config.setLoadouts(serialized);
    }
}
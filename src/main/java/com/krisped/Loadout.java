package com.krisped;

import lombok.Data;
import java.util.Arrays;

/**
 * Simple loadout data (equipment + inventory).
 * Not yet wired to UI (persistence can be added later).
 */
@Data
public class Loadout
{
    private String name;
    private int[] equipment;
    private int[] inventory;

    public Loadout(String name, int equipmentSlots, int inventorySlots)
    {
        this.name = name;
        this.equipment = new int[equipmentSlots];
        this.inventory = new int[inventorySlots];
        Arrays.fill(this.equipment, -1);
        Arrays.fill(this.inventory, -1);
    }

    public String toStorageString()
    {
        return escape(name) + "|" + join(equipment) + "|" + join(inventory);
    }

    public static Loadout fromStorageString(String s, int equipmentSlots, int inventorySlots)
    {
        String[] parts = s.split("\\|");
        if (parts.length != 3) return null;
        String name = unescape(parts[0]);
        Loadout l = new Loadout(name, equipmentSlots, inventorySlots);
        parseInto(parts[1], l.equipment);
        parseInto(parts[2], l.inventory);
        return l;
    }

    private static void parseInto(String csv, int[] target)
    {
        if (csv.isEmpty()) return;
        String[] split = csv.split(",");
        for (int i = 0; i < split.length && i < target.length; i++)
        {
            try { target[i] = Integer.parseInt(split[i]); }
            catch (NumberFormatException e) { target[i] = -1; }
        }
    }

    private static String join(int[] arr)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++)
        {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static String escape(String s)   { return s.replace("|", "_"); }
    private static String unescape(String s) { return s; }

    @Override public String toString() { return name; }
}
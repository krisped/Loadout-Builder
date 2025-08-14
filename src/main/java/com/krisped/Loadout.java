package com.krisped;

import lombok.Data;
import java.util.Arrays;

/**
 * Loadout with per-slot item ids and quantities.
 * Serialization format (v2): name|eqIds|eqQty|invIds|invQty
 * Backward compatibility: v1 (name|eqIds|invIds) => implicit qty=1 if id>0.
 */
@Data
public class Loadout
{
    private String name;
    private int[] equipmentIds;
    private int[] equipmentQty;
    private int[] inventoryIds;
    private int[] inventoryQty;

    public Loadout(String name, int equipmentSlots, int inventorySlots)
    {
        this.name = name;
        this.equipmentIds = new int[equipmentSlots];
        this.equipmentQty = new int[equipmentSlots];
        this.inventoryIds = new int[inventorySlots];
        this.inventoryQty = new int[inventorySlots];
        Arrays.fill(this.equipmentIds, -1);
        Arrays.fill(this.inventoryIds, -1);
        // qty arrays default 0 (treated as 1 if id>0 & qty<=0 when loading)
    }

    public String toStorageString()
    {
        return escape(name) + "|" + join(equipmentIds) + "|" + join(equipmentQty) + "|" + join(inventoryIds) + "|" + join(inventoryQty);
    }

    public static Loadout fromStorageString(String s, int equipmentSlots, int inventorySlots)
    {
        String[] parts = s.split("\\|");
        if (parts.length == 3)
        {
            // v1 legacy: name|eqIds|invIds
            String name = unescape(parts[0]);
            Loadout l = new Loadout(name, equipmentSlots, inventorySlots);
            parseInto(parts[1], l.equipmentIds);
            parseInto(parts[2], l.inventoryIds);
            // set qty =1 where id>0
            for (int i = 0; i < l.equipmentIds.length; i++) if (l.equipmentIds[i] > 0) l.equipmentQty[i] = 1;
            for (int i = 0; i < l.inventoryIds.length; i++) if (l.inventoryIds[i] > 0) l.inventoryQty[i] = 1;
            return l;
        }
        if (parts.length != 5) return null;
        String name = unescape(parts[0]);
        Loadout l = new Loadout(name, equipmentSlots, inventorySlots);
        parseInto(parts[1], l.equipmentIds);
        parseInto(parts[2], l.equipmentQty);
        parseInto(parts[3], l.inventoryIds);
        parseInto(parts[4], l.inventoryQty);
        // normalize quantities
        for (int i = 0; i < l.equipmentIds.length; i++) if (l.equipmentIds[i] > 0 && l.equipmentQty[i] <= 0) l.equipmentQty[i] = 1;
        for (int i = 0; i < l.inventoryIds.length; i++) if (l.inventoryIds[i] > 0 && l.inventoryQty[i] <= 0) l.inventoryQty[i] = 1;
        return l;
    }

    private static void parseInto(String csv, int[] target)
    {
        if (csv.isEmpty()) return;
        String[] split = csv.split(",");
        for (int i = 0; i < split.length && i < target.length; i++)
        {
            try { target[i] = Integer.parseInt(split[i]); }
            catch (NumberFormatException e) { target[i] = (target == null ? -1 : target[i]); }
        }
    }

    private static String join(int[] arr)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++)
        {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static String escape(String s)   { return s.replace("|", "_"); }
    private static String unescape(String s) { return s; }

    @Override public String toString() { return name; }
}
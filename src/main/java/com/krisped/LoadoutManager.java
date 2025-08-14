package com.krisped;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loadout persistence.
 * Current (v5) format: one JSON file per loadout: <safeName>.json
 *   {
 *     "name": "...",
 *     "eq": [ {"id":123,"q":1}, null, ... ],  // length = equipmentSlotCount
 *     "inv": [ {"id":556,"q":2000}, null, ... ] // length = 28
 *   }
 * Legacy support:
 *  - .txt files in prior v4 (section headers) and earlier SERIAL formats are still imported once; on update they are rewritten as .json.
 */
public class LoadoutManager
{
    private static final String LEGACY_FILE = "loadouts.dat"; // old aggregated config backup
    private static final String DIR_NAME = "loadouts";

    private final LoadoutBuilderConfig config;
    private final List<Loadout> loadouts = new ArrayList<>();
    private final File baseDir;
    private final File loadoutDir;
    private final ItemManager itemManager;
    private final Gson gson = new Gson();

    public LoadoutManager(LoadoutBuilderConfig config, ItemManager itemManager)
    {
        this.config = config;
        this.itemManager = itemManager;
        this.baseDir = new File(System.getProperty("user.home"), ".kp");
        if (!baseDir.exists()) baseDir.mkdirs();
        this.loadoutDir = new File(baseDir, DIR_NAME);
        if (!loadoutDir.exists()) loadoutDir.mkdirs();
        loadFromDisk();
    }

    public List<Loadout> getAll() { return Collections.unmodifiableList(loadouts); }

    public void add(Loadout l)
    {
        Loadout existing = findByName(l.getName());
        if (existing != null) loadouts.remove(existing);
        loadouts.add(l);
        writeSingle(l);
        mirrorToConfig();
    }

    public void update() { persistAll(); }

    public void remove(Loadout l)
    {
        loadouts.remove(l);
        deleteFileVariants(l.getName());
        mirrorToConfig();
    }

    public void rename(Loadout l, String newName)
    {
        String old = l.getName();
        if (old.equals(newName) || findByName(newName) != null) return;
        l.setName(newName);
        // attempt to rename existing .json or legacy .txt
        File oldJson = fileFor(old, ".json");
        File oldTxt  = fileFor(old, ".txt");
        File newJson = fileFor(newName, ".json");
        if (oldJson.exists()) oldJson.renameTo(newJson);
        else if (oldTxt.exists()) oldTxt.renameTo(newJson);
        writeSingle(l); // ensure updated content
        mirrorToConfig();
    }

    public int equipmentSlotCount() { return EquipmentInventorySlot.values().length; }

    private Loadout findByName(String name)
    {
        for (Loadout l : loadouts)
            if (l.getName().equalsIgnoreCase(name)) return l;
        return null;
    }

    /* ================= Loading ================= */

    private void loadFromDisk()
    {
        loadouts.clear();

        // Import legacy aggregated file once (no rewrite unless user updates loadouts)
        File legacyAgg = new File(baseDir, LEGACY_FILE);
        if (legacyAgg.exists())
        {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(legacyAgg), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = br.readLine()) != null)
                {
                    if (line.isEmpty()) continue;
                    Loadout l = Loadout.fromStorageString(line, equipmentSlotCount(), 28);
                    if (l != null) loadouts.add(l);
                }
            }
            catch (IOException ignored) {}
        }

        // Load individual files (.json preferred, then legacy .txt)
        File[] files = loadoutDir.listFiles(f -> f.isFile() && (f.getName().toLowerCase().endsWith(".json") || f.getName().toLowerCase().endsWith(".txt")));
        if (files != null)
        {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files)
            {
                if (f.getName().toLowerCase().endsWith(".json"))
                {
                    Loadout l = readJsonFile(f);
                    if (l != null && findByName(l.getName()) == null) loadouts.add(l);
                }
                else if (f.getName().toLowerCase().endsWith(".txt"))
                {
                    Loadout l = readLegacyTextFile(f);
                    if (l != null && findByName(l.getName()) == null) loadouts.add(l);
                }
            }
        }

        // Fallback to config backup if still empty
        if (loadouts.isEmpty())
        {
            String raw = config.loadouts();
            if (raw != null && !raw.isEmpty())
            {
                String[] parts = raw.split(";;");
                for (String s : parts)
                {
                    if (s.isEmpty()) continue;
                    Loadout l = Loadout.fromStorageString(s, equipmentSlotCount(), 28);
                    if (l != null) loadouts.add(l);
                }
            }
        }

        // After initial load, write everything out as JSON for normalization
        persistAll();
    }

    private Loadout readJsonFile(File f)
    {
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))
        {
            J5 obj = gson.fromJson(r, J5.class);
            if (obj == null || obj.name == null) return null;
            Loadout l = new Loadout(obj.name, equipmentSlotCount(), 28);
            if (obj.eq != null)
            {
                for (int i = 0; i < obj.eq.size() && i < l.getEquipmentIds().length; i++)
                {
                    JItem it = obj.eq.get(i);
                    if (it == null || it.id <= 0) continue;
                    l.getEquipmentIds()[i] = it.id;
                    l.getEquipmentQty()[i] = it.q != null && it.q > 0 ? it.q : 1;
                }
            }
            if (obj.inv != null)
            {
                for (int i = 0; i < obj.inv.size() && i < l.getInventoryIds().length; i++)
                {
                    JItem it = obj.inv.get(i);
                    if (it == null || it.id <= 0) continue;
                    l.getInventoryIds()[i] = it.id;
                    l.getInventoryQty()[i] = it.q != null && it.q > 0 ? it.q : 1;
                }
            }
            return l;
        }
        catch (Exception ignored) { return null; }
    }

    private Loadout readLegacyTextFile(File f)
    {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)))
        {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            // v4 header-based or earlier
            Loadout l = parseLegacyLines(lines);
            return l;
        }
        catch (IOException ignored) { return null; }
    }

    private Loadout parseLegacyLines(List<String> lines)
    {
        // Look for SERIAL= first
        for (String ln : lines)
        {
            if (ln.startsWith("SERIAL="))
            {
                String serial = ln.substring(7).trim();
                return Loadout.fromStorageString(serial, equipmentSlotCount(), 28);
            }
        }
        // Section header JSON (v4) -> reconstruct JSON block
        int jsonHeader = indexOfHeader(lines, "JSON");
        if (jsonHeader != -1)
        {
            int jsonStart = jsonHeader + 1;
            int jsonEnd = firstHeaderAfter(lines, jsonStart);
            String json = joinTrimmed(lines, jsonStart, jsonEnd);
            // Could be the earlier panel JSON format {"setup":{...}}
            Loadout attempt = parsePanelJson(json);
            if (attempt != null) return attempt;
        }
        // Single-line fallback (v1/v2/v3 inline) first non-comment
        for (String ln : lines)
        {
            String t = ln.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) continue;
            Loadout l = Loadout.fromStorageString(t, equipmentSlotCount(), 28);
            if (l != null) return l;
        }
        return null;
    }

    private int indexOfHeader(List<String> lines, String header)
    {
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).trim().equalsIgnoreCase(header)) return i;
        return -1;
    }

    private int firstHeaderAfter(List<String> lines, int from)
    {
        for (int i = from; i < lines.size(); i++)
        {
            String t = lines.get(i).trim();
            if (t.equalsIgnoreCase("JSON") || t.equalsIgnoreCase("Repcal") || t.equalsIgnoreCase("KittyKeys")) return i;
        }
        return lines.size();
    }

    private String joinTrimmed(List<String> lines, int start, int end)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++)
        {
            String ln = lines.get(i);
            if (ln.equalsIgnoreCase("Repcal") || ln.equalsIgnoreCase("KittyKeys")) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(ln);
        }
        return sb.toString().trim();
    }

    // Panel JSON legacy model
    private static class PanelRoot { PanelSetup setup; }
    private static class PanelSetup { List<JItem> inv; List<JItem> eq; String name; }

    private Loadout parsePanelJson(String json)
    {
        try
        {
            PanelRoot root = gson.fromJson(json, PanelRoot.class);
            if (root == null || root.setup == null) return null;
            Loadout l = new Loadout(root.setup.name != null ? root.setup.name : "Loadout", equipmentSlotCount(), 28);
            if (root.setup.eq != null)
            {
                for (int i = 0; i < root.setup.eq.size() && i < l.getEquipmentIds().length; i++)
                {
                    JItem ji = root.setup.eq.get(i);
                    if (ji == null || ji.id <= 0) continue;
                    l.getEquipmentIds()[i] = ji.id;
                    l.getEquipmentQty()[i] = ji.q != null && ji.q > 0 ? ji.q : 1;
                }
            }
            if (root.setup.inv != null)
            {
                for (int i = 0; i < root.setup.inv.size() && i < l.getInventoryIds().length; i++)
                {
                    JItem ji = root.setup.inv.get(i);
                    if (ji == null || ji.id <= 0) continue;
                    l.getInventoryIds()[i] = ji.id;
                    l.getInventoryQty()[i] = ji.q != null && ji.q > 0 ? ji.q : 1;
                }
            }
            return l;
        }
        catch (Exception ignored) { return null; }
    }

    /* ================= Persistence ================= */

    private void persistAll()
    {
        if (!loadoutDir.exists()) loadoutDir.mkdirs();
        // Write all as JSON (overwriting or replacing legacy .txt)
        Set<String> expected = loadouts.stream().map(l -> fileNameFor(l.getName(), ".json")).collect(Collectors.toSet());
        File[] existing = loadoutDir.listFiles();
        if (existing != null)
        {
            for (File f : existing)
            {
                String lower = f.getName().toLowerCase();
                if (lower.endsWith(".json"))
                {
                    if (!expected.contains(f.getName())) f.delete();
                }
                // leave legacy .txt in place until possibly removed manually (non-destructive)
            }
        }
        for (Loadout l : loadouts) writeSingle(l);
        mirrorToConfig();
    }

    private void writeSingle(Loadout l)
    {
        File out = fileFor(l.getName(), ".json");
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)))
        {
            int eqSlots = equipmentSlotCount();
            bw.write('{'); bw.newLine();
            bw.write("  \"name\": \"" + escapeJson(l.getName()) + "\","); bw.newLine();
            // equipment
            bw.write("  \"eq\": ["); bw.newLine();
            for (int i = 0; i < eqSlots; i++)
            {
                int id = l.getEquipmentIds()[i];
                int q  = l.getEquipmentQty()[i];
                bw.write("    ");
                if (id > 0) bw.write("{\"id\":" + id + ",\"q\":" + Math.max(1,q) + "}"); else bw.write("null");
                if (i < eqSlots - 1) bw.write(',');
                bw.newLine();
            }
            bw.write("  ],"); bw.newLine();
            // inventory
            bw.write("  \"inv\": ["); bw.newLine();
            for (int i = 0; i < l.getInventoryIds().length; i++)
            {
                int id = l.getInventoryIds()[i];
                int q  = l.getInventoryQty()[i];
                bw.write("    ");
                if (id > 0) bw.write("{\"id\":" + id + ",\"q\":" + Math.max(1,q) + "}"); else bw.write("null");
                if (i < l.getInventoryIds().length - 1) bw.write(',');
                bw.newLine();
            }
            bw.write("  ]"); bw.newLine();
            bw.write('}'); bw.newLine();
        }
        catch (IOException ignored) {}
    }

    private void deleteFileVariants(String name)
    {
        File fJson = fileFor(name, ".json");
        File fTxt  = fileFor(name, ".txt");
        if (fJson.exists()) fJson.delete();
        if (fTxt.exists()) fTxt.delete();
    }

    private File fileFor(String name, String ext)
    {
        return new File(loadoutDir, fileNameFor(name, ext));
    }

    private String fileNameFor(String name, String ext)
    {
        String safe = name.replaceAll("[^a-zA-Z0-9._ -]", "_").replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "loadout" + System.currentTimeMillis();
        if (!ext.startsWith(".")) ext = "." + ext;
        return safe + ext;
    }

    private void mirrorToConfig()
    {
        String serialized = loadouts.stream().map(Loadout::toStorageString).collect(Collectors.joining(";;"));
        config.setLoadouts(serialized);
    }

    private String itemName(int id)
    {
        try { return itemManager.getItemComposition(id).getName(); }
        catch (Throwable t) { return "Item " + id; }
    }

    /* ================= Legacy helpers kept for possible export ================= */

    private String escapeJson(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ================= JSON model (v5) ================= */
    private static class J5 { String name; List<JItem> eq; List<JItem> inv; }
    private static class JItem { int id; @SerializedName("q") Integer q; }
}

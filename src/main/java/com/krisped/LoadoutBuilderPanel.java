package com.krisped;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/**
 * Panel for building a loadout: equipment + inventory,
 * Repcal/KittyKeys eksport, import og clipboard export.
 *
 * JSON-import støttes.
 * KittyKeys:
 *  - WITHDRAW bruker sanitert navn
 *  - BANK_EQUIP bruker samme sanitert navn
 *
 * Sanitizing-krav (oppdatert):
 *  - Ikke uppercase (returner lowercase)
 *  - Behold eksisterende parenteser (ikke fjern dem, ikke legg til nye)
 *  - Sett inn en underscore før '(' hvis den følger umiddelbart etter et ordtegn (f.eks. glory(5) -> glory_(5))
 *  - Fjern/erstat tegn som ikke er [A-Za-z0-9() ] med mellomrom
 *  - Kollaps flere mellomrom/underscores til en enkelt underscore
 *  - Trim undersores i start/slutt
 */
public class LoadoutBuilderPanel extends PluginPanel implements LoadoutSlot.SlotActionHandler
{
    private static final int EQUIPMENT_GRID_HGAP = 4;
    private static final int EQUIPMENT_GRID_VGAP = 4;
    private static final int INVENTORY_GRID_HGAP = 4;
    private static final int INVENTORY_GRID_VGAP = 4;

    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final Client client;

    private final Map<EquipmentInventorySlot, LoadoutSlot> equipmentSlots = new EnumMap<>(EquipmentInventorySlot.class);
    private final JPanel equipmentPanel = new JPanel();

    private final LoadoutSlot[] inventorySlots = new LoadoutSlot[28];
    private final JPanel inventoryPanel = new JPanel();

    private final JButton resetButton = new JButton("Reset All");
    private final JButton copyButton  = new JButton("Copy Loadout");

    private JTextArea repcalArea;
    private JButton repcalButton;
    private JButton kittyKeysButton;
    private JButton importButton;
    private JButton exportButton;

    private final Gson gson = new Gson();

    // Mapping av eq-array index til faktiske slots (null = placeholder/hopp over)
    // Korrigert: index 9 = GLOVES, index 10 = BOOTS
    private static final EquipmentInventorySlot[] EQ_INDEX_MAP = new EquipmentInventorySlot[]{
            EquipmentInventorySlot.HEAD,   // 0
            EquipmentInventorySlot.CAPE,   // 1
            EquipmentInventorySlot.AMULET, // 2
            EquipmentInventorySlot.WEAPON, // 3
            EquipmentInventorySlot.BODY,   // 4
            EquipmentInventorySlot.SHIELD, // 5
            null,                          // 6 placeholder
            EquipmentInventorySlot.LEGS,   // 7
            null,                          // 8 placeholder
            EquipmentInventorySlot.GLOVES, // 9
            EquipmentInventorySlot.BOOTS,  // 10
            null,                          // 11 placeholder
            EquipmentInventorySlot.RING,   // 12
            EquipmentInventorySlot.AMMO    // 13
    };

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread, Client client)
    {
        super(false);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.client = client;
        buildUI();
    }

    /* ================= UI BUILD ================= */

    private void buildUI()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        buildEquipmentPanel();
        add(Box.createVerticalStrut(10));
        buildInventoryPanel();
        add(Box.createVerticalStrut(10));
        buildLoadoutSection();
        add(Box.createVerticalGlue());
    }

    private void buildEquipmentPanel()
    {
        JPanel wrapper = new JPanel(new BorderLayout(0,6));
        wrapper.setBorder(new TitledBorder("Equipment"));
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));

        equipmentPanel.setLayout(new GridLayout(5, 3, EQUIPMENT_GRID_HGAP, EQUIPMENT_GRID_VGAP));
        equipmentPanel.setOpaque(false);

        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
        {
            equipmentSlots.put(slot, new LoadoutSlot(itemManager, this, slot.ordinal(), true));
        }

        addEquipCell(null, null);
        addEquipCell(EquipmentInventorySlot.HEAD, "Head");
        addEquipCell(null, null);

        addEquipCell(EquipmentInventorySlot.CAPE, "Cape");
        addEquipCell(EquipmentInventorySlot.AMULET, "Amulet");
        addEquipCell(EquipmentInventorySlot.AMMO, "Ammo");

        addEquipCell(EquipmentInventorySlot.WEAPON, "Weapon");
        addEquipCell(EquipmentInventorySlot.BODY, "Body");
        addEquipCell(EquipmentInventorySlot.SHIELD, "Shield");

        addEquipCell(null, null);
        addEquipCell(EquipmentInventorySlot.LEGS, "Legs");
        addEquipCell(null, null);

        addEquipCell(EquipmentInventorySlot.GLOVES, "Gloves");
        addEquipCell(EquipmentInventorySlot.BOOTS, "Boots");
        addEquipCell(EquipmentInventorySlot.RING, "Ring");

        wrapper.add(equipmentPanel, BorderLayout.CENTER);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        buttonRow.setOpaque(false);
        resetButton.addActionListener(e -> resetAll());
        copyButton.addActionListener(e -> copyLoadout());
        buttonRow.add(copyButton);
        buttonRow.add(resetButton);
        wrapper.add(buttonRow, BorderLayout.SOUTH);

        add(wrapper);
    }

    private void addEquipCell(EquipmentInventorySlot slot, String labelText)
    {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);
        cell.setAlignmentX(Component.CENTER_ALIGNMENT);

        if (slot == null)
        {
            cell.add(Box.createVerticalStrut(48));
            equipmentPanel.add(cell);
            return;
        }

        LoadoutSlot ls = equipmentSlots.get(slot);
        ls.setAlignmentX(Component.CENTER_ALIGNMENT);
        cell.add(ls);

        JLabel lbl = new JLabel(labelText, SwingConstants.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setForeground(new Color(215, 215, 215));
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        cell.add(lbl);

        equipmentPanel.add(cell);
    }

    private void buildInventoryPanel()
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new TitledBorder("Inventory"));
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 580));

        inventoryPanel.setLayout(new GridLayout(7, 4, INVENTORY_GRID_HGAP, INVENTORY_GRID_VGAP));
        inventoryPanel.setOpaque(false);

        for (int i = 0; i < 28; i++)
        {
            inventorySlots[i] = new LoadoutSlot(itemManager, this, i, false);
            JPanel c = new JPanel();
            c.setOpaque(false);
            c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
            inventorySlots[i].setAlignmentX(Component.CENTER_ALIGNMENT);
            c.add(inventorySlots[i]);
            inventoryPanel.add(c);
        }

        wrapper.add(inventoryPanel, BorderLayout.CENTER);
        add(wrapper);
    }

    private void buildLoadoutSection()
    {
        JPanel wrapper = new JPanel(new BorderLayout(4,4));
        wrapper.setBorder(new TitledBorder("Loadout"));
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 350));

        repcalArea = new JTextArea();
        repcalArea.setEditable(true);
        repcalArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        repcalArea.setLineWrap(false);
        repcalArea.setRows(14);

        JScrollPane sp = new JScrollPane(repcalArea);
        wrapper.add(sp, BorderLayout.CENTER);

        JPanel buttonsGrid = new JPanel(new GridLayout(2,2,6,6));
        buttonsGrid.setOpaque(false);

        repcalButton = new JButton("Repcal");
        kittyKeysButton = new JButton("KittyKeys");
        importButton = new JButton("Import");
        exportButton = new JButton("Export");

        repcalButton.addActionListener(e -> generateRepcalString());
        kittyKeysButton.addActionListener(e -> generateKittyKeysScript());
        importButton.addActionListener(e -> importRepcalCodes());
        exportButton.addActionListener(e -> exportToClipboard());

        buttonsGrid.add(repcalButton);
        buttonsGrid.add(kittyKeysButton);
        buttonsGrid.add(importButton);
        buttonsGrid.add(exportButton);

        wrapper.add(buttonsGrid, BorderLayout.SOUTH);
        add(wrapper);
    }

    /* ================= BASIC ACTIONS ================= */

    private void resetAll()
    {
        for (LoadoutSlot slot : equipmentSlots.values()) slot.clear();
        for (LoadoutSlot slot : inventorySlots) slot.clear();
        repcalArea.setText("");
    }

    private void copyLoadout()
    {
        clientThread.invoke(() -> {
            ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
            ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
            Item[] eqItems = eq != null ? eq.getItems() : new Item[0];
            Item[] invItems = inv != null ? inv.getItems() : new Item[0];

            SwingUtilities.invokeLater(() -> {
                for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
                {
                    int idx = slot.getSlotIdx();
                    int id = -1;
                    int qty = 0;
                    if (idx >= 0 && idx < eqItems.length)
                    {
                        Item it = eqItems[idx];
                        if (it != null && it.getId() > 0)
                        {
                            id = it.getId();
                            qty = it.getQuantity();
                        }
                    }
                    equipmentSlots.get(slot).setItem(id, qty);
                }
                for (int i = 0; i < inventorySlots.length; i++)
                {
                    int id = -1;
                    int qty = 0;
                    if (invItems != null && i < invItems.length)
                    {
                        Item it = invItems[i];
                        if (it != null && it.getId() > 0)
                        {
                            id = it.getId();
                            qty = it.getQuantity();
                        }
                    }
                    inventorySlots[i].setItem(id, qty);
                }
            });
        });
    }

    private void exportToClipboard()
    {
        String text = repcalArea.getText();
        if (text == null || text.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Ingen tekst å kopiere. Generer først (Repcal / KittyKeys).", "Export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        copyToClipboard(text);
        JOptionPane.showMessageDialog(this, "Loadout kopiert til clipboard.", "Export", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copyToClipboard(String s)
    {
        try
        {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(s), null);
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog(this, "Kunne ikke kopiere: " + ex.getMessage(), "Clipboard feil", JOptionPane.ERROR_MESSAGE);
        }
    }

    /* ================= REPCAL EXPORT ================= */

    private void generateRepcalString()
    {
        clientThread.invoke(() -> {
            StringBuilder sb = new StringBuilder();

            EquipmentInventorySlot[] order = {
                    EquipmentInventorySlot.BOOTS,
                    EquipmentInventorySlot.AMULET,
                    EquipmentInventorySlot.SHIELD,
                    EquipmentInventorySlot.CAPE,
                    EquipmentInventorySlot.GLOVES,
                    EquipmentInventorySlot.BODY,
                    EquipmentInventorySlot.HEAD,
                    EquipmentInventorySlot.RING,
                    EquipmentInventorySlot.LEGS,
                    EquipmentInventorySlot.WEAPON,
                    EquipmentInventorySlot.AMMO
            };

            for (EquipmentInventorySlot slot : order)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls == null || ls.getItemId() <= 0) continue;
                String name = resolveName(ls);
                String code = equipmentCode(slot);
                String qtyString = isWeaponOrAmmoWildcard(slot, ls, name)
                        ? "*"
                        : Integer.toString(Math.max(1, ls.getQuantity()));
                sb.append(code).append(":").append(name).append(":").append(qtyString).append("\n");
            }

            LinkedHashMap<String, Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                String name = resolveName(ls);
                int add = ls.isStackable() ? Math.max(1, ls.getQuantity()) : 1;
                invCounts.merge(name, add, Integer::sum);
            }

            for (Map.Entry<String, Integer> e : invCounts.entrySet())
            {
                sb.append("I:").append(e.getKey()).append(":").append(e.getValue()).append("\n");
            }

            final String output = sb.toString().trim();
            SwingUtilities.invokeLater(() -> repcalArea.setText(output));
        });
    }

    /* ================= KITTYKEYS EXPORT ================= */

    private void generateKittyKeysScript()
    {
        clientThread.invoke(() -> {
            StringBuilder sb = new StringBuilder();

            EquipmentInventorySlot[] order = {
                    EquipmentInventorySlot.HEAD,
                    EquipmentInventorySlot.BODY,
                    EquipmentInventorySlot.RING,
                    EquipmentInventorySlot.CAPE,
                    EquipmentInventorySlot.AMULET,
                    EquipmentInventorySlot.WEAPON,
                    EquipmentInventorySlot.SHIELD,
                    EquipmentInventorySlot.LEGS,
                    EquipmentInventorySlot.GLOVES,
                    EquipmentInventorySlot.BOOTS,
                    EquipmentInventorySlot.AMMO
            };

            // Lagre sanitized navn i innsettingsrekkefølge
            LinkedHashMap<EquipmentInventorySlot, String> sanitizedNames = new LinkedHashMap<>();

            for (EquipmentInventorySlot slot : order)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null && ls.getItemId() > 0)
                {
                    String rawName = resolveName(ls);
                    String sanitized = kittyKeysItemName(rawName);
                    String qty = isWeaponOrAmmoWildcard(slot, ls, rawName)
                            ? "*"
                            : Integer.toString(Math.max(1, ls.getQuantity()));
                    sb.append("WITHDRAW ").append(sanitized).append(" ").append(qty).append("\n");
                    sanitizedNames.put(slot, sanitized);
                }
            }
            if (!sanitizedNames.isEmpty()) sb.append("\n");

            for (String sanitized : sanitizedNames.values())
            {
                sb.append("BANK_EQUIP ").append(sanitized).append("\n");
            }
            if (!sanitizedNames.isEmpty()) sb.append("\n");

            LinkedHashMap<String, Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                String rawName = resolveName(ls);
                int add = ls.isStackable() ? Math.max(1, ls.getQuantity()) : 1;
                invCounts.merge(rawName, add, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : invCounts.entrySet())
            {
                String sanitized = kittyKeysItemName(e.getKey());
                sb.append("WITHDRAW ").append(sanitized).append(" ").append(e.getValue()).append("\n");
            }

            final String output = sb.toString().trim();
            SwingUtilities.invokeLater(() -> repcalArea.setText(output));
        });
    }

    /**
     * KittyKeys navn-sanitering (oppdatert krav):
     * - Behold parenteser som finnes i originalen.
     * - Ikke legg til parenteser som ikke finnes.
     * - Sett inn underscore før '(' hvis den kommer direkte etter et ordtegn (bokstav/tall) eller apostrof.
     * - Tillat kun [a-z0-9() ] i mellom-steg (behold parenteser), andre tegn -> mellomrom.
     * - Konverter til lowercase (ikke uppercase).
     * - Mellomrom -> underscore, kollaps flere underscores.
     * - Fjern leading/trailing underscores.
     */
    private String kittyKeysItemName(String name)
    {
        if (name == null) return "";
        String out = name;

        // Normaliser fancy apostrof
        out = out.replace("’", "'");

        // Sett inn underscore før '(' hvis det ikke allerede er mellomrom/underscore før
        // Eksempel: "glory(5)" -> "glory_(5)"
        out = out.replaceAll("(?<![\\s_])\\(", "_(");

        // Tillat kun bokstaver, tall, parenteser og space midlertidig
        out = out.replaceAll("[^A-Za-z0-9() ]", " ");

        // Trim og collapse spaces til én underscore
        out = out.trim().replaceAll("\\s+", "_");

        // Kollaps flere underscores
        out = out.replaceAll("_+", "_");

        // Fjern leading/trailing underscore
        out = out.replaceAll("^_+", "").replaceAll("_+$", "");

        // Lowercase (krav: ikke uppercase)
        out = out.toLowerCase(Locale.ROOT);

        return out;
    }

    /* ================= IMPORT (Repcal + JSON autodetect) ================= */

    private void importRepcalCodes()
    {
        String text = repcalArea.getText();
        if (text == null || text.trim().isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Ingen kode å importere.", "Import", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String trimmed = text.trim();
        if (looksLikeJsonLoadout(trimmed))
        {
            if (importJsonLoadout(trimmed))
                return; // JSON OK -> ferdig
        }

        List<RepcalLine> lines = parseRepcalLines(text);
        if (lines.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Fant ingen gyldige linjer.", "Import", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<String> equipmentCodesInInput = new HashSet<>();
        boolean hasInventory = false;
        for (RepcalLine l : lines)
        {
            if (l.code.equalsIgnoreCase("I"))
                hasInventory = true;
            else
                equipmentCodesInInput.add(l.code.toUpperCase(Locale.ROOT));
        }

        Map<String, EquipmentInventorySlot> codeToSlot = codeToSlotMap();
        for (String c : equipmentCodesInInput)
        {
            EquipmentInventorySlot slot = codeToSlot.get(c);
            if (slot != null)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null) ls.clear();
            }
        }
        if (hasInventory)
        {
            for (LoadoutSlot s : inventorySlots) s.clear();
        }

        clientThread.invoke(() -> {
            List<String> errors = new ArrayList<>();
            int invPtr = 0;

            for (RepcalLine line : lines)
            {
                if (line.code.equalsIgnoreCase("I"))
                {
                    int itemId = resolveItemId(line.name, errors);
                    if (itemId <= 0) continue;

                    ItemComposition comp;
                    boolean stackable;
                    try
                    {
                        comp = itemManager.getItemComposition(itemId);
                        stackable = comp.isStackable() || comp.getNote() != -1;
                    }
                    catch (Exception ex)
                    {
                        errors.add("Feil ved henting: " + line.name);
                        continue;
                    }

                    int qty = line.quantity <= 0 ? 1 : line.quantity;
                    if (stackable)
                    {
                        while (invPtr < inventorySlots.length && inventorySlots[invPtr].getItemId() > 0)
                            invPtr++;
                        if (invPtr >= inventorySlots.length)
                        {
                            errors.add("Inventory full (stackable " + line.name + ")");
                            continue;
                        }
                        final int slotIndex = invPtr;
                        invPtr++;
                        SwingUtilities.invokeLater(() -> inventorySlots[slotIndex].setItem(itemId, qty));
                    }
                    else
                    {
                        for (int i = 0; i < qty; i++)
                        {
                            while (invPtr < inventorySlots.length && inventorySlots[invPtr].getItemId() > 0)
                                invPtr++;
                            if (invPtr >= inventorySlots.length)
                            {
                                errors.add("Inventory full (" + line.name + " +" + (qty - i) + " rester)");
                                break;
                            }
                            final int slotIndex = invPtr;
                            invPtr++;
                            SwingUtilities.invokeLater(() -> inventorySlots[slotIndex].setItem(itemId, 1));
                        }
                    }
                }
                else
                {
                    EquipmentInventorySlot slot = codeToSlot.get(line.code.toUpperCase(Locale.ROOT));
                    if (slot == null)
                    {
                        errors.add("Ukjent kode: " + line.code);
                        continue;
                    }
                    int itemId = resolveItemId(line.name, errors);
                    if (itemId <= 0) continue;
                    final LoadoutSlot ls = equipmentSlots.get(slot);
                    if (ls == null)
                    {
                        errors.add("Ingen slot for: " + line.code);
                        continue;
                    }
                    SwingUtilities.invokeLater(() -> ls.setItem(itemId, line.quantity <= 0 ? 1 : line.quantity));
                }
            }

            if (!errors.isEmpty())
            {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        String.join("\n", errors),
                        "Import – problemer",
                        JOptionPane.WARNING_MESSAGE
                ));
            }
        });
    }

    private boolean looksLikeJsonLoadout(String text)
    {
        return text.startsWith("{") && text.contains("\"setup\"");
    }

    private boolean importJsonLoadout(String json)
    {
        JsonRoot root;
        try
        {
            root = gson.fromJson(json, JsonRoot.class);
        }
        catch (JsonSyntaxException ex)
        {
            JOptionPane.showMessageDialog(this, "Ugyldig JSON-format: " + ex.getMessage(), "JSON Import", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (root == null || root.setup == null)
        {
            JOptionPane.showMessageDialog(this, "JSON mangler 'setup'.", "JSON Import", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        resetAll();

        if (root.setup.inv != null)
        {
            for (int i = 0; i < Math.min(28, root.setup.inv.size()); i++)
            {
                JsonItem ji = root.setup.inv.get(i);
                if (ji == null || ji.id <= 0) continue;
                int qty = ji.q != null && ji.q > 0 ? ji.q : 1;
                inventorySlots[i].setItem(ji.id, qty);
            }
        }

        if (root.setup.eq != null)
        {
            int len = Math.min(root.setup.eq.size(), EQ_INDEX_MAP.length);
            for (int i = 0; i < len; i++)
            {
                JsonItem ji = root.setup.eq.get(i);
                if (ji == null || ji.id <= 0) continue;
                EquipmentInventorySlot slot = EQ_INDEX_MAP[i];
                if (slot == null) continue;
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null)
                {
                    int qty = ji.q != null && ji.q > 0 ? ji.q : 1;
                    ls.setItem(ji.id, qty);
                }
            }
        }

        JOptionPane.showMessageDialog(this, "JSON-loadout importert.", "JSON Import", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /* ===== JSON data klasser ===== */

    private static class JsonRoot
    {
        JsonSetup setup;
        List<Integer> layout; // Ignoreres nå
    }
    private static class JsonSetup
    {
        List<JsonItem> inv;
        List<JsonItem> eq;
        String name;
        String hc;
    }
    private static class JsonItem
    {
        int id;
        @SerializedName("q")
        Integer q;
    }

    /* ================= HELPERS ================= */

    private String resolveName(LoadoutSlot ls)
    {
        String name = ls.getResolvedName();
        if (name == null)
        {
            try { name = itemManager.getItemComposition(ls.getItemId()).getName(); }
            catch (Exception ignored) { name = "Item " + ls.getItemId(); }
        }
        return name;
    }

    private boolean isWeaponOrAmmoWildcard(EquipmentInventorySlot slot, LoadoutSlot ls, String name)
    {
        if (slot != EquipmentInventorySlot.WEAPON && slot != EquipmentInventorySlot.AMMO)
            return false;
        String lower = name.toLowerCase();
        if (!ls.isStackable() && ls.getQuantity() <= 1)
            return false;
        return lower.contains("dart") || lower.contains("knife") || lower.contains("javelin")
                || lower.contains("throwing axe") || lower.contains("thrownaxe") || lower.contains("chinchompa")
                || lower.contains("arrow") || lower.contains("bolt") || lower.contains("gem bolt")
                || lower.contains("bolt rack") || lower.contains("brutal") || lower.contains("cannonball");
    }

    private String equipmentSlotToken(EquipmentInventorySlot slot)
    {
        switch (slot)
        {
            case HEAD: return "HELM";
            case CAPE: return "CAPE";
            case AMULET: return "AMULET";
            case WEAPON: return "WEAPON";
            case BODY: return "BODY";
            case SHIELD: return "SHIELD";
            case LEGS: return "LEGS";
            case GLOVES: return "GLOVES";
            case BOOTS: return "BOOTS";
            case RING: return "RING";
            case AMMO: return "AMMO";
            default: return slot.name();
        }
    }

    private String equipmentCode(EquipmentInventorySlot slot)
    {
        switch (slot)
        {
            case HEAD: return "H";
            case CAPE: return "Ca";
            case AMULET: return "N";
            case WEAPON: return "W";
            case BODY: return "C";
            case SHIELD: return "S";
            case LEGS: return "L";
            case GLOVES: return "G";
            case BOOTS: return "B";
            case RING: return "R";
            case AMMO: return "A";
            default: return slot.name();
        }
    }

    private Map<String, EquipmentInventorySlot> codeToSlotMap()
    {
        Map<String, EquipmentInventorySlot> m = new HashMap<>();
        m.put("H", EquipmentInventorySlot.HEAD);
        m.put("CA", EquipmentInventorySlot.CAPE);
        m.put("N", EquipmentInventorySlot.AMULET);
        m.put("W", EquipmentInventorySlot.WEAPON);
        m.put("C", EquipmentInventorySlot.BODY);
        m.put("S", EquipmentInventorySlot.SHIELD);
        m.put("L", EquipmentInventorySlot.LEGS);
        m.put("G", EquipmentInventorySlot.GLOVES);
        m.put("B", EquipmentInventorySlot.BOOTS);
        m.put("R", EquipmentInventorySlot.RING);
        m.put("A", EquipmentInventorySlot.AMMO);
        return m;
    }

    /* ================= REPCAL PARSING ================= */

    private static class RepcalLine
    {
        final String code;
        final String name;
        final int quantity;
        RepcalLine(String code, String name, int quantity)
        {
            this.code = code;
            this.name = name;
            this.quantity = quantity;
        }
    }

    private List<RepcalLine> parseRepcalLines(String text)
    {
        List<RepcalLine> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines)
        {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(":", 3);
            if (parts.length < 2) continue;

            String code = parts[0].trim();
            String name;
            int qty = 1;

            if (parts.length == 2)
            {
                name = parts[1].trim();
            }
            else
            {
                name = parts[1].trim();
                String qRaw = parts[2].trim();
                if (qRaw.equals("*"))
                    qty = 1;
                else
                {
                    try { qty = Integer.parseInt(qRaw); }
                    catch (NumberFormatException ignored) { qty = 1; }
                }
            }

            out.add(new RepcalLine(code, name, qty));
        }
        return out;
    }

    /* ================= MATCHING & SEARCH ================= */

    private volatile List<Integer> allItemIdsCache = null;
    private volatile boolean buildingIndex = false;
    private Map<String,Integer> exactNameMap = null;

    private void ensureIndex()
    {
        if (allItemIdsCache != null || buildingIndex) return;
        buildingIndex = true;
        int max = 40_000;
        List<Integer> tmp = new ArrayList<>(9000);
        for (int id = 1; id < max; id++)
        {
            try
            {
                String nm = itemManager.getItemComposition(id).getName();
                if (nm != null && !nm.equalsIgnoreCase("null"))
                    tmp.add(id);
            }
            catch (Exception ignored){}
        }
        allItemIdsCache = tmp;
        buildingIndex = false;
    }

    private void ensureExactNameMap()
    {
        if (exactNameMap != null) return;
        ensureIndex();
        Map<String,Integer> m = new HashMap<>();
        if (allItemIdsCache != null)
        {
            for (int id : allItemIdsCache)
            {
                try
                {
                    String nm = itemManager.getItemComposition(id).getName();
                    if (nm != null && !nm.equalsIgnoreCase("null"))
                    {
                        String key = nm.toLowerCase();
                        m.putIfAbsent(key, id);
                    }
                }
                catch (Exception ignored){}
            }
        }
        exactNameMap = m;
    }

    private int resolveItemId(String nameInput, List<String> errors)
    {
        if (nameInput == null || nameInput.isEmpty())
        {
            errors.add("Tomt navn");
            return -1;
        }

        String trimmed = nameInput.trim();
        String lower = trimmed.toLowerCase();
        boolean potionDoseHeuristic = false;

        if (trimmed.endsWith("("))
        {
            potionDoseHeuristic = true;
            trimmed = trimmed.substring(0, trimmed.length()-1);
            lower = trimmed.toLowerCase();
        }

        ensureExactNameMap();

        Integer exact = exactNameMap.get(lower);
        if (exact != null)
            return exact;

        if (potionDoseHeuristic)
        {
            for (int dose = 4; dose >= 1; dose--)
            {
                String trial = trimmed + "(" + dose + ")";
                Integer eq = exactNameMap.get(trial.toLowerCase());
                if (eq != null) return eq;
            }
        }

        List<Integer> candidates = searchItems(trimmed);
        if (candidates.isEmpty())
        {
            ensureIndex();
            if (allItemIdsCache != null)
            {
                for (int id : allItemIdsCache)
                {
                    try
                    {
                        String nm = itemManager.getItemComposition(id).getName();
                        if (nm != null && nm.toLowerCase().startsWith(lower))
                            candidates.add(id);
                        if (candidates.size() >= 200) break;
                    }
                    catch (Exception ignored){}
                }
            }
        }

        if (candidates.isEmpty())
        {
            errors.add("Fant ikke item: " + nameInput);
            return -1;
        }

        int bestId = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int id : candidates)
        {
            double score = 0;
            ItemComposition comp;
            String nm;
            try
            {
                comp = itemManager.getItemComposition(id);
                nm = comp.getName();
            }
            catch (Exception ex)
            {
                continue;
            }
            if (nm == null) continue;
            String nmLower = nm.toLowerCase();

            if (nmLower.equals(lower)) score += 200;
            if (nmLower.startsWith(lower)) score += 30;
            if (nmLower.startsWith(lower) && nmLower.length() > lower.length()) score -= 40;

            score -= Math.abs(nm.length() - nameInput.length()) * 1.5;

            boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
            boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
            if (noted) score -= 25;
            if (placeholder) score -= 25;

            if (score > bestScore)
            {
                bestScore = score;
                bestId = id;
            }
        }

        if (bestId <= 0)
            errors.add("Fant ikke passende item: " + nameInput);

        return bestId;
    }

    private List<Integer> searchItems(String query)
    {
        query = query.trim();
        if (query.isEmpty()) return Collections.emptyList();
        try
        {
            Method m = itemManager.getClass().getMethod("search", String.class);
            Object res = m.invoke(itemManager, query);
            if (res instanceof Collection)
            {
                List<Integer> list = new ArrayList<>();
                for (Object o : (Collection<?>) res)
                {
                    if (o instanceof Integer)
                        list.add((Integer) o);
                    else if (o != null)
                    {
                        try
                        {
                            Method mid = o.getClass().getMethod("getItemId");
                            Object idObj = mid.invoke(o);
                            if (idObj instanceof Integer) list.add((Integer) idObj);
                        }
                        catch (Exception ignore)
                        {
                            try
                            {
                                Method mid2 = o.getClass().getMethod("getId");
                                Object idObj2 = mid2.invoke(o);
                                if (idObj2 instanceof Integer) list.add((Integer) idObj2);
                            }
                            catch (Exception ignore2){}
                        }
                    }
                }
                return list;
            }
        }
        catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /* ================= SLOT ACTION HANDLER ================= */

    @Override
    public void onLeftClick(LoadoutSlot slot, boolean isEquipment, int index)
    {
        int chosen = ItemSearchDialog.showDialog(this, itemManager, clientThread);
        if (chosen > 0)
            slot.setItem(chosen, 1);
    }

    @Override
    public void requestItemInfoOnClientThread(LoadoutSlot slot, int itemId)
    {
        clientThread.invoke(() -> {
            try
            {
                final ItemComposition comp = itemManager.getItemComposition(itemId);
                final String name = comp.getName();
                final boolean stackable = comp.isStackable() || comp.getNote() != -1;
                final BufferedImage icon = itemManager.getImage(itemId);
                SwingUtilities.invokeLater(() -> slot.setResolvedItemInfo(name, icon, stackable));
            }
            catch (Exception ex)
            {
                SwingUtilities.invokeLater(() -> slot.setResolvedItemInfo(null, null, false));
            }
        });
    }

    @Override
    public void onAddAmount(LoadoutSlot slot, int delta)
    {
        if (slot.getItemId() <= 0) return;

        if (slot.isEquipment())
        {
            slot.incrementQuantityInternal(delta);
            return;
        }

        if (slot.isStackable())
        {
            slot.incrementQuantityInternal(delta);
        }
        else
        {
            addNonStackableCopies(slot.getItemId(), delta);
        }
    }

    @Override
    public void onSetTotalAmount(LoadoutSlot slot, int targetTotal)
    {
        if (slot.getItemId() <= 0 || targetTotal <= 0) return;

        if (slot.isEquipment())
        {
            slot.setQuantityInternal(targetTotal);
            return;
        }

        if (slot.isStackable())
        {
            slot.setQuantityInternal(targetTotal);
        }
        else
        {
            setNonStackableTotal(slot.getItemId(), targetTotal);
        }
    }

    /* ================= NON-STACKABLE HELPERS ================= */

    private void addNonStackableCopies(int itemId, int countToAdd)
    {
        for (int i = 0; i < inventorySlots.length && countToAdd > 0; i++)
        {
            LoadoutSlot s = inventorySlots[i];
            if (s.getItemId() <= 0)
            {
                s.setItem(itemId, 1);
                countToAdd--;
            }
        }
    }

    private void setNonStackableTotal(int itemId, int targetTotal)
    {
        List<LoadoutSlot> existing = new ArrayList<>();
        List<LoadoutSlot> empty = new ArrayList<>();

        for (LoadoutSlot s : inventorySlots)
        {
            if (s.getItemId() == itemId) existing.add(s);
            else if (s.getItemId() <= 0) empty.add(s);
        }

        int current = existing.size();
        if (current == targetTotal) return;

        if (current < targetTotal)
        {
            int need = targetTotal - current;
            for (LoadoutSlot s : empty)
            {
                if (need <= 0) break;
                s.setItem(itemId, 1);
                need--;
            }
        }
        else
        {
            int remove = current - targetTotal;
            for (int i = targetTotal; i < existing.size() && remove > 0; i++)
            {
                existing.get(i).clear();
                remove--;
            }
        }
    }

    public void removeAllOccurrences(int itemId)
    {
        for (LoadoutSlot s : inventorySlots)
        {
            if (s.getItemId() == itemId)
                s.clear();
        }
    }

    void performSlotDrop(LoadoutSlot source, LoadoutSlot target)
    {
        if (source == null || target == null || source == target) return;
        if (source.getItemId() <= 0) return;
        if (source.isEquipment() || target.isEquipment())
        {
            return;
        }

        if (source.isStackable() && target.isStackable()
                && source.getItemId() == target.getItemId())
        {
            int newQty = source.getQuantity() + target.getQuantity();
            target.setQuantityInternal(newQty);
            source.clear();
            return;
        }

        int srcId = source.getItemId();
        int srcQty = source.getQuantity();
        int tgtId = target.getItemId();
        int tgtQty = target.getQuantity();

        source.setItem(tgtId, tgtQty == 0 ? 1 : tgtQty);
        target.setItem(srcId, srcQty == 0 ? 1 : srcQty);
    }

    /* ================= STATIC HELPERS ================= */

    public static LoadoutBuilderPanel findPanel(Component c)
    {
        while (c != null && !(c instanceof LoadoutBuilderPanel))
            c = c.getParent();
        return (LoadoutBuilderPanel) c;
    }

    public LoadoutSlot[] getInventorySlots()
    {
        return inventorySlots;
    }
}
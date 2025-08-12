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
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/**
 * Loadout builder – dynamic width, compact spacing.
 * This revision:
 *  - Removed help button.
 *  - All UI text, tooltips, dialogs translated to English.
 *  - Short button labels (Repcal, KittyKeys, Import, Export).
 *  - Slot labels font one step smaller than previous (reduced from 15f -> 14f).
 */
public class LoadoutBuilderPanel extends PluginPanel implements LoadoutSlot.SlotActionHandler
{
    private static final int EQUIPMENT_GRID_HGAP = 0;
    private static final int EQUIPMENT_GRID_VGAP = 0;
    private static final int INVENTORY_GRID_HGAP = 0;
    private static final int INVENTORY_GRID_VGAP = 0;

    private static final int EQUIPMENT_SECTION_MAX_H = 300;
    private static final int INVENTORY_SECTION_MAX_H = 500;
    private static final int LOADOUT_SECTION_MAX_H   = 300;

    // Font configuration
    private static final float TITLE_FONT_SIZE      = 16f;
    private static final float SLOT_LABEL_FONT_SIZE = 14f;   // one notch smaller now
    private static final float BUTTON_FONT_SIZE     = 15.5f;
    private static final float TEXTAREA_FONT_SIZE   = 14f;
    private static final int   BUTTON_HEIGHT        = 28;

    private Font runescape;
    private Font runescapeBold;
    private Font fallback;

    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final Client client;

    private final Map<EquipmentInventorySlot, LoadoutSlot> equipmentSlots = new EnumMap<>(EquipmentInventorySlot.class);
    private final JPanel equipmentPanel = new JPanel();
    private final LoadoutSlot[] inventorySlots = new LoadoutSlot[28];
    private final JPanel inventoryPanel = new JPanel();

    private JButton copyButton;
    private JButton resetButton;

    private JTextArea repcalArea;
    private JButton repcalButton;
    private JButton kittyKeysButton;
    private JButton importButton;
    private JButton exportButton;

    private final Gson gson = new Gson();
    private JPanel contentPanel;

    private static final EquipmentInventorySlot[] EQ_INDEX_MAP = new EquipmentInventorySlot[]{
            EquipmentInventorySlot.HEAD,
            EquipmentInventorySlot.CAPE,
            EquipmentInventorySlot.AMULET,
            EquipmentInventorySlot.WEAPON,
            EquipmentInventorySlot.BODY,
            EquipmentInventorySlot.SHIELD,
            null,
            EquipmentInventorySlot.LEGS,
            null,
            EquipmentInventorySlot.GLOVES,
            EquipmentInventorySlot.BOOTS,
            null,
            EquipmentInventorySlot.RING,
            EquipmentInventorySlot.AMMO
    };

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread, Client client)
    {
        super(false);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.client = client;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        initFonts();
        buildUI();
    }

    private void initFonts()
    {
        try
        {
            runescape = FontManager.getRunescapeFont();
            runescapeBold = FontManager.getRunescapeBoldFont();
        }
        catch (Throwable t)
        {
            runescape = new Font("Dialog", Font.PLAIN, 14);
            runescapeBold = runescape.deriveFont(Font.BOLD);
        }
        fallback = new Font("Dialog", Font.PLAIN, 14);
    }

    /* ================= UI ================= */

    private void buildUI()
    {
        setLayout(new BorderLayout());

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);

        buildEquipmentSection();
        contentPanel.add(Box.createVerticalStrut(2));
        buildInventorySection();
        contentPanel.add(Box.createVerticalStrut(2));
        buildLoadoutSection();
        contentPanel.add(Box.createVerticalStrut(2));

        JScrollPane scroll = new JScrollPane(contentPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(scroll, BorderLayout.CENTER);
    }

    private TitledBorder titled(String title)
    {
        Font f = (runescapeBold != null ? runescapeBold : fallback).deriveFont(Font.BOLD, TITLE_FONT_SIZE);
        TitledBorder tb = new TitledBorder(title);
        tb.setTitleFont(f);
        return tb;
    }

    private JPanel sectionWrapper(String title, int maxHeight)
    {
        JPanel p = new JPanel(new BorderLayout(0,0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                titled(title),
                new EmptyBorder(2,2,2,2)
        ));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JButton makeButton(String text)
    {
        Font base = (runescape != null ? runescape : fallback).deriveFont(Font.PLAIN, BUTTON_FONT_SIZE);
        JButton b = new JButton(text);
        b.setFont(base);
        b.setMargin(new Insets(2,10,2,10));
        b.setFocusPainted(false);
        // Keep native RL look (no custom background)
        b.setPreferredSize(new Dimension(10, BUTTON_HEIGHT));
        b.setMinimumSize(new Dimension(10, BUTTON_HEIGHT));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_HEIGHT));
        return b;
    }

    private JLabel makeSlotLabel(String text)
    {
        Font f = (runescapeBold != null ? runescapeBold : fallback).deriveFont(Font.BOLD, SLOT_LABEL_FONT_SIZE);
        JLabel lbl = new JLabel(text, SwingConstants.CENTER);
        lbl.setFont(f);
        lbl.setForeground(new java.awt.Color(230,230,230));
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbl.setBorder(new EmptyBorder(1,0,0,0));
        return lbl;
    }

    private void buildEquipmentSection()
    {
        JPanel wrapper = sectionWrapper("Equipment", EQUIPMENT_SECTION_MAX_H);

        equipmentPanel.setLayout(new GridLayout(5, 3, EQUIPMENT_GRID_HGAP, EQUIPMENT_GRID_VGAP));
        equipmentPanel.setOpaque(false);
        equipmentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

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

        JPanel buttonRow = new JPanel(new GridLayout(1,2,2,0));
        buttonRow.setOpaque(false);
        buttonRow.setBorder(new EmptyBorder(2,0,0,0));
        copyButton = makeButton("Copy equipped");
        resetButton = makeButton("Clear all");
        copyButton.setToolTipText("Copy current equipment + inventory from the live client into the builder.");
        resetButton.setToolTipText("Clear all equipment slots, inventory slots and the text area.");
        copyButton.addActionListener(e -> copyLoadout());
        resetButton.addActionListener(e -> resetAll());
        buttonRow.add(copyButton);
        buttonRow.add(resetButton);
        wrapper.add(buttonRow, BorderLayout.SOUTH);

        contentPanel.add(wrapper);
    }

    private void addEquipCell(EquipmentInventorySlot slot, String labelText)
    {
        JPanel cell = new JPanel();
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setOpaque(false);
        cell.setAlignmentX(Component.CENTER_ALIGNMENT);

        if (slot == null)
        {
            cell.add(Box.createVerticalStrut(46));
            equipmentPanel.add(cell);
            return;
        }

        LoadoutSlot ls = equipmentSlots.get(slot);
        ls.setAlignmentX(Component.CENTER_ALIGNMENT);
        cell.add(ls);
        cell.add(makeSlotLabel(labelText));
        equipmentPanel.add(cell);
    }

    private void buildInventorySection()
    {
        JPanel wrapper = sectionWrapper("Inventory", INVENTORY_SECTION_MAX_H);

        inventoryPanel.setLayout(new GridLayout(7, 4, INVENTORY_GRID_HGAP, INVENTORY_GRID_VGAP));
        inventoryPanel.setOpaque(false);
        inventoryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (int i = 0; i < 28; i++)
        {
            inventorySlots[i] = new LoadoutSlot(itemManager, this, i, false);
            JPanel slotHolder = new JPanel();
            slotHolder.setOpaque(false);
            slotHolder.setLayout(new BoxLayout(slotHolder, BoxLayout.Y_AXIS));
            slotHolder.add(inventorySlots[i]);
            inventoryPanel.add(slotHolder);
        }

        wrapper.add(inventoryPanel, BorderLayout.CENTER);
        contentPanel.add(wrapper);
    }

    private void buildLoadoutSection()
    {
        JPanel wrapper = sectionWrapper("Loadout", LOADOUT_SECTION_MAX_H);
        wrapper.setLayout(new BorderLayout(0,2));

        repcalArea = new JTextArea();
        repcalArea.setEditable(true);
        repcalArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int)TEXTAREA_FONT_SIZE));
        repcalArea.setLineWrap(true);
        repcalArea.setWrapStyleWord(false);
        repcalArea.setRows(10);
        repcalArea.setTabSize(4);

        JScrollPane inner = new JScrollPane(repcalArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inner.setBorder(BorderFactory.createLineBorder(new java.awt.Color(70,70,70)));

        wrapper.add(inner, BorderLayout.CENTER);

        JPanel bottomRow = new JPanel(new BorderLayout(4,0));
        bottomRow.setOpaque(false);

        JPanel buttonGrid = new JPanel(new GridLayout(2,2,2,2));
        buttonGrid.setOpaque(false);

        repcalButton    = makeButton("Repcal");
        kittyKeysButton = makeButton("KittyKeys");
        importButton    = makeButton("Import");
        exportButton    = makeButton("Export");

        repcalButton.setToolTipText("Generate Repcal formatted lines from the current loadout.");
        kittyKeysButton.setToolTipText("Generate WITHDRAW + BANK_WIELD script lines for KittyKeys.");
        importButton.setToolTipText("Import Repcal lines or JSON from the text area.");
        exportButton.setToolTipText("Copy the current text area contents to clipboard.");

        repcalButton.addActionListener(e -> generateRepcalString());
        kittyKeysButton.addActionListener(e -> generateKittyKeysScript());
        importButton.addActionListener(e -> importRepcalCodes());
        exportButton.addActionListener(e -> exportToClipboard());

        buttonGrid.add(repcalButton);
        buttonGrid.add(kittyKeysButton);
        buttonGrid.add(importButton);
        buttonGrid.add(exportButton);

        bottomRow.add(buttonGrid, BorderLayout.CENTER);

        wrapper.add(bottomRow, BorderLayout.SOUTH);
        contentPanel.add(wrapper);
    }

    /* ================= BASIC ACTIONS ================= */

    private void resetAll()
    {
        for (LoadoutSlot s : equipmentSlots.values()) s.clear();
        for (LoadoutSlot s : inventorySlots) s.clear();
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
            JOptionPane.showMessageDialog(this, "No text to copy.", "Export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        copyToClipboard(text);
        JOptionPane.showMessageDialog(this, "Copied to clipboard.", "Export", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copyToClipboard(String s)
    {
        try
        {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog(this, "Clipboard error: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
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

            LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                String name = resolveName(ls);
                int add = ls.isStackable() ? Math.max(1, ls.getQuantity()) : 1;
                invCounts.merge(name, add, Integer::sum);
            }
            for (Map.Entry<String,Integer> e : invCounts.entrySet())
                sb.append("I:").append(e.getKey()).append(":").append(e.getValue()).append("\n");

            String out = sb.toString().trim();
            SwingUtilities.invokeLater(() -> {
                repcalArea.setText(out);
                repcalArea.setCaretPosition(0);
            });
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

            LinkedHashMap<EquipmentInventorySlot,String> names = new LinkedHashMap<>();
            for (EquipmentInventorySlot slot : order)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null && ls.getItemId() > 0)
                {
                    String raw = resolveName(ls);
                    String sanitized = kittyKeysItemName(raw);
                    String qty = isWeaponOrAmmoWildcard(slot, ls, raw)
                            ? "*"
                            : Integer.toString(Math.max(1, ls.getQuantity()));
                    sb.append("WITHDRAW ").append(sanitized).append(" ").append(qty).append("\n");
                    names.put(slot, sanitized);
                }
            }
            if (!names.isEmpty()) sb.append("\n");
            for (String n : names.values())
                sb.append("BANK_WIELD ").append(n).append("\n");
            if (!names.isEmpty()) sb.append("\n");

            LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                String raw = resolveName(ls);
                int add = ls.isStackable() ? Math.max(1, ls.getQuantity()) : 1;
                invCounts.merge(raw, add, Integer::sum);
            }
            for (Map.Entry<String,Integer> e : invCounts.entrySet())
                sb.append("WITHDRAW ").append(kittyKeysItemName(e.getKey())).append(" ").append(e.getValue()).append("\n");

            String out = sb.toString().trim();
            SwingUtilities.invokeLater(() -> {
                repcalArea.setText(out);
                repcalArea.setCaretPosition(0);
            });
        });
    }

    private String kittyKeysItemName(String name)
    {
        if (name == null) return "";
        return name.trim()
                .replace("’", "'")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    /* ================= IMPORT (Repcal + JSON) ================= */

    private void importRepcalCodes()
    {
        String text = repcalArea.getText();
        if (text == null || text.trim().isEmpty())
        {
            JOptionPane.showMessageDialog(this, "No code to import.", "Import", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String trimmed = text.trim();
        if (looksLikeJsonLoadout(trimmed))
        {
            if (importJsonLoadout(trimmed)) return;
        }

        List<RepcalLine> lines = parseRepcalLines(text);
        if (lines.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "No valid lines found.", "Import", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<String> eqCodes = new HashSet<>();
        boolean hasInv = false;
        for (RepcalLine l : lines)
        {
            if (l.code.equalsIgnoreCase("I")) hasInv = true;
            else eqCodes.add(l.code.toUpperCase(Locale.ROOT));
        }

        Map<String, EquipmentInventorySlot> codeMap = codeToSlotMap();
        for (String c : eqCodes)
        {
            EquipmentInventorySlot slot = codeMap.get(c);
            if (slot != null)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null) ls.clear();
            }
        }
        if (hasInv)
            for (LoadoutSlot s : inventorySlots) s.clear();

        clientThread.invoke(() -> {
            List<String> errors = new ArrayList<>();
            int invPtr = 0;

            for (RepcalLine l : lines)
            {
                if (l.code.equalsIgnoreCase("I"))
                {
                    int itemId = resolveItemId(l.name, errors);
                    if (itemId <= 0) continue;

                    boolean stackable;
                    try
                    {
                        ItemComposition c = itemManager.getItemComposition(itemId);
                        stackable = c.isStackable() || c.getNote() != -1;
                    }
                    catch (Exception ex)
                    {
                        errors.add("Failed item lookup: " + l.name);
                        continue;
                    }

                    int qty = l.quantity <= 0 ? 1 : l.quantity;
                    if (stackable)
                    {
                        while (invPtr < inventorySlots.length && inventorySlots[invPtr].getItemId() > 0)
                            invPtr++;
                        if (invPtr >= inventorySlots.length)
                        {
                            errors.add("Inventory full (stackable " + l.name + ")");
                            continue;
                        }
                        final int slotIndex = invPtr++;
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
                                errors.add("Inventory full (" + l.name + ")");
                                break;
                            }
                            final int slotIndex = invPtr++;
                            SwingUtilities.invokeLater(() -> inventorySlots[slotIndex].setItem(itemId, 1));
                        }
                    }
                }
                else
                {
                    EquipmentInventorySlot slot = codeMap.get(l.code.toUpperCase(Locale.ROOT));
                    if (slot == null)
                    {
                        errors.add("Unknown code: " + l.code);
                        continue;
                    }
                    int itemId = resolveItemId(l.name, errors);
                    if (itemId <= 0) continue;
                    final int qty = l.quantity <= 0 ? 1 : l.quantity;
                    SwingUtilities.invokeLater(() -> {
                        LoadoutSlot ls = equipmentSlots.get(slot);
                        if (ls != null) ls.setItem(itemId, qty);
                    });
                }
            }

            if (!errors.isEmpty())
            {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this, String.join("\n", errors),
                        "Import – issues", JOptionPane.WARNING_MESSAGE
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
            JOptionPane.showMessageDialog(this, "Invalid JSON: " + ex.getMessage(),
                    "JSON Import", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (root == null || root.setup == null)
        {
            JOptionPane.showMessageDialog(this, "JSON missing 'setup' object.",
                    "JSON Import", JOptionPane.ERROR_MESSAGE);
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

        JOptionPane.showMessageDialog(this, "JSON loadout imported.",
                "JSON Import", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /* ===== JSON data classes ===== */
    private static class JsonRoot { JsonSetup setup; List<Integer> layout; }
    private static class JsonSetup { List<JsonItem> inv; List<JsonItem> eq; String name; String hc; }
    private static class JsonItem { int id; @SerializedName("q") Integer q; }

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
        if (!ls.isStackable() && ls.getQuantity() <= 1) return false;
        String lower = name.toLowerCase();
        return lower.contains("dart") || lower.contains("knife") || lower.contains("javelin")
                || lower.contains("throwing axe") || lower.contains("thrownaxe") || lower.contains("chinchompa")
                || lower.contains("arrow") || lower.contains("bolt") || lower.contains("gem bolt")
                || lower.contains("bolt rack") || lower.contains("brutal") || lower.contains("cannonball");
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
            this.code = code; this.name = name; this.quantity = quantity;
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
                name = parts[1].trim();
            else
            {
                name = parts[1].trim();
                String qRaw = parts[2].trim();
                if (!qRaw.equals("*"))
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
        List<Integer> tmp = new ArrayList<>(9000);
        for (int id = 1; id < 40_000; id++)
        {
            try
            {
                String nm = itemManager.getItemComposition(id).getName();
                if (nm != null && !"null".equalsIgnoreCase(nm))
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
                    if (nm != null && !"null".equalsIgnoreCase(nm))
                        m.putIfAbsent(nm.toLowerCase(), id);
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
            errors.add("Empty name");
            return -1;
        }

        String trimmed = nameInput.trim();
        String lower = trimmed.toLowerCase();
        boolean potionHeuristic = false;

        if (trimmed.endsWith("("))
        {
            potionHeuristic = true;
            trimmed = trimmed.substring(0, trimmed.length()-1);
            lower = trimmed.toLowerCase();
        }

        ensureExactNameMap();

        Integer exact = exactNameMap.get(lower);
        if (exact != null)
            return exact;

        if (potionHeuristic)
        {
            for (int dose = 4; dose >= 1; dose--)
            {
                Integer eq = exactNameMap.get((trimmed + "(" + dose + ")").toLowerCase());
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
                        if (candidates.size() >= 150) break;
                    }
                    catch (Exception ignored){}
                }
            }
        }

        if (candidates.isEmpty())
        {
            errors.add("Item not found: " + nameInput);
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
            if (nmLower.startsWith(lower)) score += 40;
            score -= Math.abs(nm.length() - nameInput.length()) * 1.2;

            boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
            boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
            if (noted) score -= 20;
            if (placeholder) score -= 20;

            if (score > bestScore)
            {
                bestScore = score;
                bestId = id;
            }
        }

        if (bestId <= 0)
            errors.add("No suitable item match: " + nameInput);

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

    /* ================= SlotActionHandler ================= */

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
        if (source.isEquipment() || target.isEquipment()) return;

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

        source.setItem(tgtId, tgtId > 0 ? tgtQty : 0);
        target.setItem(srcId, srcId > 0 ? srcQty : 0);
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
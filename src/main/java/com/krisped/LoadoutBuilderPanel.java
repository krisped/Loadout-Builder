package com.krisped;

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
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/**
 * Panel for building a loadout: equipment + inventory.
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

    // Loadout output section
    private JTextArea repcalArea;
    private JButton repcalButton;
    private JButton importButton;

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread, Client client)
    {
        super(false);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.client = client;
        buildUI();
    }

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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.setOpaque(false);
        repcalButton = new JButton("Repcal");
        importButton = new JButton("Import");
        repcalButton.addActionListener(e -> generateRepcalString());
        importButton.addActionListener(e -> importRepcalCodes());
        buttons.add(repcalButton);
        buttons.add(importButton);

        wrapper.add(buttons, BorderLayout.SOUTH);

        add(wrapper);
    }

    /* ================= Actions ================= */

    private void resetAll()
    {
        for (LoadoutSlot slot : equipmentSlots.values())
            slot.clear();
        for (LoadoutSlot slot : inventorySlots)
            slot.clear();
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

    /* ================= Repcal Generation ================= */

    private void generateRepcalString()
    {
        clientThread.invoke(() -> {
            StringBuilder sb = new StringBuilder();

            // Equipment (alltid qty 1, eller * for visse våpen/ammo)
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

                String name = ls.getResolvedName();
                if (name == null)
                {
                    try { name = itemManager.getItemComposition(ls.getItemId()).getName(); }
                    catch (Exception ignored) { name = "Item " + ls.getItemId(); }
                }

                String code = equipmentCode(slot);
                String qtyString = isWeaponOrAmmoWildcard(slot, ls, name) ? "*" : "1";
                sb.append(code).append(":").append(name).append(":").append(qtyString).append("\n");
            }

            // Inventory grupperes
            LinkedHashMap<String, Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                String name = ls.getResolvedName();
                if (name == null)
                {
                    try { name = itemManager.getItemComposition(ls.getItemId()).getName(); }
                    catch (Exception ignored) { name = "Item " + ls.getItemId(); }
                }

                int add = 1;
                if (ls.isStackable())
                {
                    int q = ls.getQuantity();
                    add = (q <= 0 ? 1 : q);
                }
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

    private boolean isWeaponOrAmmoWildcard(EquipmentInventorySlot slot, LoadoutSlot ls, String name)
    {
        if (!ls.isStackable()) return false;
        if (slot == EquipmentInventorySlot.WEAPON || slot == EquipmentInventorySlot.AMMO)
        {
            String lower = name.toLowerCase();
            return lower.contains("dart")
                    || lower.contains("knife")
                    || lower.contains("javelin")
                    || lower.contains("throwing axe")
                    || lower.contains("thrownaxe")
                    || lower.contains("chinchompa")
                    || lower.contains("arrow")
                    || lower.contains("bolt")
                    || lower.contains("gem bolt")
                    || lower.contains("bolt rack")
                    || lower.contains("brutal")
                    || lower.contains("cannonball");
        }
        return false;
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

    /* ================= Repcal Import ================= */

    private void importRepcalCodes()
    {
        String text = repcalArea.getText();
        if (text == null || text.trim().isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Ingen kode å importere.", "Import", JOptionPane.INFORMATION_MESSAGE);
            return;
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
                        // Ett slot med samlet qty
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
                        // Flere slots, en per stk
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
                    // Mengde i equipment er uansett 1 (lagres bare som 1)
                    SwingUtilities.invokeLater(() -> ls.setItem(itemId, 1));
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

            name = normalizeNameInput(name);

            out.add(new RepcalLine(code, name, qty));
        }
        return out;
    }

    private String normalizeNameInput(String name)
    {
        if (name.endsWith("("))
        {
            return name;
        }
        return name;
    }

    /* ================== MATCHING / SCORING ================== */

    private int resolveItemId(String nameInput, List<String> errors)
    {
        String name = nameInput;
        boolean potionDoseHeuristic = false;

        if (name.endsWith("("))
        {
            potionDoseHeuristic = true;
            name = name.substring(0, name.length() - 1);
        }

        List<Integer> candidates = searchItems(name);
        if (candidates.isEmpty())
        {
            ensureIndex();
            if (allItemIdsCache != null)
            {
                String lower = name.toLowerCase();
                for (int id : allItemIdsCache)
                {
                    try
                    {
                        String nm = itemManager.getItemComposition(id).getName();
                        if (nm != null && nm.toLowerCase().startsWith(lower))
                        {
                            candidates.add(id);
                            if (candidates.size() >= 200) break;
                        }
                    }
                    catch (Exception ignored) {}
                }
            }
        }

        if (potionDoseHeuristic)
        {
            for (int dose = 4; dose >= 1; dose--)
            {
                String trial = name + "(" + dose + ")";
                int id = exactNameLookup(trial);
                if (id > 0) return id;
            }
        }

        if (candidates.isEmpty())
        {
            int ex = exactNameLookup(nameInput);
            if (ex > 0) return ex;
            errors.add("Fant ikke item: " + nameInput);
            return -1;
        }

        int bestId = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        String inputLower = nameInput.toLowerCase();
        boolean inputStartsWithRaw = inputLower.startsWith("raw ");

        for (int id : candidates)
        {
            double score = 0.0;
            String nm;
            ItemComposition comp;
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

            if (nmLower.startsWith(inputLower)) score += 10;
            if (nmLower.equals(inputLower)) score += 50;
            if (nm.equals(nameInput)) score += 5;

            if (!inputStartsWithRaw && nmLower.startsWith("raw "))
                score -= 8;

            if (nmLower.equals(inputLower)) score += 10;

            boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
            boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
            if (noted) score -= 6;
            if (placeholder) score -= 6;

            if (!comp.isMembers()) score += 1;

            score -= Math.abs(nm.length() - nameInput.length()) * 0.2;

            if (score > bestScore)
            {
                bestScore = score;
                bestId = id;
            }
        }

        if (bestId <= 0)
        {
            errors.add("Fant ikke passende item: " + nameInput);
        }
        return bestId;
    }

    /* ======= Item search helpers ======= */

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

    private int exactNameLookup(String exactName)
    {
        if (exactName == null || exactName.isEmpty()) return -1;
        ensureIndex();
        String target = exactName.toLowerCase();
        if (allItemIdsCache != null)
        {
            for (int id : allItemIdsCache)
            {
                try
                {
                    String nm = itemManager.getItemComposition(id).getName();
                    if (nm != null && nm.equalsIgnoreCase(target))
                        return id;
                }
                catch (Exception ignored){}
            }
        }
        return -1;
    }

    private volatile List<Integer> allItemIdsCache = null;
    private volatile boolean buildingIndex = false;

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
        if (slot.isStackable())
            slot.incrementQuantityInternal(delta);
        else
            addNonStackableCopies(slot.getItemId(), delta);
    }

    @Override
    public void onSetTotalAmount(LoadoutSlot slot, int targetTotal)
    {
        if (slot.getItemId() <= 0 || targetTotal <= 0) return;
        if (slot.isStackable())
            slot.setQuantityInternal(targetTotal);
        else
            setNonStackableTotal(slot.getItemId(), targetTotal);
    }

    /* ================= Non-stackable handling ================= */

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

    void performSlotDrop(LoadoutSlot source, LoadoutSlot target)
    {
        if (source == null || target == null || source == target) return;
        if (source.getItemId() <= 0) return;

        if (source.isEquipment() || target.isEquipment())
        {
            return; // Only inventory swapping for now
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
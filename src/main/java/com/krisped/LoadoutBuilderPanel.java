package com.krisped;

import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /* ================= Actions ================= */

    private void resetAll()
    {
        for (LoadoutSlot slot : equipmentSlots.values())
            slot.clear();
        for (LoadoutSlot slot : inventorySlots)
            slot.clear();
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
                final var comp = itemManager.getItemComposition(itemId);
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
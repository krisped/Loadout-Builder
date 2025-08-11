package com.krisped;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class LoadoutBuilderPanel extends PluginPanel
{
    public enum EquipmentSlot
    {
        HEAD,
        CAPE,
        NECK,
        AMMO,
        WEAPON,
        BODY,
        SHIELD,
        LEGS,
        HANDS,
        BOOTS,
        RING
    }

    private static final int INV_TILE_SIZE = 34;
    private static final int EQUIP_TILE_SIZE = 42;
    private static final int GAP = 8;
    private static final Color TILE_BG = new Color(53, 49, 44);
    private static final Color TILE_BORDER = new Color(88, 80, 72);
    private static final Color CAPTION_FG = new Color(180, 180, 180);
    private static final Font CAPTION_FONT = new Font("Dialog", Font.PLAIN, 10);
    private static final int MAX_ITEM_ID = 35000;

    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final Map<EquipmentSlot, JButton> equipmentButtons = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Integer> equipped = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, String> equipmentLastQuery = new EnumMap<>(EquipmentSlot.class);

    private final JButton[] inventoryButtons = new JButton[28];
    private final Integer[] inventoryIds = new Integer[28];
    private final String[] inventoryLastQuery = new String[28];

    // Drag state
    private int pressIndex = -1;
    private int hoverIndex = -1;

    // Loadout Text UI
    private final JTextArea loadoutArea = new JTextArea(10, 40);
    private final JButton repcalButton = new JButton("Repcal Loadout");
    private final JButton copyButton = new JButton("Copy");
    private final JButton importButton = new JButton("Import");

    // Navneoppslag-cache (bygges én gang på client thread)
    private final Map<String, Integer> nameToIdCache = new HashMap<>();
    private boolean nameIndexBuilt = false;

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread)
    {
        this.itemManager = itemManager;
        this.clientThread = clientThread;

        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(GAP, GAP, GAP, GAP));

        JPanel equipmentPanel = buildEquipmentGrid();
        equipmentPanel.setBorder(BorderFactory.createTitledBorder("Equipment"));
        equipmentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(equipmentPanel);
        content.add(Box.createVerticalStrut(GAP));

        JPanel inventoryPanel = buildInventoryGrid();
        inventoryPanel.setBorder(BorderFactory.createTitledBorder("Inventory"));
        inventoryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(inventoryPanel);
        content.add(Box.createVerticalStrut(GAP));

        JPanel loadoutTextPanel = buildLoadoutTextPanel();
        loadoutTextPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(loadoutTextPanel);

        add(content, BorderLayout.CENTER);
    }

    private JPanel buildEquipmentGrid()
    {
        JPanel grid = new JPanel(new GridLayout(5, 3, GAP, GAP));
        grid.setOpaque(false);

        grid.add(emptyCell());
        grid.add(createSlotCell(EquipmentSlot.HEAD));
        grid.add(emptyCell());

        grid.add(createSlotCell(EquipmentSlot.CAPE));
        grid.add(createSlotCell(EquipmentSlot.NECK));
        grid.add(createSlotCell(EquipmentSlot.AMMO));

        grid.add(createSlotCell(EquipmentSlot.WEAPON));
        grid.add(createSlotCell(EquipmentSlot.BODY));
        grid.add(createSlotCell(EquipmentSlot.SHIELD));

        grid.add(emptyCell());
        grid.add(createSlotCell(EquipmentSlot.LEGS));
        grid.add(emptyCell());

        grid.add(createSlotCell(EquipmentSlot.HANDS));
        grid.add(createSlotCell(EquipmentSlot.BOOTS));
        grid.add(createSlotCell(EquipmentSlot.RING));

        return grid;
    }

    private JComponent createSlotCell(EquipmentSlot slot)
    {
        JButton tile = createTileButton(true);
        tile.setToolTipText(slotTooltip(slot));
        tile.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (SwingUtilities.isRightMouseButton(e))
                {
                    showEquipmentPopup(tile, slot, e.getX(), e.getY());
                }
            }
            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (SwingUtilities.isLeftMouseButton(e))
                {
                    onPickItemForSlot(slot, tile, equipmentLastQuery.get(slot));
                }
            }
        });
        equipmentButtons.put(slot, tile);

        JLabel caption = new JLabel(slotCaption(slot), SwingConstants.CENTER);
        caption.setFont(CAPTION_FONT);
        caption.setForeground(CAPTION_FG);
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);
        caption.setBorder(new EmptyBorder(2, 0, 0, 0));

        JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        tile.setAlignmentX(Component.CENTER_ALIGNMENT);
        cell.add(tile);
        cell.add(caption);
        return cell;
    }

    private JPanel buildInventoryGrid()
    {
        JPanel grid = new JPanel(new GridLayout(7, 4, GAP, GAP));
        grid.setOpaque(false);

        for (int i = 0; i < 28; i++)
        {
            final int index = i;
            JButton btn = createTileButton(false);
            btn.setToolTipText("Inventory slot " + (i + 1));

            MouseAdapter adapter = new MouseAdapter()
            {
                @Override
                public void mousePressed(MouseEvent e)
                {
                    if (SwingUtilities.isRightMouseButton(e))
                    {
                        showInventoryPopup(btn, index, e.getX(), e.getY());
                        return;
                    }
                    pressIndex = index;
                }

                @Override
                public void mouseReleased(MouseEvent e)
                {
                    if (!SwingUtilities.isLeftMouseButton(e))
                    {
                        pressIndex = -1;
                        return;
                    }

                    int releaseTarget = (hoverIndex != -1) ? hoverIndex : index;

                    if (pressIndex == -1)
                    {
                        // no-op
                    }
                    else if (pressIndex == releaseTarget)
                    {
                        onPickInventoryItem(releaseTarget, btn, inventoryLastQuery[releaseTarget]);
                    }
                    else
                    {
                        swapInventorySlots(pressIndex, releaseTarget);
                    }
                    pressIndex = -1;
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    hoverIndex = index;
                    btn.setBorder(new LineBorder(TILE_BORDER.brighter(), 2));
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    if (hoverIndex == index) hoverIndex = -1;
                    btn.setBorder(new LineBorder(TILE_BORDER));
                }
            };

            btn.addMouseListener(adapter);
            inventoryButtons[i] = btn;
            grid.add(btn);
        }

        return grid;
    }

    private JPanel buildLoadoutTextPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createTitledBorder("Loadout Text"));

        loadoutArea.setEditable(true);
        loadoutArea.setLineWrap(true);
        loadoutArea.setWrapStyleWord(true);

        // Top: to rader (Repcal) + (Copy/Import)
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row1.setOpaque(false);
        repcalButton.setToolTipText("Generer tekst fra nåværende utstyr og inventory");
        repcalButton.addActionListener(e -> generateLoadoutText());
        row1.add(repcalButton);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row2.setOpaque(false);
        copyButton.setToolTipText("Kopier teksten i feltet til utklippstavlen");
        copyButton.addActionListener(e -> copyLoadoutToClipboard());
        importButton.setToolTipText("Importer fra teksten i feltet til slots og inventory");
        importButton.addActionListener(e -> importFromText(loadoutArea.getText()));
        row2.add(copyButton);
        row2.add(importButton);

        header.add(row1);
        header.add(row2);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(loadoutArea), BorderLayout.CENTER);

        return panel;
    }

    private JButton createTileButton(boolean equipment)
    {
        JButton btn = new JButton();
        int size = equipment ? EQUIP_TILE_SIZE : INV_TILE_SIZE;
        Dimension d = new Dimension(size, size);
        btn.setPreferredSize(d);
        btn.setMinimumSize(d);
        btn.setMaximumSize(d);

        btn.setFocusPainted(false);
        btn.setContentAreaFilled(true);
        btn.setOpaque(true);
        btn.setBackground(TILE_BG);
        btn.setForeground(CAPTION_FG);
        btn.setBorder(new LineBorder(TILE_BORDER));
        btn.setHorizontalTextPosition(SwingConstants.CENTER);
        btn.setVerticalTextPosition(SwingConstants.CENTER);
        btn.setMargin(new Insets(0, 0, 0, 0));
        return btn;
    }

    private Component emptyCell()
    {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    // Equipment

    private void showEquipmentPopup(JButton btn, EquipmentSlot slot, int x, int y)
    {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem change = new JMenuItem("Change item...");
        change.addActionListener(a -> onPickItemForSlot(slot, btn, equipmentLastQuery.get(slot)));
        JMenuItem clear = new JMenuItem("Remove item");
        clear.addActionListener(a -> clearSlot(slot));
        menu.add(change);
        menu.add(clear);
        menu.show(btn, x, y);
    }

    private void onPickItemForSlot(EquipmentSlot slot, JButton btn, String initialQuery)
    {
        ItemSelectionDialog dialog = new ItemSelectionDialog(
                SwingUtilities.getWindowAncestor(this), itemManager, clientThread,
                "Velg item: " + slotTooltip(slot), initialQuery
        );
        Optional<ItemSelectionDialog.Result> res = dialog.showDialog();
        res.ifPresent(result ->
        {
            equipmentLastQuery.put(slot, result.getQuery());
            setEquipmentItem(slot, result.getItem().getId(), result.getItem().getName());
        });
    }

    private void clearSlot(EquipmentSlot slot)
    {
        equipped.remove(slot);
        JButton btn = equipmentButtons.get(slot);
        if (btn != null)
        {
            btn.setIcon(null);
            btn.setToolTipText(slotTooltip(slot));
            revalidate();
            repaint();
        }
    }

    private void clearAllEquipment()
    {
        for (EquipmentSlot s : EquipmentSlot.values())
        {
            clearSlot(s);
        }
    }

    private void setEquipmentItem(EquipmentSlot slot, int itemId, String itemName)
    {
        JButton btn = equipmentButtons.get(slot);
        if (btn != null)
        {
            // Sett ikon asynkront – unngå "hover før vises"
            setButtonIconAsync(btn, itemId);
            btn.setToolTipText(itemName + " (ID: " + itemId + ")");
            revalidate();
            repaint();
        }
        equipped.put(slot, itemId);
    }

    // Inventory

    private void showInventoryPopup(JButton btn, int index, int x, int y)
    {
        JPopupMenu menu = new JPopupMenu();
        Integer id = inventoryIds[index];

        if (id == null)
        {
            JMenuItem choose = new JMenuItem("Choose item...");
            choose.addActionListener(a -> onPickInventoryItem(index, btn, inventoryLastQuery[index]));
            menu.add(choose);
        }
        else
        {
            final int itemId = id;

            JMenuItem change = new JMenuItem("Change item...");
            change.addActionListener(a -> onPickInventoryItem(index, btn, inventoryLastQuery[index]));
            menu.add(change);

            JMenuItem plus1 = new JMenuItem("Add +1");
            JMenuItem plus5 = new JMenuItem("Add +5");
            JMenuItem plus10 = new JMenuItem("Add +10");
            plus1.addActionListener(a -> fillNextFreeSlots(itemId, 1, index + 1));
            plus5.addActionListener(a -> fillNextFreeSlots(itemId, 5, index + 1));
            plus10.addActionListener(a -> fillNextFreeSlots(itemId, 10, index + 1));
            menu.add(plus1);
            menu.add(plus5);
            menu.add(plus10);

            JMenuItem setAmount = new JMenuItem("Set amount...");
            setAmount.addActionListener(a ->
            {
                Integer amt = promptAmount("Total amount of this item in inventory:", countItemInInventory(itemId));
                if (amt != null)
                {
                    ensureItemCount(itemId, amt, index);
                }
            });
            menu.add(setAmount);

            menu.addSeparator();
            JMenuItem removeOne = new JMenuItem("Remove item");
            removeOne.addActionListener(a -> clearInventorySlot(index));
            menu.add(removeOne);

            JMenuItem removeAll = new JMenuItem("Remove all of this item");
            removeAll.addActionListener(a -> removeAllOfItem(itemId));
            menu.add(removeAll);
        }

        menu.show(btn, x, y);
    }

    private void onPickInventoryItem(int index, JButton btn, String initialQuery)
    {
        ItemSelectionDialog dialog = new ItemSelectionDialog(
                SwingUtilities.getWindowAncestor(this), itemManager, clientThread,
                "Velg item til inventory slot " + (index + 1), initialQuery
        );
        Optional<ItemSelectionDialog.Result> res = dialog.showDialog();
        res.ifPresent(result ->
        {
            inventoryLastQuery[index] = result.getQuery();
            int itemId = result.getItem().getId();

            Integer amt = promptAmount("How many to have in inventory (total)?", Math.max(1, countItemInInventory(itemId)));
            if (amt == null) return;

            setInventorySlot(index, itemId);
            ensureItemCount(itemId, amt, index);
        });
    }

    private void setInventorySlot(int index, int itemId)
    {
        inventoryIds[index] = itemId;
        updateInventoryButtonIcon(index);
    }

    private void clearInventorySlot(int index)
    {
        inventoryIds[index] = null;
        JButton btn = inventoryButtons[index];
        if (btn != null)
        {
            btn.setIcon(null);
            btn.setToolTipText("Inventory slot " + (index + 1));
            btn.setBorder(new LineBorder(TILE_BORDER));
            revalidate();
            repaint();
        }
    }

    private void clearInventoryAll()
    {
        for (int i = 0; i < 28; i++)
        {
            clearInventorySlot(i);
        }
    }

    private void swapInventorySlots(int a, int b)
    {
        Integer tmp = inventoryIds[a];
        inventoryIds[a] = inventoryIds[b];
        inventoryIds[b] = tmp;
        updateInventoryButtonIcon(a);
        updateInventoryButtonIcon(b);
    }

    private void fillNextFreeSlots(int itemId, int count, int startIndex)
    {
        int remaining = count;
        for (int i = startIndex; i < 28 && remaining > 0; i++)
        {
            if (inventoryIds[i] == null)
            {
                setInventorySlot(i, itemId);
                remaining--;
            }
        }
        for (int i = 0; i < startIndex && remaining > 0; i++)
        {
            if (inventoryIds[i] == null)
            {
                setInventorySlot(i, itemId);
                remaining--;
            }
        }
        if (remaining > 0)
        {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private int countItemInInventory(int itemId)
    {
        int c = 0;
        for (Integer id : inventoryIds)
        {
            if (id != null && id == itemId) c++;
        }
        return c;
    }

    private List<Integer> findItemIndices(int itemId)
    {
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < 28; i++)
        {
            if (inventoryIds[i] != null && inventoryIds[i] == itemId)
            {
                idxs.add(i);
            }
        }
        return idxs;
    }

    private void removeAllOfItem(int itemId)
    {
        boolean removedAny = false;
        for (int i = 0; i < 28; i++)
        {
            if (inventoryIds[i] != null && inventoryIds[i] == itemId)
            {
                clearInventorySlot(i);
                removedAny = true;
            }
        }
        if (!removedAny)
        {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void ensureItemCount(int itemId, int desired, int preferStartIndex)
    {
        if (desired < 0) desired = 0;

        List<Integer> indices = findItemIndices(itemId);
        int current = indices.size();

        if (desired > 0 && (preferStartIndex >= 0 && preferStartIndex < 28))
        {
            if (inventoryIds[preferStartIndex] == null || !inventoryIds[preferStartIndex].equals(itemId))
            {
                setInventorySlot(preferStartIndex, itemId);
                indices.add(preferStartIndex);
                current++;
            }
        }

        if (current > desired)
        {
            int toRemove = current - desired;

            for (int i = indices.size() - 1; i >= 0 && toRemove > 0; i--)
            {
                int idx = indices.get(i);
                if (idx == preferStartIndex) continue;
                if (inventoryIds[idx] != null && inventoryIds[idx] == itemId)
                {
                    clearInventorySlot(idx);
                    toRemove--;
                }
            }
            for (int i = indices.size() - 1; i >= 0 && toRemove > 0; i--)
            {
                int idx = indices.get(i);
                if (inventoryIds[idx] != null && inventoryIds[idx] == itemId)
                {
                    clearInventorySlot(idx);
                    toRemove--;
                }
            }
            return;
        }

        if (current < desired)
        {
            int shortage = desired - current;
            int start = (preferStartIndex >= 0 && preferStartIndex < 28) ? (preferStartIndex + 1) : 0;
            fillNextFreeSlots(itemId, shortage, start);
        }
    }

    private void updateInventoryButtonIcon(int index)
    {
        Integer id = inventoryIds[index];
        JButton btn = inventoryButtons[index];
        if (btn == null) return;

        if (id == null)
        {
            btn.setIcon(null);
            btn.setToolTipText("Inventory slot " + (index + 1));
            return;
        }

        // Sett ikon asynkront – unngå "hover før synlig"
        setButtonIconAsync(btn, id);
        btn.setToolTipText("Item ID: " + id);
        revalidate();
        repaint();
    }

    private void setButtonIconAsync(JButton btn, int itemId)
    {
        try
        {
            AsyncBufferedImage img = itemManager.getImage(itemId);
            if (img == null)
            {
                btn.setIcon(null);
                return;
            }

            // Sett ikon nå (kan være tom inntil lastet), og oppdater når ferdig
            btn.setIcon(new ImageIcon(img));
            img.onLoaded(() -> SwingUtilities.invokeLater(() ->
            {
                btn.setIcon(new ImageIcon(img));
                btn.revalidate();
                btn.repaint();
            }));
        }
        catch (Exception e)
        {
            btn.setIcon(null);
        }
    }

    private Image safeGetImage(int itemId)
    {
        try { return itemManager.getImage(itemId); }
        catch (Throwable t) { return null; }
    }

    private String slotTooltip(EquipmentSlot slot)
    {
        switch (slot)
        {
            case HEAD: return "Helmet";
            case CAPE: return "Cape";
            case NECK: return "Amulet";
            case AMMO: return "Ammo";
            case WEAPON: return "Weapon";
            case BODY: return "Body";
            case SHIELD: return "Shield";
            case LEGS: return "Legs";
            case HANDS: return "Gloves";
            case BOOTS: return "Boots";
            case RING: return "Ring";
            default: return slot.name();
        }
    }

    private String slotCaption(EquipmentSlot slot)
    {
        switch (slot)
        {
            case HEAD: return "Head";
            case CAPE: return "Cape";
            case NECK: return "Amulet";
            case AMMO: return "Ammo";
            case WEAPON: return "Weapon";
            case BODY: return "Body";
            case SHIELD: return "Shield";
            case LEGS: return "Legs";
            case HANDS: return "Gloves";
            case BOOTS: return "Boots";
            case RING: return "Ring";
            default: return slot.name();
        }
    }

    private Integer promptAmount(String message, int defaultVal)
    {
        String s = JOptionPane.showInputDialog(this, message, String.valueOf(Math.max(0, defaultVal)));
        if (s == null) return null;
        try
        {
            int v = Integer.parseInt(s.trim());
            return v >= 0 ? v : null;
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    // ---------- Loadout Text generering / copy / import ----------

    private void generateLoadoutText()
    {
        loadoutArea.setText("Generating...");
        clientThread.invoke(() ->
        {
            StringBuilder sb = new StringBuilder();

            EquipmentSlot[] order = new EquipmentSlot[]{
                    EquipmentSlot.BOOTS,   // B
                    EquipmentSlot.NECK,    // N
                    EquipmentSlot.SHIELD,  // S
                    EquipmentSlot.CAPE,    // Ca
                    EquipmentSlot.HANDS,   // G
                    EquipmentSlot.BODY,    // C
                    EquipmentSlot.HEAD,    // H
                    EquipmentSlot.RING,    // R
                    EquipmentSlot.LEGS,    // L
                    EquipmentSlot.WEAPON,  // W
                    EquipmentSlot.AMMO     // A
            };

            for (EquipmentSlot s : order)
            {
                Integer id = equipped.get(s);
                if (id == null) continue;
                String name = getNameOnClientThread(id);
                if (name == null || name.equalsIgnoreCase("null")) continue;
                String code = codeForSlot(s);
                sb.append(code).append(":").append(name).append(":1").append("\n");
            }

            LinkedHashMap<Integer, Integer> counts = new LinkedHashMap<>();
            for (Integer id : inventoryIds)
            {
                if (id == null) continue;
                counts.merge(id, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> e : counts.entrySet())
            {
                String name = getNameOnClientThread(e.getKey());
                if (name == null || name.equalsIgnoreCase("null")) continue;
                sb.append("I:").append(name).append(":").append(e.getValue()).append("\n");
            }

            String out = sb.toString().trim();
            SwingUtilities.invokeLater(() -> loadoutArea.setText(out));
        });
    }

    private void copyLoadoutToClipboard()
    {
        String text = loadoutArea.getText();
        if (text == null || text.trim().isEmpty())
        {
            generateLoadoutText();
            SwingUtilities.invokeLater(() -> copyToClipboard(loadoutArea.getText()));
        }
        else
        {
            copyToClipboard(text);
        }
    }

    private void copyToClipboard(String text)
    {
        try
        {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(text), null);
            JOptionPane.showMessageDialog(this, "Loadout copied to clipboard.", "Copied", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception ex)
        {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this, "Failed to copy to clipboard.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importFromText(String text)
    {
        if (text == null) return;
        final String original = text;

        repcalButton.setEnabled(false);
        copyButton.setEnabled(false);
        importButton.setEnabled(false);

        List<ImportEntry> entries = parseEntries(original);

        clientThread.invoke(() ->
        {
            Map<String, Integer> resolved = new LinkedHashMap<>();
            List<String> notFound = new ArrayList<>();

            for (ImportEntry entry : entries)
            {
                String name = entry.itemName;
                if (resolved.containsKey(name)) continue;
                Integer id = resolveItemIdByName(name);
                if (id == null)
                {
                    notFound.add(name);
                }
                resolved.put(name, id);
            }

            Map<EquipmentSlot, Integer> equipToSet = new EnumMap<>(EquipmentSlot.class);
            LinkedHashMap<Integer, Integer> invCounts = new LinkedHashMap<>();

            for (ImportEntry entry : entries)
            {
                Integer id = resolved.get(entry.itemName);
                if (id == null) continue;
                if (entry.isInventory)
                {
                    int count = entry.amount < 0 ? 28 : entry.amount;
                    invCounts.merge(id, count, Integer::sum);
                }
                else if (entry.slot != null)
                {
                    equipToSet.put(entry.slot, id);
                }
            }

            // For tooltip/tekst – hent navn for alle relevante id-er
            Set<Integer> allIds = new HashSet<>();
            allIds.addAll(equipToSet.values());
            allIds.addAll(invCounts.keySet());
            Map<Integer, String> idToName = new HashMap<>();
            for (Integer id : allIds)
            {
                if (id == null) continue;
                String nm = getNameOnClientThread(id);
                idToName.put(id, nm != null ? nm : ("ID " + id));
            }

            SwingUtilities.invokeLater(() ->
            {
                try
                {
                    clearAllEquipment();
                    clearInventoryAll();

                    for (Map.Entry<EquipmentSlot, Integer> e : equipToSet.entrySet())
                    {
                        Integer id = e.getValue();
                        String name = idToName.getOrDefault(id, "ID " + id);
                        setEquipmentItem(e.getKey(), id, name);
                    }

                    for (Map.Entry<Integer, Integer> e : invCounts.entrySet())
                    {
                        int id = e.getKey();
                        int count = Math.max(0, Math.min(28, e.getValue()));
                        fillNextFreeSlots(id, count, 0);
                    }

                    loadoutArea.setText(original);

                    if (!notFound.isEmpty())
                    {
                        Toolkit.getDefaultToolkit().beep();
                        JOptionPane.showMessageDialog(this,
                                "Kunne ikke finne disse item-navnene:\n- " + String.join("\n- ", notFound),
                                "Noen items ble ikke funnet",
                                JOptionPane.WARNING_MESSAGE);
                    }
                }
                finally
                {
                    repcalButton.setEnabled(true);
                    copyButton.setEnabled(true);
                    importButton.setEnabled(true);
                }
            });
        });
    }

    private static class ImportEntry
    {
        final boolean isInventory;
        final EquipmentSlot slot;
        final String itemName;
        final int amount; // -1 betyr '*'

        ImportEntry(boolean isInventory, EquipmentSlot slot, String itemName, int amount)
        {
            this.isInventory = isInventory;
            this.slot = slot;
            this.itemName = itemName;
            this.amount = amount;
        }
    }

    private List<ImportEntry> parseEntries(String text)
    {
        List<ImportEntry> list = new ArrayList<>();
        if (text == null) return list;
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines)
        {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(":", 3);
            if (parts.length < 2) continue;

            String codeRaw = parts[0].trim();
            String name = parts[1].trim();
            String amtStr = parts.length >= 3 ? parts[2].trim() : "1";

            int amount = 1;
            if (amtStr.equals("*")) amount = -1;
            else
            {
                try { amount = Integer.parseInt(amtStr); }
                catch (NumberFormatException ignored) { amount = 1; }
            }

            EquipmentSlot slot = codeToSlot(codeRaw);
            if (slot == null)
            {
                if (codeRaw.equalsIgnoreCase("I"))
                {
                    list.add(new ImportEntry(true, null, name, amount));
                }
            }
            else
            {
                list.add(new ImportEntry(false, slot, name, amount));
            }
        }
        return list;
    }

    private EquipmentSlot codeToSlot(String codeRaw)
    {
        if (codeRaw == null) return null;
        String code = codeRaw.trim();
        if (code.equalsIgnoreCase("Ca")) return EquipmentSlot.CAPE;

        switch (code.toUpperCase(Locale.ROOT))
        {
            case "H": return EquipmentSlot.HEAD;
            case "N": return EquipmentSlot.NECK;
            case "S": return EquipmentSlot.SHIELD;
            case "G": return EquipmentSlot.HANDS;
            case "C": return EquipmentSlot.BODY;
            case "B": return EquipmentSlot.BOOTS;
            case "R": return EquipmentSlot.RING;
            case "L": return EquipmentSlot.LEGS;
            case "W": return EquipmentSlot.WEAPON;
            case "A": return EquipmentSlot.AMMO;
            default: return null;
        }
    }

    // Må kalles på ClientThread
    private String getNameOnClientThread(int itemId)
    {
        try
        {
            return itemManager.getItemComposition(itemId).getName();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // Bygg navneindeks én gang (ekskluder noted/placeholder via canonicalize)
    private void buildNameIndexIfNeeded()
    {
        if (nameIndexBuilt) return;

        for (int id = 0; id <= MAX_ITEM_ID; id++)
        {
            try
            {
                String n = itemManager.getItemComposition(id).getName();
                if (n == null || n.equalsIgnoreCase("null")) continue;

                int canon = itemManager.canonicalize(id);
                String key = n.toLowerCase(Locale.ROOT);

                // lagre base-varianten
                nameToIdCache.putIfAbsent(key, canon);
            }
            catch (Exception ignored) {}
        }
        nameIndexBuilt = true;
    }

    // Søk uten ItemManager.Item-typen (refleksjon på search-resultater) med presisjon først
    private Integer resolveItemIdByName(String name)
    {
        if (name == null || name.isEmpty()) return null;
        final String qLower = name.toLowerCase(Locale.ROOT);

        // 1) Raskt søk (tradeables): eksakt match først
        try
        {
            List<?> results = itemManager.search(name);
            Integer exact = null;
            List<Integer> contains = new ArrayList<>();

            for (Object itObj : results)
            {
                String itName = getNameViaReflection(itObj);
                Integer itId = getIdViaReflection(itObj);
                if (itName == null || itId == null) continue;

                if (itName.equalsIgnoreCase(name))
                {
                    exact = itemManager.canonicalize(itId);
                    break;
                }
                if (itName.toLowerCase(Locale.ROOT).contains(qLower))
                {
                    contains.add(itemManager.canonicalize(itId));
                }
            }
            if (exact != null) return exact;

            // 2) Full indeks – eksakt navn på tvers av alle items
            buildNameIndexIfNeeded();
            Integer idxId = nameToIdCache.get(qLower);
            if (idxId != null) return idxId;

            // 3) Heuristisk contains fra søk – unngå "page set", " set"
            Integer best = pickBestContains(results, name);
            if (best != null) return itemManager.canonicalize(best);
        }
        catch (Exception ignored)
        {
            // fallbacks under
        }

        // 4) Fallback: lineær contains i indeks (billig etter cache)
        int bestScore = Integer.MIN_VALUE;
        Integer bestId = null;
        for (Map.Entry<String, Integer> e : nameToIdCache.entrySet())
        {
            String nm = e.getKey();
            if (!nm.contains(qLower)) continue;
            int score = scoreNameContains(nm, qLower);
            if (score > bestScore)
            {
                bestScore = score;
                bestId = e.getValue();
            }
        }

        return bestId;
    }

    private Integer pickBestContains(List<?> results, String query)
    {
        String q = query.toLowerCase(Locale.ROOT);
        int bestScore = Integer.MIN_VALUE;
        Integer bestId = null;

        for (Object itObj : results)
        {
            String itName = getNameViaReflection(itObj);
            Integer itId = getIdViaReflection(itObj);
            if (itName == null || itId == null) continue;

            String nm = itName.toLowerCase(Locale.ROOT);
            if (!nm.contains(q)) continue;

            int score = scoreNameContains(nm, q);
            if (score > bestScore)
            {
                bestScore = score;
                bestId = itId;
            }
        }
        return bestId;
    }

    // Høyere score = bedre. Straff " page set"/" set", belønn prefix og kort lengdeforskjell.
    private int scoreNameContains(String candidateLower, String queryLower)
    {
        int score = 0;
        if (candidateLower.startsWith(queryLower)) score += 5;
        score -= Math.abs(candidateLower.length() - queryLower.length()); // kortere diff er bedre
        if (candidateLower.contains(" page set")) score -= 8;
        if (candidateLower.endsWith(" set")) score -= 6;
        if (candidateLower.contains(" placeholder")) score -= 4;
        if (candidateLower.contains(" noted")) score -= 4;
        return score;
    }

    private String getNameViaReflection(Object itObj)
    {
        try
        {
            Method m = itObj.getClass().getMethod("getName");
            Object v = m.invoke(itObj);
            return (v instanceof String) ? (String) v : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private Integer getIdViaReflection(Object itObj)
    {
        try
        {
            Method m = itObj.getClass().getMethod("getId");
            Object v = m.invoke(itObj);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Number) return ((Number) v).intValue();
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String codeForSlot(EquipmentSlot slot)
    {
        switch (slot)
        {
            case HEAD: return "H";
            case CAPE: return "Ca";
            case NECK: return "N";
            case AMMO: return "A";
            case WEAPON: return "W";
            case BODY: return "C";
            case SHIELD: return "S";
            case LEGS: return "L";
            case HANDS: return "G";
            case BOOTS: return "B";
            case RING: return "R";
            default: return slot.name();
        }
    }
}
package com.krisped;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;

@Slf4j
public class LoadoutBuilderPanel extends PluginPanel
{
    public enum EquipmentSlot
    {
        HEAD, CAPE, NECK, AMMO, WEAPON, BODY, SHIELD, LEGS, HANDS, BOOTS, RING
    }

    private static final int INV_TILE_SIZE = 34;
    private static final int EQUIP_TILE_SIZE = 42;
    private static final int GAP = 8;
    private static final int INVENTORY_SIZE = 28;
    private static final Color TILE_BG = new Color(53, 49, 44);
    private static final Color TILE_BORDER = new Color(88, 80, 72);
    private static final Color CAPTION_FG = new Color(180, 180, 180);
    private static final Font CAPTION_FONT = new Font("Dialog", Font.PLAIN, 10);

    // ABSOLUTELY CRITICAL: Known stackable items
    private static final Set<Integer> FORCED_STACKABLES;

    static {
        Set<Integer> stackables = new HashSet<>();
        stackables.add(995);   // Coins - MUST BE HERE!
        stackables.add(6529);  // Tokkul
        stackables.add(554);   // Fire rune
        stackables.add(555);   // Water rune
        stackables.add(556);   // Air rune
        stackables.add(557);   // Earth rune
        FORCED_STACKABLES = Collections.unmodifiableSet(stackables);
        log.info("LoadoutBuilderPanel forced stackables: {}", stackables);
    }

    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final ItemResolver itemResolver;
    private final IconUtil iconUtil;

    private final Map<EquipmentSlot, JButton> equipmentButtons = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Integer> equipped = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Integer> equippedQty = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, String> equipmentLastQuery = new EnumMap<>(EquipmentSlot.class);

    private final JButton[] inventoryButtons = new JButton[INVENTORY_SIZE];
    private final Integer[] inventoryIds = new Integer[INVENTORY_SIZE];
    private final int[] inventoryQtys = new int[INVENTORY_SIZE];
    private final String[] inventoryLastQuery = new String[INVENTORY_SIZE];

    // Drag state
    private int pressIndex = -1;
    private int hoverIndex = -1;

    // Loadout Text UI
    private final JTextArea loadoutArea = new JTextArea(10, 40);
    private final JButton repcalButton = new JButton("Repcal Loadout");
    private final JButton copyButton = new JButton("Copy");
    private final JButton importButton = new JButton("Import");

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread)
    {
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.itemResolver = new ItemResolver(itemManager, clientThread);
        this.iconUtil = new IconUtil(itemManager, clientThread);

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

        log.info("LoadoutBuilderPanel initialized with forced stackables: {}", FORCED_STACKABLES);
    }

    // [All the UI building methods remain the same - buildEquipmentGrid, etc.]

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

        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            final int index = i;
            JButton btn = createTileButton(false);
            btn.setToolTipText("Inventory slot " + (i + 1));
            inventoryQtys[i] = 0;

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

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row1.setOpaque(false);
        repcalButton.addActionListener(e -> generateLoadoutText());
        row1.add(repcalButton);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row2.setOpaque(false);
        copyButton.addActionListener(e -> copyLoadoutToClipboard());
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

    // Equipment methods (unchanged)
    private void showEquipmentPopup(JButton btn, EquipmentSlot slot, int x, int y)
    {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem change = new JMenuItem("Change item...");
        change.addActionListener(a -> onPickItemForSlot(slot, btn, equipmentLastQuery.get(slot)));
        menu.add(change);

        JMenuItem setAmount = new JMenuItem("Set amount...");
        setAmount.addActionListener(a ->
        {
            Integer amt = promptAmount("Amount for this equipment item:", Math.max(1, equippedQty.getOrDefault(slot, 1)));
            if (amt == null) return;
            int q = Math.max(1, amt);
            equippedQty.put(slot, q);
            Integer id = equipped.get(slot);
            if (id != null)
            {
                setEquipmentIcon(slot, id, q);
            }
        });
        menu.add(setAmount);

        JMenuItem clear = new JMenuItem("Remove item");
        clear.addActionListener(a -> clearSlot(slot));
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
            setEquipmentItem(slot, result.getItem().getId(), result.getItem().getName(), 1);
        });
    }

    private void clearSlot(EquipmentSlot slot)
    {
        equipped.remove(slot);
        equippedQty.remove(slot);
        JButton btn = equipmentButtons.get(slot);
        if (btn != null)
        {
            btn.setIcon(null);
            btn.setToolTipText(slotTooltip(slot));
            revalidate();
            repaint();
        }
    }

    private void setEquipmentItem(EquipmentSlot slot, int itemId, String itemName, int quantity)
    {
        equipped.put(slot, itemId);
        equippedQty.put(slot, Math.max(1, quantity));
        setEquipmentIcon(slot, itemId, Math.max(1, quantity));

        JButton btn = equipmentButtons.get(slot);
        if (btn != null)
        {
            if (quantity > 1)
                btn.setToolTipText(itemName + " (ID: " + itemId + ", x" + quantity + ")");
            else
                btn.setToolTipText(itemName + " (ID: " + itemId + ")");
        }
    }

    private void setEquipmentIcon(EquipmentSlot slot, int itemId, int quantity)
    {
        JButton btn = equipmentButtons.get(slot);
        if (btn == null) return;
        iconUtil.setButtonIcon(btn, itemId, quantity);
        revalidate();
        repaint();
    }

    // FIXED INVENTORY METHODS with extensive debugging

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

            JMenuItem setAmount = new JMenuItem("Set amount (total)...");
            setAmount.addActionListener(a ->
            {
                Integer amt = promptAmount("Total amount of this item in inventory:", totalAmountOfItem(itemId));
                if (amt != null)
                {
                    log.info("User requested {} of item {} (current total: {})", amt, itemId, totalAmountOfItem(itemId));
                    ensureItemCountAsync(itemId, amt, index);
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
            log.info("User picked item {} for slot {}", itemId, index);

            Integer amt = promptAmount("How many to have in inventory (total)?", Math.max(1, totalAmountOfItem(itemId)));
            if (amt == null) return;

            log.info("User wants {} of item {} in inventory", amt, itemId);
            ensureItemCountAsync(itemId, amt, index);
        });
    }

    private Integer promptAmount(String message, int defaultValue)
    {
        while (true)
        {
            String input = JOptionPane.showInputDialog(this, message, defaultValue);
            if (input == null) return null;

            try
            {
                int value = Integer.parseInt(input.trim());
                if (value < 0)
                {
                    JOptionPane.showMessageDialog(this, "Amount must be positive", "Invalid Input", JOptionPane.ERROR_MESSAGE);
                    continue;
                }
                return value;
            }
            catch (NumberFormatException e)
            {
                JOptionPane.showMessageDialog(this, "Please enter a valid number", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // COMPLETELY REWRITTEN with extensive debugging
    private void ensureItemCountAsync(int itemId, int desired, int preferStartIndex)
    {
        log.info("=== ENSURE ITEM COUNT START ===");
        log.info("Input: itemId={}, desired={}, preferIndex={}", itemId, desired, preferStartIndex);

        clientThread.invoke(() ->
        {
            int canonId;
            try {
                canonId = itemManager.canonicalize(itemId);
                log.info("Canonicalized {} -> {}", itemId, canonId);
            } catch (Exception e) {
                canonId = itemId;
                log.warn("Failed to canonicalize {}, using original", itemId, e);
            }

            // CRITICAL: Check stackable status
            final boolean isStack = isItemStackable(canonId);
            final int desiredFinal = Math.max(0, desired);
            final int finalId = canonId;

            log.info("Item {} (canon: {}) - STACKABLE: {}, desired: {}", itemId, canonId, isStack, desiredFinal);

            SwingUtilities.invokeLater(() ->
            {
                log.info("Processing item {} on EDT - stackable: {}", finalId, isStack);

                if (isStack)
                {
                    log.info("Calling handleStackableItem for item {}", finalId);
                    handleStackableItem(finalId, desiredFinal, preferStartIndex);
                }
                else
                {
                    log.info("Calling handleNonStackableItem for item {}", finalId);
                    handleNonStackableItem(finalId, desiredFinal, preferStartIndex);
                }

                log.info("=== ENSURE ITEM COUNT END ===");
            });
        });
    }

    // ENHANCED stackable detection with LOTS of debugging
    private boolean isItemStackable(int canonId)
    {
        log.info("Checking if item {} is stackable...", canonId);

        // FIRST: Check our forced list
        if (FORCED_STACKABLES.contains(canonId)) {
            log.info("Item {} found in FORCED_STACKABLES - returning TRUE", canonId);
            return true;
        }

        // SECOND: Use ItemResolver
        boolean resolverResult = itemResolver.isStackableOnClientThread(canonId);
        log.info("ItemResolver says item {} stackable: {}", canonId, resolverResult);

        return resolverResult;
    }

    private void handleStackableItem(int itemId, int desired, int preferStartIndex)
    {
        log.info("=== HANDLING STACKABLE ITEM ===");
        log.info("Item: {}, desired: {}, prefer: {}", itemId, desired, preferStartIndex);

        if (desired == 0)
        {
            log.info("Desired amount is 0, removing all of item {}", itemId);
            removeAllOfItem(itemId);
            return;
        }

        // Find where this item currently exists
        int targetSlot = -1;

        if (preferStartIndex >= 0 && preferStartIndex < INVENTORY_SIZE &&
                Objects.equals(inventoryIds[preferStartIndex], itemId))
        {
            targetSlot = preferStartIndex;
            log.info("Item {} found in preferred slot {}", itemId, preferStartIndex);
        }
        else
        {
            for (int i = 0; i < INVENTORY_SIZE; i++)
            {
                if (Objects.equals(inventoryIds[i], itemId))
                {
                    targetSlot = i;
                    log.info("Item {} found in slot {}", itemId, i);
                    break;
                }
            }
        }

        if (targetSlot == -1)
        {
            log.info("Item {} not found anywhere, placing new stack of {}", itemId, desired);
            placeStackInNextFreeSlot(itemId, desired);
        }
        else
        {
            log.info("Updating existing slot {} with {} of item {}", targetSlot, desired, itemId);
            setInventorySlot(targetSlot, itemId, desired);

            // Remove all OTHER instances of this stackable item
            int removed = 0;
            for (int i = 0; i < INVENTORY_SIZE; i++)
            {
                if (i != targetSlot && Objects.equals(inventoryIds[i], itemId))
                {
                    log.info("Removing duplicate stackable item {} from slot {}", itemId, i);
                    clearInventorySlot(i);
                    removed++;
                }
            }
            log.info("Removed {} duplicate stacks of item {}", removed, itemId);
        }

        log.info("=== STACKABLE HANDLING COMPLETE ===");
    }

    private void handleNonStackableItem(int itemId, int desired, int preferStartIndex)
    {
        log.info("=== HANDLING NON-STACKABLE ITEM ===");
        log.info("Item: {}, desired: {}, prefer: {}", itemId, desired, preferStartIndex);

        List<Integer> indices = findItemIndices(itemId);
        int current = indices.size();
        log.info("Item {} currently in {} slots: {}", itemId, current, indices);

        // Ensure preferred slot has the item if desired > 0
        if (desired > 0 && preferStartIndex >= 0 && preferStartIndex < INVENTORY_SIZE)
        {
            if (inventoryIds[preferStartIndex] == null || !Objects.equals(inventoryIds[preferStartIndex], itemId))
            {
                if (inventoryIds[preferStartIndex] == null)
                {
                    log.info("Placing item {} in preferred empty slot {}", itemId, preferStartIndex);
                    setInventorySlot(preferStartIndex, itemId, 1);
                    current++;
                    indices.add(preferStartIndex);
                }
            }
        }

        if (current > desired)
        {
            int toRemove = current - desired;
            log.info("Need to remove {} instances of item {}", toRemove, itemId);

            for (int i = indices.size() - 1; i >= 0 && toRemove > 0; i--)
            {
                int idx = indices.get(i);
                if (idx == preferStartIndex) continue;
                if (Objects.equals(inventoryIds[idx], itemId))
                {
                    log.info("Removing item {} from slot {}", itemId, idx);
                    clearInventorySlot(idx);
                    toRemove--;
                }
            }
        }
        else if (current < desired)
        {
            int shortage = desired - current;
            log.info("Need to add {} more instances of item {}", shortage, itemId);
            int start = (preferStartIndex >= 0 && preferStartIndex < INVENTORY_SIZE) ? (preferStartIndex + 1) : 0;
            fillNextFreeSlots(itemId, shortage, start);
        }

        log.info("=== NON-STACKABLE HANDLING COMPLETE ===");
    }

    private void setInventorySlot(int index, int itemId, int quantity)
    {
        inventoryIds[index] = itemId;
        inventoryQtys[index] = Math.max(1, quantity);
        updateInventoryButtonIcon(index);
        log.info("Set slot {}: item {} x{}", index, itemId, quantity);
    }

    private void clearInventorySlot(int index)
    {
        Integer oldId = inventoryIds[index];
        int oldQty = inventoryQtys[index];

        inventoryIds[index] = null;
        inventoryQtys[index] = 0;
        JButton btn = inventoryButtons[index];
        if (btn != null)
        {
            btn.setIcon(null);
            btn.setToolTipText("Inventory slot " + (index + 1));
            btn.setBorder(new LineBorder(TILE_BORDER));
            revalidate();
            repaint();
        }
        log.info("Cleared slot {}: was item {} x{}", index, oldId, oldQty);
    }

    private void clearAllEquipment()
    {
        for (EquipmentSlot s : EquipmentSlot.values())
        {
            clearSlot(s);
        }
    }

    private void clearInventoryAll()
    {
        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            clearInventorySlot(i);
        }
    }

    private void swapInventorySlots(int a, int b)
    {
        Integer tmpId = inventoryIds[a];
        int tmpQty = inventoryQtys[a];
        inventoryIds[a] = inventoryIds[b];
        inventoryQtys[a] = inventoryQtys[b];
        inventoryIds[b] = tmpId;
        inventoryQtys[b] = tmpQty;
        updateInventoryButtonIcon(a);
        updateInventoryButtonIcon(b);
        log.info("Swapped slots {} and {}", a, b);
    }

    private void fillNextFreeSlots(int itemId, int count, int startIndex)
    {
        int remaining = count;
        log.info("Filling {} slots with item {}, starting from {}", count, itemId, startIndex);

        for (int i = startIndex; i < INVENTORY_SIZE && remaining > 0; i++)
        {
            if (inventoryIds[i] == null)
            {
                setInventorySlot(i, itemId, 1);
                remaining--;
            }
        }
        for (int i = 0; i < startIndex && remaining > 0; i++)
        {
            if (inventoryIds[i] == null)
            {
                setInventorySlot(i, itemId, 1);
                remaining--;
            }
        }
        if (remaining > 0)
        {
            Toolkit.getDefaultToolkit().beep();
            log.warn("Could not place {} more items, inventory full", remaining);
        }
    }

    private void placeStackInNextFreeSlot(int itemId, int quantity)
    {
        log.info("Placing stack of {} x{} in next free slot", itemId, quantity);

        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            if (inventoryIds[i] == null)
            {
                setInventorySlot(i, itemId, Math.max(1, quantity));
                log.info("Placed stack in slot {}", i);
                return;
            }
        }
        Toolkit.getDefaultToolkit().beep();
        log.warn("Could not place stack of {} x{}, inventory full", itemId, quantity);
    }

    private int totalAmountOfItem(int itemId)
    {
        int total = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            if (Objects.equals(inventoryIds[i], itemId))
            {
                total += Math.max(1, inventoryQtys[i]);
            }
        }
        return total;
    }

    private List<Integer> findItemIndices(int itemId)
    {
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            if (Objects.equals(inventoryIds[i], itemId))
            {
                idxs.add(i);
            }
        }
        return idxs;
    }

    private void removeAllOfItem(int itemId)
    {
        boolean removedAny = false;
        for (int i = 0; i < INVENTORY_SIZE; i++)
        {
            if (Objects.equals(inventoryIds[i], itemId))
            {
                clearInventorySlot(i);
                removedAny = true;
            }
        }
        if (!removedAny) {
            Toolkit.getDefaultToolkit().beep();
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

        int qty = Math.max(1, inventoryQtys[index]);
        iconUtil.setButtonIcon(btn, id, qty);

        if (qty > 1)
            btn.setToolTipText("Item ID: " + id + " (x" + qty + ")");
        else
            btn.setToolTipText("Item ID: " + id);

        revalidate();
        repaint();
    }

    // [Rest of methods remain unchanged - generateLoadoutText, copyLoadoutToClipboard, importFromText, parseEntries, helper methods]

    private void generateLoadoutText()
    {
        loadoutArea.setText("Generating...");
        clientThread.invoke(() ->
        {
            StringBuilder sb = new StringBuilder();

            EquipmentSlot[] order = new EquipmentSlot[]{
                    EquipmentSlot.BOOTS, EquipmentSlot.NECK, EquipmentSlot.SHIELD, EquipmentSlot.CAPE,
                    EquipmentSlot.HANDS, EquipmentSlot.BODY, EquipmentSlot.HEAD, EquipmentSlot.RING,
                    EquipmentSlot.LEGS, EquipmentSlot.WEAPON, EquipmentSlot.AMMO
            };

            for (EquipmentSlot s : order)
            {
                Integer id = equipped.get(s);
                if (id == null) continue;
                String name = itemResolver.getNameOnClientThread(id);
                if (name == null || name.equalsIgnoreCase("null")) continue;
                String code = codeForSlot(s);
                int qty = Math.max(1, equippedQty.getOrDefault(s, 1));
                sb.append(code).append(":").append(name).append(":").append(qty).append("\n");
            }

            LinkedHashMap<Integer, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < INVENTORY_SIZE; i++)
            {
                Integer id = inventoryIds[i];
                if (id == null) continue;
                int qty = Math.max(1, inventoryQtys[i]);
                counts.merge(id, qty, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> e : counts.entrySet())
            {
                String name = itemResolver.getNameOnClientThread(e.getKey());
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
        log.info("Importing {} entries from text", entries.size());

        clientThread.invoke(() ->
        {
            Map<String, Integer> resolved = new LinkedHashMap<>();
            List<String> notFound = new ArrayList<>();

            for (ImportEntry entry : entries)
            {
                String name = entry.itemName;
                if (resolved.containsKey(name)) continue;
                Integer id = itemResolver.resolve(name);
                if (id == null) {
                    notFound.add(name);
                    log.warn("Could not resolve item name: '{}'", name);
                } else {
                    log.info("Resolved '{}' to item {}", name, id);
                }
                resolved.put(name, id);
            }

            Map<EquipmentSlot, Integer> equipToSet = new EnumMap<>(EquipmentSlot.class);
            Map<EquipmentSlot, Integer> equipQtyToSet = new EnumMap<>(EquipmentSlot.class);
            LinkedHashMap<Integer, Integer> invCounts = new LinkedHashMap<>();

            for (ImportEntry entry : entries)
            {
                Integer id = resolved.get(entry.itemName);
                if (id == null) continue;

                int canonId = itemManager.canonicalize(id);

                if (entry.isInventory)
                {
                    int count = entry.amount < 0 ? INVENTORY_SIZE : entry.amount;
                    invCounts.merge(canonId, count, Integer::sum);
                    log.info("Import: inventory item '{}' (id {}) x{}", entry.itemName, canonId, count);
                }
                else if (entry.slot != null)
                {
                    equipToSet.put(entry.slot, canonId);
                    int eqQty = entry.amount < 0 ? 1 : Math.max(1, entry.amount);
                    equipQtyToSet.put(entry.slot, eqQty);
                    log.info("Import: equipment {} item '{}' (id {}) x{}", entry.slot, entry.itemName, canonId, eqQty);
                }
            }

            Set<Integer> allIds = new HashSet<>();
            allIds.addAll(equipToSet.values());
            allIds.addAll(invCounts.keySet());
            Map<Integer, String> idToName = new HashMap<>();
            for (Integer id : allIds)
            {
                if (id == null) continue;
                String nm = itemResolver.getNameOnClientThread(id);
                idToName.put(id, nm != null ? nm : ("ID " + id));
            }

            SwingUtilities.invokeLater(() ->
            {
                try
                {
                    log.info("Clearing all equipment and inventory for import");
                    clearAllEquipment();
                    clearInventoryAll();

                    // Equipment
                    for (Map.Entry<EquipmentSlot, Integer> e : equipToSet.entrySet())
                    {
                        EquipmentSlot slot = e.getKey();
                        int id = e.getValue();
                        int qty = Math.max(1, equipQtyToSet.getOrDefault(slot, 1));
                        String name = idToName.getOrDefault(id, "ID " + id);
                        setEquipmentItem(slot, id, name, qty);
                    }

                    // Inventory
                    for (Map.Entry<Integer, Integer> e2 : invCounts.entrySet())
                    {
                        log.info("Import: setting inventory item {} to amount {}", e2.getKey(), e2.getValue());
                        ensureItemCountAsync(e2.getKey(), Math.max(0, e2.getValue()), 0);
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
        final int amount;

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
                try {
                    amount = Integer.parseInt(amtStr);
                }
                catch (NumberFormatException ignored) {
                    amount = 1;
                }
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

    // Helper methods (unchanged)
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
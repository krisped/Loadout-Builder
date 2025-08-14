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
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/*
 * LoadoutBuilderPanel – Stabil v6
 * - Intern JScrollPane over innhold (scroll funker).
 * - Equipment/Inventory grids låses i størrelse og pakkes i FlowLayout-holdere.
 * - Equipment-grid sentrert; Inventory venstrejustert.
 * - Litt mindre ruter (SLOT_SIZE=50 i LoadoutSlot) og litt mindre gaps (5).
 * - Økt seksjon-padding venstre/høyre (EmptyBorder(4, 8, 4, 8)) for balansert luft.
 * - KittyKeys: dialog med JA/NEI og inputfelt for antall TICK (default 4).
 * - Ingen duplikate metoder.
 */

public class LoadoutBuilderPanel extends PluginPanel implements LoadoutSlot.SlotActionHandler
{
    /* Layout */
    private static final int EQUIP_ROWS = 5;
    private static final int EQUIP_COLS = 3;
    private static final int INV_ROWS = 7;
    private static final int INV_COLS = 4;

    private static final int GRID_HGAP = 5;
    private static final int GRID_VGAP = 5;

    /* UI styling */
    private static final float TITLE_FONT_SIZE      = 16f;
    private static final float SLOT_LABEL_FONT_SIZE = 13f;
    private static final float BUTTON_FONT_SIZE     = 15f;
    private static final float TEXTAREA_FONT_SIZE   = 14f;
    private static final int   BUTTON_HEIGHT        = 24; // reduced from 26 for slightly smaller buttons
    private static final int   SECTION_SPACING      = 4; // was 6, tighter vertical spacing

    /* Deps */
    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final Client client;

    /* Fonts */
    private Font runescape;
    private Font runescapeBold;
    private Font fallback;

    /* Data */
    private final Map<EquipmentInventorySlot, LoadoutSlot> equipmentSlots = new EnumMap<>(EquipmentInventorySlot.class);
    private final LoadoutSlot[] inventorySlots = new LoadoutSlot[28];

    /* UI */
    private JPanel equipmentGrid;
    private JPanel inventoryGrid;
    private JTextArea repcalArea;
    private JButton repcalButton;
    private JButton kittyKeysButton;
    private JButton importButton;
    private JButton exportButton;
    private JButton copyButton;
    private JButton clearButton;
    // New: references to section panels for width normalization
    private JPanel equipmentSection;
    private JPanel inventorySection;
    private JPanel loadoutSection;

    private final Gson gson = new Gson();

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

    /* Fonts */
    private void initFonts()
    {
        try
        {
            runescape = FontManager.getRunescapeFont();
            runescapeBold = FontManager.getRunescapeBoldFont();
        }
        catch (Exception e)
        {
            runescape = new Font("Dialog", Font.PLAIN, 14);
            runescapeBold = runescape.deriveFont(Font.BOLD);
        }
        fallback = new Font("Dialog", Font.PLAIN, 14);
    }

    /* UI build */
    private void buildUI()
    {
        setLayout(new BorderLayout());

        JPanel root = new JPanel();
        root.setOpaque(false);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        root.add(buildEquipmentSection());
        root.add(Box.createVerticalStrut(SECTION_SPACING));
        root.add(buildInventorySection());
        root.add(Box.createVerticalStrut(SECTION_SPACING));
        root.add(buildLoadoutSection());

        JScrollPane scroll = new JScrollPane(root,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

        add(scroll, BorderLayout.CENTER);
        unifySectionWidths();
    }

    private JPanel titledSection(String title)
    {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        TitledBorder tb = new TitledBorder(title);
        tb.setTitleFont((runescapeBold != null ? runescapeBold : fallback).deriveFont(Font.BOLD, TITLE_FONT_SIZE));
        // Further reduced padding (was 2,6,2,6) now 2,4,2,4 to trim width
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(2,4,2,4)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JPanel buildEquipmentSection()
    {
        JPanel section = titledSection("Equipment");
        this.equipmentSection = section;

        equipmentGrid = new JPanel(new GridLayout(EQUIP_ROWS, EQUIP_COLS, GRID_HGAP, GRID_VGAP));
        equipmentGrid.setOpaque(false);

        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
            equipmentSlots.put(slot, new LoadoutSlot(itemManager, this, slot.ordinal(), true));

        EquipmentInventorySlot[][] layout = {
                {null, EquipmentInventorySlot.HEAD, null},
                {EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET, EquipmentInventorySlot.AMMO},
                {EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD},
                {null, EquipmentInventorySlot.LEGS, null},
                {EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING}
        };

        for (int r = 0; r < EQUIP_ROWS; r++)
        {
            for (int c = 0; c < EQUIP_COLS; c++)
            {
                EquipmentInventorySlot slot = layout[r][c];
                JPanel cell = new JPanel();
                cell.setOpaque(false);
                cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
                if (slot != null)
                {
                    LoadoutSlot ls = equipmentSlots.get(slot);
                    ls.setAlignmentX(Component.CENTER_ALIGNMENT);
                    cell.add(ls);

                    JLabel lbl = makeSlotLabel(slot);
                    Dimension lp = lbl.getPreferredSize();
                    lbl.setMaximumSize(lp);
                    lbl.setMinimumSize(lp);
                    cell.add(lbl);
                }
                else
                {
                    // Tom celle for å holde gridden jevn
                    cell.add(Box.createVerticalStrut(50 + 14));
                }
                equipmentGrid.add(cell);
            }
        }

        lockPanelSize(equipmentGrid);

        // Sentrer equipment grid
        JPanel eqHolder = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        eqHolder.setOpaque(false);
        eqHolder.add(equipmentGrid);
        eqHolder.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(eqHolder);

        // Knapperekke
        JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
        row.setOpaque(false);
        copyButton = makeButton("Copy equipped");
        clearButton = makeButton("Clear all");
        copyButton.addActionListener(e -> copyLoadout());
        clearButton.addActionListener(e -> resetAll());
        row.add(copyButton);
        row.add(clearButton);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        section.add(Box.createVerticalStrut(4)); // was 6 before button row
        section.add(row);
        constrainSectionWidth(section);
        return section;
    }

    private JPanel buildInventorySection()
    {
        JPanel section = titledSection("Inventory");
        this.inventorySection = section;

        inventoryGrid = new JPanel(new GridLayout(INV_ROWS, INV_COLS, GRID_HGAP, GRID_VGAP));
        inventoryGrid.setOpaque(false);

        for (int i = 0; i < inventorySlots.length; i++)
        {
            LoadoutSlot slot = new LoadoutSlot(itemManager, this, i, false);
            inventorySlots[i] = slot;
            JPanel holder = new JPanel();
            holder.setOpaque(false);
            holder.setLayout(new BoxLayout(holder, BoxLayout.Y_AXIS));
            holder.add(slot);
            inventoryGrid.add(holder);
        }

        lockPanelSize(inventoryGrid);

        // Inventory venstrejustert
        JPanel invHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        invHolder.setOpaque(false);
        invHolder.add(inventoryGrid);
        invHolder.setAlignmentX(Component.LEFT_ALIGNMENT);

        section.add(invHolder);
        constrainSectionWidth(section);
        return section;
    }

    private JPanel buildLoadoutSection()
    {
        JPanel section = titledSection("Loadout");
        this.loadoutSection = section;
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));

        repcalArea = new JTextArea();
        repcalArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) TEXTAREA_FONT_SIZE));
        repcalArea.setLineWrap(true);
        repcalArea.setWrapStyleWord(false);
        repcalArea.setRows(9); // was 10, reduce height slightly
        repcalArea.setColumns(26); // limit width so section doesn't expand full panel width
        repcalArea.setTabSize(4);

        JScrollPane sp = new JScrollPane(repcalArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createLineBorder(new Color(70,70,70)));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(sp);
        section.add(Box.createVerticalStrut(4)); // was 6 before loadout buttons

        JPanel btnGrid = new JPanel(new GridLayout(2, 2, 6, 6));
        btnGrid.setOpaque(false);

        repcalButton = makeButton("Repcal");
        kittyKeysButton = makeButton("KittyKeys");
        importButton = makeButton("Import");
        exportButton = makeButton("Export");

        repcalButton.addActionListener(e -> generateRepcalString());
        kittyKeysButton.addActionListener(e -> generateKittyKeysScript());
        importButton.addActionListener(e -> importRepcalCodes());
        exportButton.addActionListener(e -> exportToClipboard());

        repcalButton.setToolTipText("Generate Repcal lines (equipment + inventory). Weapon/ammo stack >1 => wildcard *.");
        kittyKeysButton.setToolTipText("Generate KittyKeys script (optional TICK lines).");
        importButton.setToolTipText("Import from Repcal lines or JSON (auto-detected). Overwrites matching slots.");
        exportButton.setToolTipText("Copy the text area content to clipboard.");

        // Uniform width based on intrinsic max of these four (smaller than copy button)
        int maxW = Math.max(Math.max(repcalButton.getPreferredSize().width, kittyKeysButton.getPreferredSize().width),
                Math.max(importButton.getPreferredSize().width, exportButton.getPreferredSize().width));
        enforceUniformButtonWidth(maxW, repcalButton, kittyKeysButton, importButton, exportButton);

        btnGrid.add(repcalButton);
        btnGrid.add(kittyKeysButton);
        btnGrid.add(importButton);
        btnGrid.add(exportButton);
        btnGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(btnGrid);
        constrainSectionWidth(section);
        return section;
    }

    /* UI helpers */
    private JButton makeButton(String text)
    {
        Font base = (runescape != null ? runescape : fallback).deriveFont(Font.PLAIN, BUTTON_FONT_SIZE);
        JButton b = new JButton(text);
        b.setFont(base);
        b.setMargin(new Insets(2, 6, 2, 6)); // reduced horizontal padding slightly
        b.setFocusPainted(false);
        Dimension pref = b.getPreferredSize();
        b.setPreferredSize(new Dimension(pref.width + 2, BUTTON_HEIGHT)); // slight width padding
        b.setMinimumSize(new Dimension(pref.width + 2, BUTTON_HEIGHT));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_HEIGHT));
        return b;
    }

    private void enforceUniformButtonWidth(int width, JButton... buttons)
    {
        for (JButton b : buttons)
        {
            Dimension d = new Dimension(width, BUTTON_HEIGHT);
            b.setPreferredSize(d);
            b.setMinimumSize(d);
            b.setMaximumSize(new Dimension(width, BUTTON_HEIGHT));
        }
    }

    private void constrainSectionWidth(JPanel section)
    {
        section.revalidate();
        Dimension pref = section.getPreferredSize();
        section.setMaximumSize(new Dimension(pref.width, Integer.MAX_VALUE));
    }

    private JLabel makeSlotLabel(EquipmentInventorySlot slot)
    {
        String txt = slotLabel(slot);
        JLabel lbl = new JLabel(txt, SwingConstants.CENTER);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbl.setFont((runescapeBold != null ? runescapeBold : fallback)
                .deriveFont(Font.BOLD, SLOT_LABEL_FONT_SIZE));
        lbl.setForeground(new Color(220,220,220));
        return lbl;
    }

    private String slotLabel(EquipmentInventorySlot slot)
    {
        switch (slot)
        {
            case HEAD: return "Head";
            case CAPE: return "Cape";
            case AMULET: return "Amulet";
            case WEAPON: return "Weapon";
            case BODY: return "Body";
            case SHIELD: return "Shield";
            case LEGS: return "Legs";
            case GLOVES: return "Gloves";
            case BOOTS: return "Boots";
            case RING: return "Ring";
            case AMMO: return "Ammo";
            default: return slot.name();
        }
    }

    private void lockPanelSize(JPanel panel)
    {
        // Lås gridet til preferert størrelse slik at det ikke strekkes ved resize
        panel.doLayout();
        Dimension pref = panel.getPreferredSize();
        panel.setPreferredSize(pref);
        panel.setMinimumSize(pref);
        panel.setMaximumSize(pref);
    }

    /* Basic actions */
    private void resetAll()
    {
        for (LoadoutSlot s : equipmentSlots.values()) s.clear();
        for (LoadoutSlot s : inventorySlots) s.clear();
        if (repcalArea != null) repcalArea.setText("");
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
                    if (i < invItems.length)
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
        try
        {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            JOptionPane.showMessageDialog(this, "Copied to clipboard.", "Export", JOptionPane.INFORMATION_MESSAGE);
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog(this, "Clipboard error: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    /* Repcal export */
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
                String qty = isWildcardEquipment(slot, ls) ? "*" : Integer.toString(Math.max(1, ls.getQuantity()));
                sb.append(code).append(":").append(name).append(":").append(qty).append("\n");
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

    private boolean isWildcardEquipment(EquipmentInventorySlot slot, LoadoutSlot ls)
    {
        if (slot != EquipmentInventorySlot.WEAPON && slot != EquipmentInventorySlot.AMMO) return false;
        if (!ls.isStackable()) return false;
        return ls.getQuantity() > 1;
    }

    /* KittyKeys export: with YES/NO and ticks input */
    private void generateKittyKeysScript()
    {
        // Custom dialog: Yes/No + input for tick count (default 4)
        JTextField ticksField = new JTextField("4", 4);
        ticksField.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel msg = new JPanel();
        msg.setLayout(new BoxLayout(msg, BoxLayout.Y_AXIS));
        msg.add(new JLabel("Do you want to add TICK lines between phases?"));
        msg.add(new JLabel("(Withdraw -> Bank wield -> Inventory withdraw)"));
        msg.add(Box.createVerticalStrut(6));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(new JLabel("Number of TICK lines: "));
        row.add(ticksField);
        msg.add(row);

        Object[] options = {"Yes", "No"};
        int choice = JOptionPane.showOptionDialog(
                this,
                msg,
                "KittyKeys Export",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        int tickCount = 0;
        if (choice == JOptionPane.YES_OPTION)
        {
            try
            {
                tickCount = Integer.parseInt(ticksField.getText().trim());
                if (tickCount < 0) tickCount = 0;
            }
            catch (NumberFormatException nfe)
            {
                tickCount = 4; // fallback to default if invalid
            }
        }

        final int finalTickCount = tickCount;

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

            LinkedHashMap<EquipmentInventorySlot,String> eqNames = new LinkedHashMap<>();
            for (EquipmentInventorySlot slot : order)
            {
                LoadoutSlot ls = equipmentSlots.get(slot);
                if (ls != null && ls.getItemId() > 0)
                {
                    String name = kittyKeysItemName(resolveName(ls));
                    sb.append("WITHDRAW ").append(name).append(" ").append(Math.max(1, ls.getQuantity())).append("\n");
                    eqNames.put(slot, name);
                }
            }

            if (!eqNames.isEmpty())
            {
                if (finalTickCount > 0) appendTicksBlock(sb, finalTickCount); else sb.append("\n");
            }

            for (String nm : eqNames.values())
                sb.append("BANK_WIELD ").append(nm).append("\n");

            if (!eqNames.isEmpty())
            {
                if (finalTickCount > 0) appendTicksBlock(sb, finalTickCount); else sb.append("\n");
            }

            LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                int add = ls.isStackable() ? Math.max(1, ls.getQuantity()) : 1;
                invCounts.merge(resolveName(ls), add, Integer::sum);
            }
            for (Map.Entry<String,Integer> e : invCounts.entrySet())
            {
                sb.append("WITHDRAW ")
                        .append(kittyKeysItemName(e.getKey()))
                        .append(" ")
                        .append(e.getValue())
                        .append("\n");
            }

            String out = sb.toString().trim();
            SwingUtilities.invokeLater(() -> {
                repcalArea.setText(out);
                repcalArea.setCaretPosition(0);
            });
        });
    }

    private void appendTicksBlock(StringBuilder sb, int count)
    {
        sb.append("\n");
        for (int i = 0; i < count; i++) sb.append("TICK\n");
        sb.append("\n");
    }

    private String kittyKeysItemName(String name)
    {
        if (name == null) return "";
        return name.trim()
                .replace("’", "'")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    /* Import (Repcal + JSON) */
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
                equipmentSlots.get(slot).clear();
        }
        if (hasInv) for (LoadoutSlot s : inventorySlots) s.clear();

        clientThread.invoke(() -> {
            List<String> errors = new ArrayList<>();
            int invPtr = 0;

            for (RepcalLine l : lines)
            {
                if (l.code.equalsIgnoreCase("I"))
                {
                    int itemId = resolveItemId(l.name, errors);
                    if (itemId <= 0) continue;
                    boolean stackable = false;
                    try
                    {
                        ItemComposition comp = itemManager.getItemComposition(itemId);
                        stackable = comp.isStackable() || comp.getNote() != -1;
                    }
                    catch (Exception ignored) {}

                    int qty = Math.max(1, l.quantity);
                    if (stackable)
                    {
                        while (invPtr < inventorySlots.length && inventorySlots[invPtr].getItemId() > 0) invPtr++;
                        if (invPtr >= inventorySlots.length)
                        {
                            errors.add("Inventory full (stackable " + l.name + ")");
                            continue;
                        }
                        final int idx = invPtr++;
                        SwingUtilities.invokeLater(() -> inventorySlots[idx].setItem(itemId, qty));
                    }
                    else
                    {
                        for (int i = 0; i < qty; i++)
                        {
                            while (invPtr < inventorySlots.length && inventorySlots[invPtr].getItemId() > 0) invPtr++;
                            if (invPtr >= inventorySlots.length)
                            {
                                errors.add("Inventory full (" + l.name + ")");
                                break;
                            }
                            final int idx = invPtr++;
                            SwingUtilities.invokeLater(() -> inventorySlots[idx].setItem(itemId, 1));
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
                    final int q = Math.max(1, l.quantity);
                    SwingUtilities.invokeLater(() -> equipmentSlots.get(slot).setItem(itemId, q));
                }
            }

            if (!errors.isEmpty())
            {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, String.join("\n", errors),
                                "Import issues", JOptionPane.WARNING_MESSAGE));
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
        try { root = gson.fromJson(json, JsonRoot.class); }
        catch (JsonSyntaxException ex)
        {
            JOptionPane.showMessageDialog(this, "Invalid JSON: " + ex.getMessage(),
                    "JSON Import", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (root == null || root.setup == null)
        {
            JOptionPane.showMessageDialog(this, "JSON missing 'setup'.",
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
                int qty = ji.q != null && ji.q > 0 ? ji.q : 1;
                equipmentSlots.get(slot).setItem(ji.id, qty);
            }
        }
        JOptionPane.showMessageDialog(this, "JSON loadout imported.",
                "JSON Import", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /* JSON data classes */
    private static class JsonRoot { JsonSetup setup; List<Integer> layout; }
    private static class JsonSetup { List<JsonItem> inv; List<JsonItem> eq; String name; String hc; }
    private static class JsonItem { int id; @SerializedName("q") Integer q; }

    /* Helpers */
    private String resolveName(LoadoutSlot ls)
    {
        String n = ls.getResolvedName();
        if (n == null)
        {
            try { n = itemManager.getItemComposition(ls.getItemId()).getName(); }
            catch (Exception ignored) { n = "Item " + ls.getItemId(); }
        }
        return n;
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

    /* Item search cache */
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
        Map<String,Integer> map = new HashMap<>();
        if (allItemIdsCache != null)
        {
            for (int id : allItemIdsCache)
            {
                try
                {
                    String nm = itemManager.getItemComposition(id).getName();
                    if (nm != null && !"null".equalsIgnoreCase(nm))
                        map.putIfAbsent(nm.toLowerCase(), id);
                }
                catch (Exception ignored){}
            }
        }
        exactNameMap = map;
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
        if (exact != null) return exact;

        if (potionHeuristic)
        {
            for (int d = 4; d >= 1; d--)
            {
                Integer pot = exactNameMap.get((trimmed + "(" + d + ")").toLowerCase());
                if (pot != null) return pot;
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

        int best = -1;
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
            catch (Exception ex) { continue; }
            if (nm == null) continue;

            String nmL = nm.toLowerCase();
            if (nmL.equals(lower)) score += 200;
            if (nmL.startsWith(lower)) score += 40;
            score -= Math.abs(nm.length() - nameInput.length()) * 1.1;

            boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
            boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
            if (noted) score -= 15;
            if (placeholder) score -= 15;

            if (score > bestScore)
            {
                bestScore = score;
                best = id;
            }
        }

        if (best <= 0)
            errors.add("No suitable item match: " + nameInput);

        return best;
    }

    // EN forekomst av searchItems
    private List<Integer> searchItems(String query)
    {
        query = query.trim();
        if (query.isEmpty()) return Collections.emptyList();
        try
        {
            Method m = itemManager.getClass().getMethod("search", String.class);
            Object result = m.invoke(itemManager, query);
            if (result instanceof Collection)
            {
                List<Integer> ids = new ArrayList<>();
                for (Object o : (Collection<?>) result)
                {
                    if (o instanceof Integer)
                        ids.add((Integer) o);
                    else if (o != null)
                    {
                        try
                        {
                            Method mid = o.getClass().getMethod("getItemId");
                            Object v = mid.invoke(o);
                            if (v instanceof Integer) ids.add((Integer) v);
                        }
                        catch (Exception ignore)
                        {
                            try
                            {
                                Method mid2 = o.getClass().getMethod("getId");
                                Object v2 = mid2.invoke(o);
                                if (v2 instanceof Integer) ids.add((Integer) v2);
                            }
                            catch (Exception ignore2) {}
                        }
                    }
                }
                return ids;
            }
        }
        catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /* SlotActionHandler */
    @Override
    public void requestItemInfoOnClientThread(LoadoutSlot slot, int itemId)
    {
        clientThread.invoke(() -> {
            try
            {
                ItemComposition comp = itemManager.getItemComposition(itemId);
                String name = comp.getName();
                boolean stackable = comp.isStackable() || comp.getNote() != -1;
                BufferedImage icon = itemManager.getImage(itemId);
                SwingUtilities.invokeLater(() -> slot.setResolvedItemInfo(name, icon, stackable));
            }
            catch (Exception e)
            {
                SwingUtilities.invokeLater(() -> slot.setResolvedItemInfo(null, null, false));
            }
        });
    }

    @Override
    public void onLeftClick(LoadoutSlot slot, boolean isEquipment, int index)
    {
        int chosen = ItemSearchDialog.showDialog(this, itemManager, clientThread);
        if (chosen > 0)
            slot.setItem(chosen, 1);
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
            slot.incrementQuantityInternal(delta);
        else
            addNonStackableCopies(slot.getItemId(), delta);
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
            slot.setQuantityInternal(targetTotal);
        else
            setNonStackableTotal(slot.getItemId(), targetTotal);
    }

    /* Non-stackable helpers */
    private void addNonStackableCopies(int itemId, int add)
    {
        for (int i = 0; i < inventorySlots.length && add > 0; i++)
        {
            if (inventorySlots[i].getItemId() <= 0)
            {
                inventorySlots[i].setItem(itemId, 1);
                add--;
            }
        }
    }

    private void setNonStackableTotal(int itemId, int target)
    {
        List<LoadoutSlot> existing = new ArrayList<>();
        List<LoadoutSlot> empty = new ArrayList<>();
        for (LoadoutSlot s : inventorySlots)
        {
            if (s.getItemId() == itemId) existing.add(s);
            else if (s.getItemId() <= 0) empty.add(s);
        }
        int current = existing.size();
        if (current == target) return;
        if (current < target)
        {
            int need = target - current;
            for (LoadoutSlot e : empty)
            {
                if (need-- <= 0) break;
                e.setItem(itemId, 1);
            }
        }
        else
        {
            int remove = current - target;
            for (int i = target; i < existing.size() && remove > 0; i++)
            {
                existing.get(i).clear();
                remove--;
            }
        }
    }

    public void removeAllOccurrences(int itemId)
    {
        for (LoadoutSlot s : inventorySlots)
            if (s.getItemId() == itemId) s.clear();
    }

    void performSlotDrop(LoadoutSlot source, LoadoutSlot target)
    {
        if (source == null || target == null || source == target) return;
        if (source.getItemId() <= 0) return;
        if (source.isEquipment() || target.isEquipment()) return;

        if (source.isStackable() && target.isStackable() && source.getItemId() == target.getItemId())
        {
            target.setQuantityInternal(target.getQuantity() + source.getQuantity());
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

    /* Static helpers */
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

    /* Repcal parsing */
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
            String name = parts[1].trim();
            int qty = 1;

            if (parts.length == 3)
            {
                String qRaw = parts[2].trim();
                if (!qRaw.equals("*"))
                {
                    try { qty = Integer.parseInt(qRaw); }
                    catch (NumberFormatException ignored) {}
                }
            }

            out.add(new RepcalLine(code, name, qty));
        }
        return out;
    }

    private void unifySectionWidths()
    {
        SwingUtilities.invokeLater(() -> {
            if (inventorySection == null) return;
            int target = inventorySection.getPreferredSize().width;
            adjustSectionWidth(equipmentSection, target);
            adjustSectionWidth(loadoutSection, target);
        });
    }

    private void adjustSectionWidth(JPanel section, int targetWidth)
    {
        if (section == null) return;
        Dimension pref = section.getPreferredSize();
        if (pref.width == targetWidth) return;
        pref = new Dimension(targetWidth, pref.height);
        section.setPreferredSize(pref);
        section.setMaximumSize(new Dimension(targetWidth, Integer.MAX_VALUE));
        section.revalidate();
    }
}

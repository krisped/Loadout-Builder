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
import java.io.OutputStream; // added
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

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

    private JTabbedPane tabbedPane; // NEW store reference to switch tabs
    private JComboBox<Loadout> quickPresetCombo; // NEW quick dropdown
    private boolean suppressComboEvent = false; // guard

    private final Gson gson = new Gson();

    private final LoadoutManager loadoutManager; // NEW
    private final LoadoutBuilderConfig config;   // NEW discord config reference

    // Presets UI references
    private DefaultListModel<Loadout> presetsModel; // NEW
    private JList<Loadout> presetsList; // NEW

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

    // NEW: tracks currently loaded preset (null = unsaved/new)
    private Loadout currentLoadedLoadout;

    public LoadoutBuilderPanel(ItemManager itemManager, ClientThread clientThread, Client client, LoadoutManager loadoutManager, LoadoutBuilderConfig config)
    {
        super(false);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.client = client;
        this.loadoutManager = loadoutManager; // NEW
        this.config = config; // NEW
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

        tabbedPane = new JTabbedPane();

        // ===== Builder tab (restore original vertical layout) =====
        JPanel root = new JPanel();
        root.setOpaque(false);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.add(buildEquipmentSection());
        root.add(Box.createVerticalStrut(SECTION_SPACING));
        root.add(buildInventorySection());
        root.add(Box.createVerticalStrut(SECTION_SPACING));
        root.add(buildLoadoutSection()); // save button now embedded here

        JScrollPane scroll = new JScrollPane(root,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Container holding quick preset bar on top (outside scroll) to avoid width distortion
        JPanel builderContainer = new JPanel(new BorderLayout());
        builderContainer.setOpaque(false);
        builderContainer.add(buildQuickPresetBar(), BorderLayout.NORTH);
        builderContainer.add(scroll, BorderLayout.CENTER);
        tabbedPane.addTab("Builder", builderContainer);

        // ===== Presets tab =====
        JPanel presetsPanel = buildPresetsPanel();
        tabbedPane.addTab("Presets", presetsPanel);

        add(tabbedPane, BorderLayout.CENTER);
        unifySectionWidths();
    }

    // NEW: quick preset bar on top of builder
    private JPanel buildQuickPresetBar()
    {
        JPanel bar = new JPanel(new BorderLayout(6,0));
        bar.setOpaque(false);
        JLabel lbl = new JLabel("Preset:");
        Font labelFont = (runescapeBold != null ? runescapeBold : fallback).deriveFont(Font.BOLD, 15f);
        lbl.setFont(labelFont);
        quickPresetCombo = new JComboBox<>();
        Font comboFont = (runescape != null ? runescape : fallback).deriveFont(Font.PLAIN, 15f);
        quickPresetCombo.setFont(comboFont);
        quickPresetCombo.setMaximumRowCount(22);
        quickPresetCombo.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setFont(comboFont);
                if (value instanceof Loadout)
                    c.setText(((Loadout) value).getName());
                c.setBorder(BorderFactory.createEmptyBorder(2,6,2,6));
                return c;
            }
        });
        quickPresetCombo.setPreferredSize(new Dimension(190, BUTTON_HEIGHT + 6));
        quickPresetCombo.setMaximumSize(new Dimension(210, BUTTON_HEIGHT + 8));
        quickPresetCombo.addActionListener(e -> {
            if (suppressComboEvent) return;
            Loadout sel = (Loadout) quickPresetCombo.getSelectedItem();
            if (sel != null)
                applyLoadout(sel);
        });
        refreshQuickPresetCombo();
        bar.add(lbl, BorderLayout.WEST);
        bar.add(quickPresetCombo, BorderLayout.CENTER);
        return bar;
    }

    private void refreshQuickPresetCombo()
    {
        if (quickPresetCombo == null) return;
        suppressComboEvent = true;
        Object current = quickPresetCombo.getSelectedItem();
        quickPresetCombo.removeAllItems();
        if (loadoutManager != null)
        {
            for (Loadout l : loadoutManager.getAll())
                quickPresetCombo.addItem(l);
        }
        // restore selection if still present
        if (current instanceof Loadout)
        {
            for (int i = 0; i < quickPresetCombo.getItemCount(); i++)
            {
                if (quickPresetCombo.getItemAt(i).getName().equalsIgnoreCase(((Loadout) current).getName()))
                {
                    quickPresetCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        suppressComboEvent = false;
    }

    // Updated Presets tab to simpler UI
    private JPanel buildPresetsPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout(4,4));

        // Heading for clarity
        JLabel heading = new JLabel("Saved Loadouts");
        heading.setBorder(new EmptyBorder(6,8,2,8));
        heading.setFont((runescapeBold != null ? runescapeBold : fallback).deriveFont(Font.BOLD, TITLE_FONT_SIZE));
        panel.add(heading, BorderLayout.NORTH);

        presetsModel = new DefaultListModel<>();
        presetsList = new JList<>(presetsModel);
        presetsList.setVisibleRowCount(12);
        Font listFont = (runescape != null ? runescape : fallback).deriveFont(Font.PLAIN, 15f);
        presetsList.setFont(listFont);
        presetsList.setFixedCellHeight(24);
        presetsList.setCellRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                l.setFont(listFont);
                if (value instanceof Loadout)
                    l.setText(((Loadout) value).getName());
                l.setBorder(BorderFactory.createEmptyBorder(2,6,2,6));
                return l;
            }
        });
        presetsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        presetsList.addMouseListener(new java.awt.event.MouseAdapter(){
            @Override public void mouseClicked(java.awt.event.MouseEvent e){ if (e.getClickCount()==2) loadSelectedPreset(); }
            @Override public void mousePressed(java.awt.event.MouseEvent e){ maybeShowPresetPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){ maybeShowPresetPopup(e); }
        });
        JScrollPane listScroll = new JScrollPane(presetsList);
        listScroll.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
        panel.add(listScroll, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER,6,6));
        buttonPanel.setOpaque(false);
        Font btnFont = (runescape != null ? runescape : fallback).deriveFont(Font.PLAIN, 14f);
        JButton loadButton = new JButton("Load");
        JButton renameButton = new JButton("Rename");
        JButton deleteButton = new JButton("Delete");
        for (JButton b : new JButton[]{loadButton, renameButton, deleteButton})
        {
            b.setFont(btnFont); b.setFocusPainted(false);
        }
        loadButton.addActionListener(e -> loadSelectedPreset());
        renameButton.addActionListener(e -> renameSelectedPreset());
        deleteButton.addActionListener(e -> deleteSelectedPreset());
        buttonPanel.add(loadButton); buttonPanel.add(renameButton); buttonPanel.add(deleteButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        refreshPresetList();
        return panel;
    }

    // NEW helpers for presets
    private void refreshPresetList()
    {
        if (presetsModel == null) return;
        presetsModel.clear();
        if (loadoutManager != null)
            for (Loadout l : loadoutManager.getAll()) presetsModel.addElement(l);
    }

    private void saveCurrentLoadoutInteractively()
    {
        if (currentLoadedLoadout != null)
        {
            // Offer overwrite of existing without typing name again
            int choice = JOptionPane.showConfirmDialog(this,
                    "Overwrite loadout '" + currentLoadedLoadout.getName() + "'?",
                    "Overwrite Loadout",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == JOptionPane.YES_OPTION)
            {
                Loadout snap = snapshot(currentLoadedLoadout.getName());
                copyInto(currentLoadedLoadout, snap);
                loadoutManager.update();
                refreshPresetList();
                refreshQuickPresetCombo();
                return;
            }
            // If NO selected -> proceed to Save As dialog
        }
        // Save As / New workflow
        String defaultName = suggestDefaultName();
        String name = (String) JOptionPane.showInputDialog(this,
                "Loadout name:",
                "Save Loadout",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                defaultName);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Name cannot be empty.", "Save Loadout", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Loadout existing = findLoadoutByName(name);
        if (existing != null && existing != currentLoadedLoadout)
        {
            int res = JOptionPane.showConfirmDialog(this, "Overwrite existing loadout '"+name+"'?", "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
            if (res != JOptionPane.YES_OPTION) return;
        }
        Loadout snap = snapshot(name);
        if (existing != null)
        {
            copyInto(existing, snap);
            loadoutManager.update();
            currentLoadedLoadout = existing;
        }
        else
        {
            loadoutManager.add(snap);
            currentLoadedLoadout = findLoadoutByName(name);
        }
        refreshPresetList();
        refreshQuickPresetCombo();
        // Select in combo
        if (quickPresetCombo != null && currentLoadedLoadout != null)
        {
            suppressComboEvent = true;
            quickPresetCombo.setSelectedItem(currentLoadedLoadout);
            suppressComboEvent = false;
        }
    }

    private Loadout snapshot(String name)
    {
        int eqCount = EquipmentInventorySlot.values().length;
        Loadout l = new Loadout(name, eqCount, inventorySlots.length);
        // equipment
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
        {
            int idx = slot.ordinal();
            LoadoutSlot s = equipmentSlots.get(slot);
            if (s != null && s.getItemId() > 0)
            {
                l.getEquipmentIds()[idx] = s.getItemId();
                l.getEquipmentQty()[idx] = Math.max(1, s.getQuantity());
            }
        }
        // inventory
        for (int i = 0; i < inventorySlots.length; i++)
        {
            LoadoutSlot s = inventorySlots[i];
            if (s.getItemId() > 0)
            {
                l.getInventoryIds()[i] = s.getItemId();
                l.getInventoryQty()[i] = Math.max(1, s.getQuantity());
            }
        }
        return l;
    }

    private void applyLoadout(Loadout l)
    {
        if (l == null) return;
        resetAll();
        // equipment
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
        {
            int idx = slot.ordinal();
            if (idx < l.getEquipmentIds().length)
            {
                int id = l.getEquipmentIds()[idx];
                int q = (idx < l.getEquipmentQty().length) ? l.getEquipmentQty()[idx] : 1;
                if (id > 0) equipmentSlots.get(slot).setItem(id, q);
            }
        }
        // inventory
        for (int i = 0; i < inventorySlots.length && i < l.getInventoryIds().length; i++)
        {
            int id = l.getInventoryIds()[i];
            int q = (i < l.getInventoryQty().length) ? l.getInventoryQty()[i] : 1;
            if (id > 0) inventorySlots[i].setItem(id, q);
        }
        currentLoadedLoadout = l; // track loaded preset
        // sync combo selection
        if (quickPresetCombo != null)
        {
            suppressComboEvent = true;
            for (int i = 0; i < quickPresetCombo.getItemCount(); i++)
            {
                if (quickPresetCombo.getItemAt(i).getName().equalsIgnoreCase(l.getName()))
                {
                    quickPresetCombo.setSelectedIndex(i);
                    break;
                }
            }
            suppressComboEvent = false;
        }
    }

    private void newLoadout()
    {
        // Reset slots
        resetAll();
        // Clear current reference and combo selection
        currentLoadedLoadout = null;
        if (quickPresetCombo != null)
        {
            suppressComboEvent = true;
            quickPresetCombo.setSelectedItem(null);
            quickPresetCombo.setSelectedIndex(-1);
            suppressComboEvent = false;
        }
    }

    private void loadSelectedPreset()
    {
        if (presetsList == null) return;
        Loadout sel = presetsList.getSelectedValue();
        if (sel == null) return;
        applyLoadout(sel);
        currentLoadedLoadout = sel;
        switchToBuilderTab();
        // removed load confirmation dialog
    }

    private void deleteSelectedPreset()
    {
        if (presetsList == null) return;
        Loadout sel = presetsList.getSelectedValue();
        if (sel == null) return;
        int res = JOptionPane.showConfirmDialog(this, "Delete loadout '"+sel.getName()+"'?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (res != JOptionPane.YES_OPTION) return;
        loadoutManager.remove(sel);
        refreshPresetList();
        refreshQuickPresetCombo();
    }

    private void renameSelectedPreset()
    {
        if (presetsList == null) return;
        Loadout sel = presetsList.getSelectedValue();
        if (sel == null) return;
        String newName = JOptionPane.showInputDialog(this, "New name:", sel.getName());
        if (newName == null) return;
        newName = newName.trim();
        if (newName.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Name cannot be empty.", "Rename", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Loadout conflict = findLoadoutByName(newName);
        if (conflict != null && conflict != sel)
        {
            JOptionPane.showMessageDialog(this, "A loadout with that name already exists.", "Rename", JOptionPane.WARNING_MESSAGE);
            return;
        }
        loadoutManager.rename(sel, newName);
        refreshPresetList();
        refreshQuickPresetCombo();
        currentLoadedLoadout = sel; // keep reference (renamed)
    }

    private void switchToBuilderTab()
    {
        if (tabbedPane != null)
            tabbedPane.setSelectedIndex(0);
    }

    private Loadout findLoadoutByName(String name)
    {
        if (loadoutManager == null) return null;
        for (Loadout l : loadoutManager.getAll())
            if (l.getName().equalsIgnoreCase(name)) return l;
        return null;
    }

    // Added: generate a sensible default name when saving a new loadout or doing "Save As".
    private String suggestDefaultName()
    {
        // If we are saving a copy of an existing loaded loadout, try to reuse its name or a numbered variant.
        if (currentLoadedLoadout != null)
        {
            String base = currentLoadedLoadout.getName();
            if (base != null && !base.isBlank())
            {
                // If the base name is free (because user chose Save As after declining overwrite), just use it.
                if (findLoadoutByName(base) == null) return base;
                // Otherwise append (2), (3), ... until free.
                for (int i = 2; i < 1000; i++)
                {
                    String candidate = base + " (" + i + ")";
                    if (findLoadoutByName(candidate) == null) return candidate;
                }
            }
        }
        // Fallback pattern: Loadout 1, Loadout 2, ...
        for (int i = 1; i < 1000; i++)
        {
            String candidate = "Loadout " + i;
            if (findLoadoutByName(candidate) == null) return candidate;
        }
        return "Loadout"; // absolute fallback
    }

    private void copyInto(Loadout target, Loadout source)
    {
        System.arraycopy(source.getEquipmentIds(), 0, target.getEquipmentIds(), 0, target.getEquipmentIds().length);
        System.arraycopy(source.getEquipmentQty(), 0, target.getEquipmentQty(), 0, target.getEquipmentQty().length);
        System.arraycopy(source.getInventoryIds(), 0, target.getInventoryIds(), 0, target.getInventoryIds().length);
        System.arraycopy(source.getInventoryQty(), 0, target.getInventoryQty(), 0, target.getInventoryQty().length);
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
        // Replace Clear all with New
        JButton newButton = makeButton("New");
        copyButton.addActionListener(e -> copyLoadout());
        newButton.addActionListener(e -> newLoadout());
        row.add(copyButton);
        row.add(newButton);
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
        repcalArea.setRows(9);
        repcalArea.setColumns(26);
        repcalArea.setTabSize(4);

        JScrollPane sp = new JScrollPane(repcalArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createLineBorder(new Color(70,70,70)));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(sp);
        section.add(Box.createVerticalStrut(4));

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

        // Embedded save button (keeps original vertical flow)
        section.add(Box.createVerticalStrut(4));
        JButton saveBtn = makeButton("Save loadout");
        saveBtn.addActionListener(e -> saveCurrentLoadoutInteractively());
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(saveBtn);

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
            // Inventory
            LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
            // iterate current panel inventory slots (not a stored loadout)
            for (LoadoutSlot ls : inventorySlots)
            {
                if (ls.getItemId() <= 0) continue;
                int id = ls.getItemId();
                String rawName = "item_" + id;
                boolean stackable = false;
                try
                {
                    ItemComposition comp = itemManager.getItemComposition(id);
                    rawName = sanitizeItemName(comp.getName());
                    stackable = comp.isStackable() || comp.getNote() != -1;
                }
                catch (Exception ignored) {}
                int qty = Math.max(1, ls.getQuantity());
                int add = stackable ? qty : 1;
                invCounts.merge(rawName, add, Integer::sum);
            }
            for (Map.Entry<String,Integer> e : invCounts.entrySet())
            {
                sb.append("WITHDRAW ").append(kittyKeysItemName(e.getKey())).append(" ").append(e.getValue()).append('\n');
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
        return sanitizeItemName(n);
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

    private String buildRepcalForLoadout(Loadout loadout)
    {
        if (loadout == null) return "";
        StringBuilder sb = new StringBuilder();
        // Order copied from generateRepcalString
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
        int[] eqIds = loadout.getEquipmentIds();
        int[] eqQty = loadout.getEquipmentQty();
        for (EquipmentInventorySlot slot : order)
        {
            int idx = slot.ordinal();
            if (idx >= eqIds.length) continue;
            int id = eqIds[idx];
            if (id <= 0) continue;
            int qty = (idx < eqQty.length && eqQty[idx] > 0) ? eqQty[idx] : 1;
            String name = "Item " + id;
            try { name = sanitizeItemName(itemManager.getItemComposition(id).getName()); }
            catch (Exception ignored) {}
            boolean stackable = false;
            try
            {
                ItemComposition comp = itemManager.getItemComposition(id);
                stackable = comp.isStackable() || comp.getNote() != -1;
            }
            catch (Exception ignored) {}
            String outQty;
            if ((slot == EquipmentInventorySlot.WEAPON || slot == EquipmentInventorySlot.AMMO) && stackable && qty > 1)
                outQty = "*"; // wildcard semantics kept
            else
                outQty = Integer.toString(Math.max(1, qty));
            sb.append(equipmentCode(slot)).append(":").append(name).append(":").append(outQty).append('\n');
        }
        // Inventory aggregation
        LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
        int[] invIds = loadout.getInventoryIds();
        int[] invQty = loadout.getInventoryQty();
        for (int i = 0; i < invIds.length; i++)
        {
            int id = invIds[i];
            if (id <= 0) continue;
            String name = "Item " + id;
            boolean stackable = false;
            try
            {
                ItemComposition comp = itemManager.getItemComposition(id);
                name = sanitizeItemName(comp.getName());
                stackable = comp.isStackable() || comp.getNote() != -1;
            }
            catch (Exception ignored) {}
            int qty = (i < invQty.length && invQty[i] > 0) ? invQty[i] : 1;
            int add = stackable ? qty : 1;
            invCounts.merge(name, add, Integer::sum);
        }
        for (Map.Entry<String,Integer> e : invCounts.entrySet())
            sb.append("I:").append(e.getKey()).append(":").append(e.getValue()).append('\n');
        return sb.toString().trim();
    }

    private String buildKittyKeysForLoadout(Loadout loadout, int tickCount)
    {
        if (loadout == null) return "";
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
        int[] eqIds = loadout.getEquipmentIds();
        int[] eqQty = loadout.getEquipmentQty();
        LinkedHashMap<EquipmentInventorySlot,String> eqNames = new LinkedHashMap<>();
        for (EquipmentInventorySlot slot : order)
        {
            int idx = slot.ordinal();
            if (idx >= eqIds.length) continue;
            int id = eqIds[idx];
            if (id <= 0) continue;
            int qty = (idx < eqQty.length && eqQty[idx] > 0) ? eqQty[idx] : 1;
            String name = "item_" + id;
            try { name = kittyKeysItemName(sanitizeItemName(itemManager.getItemComposition(id).getName())); }
            catch (Exception ignored) {}
            eqNames.put(slot, name);
            sb.append("WITHDRAW ").append(name).append(" ").append(Math.max(1, qty)).append('\n');
        }
        if (!eqNames.isEmpty())
        {
            if (tickCount > 0) appendTicksBlock(sb, tickCount); else sb.append('\n');
        }
        for (String nm : eqNames.values())
            sb.append("BANK_WIELD ").append(nm).append('\n');
        if (!eqNames.isEmpty())
        {
            if (tickCount > 0) appendTicksBlock(sb, tickCount); else sb.append('\n');
        }
        // Inventory
        LinkedHashMap<String,Integer> invCounts = new LinkedHashMap<>();
        // iterate current panel inventory slots (not a stored loadout)
        for (LoadoutSlot ls : inventorySlots)
        {
            if (ls.getItemId() <= 0) continue;
            int id = ls.getItemId();
            String rawName = "item_" + id;
            boolean stackable = false;
            try
            {
                ItemComposition comp = itemManager.getItemComposition(id);
                rawName = sanitizeItemName(comp.getName());
                stackable = comp.isStackable() || comp.getNote() != -1;
            }
            catch (Exception ignored) {}
            int qty = Math.max(1, ls.getQuantity());
            int add = stackable ? qty : 1;
            invCounts.merge(rawName, add, Integer::sum);
        }
        for (Map.Entry<String,Integer> e : invCounts.entrySet())
        {
            sb.append("WITHDRAW ").append(kittyKeysItemName(e.getKey())).append(" ").append(e.getValue()).append('\n');
        }
        return sb.toString().trim();
    }

    private void maybeShowPresetPopup(java.awt.event.MouseEvent e)
    {
        if (!e.isPopupTrigger()) return;
        int idx = presetsList.locationToIndex(e.getPoint());
        if (idx >= 0) presetsList.setSelectedIndex(idx);
        Loadout sel = presetsList.getSelectedValue();
        if (sel == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem sendRepcal = new JMenuItem("Send Repcal Code to Webhook");
        JMenuItem sendKitty = new JMenuItem("Send KittyKeys Code to Webhook");
        sendRepcal.addActionListener(ev -> sendLoadoutToWebhook(sel, true));
        sendKitty.addActionListener(ev -> sendLoadoutToWebhook(sel, false));
        menu.add(sendRepcal);
        menu.add(sendKitty);
        menu.show(presetsList, e.getX(), e.getY());
    }

    private void sendLoadoutToWebhook(Loadout loadout, boolean repcal)
    {
        String webhook = (config != null) ? config.discordWebhook() : "";
        if (webhook == null || webhook.trim().isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Discord webhook not set in config.", "Webhook", JOptionPane.WARNING_MESSAGE);
            return;
        }
        clientThread.invokeLater(() -> {
            String title = loadout.getName();
            String body = repcal ? buildRepcalForLoadout(loadout) : buildKittyKeysForLoadout(loadout, 4);
            String payloadText = title + "\n```\n" + body + "\n```";
            postDiscordWebhookAsync(webhook.trim(), payloadText, success -> SwingUtilities.invokeLater(() -> {
                if (success)
                    JOptionPane.showMessageDialog(this, "Webhook sendt.", "Webhook", JOptionPane.INFORMATION_MESSAGE);
                else
                    JOptionPane.showMessageDialog(this, "Webhook feilet (se log).", "Webhook", JOptionPane.ERROR_MESSAGE);
            }));
        });
    }

    private void postDiscordWebhookAsync(String url, String content)
    {
        postDiscordWebhookAsync(url, content, null);
    }

    private void postDiscordWebhookAsync(String url, String content, Consumer<Boolean> callback)
    {
        String trimmed = content;
        if (trimmed.length() > 1900)
            trimmed = trimmed.substring(0, 1900) + "...";
        final String jsonPayload = "{\"content\":\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        new Thread(() -> {
            boolean ok = false;
            try
            {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) { os.write(jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                ok = code >= 200 && code < 300;
                if (!ok) System.err.println("Discord webhook failed: HTTP " + code);
            }
            catch (Exception ex)
            {
                System.err.println("Discord webhook error: " + ex.getMessage());
            }
            if (callback != null) callback.accept(ok);
        }, "WebhookSender").start();
    }

    private static final String MEMBERS_SUFFIX_REGEX = "(?i) \\((members)\\)$"; // strip trailing (members)
    private static String sanitizeItemName(String s)
    {
        if (s == null) return "";
        String cleaned = s.replaceAll(MEMBERS_SUFFIX_REGEX, "").trim();
        // Some item names can be the literal string "null" in cache; normalize to empty
        if (cleaned.equalsIgnoreCase("null")) return "";
        return cleaned;
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

    // ===== SlotActionHandler implementation & inventory helpers =====
    @Override
    public void requestItemInfoOnClientThread(LoadoutSlot slot, int itemId)
    {
        clientThread.invoke(() -> {
            try
            {
                ItemComposition comp = itemManager.getItemComposition(itemId);
                String name = sanitizeItemName(comp.getName());
                boolean stackable = comp.isStackable() || comp.getNote() != -1;
                BufferedImage icon = itemManager.getImage(itemId);
                SwingUtilities.invokeLater(() -> slot.setResolvedItemInfo(name, icon, stackable));
            }
            catch (Exception ex)
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
            if (s.getItemId() == itemId) existing.add(s); else if (s.getItemId() <= 0) empty.add(s);
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
        if (source.isEquipment() || target.isEquipment()) return; // prevent equipment drag
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

    public LoadoutSlot[] getInventorySlots()
    {
        return inventorySlots;
    }

    public static LoadoutBuilderPanel findPanel(Component c)
    {
        while (c != null && !(c instanceof LoadoutBuilderPanel)) c = c.getParent();
        return (LoadoutBuilderPanel) c;
    }

    // ================= Added Repcal parsing & item resolution helpers =================
    private static class RepcalLine
    {
        final String code; // equipment code or 'I'
        final String name; // item name or id
        final int quantity; // >=1
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
        if (text == null) return out;
        String cleaned = text
                .replace("```", "") // strip potential code fences
                .replace('\r', '\n');
        String[] lines = cleaned.split("\n+");
        for (String raw : lines)
        {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Accept comments starting with # or //
            if (line.startsWith("#") || line.startsWith("//")) continue;
            String[] parts = line.split(":");
            if (parts.length < 2) continue; // need at least code:name
            String code = parts[0].trim();
            String name = parts[1].trim();
            if (code.isEmpty() || name.isEmpty()) continue;
            int qty = 1;
            if (parts.length >= 3)
            {
                String qtok = parts[2].trim();
                if (qtok.equals("*")) // wildcard => treat as 1 (user can edit later)
                    qty = 1;
                else
                {
                    try { qty = Integer.parseInt(qtok); } catch (NumberFormatException ignored) { qty = 1; }
                }
            }
            if (qty <= 0) qty = 1;
            out.add(new RepcalLine(code, name, qty));
        }
        return out;
    }

    private Map<String, EquipmentInventorySlot> codeToSlotMap()
    {
        Map<String, EquipmentInventorySlot> m = new HashMap<>();
        m.put("H", EquipmentInventorySlot.HEAD);
        m.put("Ca", EquipmentInventorySlot.CAPE);
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

    private static final Map<String,Integer> NAME_CACHE = new HashMap<>();
    private static volatile boolean NAME_CACHE_BUILT = false;

    private synchronized void buildNameCache()
    {
        if (NAME_CACHE_BUILT) return;
        // Heuristic upper bound – RuneLite item IDs currently < 60k.
        final int MAX_ID = 60000;
        for (int id = 0; id <= MAX_ID; id++)
        {
            try
            {
                ItemComposition comp = itemManager.getItemComposition(id);
                if (comp == null) continue;
                String nm = sanitizeItemName(comp.getName());
                if (nm.isEmpty() || nm.equalsIgnoreCase("null")) continue;
                NAME_CACHE.putIfAbsent(nm.toLowerCase(Locale.ROOT), id);
            }
            catch (Exception ignored) {}
        }
        NAME_CACHE_BUILT = true;
    }

    private int resolveItemId(String rawName, List<String> errors)
    {
        if (rawName == null) return -1;
        String name = rawName.trim();
        if (name.isEmpty()) return -1;
        // Direct numeric id
        try { return Integer.parseInt(name); } catch (NumberFormatException ignored) {}

        String sanitized = sanitizeItemName(name).toLowerCase(Locale.ROOT);

        // Try reflective search on ItemManager (RuneLite internal) if available
        try
        {
            Method m = itemManager.getClass().getDeclaredMethod("search", String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) m.invoke(itemManager, sanitized);
            if (ids != null)
            {
                for (Integer id : ids)
                {
                    if (id == null || id <= 0) continue;
                    try
                    {
                        ItemComposition comp = itemManager.getItemComposition(id);
                        if (comp == null) continue;
                        String nm = sanitizeItemName(comp.getName());
                        if (nm.equalsIgnoreCase(name) || nm.equalsIgnoreCase(sanitized)) return id;
                        // Accept contains match if unique
                        if (nm.equalsIgnoreCase(name)) return id;
                    }
                    catch (Exception ignored) {}
                }
                if (ids.size() == 1 && ids.get(0) != null && ids.get(0) > 0) return ids.get(0);
            }
        }
        catch (Exception ignored) {}

        // Fallback: build cache and lookup exact name
        buildNameCache();
        Integer id = NAME_CACHE.get(sanitized);
        if (id != null) return id;

        // Fuzzy: try linear scan over cache for contains (first match)
        for (Map.Entry<String,Integer> e : NAME_CACHE.entrySet())
        {
            if (e.getKey().contains(sanitized)) return e.getValue();
        }

        if (errors != null)
            errors.add("Item not found: " + rawName);
        return -1;
    }
    // ================= End added helpers =================
}

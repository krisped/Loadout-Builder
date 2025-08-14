package com.krisped;

import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Item search dialog with optional category/type filters (none pre-enabled).
 * Supports:
 *  - Name or ID search (press Enter).
 *  - Sorting by name or ID.
 *  - In‑memory filtering (no re-query on toggle).
 *  - Wearable / Consumable detection from actions + simple heuristics.
 */
public class ItemSearchDialog extends JDialog
{
    private static final float LIST_MAIN_FONT_SIZE = 16f;
    private static final float LIST_META_FONT_SIZE = 15f;
    private static final float DETAIL_FONT_SIZE    = 16f;
    private static final String FONT_FAMILY        = "SansSerif";

    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final JTextField searchField = new JTextField();
    private final JButton filterButton   = new JButton("Filters ▾");
    private final JComboBox<SortMode> sortCombo = new JComboBox<>(SortMode.values());
    private JPopupMenu filterMenu;

    private final DefaultListModel<Result> listModel = new DefaultListModel<>();
    private final JList<Result> resultList = new JList<>(listModel);
    private final JButton okBtn      = new JButton("Select");
    private final JButton cancelBtn  = new JButton("Cancel");
    private final JLabel statusLabel = new JLabel(" ");
    private final AATextArea detailArea = new AATextArea();
    private final JLabel largeIconLabel = new JLabel();

    private int selectedItemId = -1;

    private volatile List<Integer> allItemIdsCache = null;
    private volatile boolean buildingIndex = false;
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private int hoverIndex = -1;

    private final EnumSet<FilterFlag> activeFilters = EnumSet.noneOf(FilterFlag.class);
    private final Map<FilterFlag, JCheckBoxMenuItem> filterItems = new EnumMap<>(FilterFlag.class);

    private List<Result> originalResults = Collections.emptyList();
    private String lastSearch = "";

    public static int showDialog(Component parent, ItemManager itemManager, ClientThread clientThread)
    {
        Frame f = JOptionPane.getFrameForComponent(parent);
        ItemSearchDialog d = new ItemSearchDialog(f, itemManager, clientThread);
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return d.selectedItemId;
    }

    private ItemSearchDialog(Frame owner, ItemManager itemManager, ClientThread clientThread)
    {
        super(owner, "Item Search", true);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        buildUI();
        buildFilterMenu();
        attach();
        sortCombo.setSelectedItem(SortMode.ID_ASC);
        setPreferredSize(new Dimension(690, 700));
        pack();
        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
    }

    /* ================= UI ================= */

    private void buildUI()
    {
        setLayout(new BorderLayout(8,8));
        JPanel top = new JPanel(new BorderLayout(6,4));
        top.setBorder(BorderFactory.createEmptyBorder(8,8,0,8));

        JLabel lbl = new JLabel("Search by name or ID and press Enter (e.g. Dragon scimitar)");
        lbl.setFont(fontPlain(LIST_MAIN_FONT_SIZE));
        top.add(lbl, BorderLayout.NORTH);

        JPanel searchLine = new JPanel(new BorderLayout(6,0));
        searchField.setFont(fontPlain(15));
        searchField.setMargin(new Insets(3,6,3,6));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        filterButton.setFont(fontPlain(14));
        filterButton.setFocusable(false);
        sortCombo.setFont(fontPlain(14));
        sortCombo.setFocusable(false);
        right.add(filterButton);
        right.add(sortCombo);

        searchLine.add(searchField, BorderLayout.CENTER);
        searchLine.add(right, BorderLayout.EAST);
        top.add(searchLine, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.55);

        resultList.setCellRenderer(new Renderer());
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(resultList);
        listScroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        split.setLeftComponent(listScroll);

        JPanel detailPanel = new JPanel(new BorderLayout(6,6));
        detailPanel.setBorder(BorderFactory.createTitledBorder("Details"));

        JPanel iconPanel = new JPanel(new BorderLayout());
        largeIconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        largeIconLabel.setPreferredSize(new Dimension(72,72));
        iconPanel.add(largeIconLabel, BorderLayout.NORTH);
        detailPanel.add(iconPanel, BorderLayout.NORTH);

        detailArea.setEditable(false);
        detailArea.setFont(fontPlain(DETAIL_FONT_SIZE));
        detailArea.setBackground(UIManager.getColor("Panel.background"));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailPanel.add(detailScroll, BorderLayout.CENTER);

        split.setRightComponent(detailPanel);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0,8,8,8));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okBtn.setFont(fontPlain(LIST_MAIN_FONT_SIZE));
        cancelBtn.setFont(fontPlain(LIST_MAIN_FONT_SIZE));
        statusLabel.setFont(fontPlain(LIST_META_FONT_SIZE));
        okBtn.setEnabled(false);
        btns.add(okBtn);
        btns.add(cancelBtn);
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btns, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private void buildFilterMenu()
    {
        filterMenu = new JPopupMenu();
        // Updated labels with action hints
        addFilterItem(FilterFlag.WEARABLE,     "Wearable (Wear/Wield/Equip)", "Items with Wear / Wield / Equip action");
        addFilterItem(FilterFlag.CONSUMABLE,   "Consumable (Eat/Drink/Sip)",  "Items you can Eat / Drink / Sip / Consume");
        addFilterItem(FilterFlag.STACKABLE,    "Stackable",                   "Items that stack (incl. noted)");
        addFilterItem(FilterFlag.MEMBERS,      "Members",                     "Members-only items");
        addFilterItem(FilterFlag.NOTED,        "Noted",                       "Noted variants");
        addFilterItem(FilterFlag.PLACEHOLDER,  "Placeholder",                 "Placeholder variants");
        addFilterItem(FilterFlag.NORMAL,       "Normal",                      "Neither noted nor placeholder");
        filterButton.addActionListener(e -> filterMenu.show(filterButton, 0, filterButton.getHeight()));
        updateFilterButtonText();
    }

    private void addFilterItem(FilterFlag flag, String label, String tooltip)
    {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(label);
        mi.setToolTipText(tooltip);
        mi.addActionListener(e -> {
            if (mi.isSelected()) activeFilters.add(flag); else activeFilters.remove(flag);
            updateFilterButtonText();
            applyFiltersToCurrentList();
        });
        filterItems.put(flag, mi);
        filterMenu.add(mi);
    }

    private void updateFilterButtonText()
    {
        int n = activeFilters.size();
        filterButton.setText(n == 0 ? "Filters ▾" : "Filters (" + n + ") ▾");
        filterButton.setToolTipText(n == 0 ? "No filters active" : "Active: " + activeFilters);
    }

    /* ================= Events ================= */

    private void attach()
    {
        searchField.addKeyListener(new KeyAdapter()
        {
            @Override public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) startSearch();
            }
        });

        resultList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Result r = resultList.getSelectedValue();
            okBtn.setEnabled(r != null && r.itemId > 0);
            updateDetails(r);
        });

        resultList.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2) accept();
            }
            @Override public void mouseExited(MouseEvent e)
            {
                if (hoverIndex != -1)
                {
                    hoverIndex = -1;
                    resultList.repaint();
                }
            }
        });

        resultList.addMouseMotionListener(new MouseMotionAdapter()
        {
            @Override public void mouseMoved(MouseEvent e)
            {
                int idx = resultList.locationToIndex(e.getPoint());
                if (idx != hoverIndex)
                {
                    hoverIndex = idx;
                    resultList.repaint();
                }
            }
        });

        sortCombo.addActionListener(e -> resortCurrentResults());
        okBtn.addActionListener(e -> accept());
        cancelBtn.addActionListener(e -> { selectedItemId = -1; dispose(); });

        getRootPane().setDefaultButton(okBtn);
    }

    private void accept()
    {
        Result r = resultList.getSelectedValue();
        if (r != null && r.itemId > 0)
        {
            selectedItemId = r.itemId;
            dispose();
        }
    }

    /* ================= Search & Filtering ================= */

    private void startSearch()
    {
        final String raw = searchField.getText().trim();
        lastSearch = raw;
        listModel.clear();
        okBtn.setEnabled(false);
        updateDetails(null);

        if (raw.isEmpty())
        {
            originalResults = Collections.emptyList();
            status("Empty search.");
            return;
        }

        final int gen = searchGeneration.incrementAndGet();
        status("Searching...");
        listModel.addElement(Result.placeholder("(Searching...)"));

        clientThread.invoke(() -> {
            List<Result> results;
            try { results = performSearchOnClientThread(raw); }
            catch (Exception ex)
            {
                results = Collections.singletonList(Result.placeholder("Error: " + ex.getMessage()));
            }
            final List<Result> publish = results;
            SwingUtilities.invokeLater(() -> {
                if (gen != searchGeneration.get()) return;
                originalResults = publish;
                applyFiltersToCurrentList();
            });
        });
    }

    private void applyFiltersToCurrentList()
    {
        listModel.clear();
        List<Result> source = originalResults;
        if (source.isEmpty())
        {
            listModel.addElement(Result.placeholder("(No results)"));
            status("0 result(s)");
            okBtn.setEnabled(false);
            return;
        }
        List<Result> filtered;
        if (activeFilters.isEmpty()) filtered = source;
        else
        {
            filtered = new ArrayList<>();
            for (Result r : source)
                if (r.itemId > 0 && passesFilters(r)) filtered.add(r);
        }
        if (filtered.isEmpty())
        {
            listModel.addElement(Result.placeholder("(No results match filters)"));
            status("0 result(s)");
            okBtn.setEnabled(false);
            return;
        }
        for (Result r : filtered) listModel.addElement(r);
        status(filtered.size() + " result(s)");
        resultList.setSelectedIndex(0);
    }

    private boolean passesFilters(Result r)
    {
        for (FilterFlag f : activeFilters)
        {
            switch (f)
            {
                case WEARABLE: if (!r.wearable) return false; break;
                case CONSUMABLE: if (!r.consumable) return false; break;
                case STACKABLE: if (!r.stackable) return false; break;
                case MEMBERS: if (!r.members) return false; break;
                case NOTED: if (!r.noted) return false; break;
                case PLACEHOLDER: if (!r.placeholder) return false; break;
                case NORMAL: if (!r.normal) return false; break;
            }
        }
        return true;
    }

    private List<Result> performSearchOnClientThread(String raw)
    {
        String[] tokens = Arrays.stream(raw.toLowerCase().split("\\s+"))
                .map(t -> t.replace("*","").trim())
                .filter(t -> !t.isEmpty())
                .toArray(String[]::new);

        Set<Integer> ids = new LinkedHashSet<>();

        if (tokens.length == 1)
        {
            try { ids.add(Integer.parseInt(tokens[0])); }
            catch (NumberFormatException ignored) {}
        }

        ids.addAll(invokeItemManagerSearch(String.join(" ", tokens)));
        ensureIndex();

        if (allItemIdsCache != null)
        {
            for (Integer id : allItemIdsCache)
            {
                if (id == null || id <= 0) continue;
                try
                {
                    ItemComposition comp = itemManager.getItemComposition(id);
                    if (comp == null) continue;
                    String name = comp.getName();
                    if (name == null || name.equalsIgnoreCase("null")) continue;
                    String lname = name.toLowerCase();
                    boolean all = true;
                    for (String t : tokens)
                        if (!lname.contains(t)) { all = false; break; }
                    if (all)
                    {
                        ids.add(id);
                        if (ids.size() > 900) break;
                    }
                }
                catch (Exception ignored){}
            }
        }

        List<Result> out = new ArrayList<>();
        for (Integer id : ids)
        {
            if (id == null || id <= 0) continue;
            try
            {
                ItemComposition comp = itemManager.getItemComposition(id);
                if (comp == null) continue;
                String name = comp.getName();
                if (name == null || name.equalsIgnoreCase("null") || name.isEmpty()) continue;

                boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
                boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
                boolean normal = !(placeholder || noted);
                boolean stackable = comp.isStackable() || noted;
                boolean members = comp.isMembers();

                BufferedImage img = itemManager.getImage(id);
                List<String> actions = extractActions(comp);

                boolean wearable = isWearable(actions);
                boolean consumable = isConsumable(actions, name);

                String listDisplay = name + " (" + id + ")";
                out.add(new Result(id, name, listDisplay, img,
                        stackable, noted, placeholder, normal, members, wearable, consumable, actions));
            }
            catch (Exception ignored){}
            if (out.size() >= 900) break;
        }

        sortResults(out);
        return out;
    }

    private boolean isWearable(List<String> actions)
    {
        for (String a : actions)
        {
            if (a == null) continue;
            String x = a.toLowerCase();
            if (x.contains("wear") || x.contains("wield") || x.contains("equip")) return true;
        }
        return false;
    }

    private boolean isConsumable(List<String> actions, String name)
    {
        String lowerName = name.toLowerCase();
        for (String a : actions)
        {
            if (a == null) continue;
            String x = a.toLowerCase();
            if (x.contains("eat") || x.contains("drink") || x.contains("sip") || x.contains("consume")) return true;
        }
        return lowerName.contains("potion") ||
                lowerName.contains("dose") ||
                lowerName.contains("cake") ||
                lowerName.contains("pie") ||
                lowerName.contains("brew") ||
                lowerName.contains("tea") ||
                lowerName.contains("wine") ||
                lowerName.contains("food") ||
                lowerName.contains("shark") ||
                lowerName.contains("lobster");
    }

    private void sortResults(List<Result> list)
    {
        SortMode mode = (SortMode) sortCombo.getSelectedItem();
        if (mode == null) mode = SortMode.ID_ASC;
        Comparator<Result> cmp;
        switch (mode)
        {
            case NAME_DESC: cmp = Comparator.comparing((Result r) -> r.itemName.toLowerCase()).reversed(); break;
            case ID_ASC:    cmp = Comparator.comparingInt(r -> r.itemId); break;
            case ID_DESC:   cmp = Comparator.comparingInt((Result r) -> r.itemId).reversed(); break;
            case NAME_ASC:
            default:        cmp = Comparator.comparing((Result r) -> r.itemName.toLowerCase()); break;
        }
        list.sort(cmp);
    }

    private void resortCurrentResults()
    {
        if (originalResults.isEmpty()) return;
        sortResults(originalResults);
        applyFiltersToCurrentList();
    }

    /* ================= Helpers ================= */

    private List<Integer> invokeItemManagerSearch(String query)
    {
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
                ItemComposition comp = itemManager.getItemComposition(id);
                if (comp != null)
                {
                    String nm = comp.getName();
                    if (nm != null && !nm.equalsIgnoreCase("null"))
                        tmp.add(id);
                }
            }
            catch (Exception ignored){}
        }
        allItemIdsCache = tmp;
        buildingIndex = false;
    }

    private List<String> extractActions(ItemComposition comp)
    {
        List<String> act = new ArrayList<>();
        String[] inv = comp.getInventoryActions();
        if (inv != null)
        {
            for (String a : inv)
                if (a != null && !a.equalsIgnoreCase("null") && !a.trim().isEmpty())
                    act.add(a);
        }
        if (!act.contains("Examine")) act.add("Examine");
        return act;
    }

    private void updateDetails(Result r)
    {
        if (r == null || r.itemId <= 0)
        {
            detailArea.setText("");
            largeIconLabel.setIcon(null);
            return;
        }

        if (r.icon != null)
        {
            Image scaled = r.icon.getScaledInstance(56, 56, Image.SCALE_FAST);
            largeIconLabel.setIcon(new ImageIcon(scaled));
        }
        else
        {
            largeIconLabel.setIcon(null);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(r.itemName).append('\n');
        sb.append("ID         : ").append(r.itemId).append('\n');
        sb.append("Normal     : ").append(r.normal ? "Yes" : "No").append('\n');
        sb.append("Noted      : ").append(r.noted ? "Yes" : "No").append('\n');
        sb.append("Placeholder: ").append(r.placeholder ? "Yes" : "No").append('\n');
        sb.append("Stackable  : ").append(r.stackable ? "Yes" : "No").append('\n');
        sb.append("Members    : ").append(r.members ? "Yes" : "No").append('\n');
        sb.append("Wearable   : ").append(r.wearable ? "Yes" : "No").append('\n');
        sb.append("Consumable : ").append(r.consumable ? "Yes" : "No").append('\n');
        sb.append("Actions    : ").append(r.actions.isEmpty() ? "(none)" : String.join(", ", r.actions));
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private void status(String s){ statusLabel.setText(s); }

    /* ================= Data ================= */

    private static class Result
    {
        final int itemId;
        final String itemName;
        final String listDisplay;
        final BufferedImage icon;
        final boolean stackable;
        final boolean noted;
        final boolean placeholder;
        final boolean normal;
        final boolean members;
        final boolean wearable;
        final boolean consumable;
        final List<String> actions;

        private Result(int itemId, String itemName, String listDisplay, BufferedImage icon,
                       boolean stackable, boolean noted, boolean placeholder,
                       boolean normal, boolean members,
                       boolean wearable, boolean consumable,
                       List<String> actions)
        {
            this.itemId = itemId;
            this.itemName = itemName != null ? itemName : listDisplay;
            this.listDisplay = listDisplay;
            this.icon = icon;
            this.stackable = stackable;
            this.noted = noted;
            this.placeholder = placeholder;
            this.normal = normal;
            this.members = members;
            this.wearable = wearable;
            this.consumable = consumable;
            this.actions = actions;
        }

        static Result placeholder(String text)
        {
            return new Result(-1, text, text, null,
                    false,false,false,false,false,false,false,
                    Collections.emptyList());
        }

        @Override public String toString(){ return listDisplay; }
    }

    private enum SortMode
    {
        NAME_ASC("Name A-Z"),
        NAME_DESC("Name Z-A"),
        ID_ASC("ID Low-High"),
        ID_DESC("ID High-Low");

        private final String label;
        SortMode(String label){ this.label = label; }
        @Override public String toString(){ return label; }
    }

    private enum FilterFlag
    {
        WEARABLE,
        CONSUMABLE,
        STACKABLE,
        MEMBERS,
        NOTED,
        PLACEHOLDER,
        NORMAL
    }

    /* ================= Rendering ================= */

    private class Renderer extends JPanel implements ListCellRenderer<Result>
    {
        private final JLabel iconLabel = new JLabel();
        private final JLabel textLabel = new JLabel();
        private final Font mainFont = fontPlain(LIST_MAIN_FONT_SIZE);

        Renderer()
        {
            setLayout(new BorderLayout(6,0));
            setOpaque(true);
            iconLabel.setPreferredSize(new Dimension(32,32));
            textLabel.setFont(mainFont);
            add(iconLabel, BorderLayout.WEST);
            add(textLabel, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(4,6,4,12));
        }

        @Override protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            super.paintComponent(g2);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Result> list, Result value,
                                                      int index, boolean isSelected, boolean cellHasFocus)
        {
            if (value == null)
            {
                textLabel.setText("");
                iconLabel.setIcon(null);
            }
            else
            {
                textLabel.setText(value.listDisplay);
                if (value.icon != null)
                {
                    Image scaled = value.icon.getScaledInstance(32, 32, Image.SCALE_FAST);
                    iconLabel.setIcon(new ImageIcon(scaled));
                }
                else iconLabel.setIcon(null);
            }

            Color baseBg = list.getBackground();
            if (isSelected)
            {
                setBackground(list.getSelectionBackground());
                textLabel.setForeground(list.getSelectionForeground());
            }
            else if (index == hoverIndex)
            {
                setBackground(deriveHoverColor(baseBg));
                textLabel.setForeground(list.getForeground());
            }
            else
            {
                setBackground(baseBg);
                textLabel.setForeground(list.getForeground());
            }
            return this;
        }

        private Color deriveHoverColor(Color base)
        {
            int r = Math.min(255, (int)(base.getRed()   * 1.08) + 10);
            int g = Math.min(255, (int)(base.getGreen() * 1.08) + 10);
            int b = Math.min(255, (int)(base.getBlue()  * 1.08) + 10);
            int avg = (base.getRed()+base.getGreen()+base.getBlue())/3;
            if (avg > 200)
            {
                r = Math.max(0, base.getRed() - 12);
                g = Math.max(0, base.getGreen() - 12);
                b = Math.max(0, base.getBlue() - 12);
            }
            return new Color(r,g,b);
        }
    }

    private static class AATextArea extends JTextArea
    {
        @Override protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            super.paintComponent(g2);
        }
    }

    private Font fontPlain(float size){ return new Font(FONT_FAMILY, Font.PLAIN, (int) size); }
}
package com.krisped;

import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ItemSearchDialog extends JDialog
{
    private static final float LIST_MAIN_FONT_SIZE = 16f;
    private static final float DETAIL_FONT_SIZE = 16f;
    private static final String FONT_FAMILY = "SansSerif";

    private final ItemManager itemManager;
    private final ClientThread clientThread;

    private final JTextField searchField = new JTextField();
    private final JComboBox<SortMode> sortCombo = new JComboBox<>(SortMode.values());
    private final JButton filterButton = new JButton("Filters ▾");
    private JPopupMenu filterMenu;

    private final DefaultListModel<Result> listModel = new DefaultListModel<>();
    private final JList<Result> resultList = new JList<>(listModel);
    private final JButton okBtn = new JButton("Select");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextArea detailArea = new JTextArea();
    private final JLabel largeIconLabel = new JLabel();

    private int selectedItemId = -1;

    private volatile List<Integer> allItemIdsCache = null;
    private volatile boolean buildingIndex = false;
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private int hoverIndex = -1;

    private final EnumSet<FilterFlag> activeFilters = EnumSet.noneOf(FilterFlag.class);
    private final Map<FilterFlag, JCheckBoxMenuItem> filterItems = new EnumMap<>(FilterFlag.class);

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
        attach();
        buildFilterMenu();
        sortCombo.setSelectedItem(SortMode.ID_ASC);
        setPreferredSize(new Dimension(740, 700));
        pack();
        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
    }

    /* ================= UI BUILD ================= */

    private void buildUI()
    {
        setLayout(new BorderLayout(8,8));

        JPanel top = new JPanel(new BorderLayout(6,4));
        top.setBorder(BorderFactory.createEmptyBorder(8,8,0,8));

        JLabel hint = new JLabel("Search name or ID (press Enter). Example: Dragon scimitar");
        hint.setFont(new Font(FONT_FAMILY, Font.PLAIN, (int) LIST_MAIN_FONT_SIZE));
        top.add(hint, BorderLayout.NORTH);

        JPanel searchLine = new JPanel(new BorderLayout(8,0));
        searchField.setFont(new Font(FONT_FAMILY, Font.PLAIN, 15));
        searchField.setMargin(new Insets(3,6,3,6));

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filterButton.setFont(new Font(FONT_FAMILY, Font.PLAIN, 14));
        filterButton.setFocusable(false);
        filterButton.setToolTipText("Open filters popup");
        JLabel sortLabel = new JLabel("Sort:");
        sortLabel.setFont(new Font(FONT_FAMILY, Font.PLAIN, 13));
        sortCombo.setFont(new Font(FONT_FAMILY, Font.PLAIN, 14));
        sortCombo.setFocusable(false);

        rightControls.add(filterButton);
        rightControls.add(sortLabel);
        rightControls.add(sortCombo);

        searchLine.add(searchField, BorderLayout.CENTER);
        searchLine.add(rightControls, BorderLayout.EAST);
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
        largeIconLabel.setPreferredSize(new Dimension(72, 72));
        iconPanel.add(largeIconLabel, BorderLayout.NORTH);
        detailPanel.add(iconPanel, BorderLayout.NORTH);

        detailArea.setEditable(false);
        detailArea.setFont(new Font(FONT_FAMILY, Font.PLAIN, (int) DETAIL_FONT_SIZE));
        detailArea.setOpaque(true);
        detailArea.setBackground(UIManager.getColor("Panel.background"));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        detailPanel.add(detailScroll, BorderLayout.CENTER);

        split.setRightComponent(detailPanel);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(0,8,8,8));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okBtn.setFont(new Font(FONT_FAMILY, Font.PLAIN, (int) LIST_MAIN_FONT_SIZE));
        cancelBtn.setFont(new Font(FONT_FAMILY, Font.PLAIN, (int) LIST_MAIN_FONT_SIZE));
        statusLabel.setFont(new Font(FONT_FAMILY, Font.PLAIN, 14));
        btns.add(okBtn);
        btns.add(cancelBtn);
        okBtn.setEnabled(false);
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btns, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private void buildFilterMenu()
    {
        filterMenu = new JPopupMenu();
        addFilterItem(FilterFlag.WEARABLE,    "Wearable (Wear/Wield/Equip)");
        addFilterItem(FilterFlag.STACKABLE,   "Stackable");
        addFilterItem(FilterFlag.MEMBERS,     "Members");
        addFilterItem(FilterFlag.NOTED,       "Noted");
        addFilterItem(FilterFlag.PLACEHOLDER, "Placeholder");
        addFilterItem(FilterFlag.NORMAL,      "Normal (not noted or placeholder)");
        filterMenu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear all filters");
        clear.addActionListener(e -> {
            activeFilters.clear();
            for (JCheckBoxMenuItem mi : filterItems.values()) mi.setSelected(false);
            updateFilterButtonText();
            autoRefreshAfterFilterChange();
        });
        filterMenu.add(clear);
    }

    private void addFilterItem(FilterFlag flag, String label)
    {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(label);
        mi.addActionListener(e -> {
            if (mi.isSelected()) activeFilters.add(flag);
            else activeFilters.remove(flag);
            updateFilterButtonText();
            autoRefreshAfterFilterChange();
        });
        filterItems.put(flag, mi);
        filterMenu.add(mi);
    }

    private void updateFilterButtonText()
    {
        int n = activeFilters.size();
        filterButton.setText(n == 0 ? "Filters ▾" : "Filters (" + n + ") ▾");
        filterButton.setToolTipText(n == 0 ? "No active filters" : "Active: " + activeFilters);
    }

    private void autoRefreshAfterFilterChange()
    {
        if (!searchField.getText().trim().isEmpty())
            startSearch();
    }

    /* ================= EVENT ATTACH ================= */

    private void attach()
    {
        searchField.addKeyListener(new KeyAdapter()
        {
            @Override public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) startSearch();
                else if (e.getKeyCode() == KeyEvent.VK_DOWN)
                {
                    if (!listModel.isEmpty()) resultList.requestFocusInWindow();
                }
            }
        });

        filterButton.addActionListener(e -> {
            if (filterMenu.isVisible()) filterMenu.setVisible(false);
            else filterMenu.show(filterButton, 0, filterButton.getHeight());
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
                hoverIndex = -1;
                resultList.repaint();
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
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
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

    /* ================= SEARCH ================= */

    private void startSearch()
    {
        final String raw = searchField.getText().trim();
        listModel.clear();
        okBtn.setEnabled(false);
        updateDetails(null);

        if (raw.isEmpty())
        {
            status("Empty search.");
            return;
        }

        final int gen = searchGeneration.incrementAndGet();
        status("Searching...");
        listModel.addElement(Result.searchingPlaceholder());

        clientThread.invoke(() -> {
            List<Result> results;
            try { results = performSearch(raw); }
            catch (Exception ex) { results = Collections.emptyList(); }
            final List<Result> publish = results;
            SwingUtilities.invokeLater(() -> {
                if (gen != searchGeneration.get()) return;
                listModel.clear();
                for (Result r : publish) listModel.addElement(r);
                status(publish.size() + " result(s)");
                if (!publish.isEmpty()) resultList.setSelectedIndex(0);
            });
        });
    }

    private List<Result> performSearch(String raw)
    {
        String[] tokens = Arrays.stream(raw.toLowerCase().split("\\s+"))
                .map(t -> t.replace("*","").trim())
                .filter(t -> !t.isEmpty())
                .toArray(String[]::new);

        Set<Integer> ids = new LinkedHashSet<>();

        if (tokens.length == 1)
        {
            try { ids.add(Integer.parseInt(tokens[0])); }
            catch (NumberFormatException ignored){}
        }

        List<Integer> searchRes = invokeItemManagerSearch(String.join(" ", tokens));
        ids.addAll(searchRes);

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
                    if (name == null) continue;
                    String lower = name.toLowerCase();
                    boolean match = true;
                    for (String t : tokens)
                        if (!lower.contains(t)) { match = false; break; }
                    if (match) ids.add(id);
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
                if (name == null || name.equalsIgnoreCase("null")) continue;

                boolean noted = comp.getNote() != -1 && comp.getLinkedNoteId() != -1;
                boolean placeholder = comp.getPlaceholderId() != -1 && comp.getPlaceholderTemplateId() != -1;
                boolean normal = !noted && !placeholder;
                boolean stackable = comp.isStackable();
                boolean members = comp.isMembers();
                boolean duplicator = false;
                List<String> actions = extractActions(comp);
                boolean wearable = isWearable(actions);
                BufferedImage icon = fetchIcon(id, stackable);

                String listDisplay = name + " (" + id + ")";

                Result r = new Result(
                        id,
                        name,
                        listDisplay,
                        icon,
                        stackable,
                        noted,
                        placeholder,
                        normal,
                        members,
                        duplicator,
                        actions,
                        wearable
                );
                if (passesFilters(r)) out.add(r);
            }
            catch (Exception ignored){}
            if (out.size() >= 600) break;
        }

        sortResults(out);
        return out;
    }

    private boolean passesFilters(Result r)
    {
        if (activeFilters.isEmpty()) return true;
        for (FilterFlag f : activeFilters)
        {
            switch (f)
            {
                case WEARABLE:     if (!r.wearable) return false; break;
                case STACKABLE:    if (!r.stackable) return false; break;
                case MEMBERS:      if (!r.members) return false; break;
                case NOTED:        if (!r.noted) return false; break;
                case PLACEHOLDER:  if (!r.placeholder) return false; break;
                case NORMAL:       if (!r.normal) return false; break;
            }
        }
        return true;
    }

    /* ================= SORT ================= */

    private void sortResults(List<Result> list)
    {
        SortMode mode = (SortMode) sortCombo.getSelectedItem();
        if (mode == null) mode = SortMode.ID_ASC;
        Comparator<Result> cmp;
        switch (mode)
        {
            case NAME_DESC:
                cmp = Comparator.comparing((Result r) -> r.itemName.toLowerCase()).reversed(); break;
            case ID_ASC:
                cmp = Comparator.comparingInt(r -> r.itemId); break;
            case ID_DESC:
                cmp = Comparator.comparingInt((Result r) -> r.itemId).reversed(); break;
            case NAME_ASC:
            default:
                cmp = Comparator.comparing((Result r) -> r.itemName.toLowerCase()); break;
        }
        list.sort(cmp);
    }

    private void resortCurrentResults()
    {
        List<Result> current = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++)
        {
            Result r = listModel.get(i);
            if (r.itemId > 0) current.add(r);
        }
        if (current.isEmpty()) return;
        sortResults(current);

        Result selected = resultList.getSelectedValue();
        listModel.clear();
        for (Result r : current) listModel.addElement(r);
        status(current.size() + " result(s)");

        if (selected != null)
        {
            for (int i = 0; i < listModel.size(); i++)
            {
                if (listModel.get(i).itemId == selected.itemId)
                {
                    resultList.setSelectedIndex(i);
                    resultList.ensureIndexIsVisible(i);
                    break;
                }
            }
        }
        else if (!listModel.isEmpty())
        {
            resultList.setSelectedIndex(0);
        }
    }

    /* ================= ITEM DATA HELPERS ================= */

    private List<Integer> invokeItemManagerSearch(String query)
    {
        try
        {
            Method m = itemManager.getClass().getMethod("search", String.class);
            Object res = m.invoke(itemManager, query);
            if (res instanceof Collection)
            {
                List<Integer> out = new ArrayList<>();
                for (Object o : (Collection<?>) res)
                {
                    if (o instanceof Integer) out.add((Integer) o);
                }
                return out;
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
                ItemComposition c = itemManager.getItemComposition(id);
                if (c == null) continue;
                String name = c.getName();
                if (name == null || name.equalsIgnoreCase("null")) continue;
                tmp.add(id);
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
            {
                if (a == null) continue;
                String trimmed = a.trim();
                if (trimmed.isEmpty()) continue;
                act.add(trimmed);
            }
        }
        if (!act.contains("Examine")) act.add("Examine");
        return act;
    }

    private boolean isWearable(List<String> actions)
    {
        if (actions == null || actions.isEmpty()) return false;
        for (String a : actions)
        {
            if (a == null) continue;
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.equals("wear") || lower.equals("wield") || lower.equals("equip"))
                return true;
        }
        return false;
    }

    private BufferedImage fetchIcon(int itemId, boolean stackable)
    {
        try
        {
            AsyncBufferedImage async = itemManager.getImage(itemId, 1, stackable);
            if (async == null) return null;

            boolean loaded = false;
            try
            {
                Method m = AsyncBufferedImage.class.getMethod("isLoaded");
                Object v = m.invoke(async);
                if (v instanceof Boolean) loaded = (Boolean) v;
            }
            catch (NoSuchMethodException e)
            {
                loaded = async.getWidth() > 0 && async.getHeight() > 0;
            }
            catch (Exception ignored) {}

            if (!loaded)
            {
                async.onLoaded(() -> SwingUtilities.invokeLater(() -> {
                    resultList.repaint();
                    Result sel = resultList.getSelectedValue();
                    if (sel != null && sel.itemId == itemId) updateDetails(sel);
                }));
            }
            return async;
        }
        catch (Exception e)
        {
            return null;
        }
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
            Image scaled = r.icon.getScaledInstance(56, 56, Image.SCALE_SMOOTH);
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
        sb.append("Actions    : ");
        if (r.actions.isEmpty()) sb.append("(none)");
        else sb.append(String.join(", ", r.actions));
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private void status(String s) { statusLabel.setText(s); }

    /* ================= DATA CLASSES ================= */

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
        final boolean duplicator;
        final List<String> actions;
        final boolean wearable;

        Result(int itemId,
               String itemName,
               String listDisplay,
               BufferedImage icon,
               boolean stackable,
               boolean noted,
               boolean placeholder,
               boolean normal,
               boolean members,
               boolean duplicator,
               List<String> actions,
               boolean wearable)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.listDisplay = listDisplay;
            this.icon = icon;
            this.stackable = stackable;
            this.noted = noted;
            this.placeholder = placeholder;
            this.normal = normal;
            this.members = members;
            this.duplicator = duplicator;
            this.actions = actions;
            this.wearable = wearable;
        }

        static Result searchingPlaceholder()
        {
            return new Result(-1, "(Searching...)", "(Searching...)", null,
                    false,false,false,false,false,false,
                    Collections.emptyList(), false);
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
        STACKABLE,
        MEMBERS,
        NOTED,
        PLACEHOLDER,
        NORMAL
    }

    private class Renderer extends JPanel implements ListCellRenderer<Result>
    {
        private final JLabel iconLabel = new JLabel();
        private final JLabel textLabel = new JLabel();
        private final Font mainFont = new Font(FONT_FAMILY, Font.PLAIN, (int) LIST_MAIN_FONT_SIZE);

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
                    iconLabel.setIcon(new ImageIcon(value.icon));
                else
                    iconLabel.setIcon(null);
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
            int r = Math.min(255, base.getRed() + 18);
            int g = Math.min(255, base.getGreen() + 18);
            int b = Math.min(255, base.getBlue() + 18);
            return new Color(r,g,b);
        }
    }
}
package com.krisped;

import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ItemSelectionDialog extends JDialog
{
    // Skann-parametre
    private static final int MAX_ITEM_ID = 35000;
    private static final int SCAN_CHUNK = 500;
    private static final int SCAN_DELAY_MS = 30; // Gjør skann roligere

    private final ItemManager itemManager;
    private final ClientThread clientThread;

    // UI
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<SimpleItem> listModel = new DefaultListModel<>();
    private final JList<SimpleItem> resultList = new JList<>(listModel);
    private final JButton ok = new JButton("OK");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel statusLabel = new JLabel(" ");

    // Details: verdifelter
    private final JLabel nameVal = new JLabel("-");
    private final JLabel idVal = new JLabel("-");
    private final JLabel canonicalVal = new JLabel("-");
    private final JLabel equipableVal = new JLabel("-");
    private final JLabel tradeableVal = new JLabel("-");
    private final JLabel membersVal = new JLabel("-");
    private final JLabel stackableVal = new JLabel("-");
    private final JLabel notedVal = new JLabel("-");
    private final JLabel placeholderVal = new JLabel("-");
    private final JTextArea actionsArea = new JTextArea(3, 20);

    // Søk/streaming state
    private final AtomicInteger searchToken = new AtomicInteger(0);
    private String initialQuery;
    private String currentQueryLower = "";

    // Dedup av id-er (unngå duplikater fra quick + stream)
    private final Set<Integer> seenIds = new HashSet<>();

    private SimpleItem selected;

    public static class SimpleItem
    {
        private final int id;
        private final String name;

        public SimpleItem(int id, String name)
        {
            this.id = id;
            this.name = name;
        }
        public int getId() { return id; }
        public String getName() { return name; }
        @Override
        public String toString() { return name + " (ID: " + id + ")"; }
    }

    public static class Result
    {
        private final SimpleItem item;
        private final String query;

        public Result(SimpleItem item, String query)
        {
            this.item = item;
            this.query = query;
        }
        public SimpleItem getItem() { return item; }
        public String getQuery() { return query; }
    }

    public ItemSelectionDialog(Window owner,
                               ItemManager itemManager,
                               ClientThread clientThread,
                               String title,
                               String initialQuery)
    {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.initialQuery = initialQuery;

        setLayout(new BorderLayout(8, 8));
        setPreferredSize(new Dimension(720, 600));

        // Topp: søkefelt
        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(new JLabel("Search / ID:"), BorderLayout.WEST);
        top.add(searchField, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        // Midt: liste + details
        resultList.setCellRenderer(new ItemCellRenderer(itemManager));
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(resultList);

        JPanel details = buildDetailsPanel();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, details);
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);

        // Bunn: status + knapper
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        statusLabel.setForeground(new Color(160, 160, 160));
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        ok.setEnabled(false);
        buttons.add(cancel);
        buttons.add(ok);
        bottom.add(buttons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        // Actions
        ok.addActionListener(e -> chooseSelected());
        cancel.addActionListener(e -> { selected = null; dispose(); });

        // IKKE start søk ved hver tast, kun når Enter trykkes.
        searchField.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER)
                {
                    String q = searchField.getText().trim();
                    // Hvis query er ny eller vi ikke har noen resultater ennå: start søk
                    if ((!q.isEmpty() && !q.equalsIgnoreCase(currentQueryLower)) || listModel.getSize() == 0)
                    {
                        startNewSearch();
                        return;
                    }

                    // Ellers: velg det markerte eller første
                    if (resultList.getModel().getSize() > 0 && resultList.getSelectedIndex() == -1)
                    {
                        resultList.setSelectedIndex(0);
                    }
                    chooseSelected();
                }
            }
        });

        resultList.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() >= 2)
                {
                    int idx = resultList.locationToIndex(e.getPoint());
                    if (idx >= 0)
                    {
                        resultList.setSelectedIndex(idx);
                        chooseSelected();
                    }
                }
            }
        });

        resultList.addListSelectionListener(e ->
        {
            ok.setEnabled(resultList.getSelectedIndex() >= 0);
            updateDetailsAsync(resultList.getSelectedValue());
        });

        resultList.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER)
                {
                    chooseSelected();
                }
            }
        });

        // Ikke sett default button til OK, så Enter i søkefeltet blir "search", ikke "OK"
        // getRootPane().setDefaultButton(ok);

        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowOpened(WindowEvent e)
            {
                if (initialQuery != null && !initialQuery.isEmpty())
                {
                    searchField.setText(initialQuery);
                }
                searchField.requestFocusInWindow();
                // Ikke auto-søk ved åpning, vent til Enter
            }
        });

        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildDetailsPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 4));
        grid.setBorder(BorderFactory.createTitledBorder("Details"));

        grid.add(new JLabel("Name:"));
        grid.add(nameVal);

        grid.add(new JLabel("ID:"));
        grid.add(idVal);

        grid.add(new JLabel("Canonical:"));
        grid.add(canonicalVal);

        grid.add(new JLabel("Equipable:"));
        grid.add(equipableVal);

        grid.add(new JLabel("Tradeable:"));
        grid.add(tradeableVal);

        grid.add(new JLabel("Members:"));
        grid.add(membersVal);

        grid.add(new JLabel("Stackable:"));
        grid.add(stackableVal);

        grid.add(new JLabel("Noted:"));
        grid.add(notedVal);

        grid.add(new JLabel("Placeholder:"));
        grid.add(placeholderVal);

        panel.add(grid, BorderLayout.NORTH);

        actionsArea.setEditable(false);
        actionsArea.setLineWrap(true);
        actionsArea.setWrapStyleWord(true);
        JPanel actionsWrap = new JPanel(new BorderLayout());
        actionsWrap.setBorder(BorderFactory.createTitledBorder("Inventory actions"));
        actionsWrap.add(new JScrollPane(actionsArea), BorderLayout.CENTER);

        panel.add(actionsWrap, BorderLayout.CENTER);
        return panel;
    }

    private void chooseSelected()
    {
        SimpleItem sel = resultList.getSelectedValue();
        if (sel == null)
        {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        selected = sel;
        initialQuery = searchField.getText();
        dispose();
    }

    public Optional<Result> showDialog()
    {
        setVisible(true);
        if (selected == null) return Optional.empty();
        return Optional.of(new Result(selected, initialQuery == null ? "" : initialQuery));
    }

    // Enter-utløst søk. Strømmes i roligere chunk.
    private void startNewSearch()
    {
        int token = searchToken.incrementAndGet();

        String q = searchField.getText().trim();
        currentQueryLower = q.toLowerCase(Locale.ROOT);

        listModel.clear();
        seenIds.clear();
        ok.setEnabled(false);
        setStatus(q.isEmpty() ? " " : "Searching...");

        if (q.isEmpty())
        {
            return;
        }

        // ID-søk: direkte
        if (Pattern.matches("\\d+", q))
        {
            int id;
            try { id = Integer.parseInt(q); } catch (NumberFormatException ignored) { setStatus(" "); return; }
            if (id < 0) { setStatus(" "); return; }

            clientThread.invoke(() ->
            {
                if (token != searchToken.get()) return;
                ItemComposition comp = safeComp(id);
                if (comp == null) return;

                final String name = comp.getName();
                if (name == null || "null".equalsIgnoreCase(name)) return;

                SwingUtilities.invokeLater(() ->
                {
                    if (token != searchToken.get()) return;
                    addUnique(new SimpleItem(id, name));
                    resultList.setSelectedIndex(0);
                    ok.setEnabled(true);
                    setStatus("1 result.");
                });
            });
            return;
        }

        // 1) Raskt: tradeables via ItemManager.search
        List<SimpleItem> quick = Collections.emptyList();
        try
        {
            quick = itemManager.search(q)
                    .stream()
                    .map(item -> new SimpleItem(item.getId(), item.getName()))
                    .collect(Collectors.toList());
        }
        catch (Exception ignored) {}

        if (!quick.isEmpty())
        {
            for (SimpleItem it : quick)
            {
                addUnique(it);
            }
            if (listModel.size() > 0)
            {
                resultList.setSelectedIndex(0);
                ok.setEnabled(true);
            }
            setStatus(listModel.size() + " results (scanning all items...)");
        }
        else
        {
            setStatus("Scanning all items...");
        }

        // 2) Full skanning i chunk – alle items, men med liten forsinkelse mellom chunkene
        scheduleScanChunk(0, token);
    }

    private void scheduleScanChunk(int startId, int token)
    {
        if (token != searchToken.get()) return;
        if (startId > MAX_ITEM_ID) { setStatus(finalStatus(true)); return; }

        final int s = startId;
        final int e = Math.min(startId + SCAN_CHUNK, MAX_ITEM_ID + 1);

        clientThread.invoke(() ->
        {
            if (token != searchToken.get()) return;

            List<SimpleItem> found = new ArrayList<>();
            for (int id = s; id < e; id++)
            {
                ItemComposition comp = safeComp(id);
                if (comp == null) continue;

                String name = comp.getName();
                if (name == null || "null".equalsIgnoreCase(name)) continue;
                if (!name.toLowerCase(Locale.ROOT).contains(currentQueryLower)) continue;

                // Samle – dedup håndteres på EDT
                found.add(new SimpleItem(id, name));
            }

            SwingUtilities.invokeLater(() ->
            {
                if (token != searchToken.get()) return;

                for (SimpleItem it : found)
                {
                    addUnique(it);
                }

                if (listModel.size() > 0 && resultList.getSelectedIndex() == -1)
                {
                    resultList.setSelectedIndex(0);
                    ok.setEnabled(true);
                }

                boolean finished = (e > MAX_ITEM_ID);
                setStatus(finalStatus(finished));
            });

            // Neste chunk – legg inn liten forsinkelse for å unngå lag/lock
            if (e <= MAX_ITEM_ID && token == searchToken.get())
            {
                SwingUtilities.invokeLater(() -> scheduleNextChunk(e, token));
            }
        });
    }

    private void scheduleNextChunk(int nextStartId, int token)
    {
        if (token != searchToken.get()) return;
        javax.swing.Timer t = new javax.swing.Timer(SCAN_DELAY_MS, ev -> scheduleScanChunk(nextStartId, token));
        t.setRepeats(false);
        t.start();
    }

    private boolean addUnique(SimpleItem it)
    {
        if (seenIds.contains(it.getId())) return false;
        seenIds.add(it.getId());
        listModel.addElement(it);
        return true;
    }

    private String finalStatus(boolean finished)
    {
        int n = listModel.size();
        return finished ? (n + " results.") : (n + " results (scanning...)");
    }

    // Trygge helpers

    private ItemComposition safeComp(int id)
    {
        try { return itemManager.getItemComposition(id); }
        catch (Exception ex) { return null; }
    }

    private void updateDetailsAsync(SimpleItem item)
    {
        if (item == null)
        {
            nameVal.setText("-");
            idVal.setText("-");
            canonicalVal.setText("-");
            equipableVal.setText("-");
            tradeableVal.setText("-");
            membersVal.setText("-");
            stackableVal.setText("-");
            notedVal.setText("-");
            placeholderVal.setText("-");
            actionsArea.setText("");
            return;
        }

        final int id = item.getId();
        nameVal.setText(item.getName());
        idVal.setText(String.valueOf(id));
        canonicalVal.setText("...");
        equipableVal.setText("...");
        tradeableVal.setText("...");
        membersVal.setText("...");
        stackableVal.setText("...");
        notedVal.setText("...");
        placeholderVal.setText("...");
        actionsArea.setText("");

        clientThread.invoke(() ->
        {
            ItemComposition comp = safeComp(id);
            if (comp == null) return;

            final int canon = itemManager.canonicalize(id);
            final String[] inv = comp.getInventoryActions();

            final String actionsText = (inv == null)
                    ? ""
                    : Arrays.stream(inv)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));

            final boolean equipable = hasWearAction(inv);
            final boolean tradeable = safeIs(() -> comp.isTradeable());
            final boolean members = safeIs(() -> comp.isMembers());
            final boolean stackable = safeIs(() -> comp.isStackable());
            final boolean noted = safeIs(() -> comp.getNote() != -1);
            final boolean placeholder = safeIs(() -> comp.getPlaceholderTemplateId() != -1);

            final String canonText = (canon == id) ? String.valueOf(canon) : (canon + " (base variant)");

            SwingUtilities.invokeLater(() ->
            {
                canonicalVal.setText(canonText);
                equipableVal.setText(yesNo(equipable));
                tradeableVal.setText(yesNo(tradeable));
                membersVal.setText(yesNo(members));
                stackableVal.setText(yesNo(stackable));
                notedVal.setText(yesNo(noted));
                placeholderVal.setText(yesNo(placeholder));
                actionsArea.setText(actionsText);
            });
        });
    }

    private boolean hasWearAction(String[] actions)
    {
        if (actions == null) return false;
        for (String a : actions)
        {
            if (a == null) continue;
            String s = a.toLowerCase(Locale.ROOT);
            if (s.equals("wear") || s.equals("wield") || s.equals("equip"))
            {
                return true;
            }
        }
        return false;
    }

    private interface CheckedBool { boolean get() throws Exception; }
    private boolean safeIs(CheckedBool c)
    {
        try { return c.get(); }
        catch (Exception ex) { return false; }
    }

    private String yesNo(boolean b) { return b ? "Yes" : "No"; }

    private void setStatus(String msg)
    {
        statusLabel.setText(msg);
    }

    private static class ItemCellRenderer extends DefaultListCellRenderer
    {
        private final ItemManager itemManager;

        ItemCellRenderer(ItemManager itemManager)
        {
            this.itemManager = itemManager;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus)
        {
            JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SimpleItem)
            {
                SimpleItem it = (SimpleItem) value;
                lbl.setText(it.getName() + " (ID: " + it.getId() + ")");
                try
                {
                    // Ikon hentes kun for synlige rader
                    lbl.setIcon(new ImageIcon(itemManager.getImage(it.getId())));
                }
                catch (Exception ignored) { lbl.setIcon(null); }
                lbl.setHorizontalTextPosition(SwingConstants.RIGHT);
                lbl.setIconTextGap(8);
            }
            return lbl;
        }
    }
}
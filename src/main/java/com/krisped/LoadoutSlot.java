package com.krisped;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Loadout slot.
 * Quantity text moved to top-left.
 */
public class LoadoutSlot extends JComponent
{
    private static final boolean DEBUG_MENU = false;

    // Reduced from 52 -> 50 for slightly smaller slots
    private static final int SLOT_SIZE = 50;
    private static final Dimension SIZE = new Dimension(SLOT_SIZE, SLOT_SIZE);

    private static final int AMOUNT_FONT_SIZE = 13;
    private static final Font AMOUNT_FONT = new Font("SansSerif", Font.BOLD, AMOUNT_FONT_SIZE);
    private static final int AMOUNT_INSET = 2;

    // Subtle background behind quantity for readability
    private static final boolean SHOW_QTY_BG = true;

    private final ItemManager itemManager;
    private final SlotActionHandler handler;
    private final int index;
    private final boolean equipment;

    private int itemId = -1;
    private int quantity = 0;
    private boolean stackable = false;

    private BufferedImage cachedIcon;
    private String resolvedName;
    private boolean hover;
    private String baseTooltip;

    private static boolean dragging = false;
    private static LoadoutSlot dragSource = null;
    private static Cursor originalCursor = null;

    private final Color baseBg = ColorScheme.DARKER_GRAY_COLOR;
    private final Color hoverBg = baseBg.brighter();
    private final Color borderColor = Color.DARK_GRAY;
    private final Color hoverBorder = new Color(190, 190, 190);

    public interface SlotActionHandler
    {
        void onLeftClick(LoadoutSlot slot, boolean isEquipment, int index);
        void requestItemInfoOnClientThread(LoadoutSlot slot, int itemId);
        void onAddAmount(LoadoutSlot slot, int delta);
        void onSetTotalAmount(LoadoutSlot slot, int targetTotal);
    }

    public LoadoutSlot(ItemManager itemManager, SlotActionHandler handler, int index, boolean equipment)
    {
        this.itemManager = itemManager;
        this.handler = handler;
        this.index = index;
        this.equipment = equipment;
        setOpaque(true);
        installMouse();
    }

    /* ================= Public API ================= */

    public void setItem(int id, int qty)
    {
        itemId = id;
        quantity = Math.max(0, qty);
        stackable = false;
        cachedIcon = null;
        resolvedName = null;
        baseTooltip = null;

        if (itemId > 0 && handler != null)
            handler.requestItemInfoOnClientThread(this, itemId);
        else
            setToolTipText(null);
        repaint();
    }

    public void clear()
    {
        itemId = -1;
        quantity = 0;
        stackable = false;
        cachedIcon = null;
        resolvedName = null;
        baseTooltip = null;
        setToolTipText(null);
        repaint();
    }

    public void incrementQuantityInternal(int delta)
    {
        if (itemId <= 0) return;
        quantity = Math.max(1, quantity + delta);
        updateTooltip();
        repaint();
    }

    public void setQuantityInternal(int newQty)
    {
        if (itemId <= 0) return;
        quantity = Math.max(1, newQty);
        updateTooltip();
        repaint();
    }

    public void decrementQuantityInternal(int delta)
    {
        if (itemId <= 0) return;
        quantity -= delta;
        if (quantity <= 0)
        {
            clear();
            return;
        }
        updateTooltip();
        repaint();
    }

    public void setResolvedItemInfo(String name, BufferedImage icon, boolean stackableFlag)
    {
        resolvedName = name;
        cachedIcon = icon;
        stackable = stackableFlag;

        if (quantity <= 0)
            quantity = 1;

        baseTooltip = (resolvedName != null ? resolvedName : "Item " + itemId) + " (" + itemId + ")";
        updateTooltip();
        repaint();
    }

    public int  getItemId()         { return itemId; }
    public int  getQuantity()       { return quantity; }
    public boolean isStackable()    { return stackable; }
    public int  getSlotIndex()      { return index; }
    public boolean isEquipment()    { return equipment; }
    public String getResolvedName() { return resolvedName; }

    private void updateTooltip()
    {
        if (itemId > 0)
        {
            String tt = baseTooltip != null ? baseTooltip : ("Item " + itemId);
            if (quantity > 1) tt += " x" + quantity;
            setToolTipText(tt);
        }
        else setToolTipText(null);
    }

    /* ================= Mouse / Drag ================= */

    private void installMouse()
    {
        MouseAdapter ma = new MouseAdapter()
        {
            private Point pressPoint = null;
            @Override public void mouseEntered(MouseEvent e){ hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e){ hover = false; repaint(); }

            @Override
            public void mousePressed(MouseEvent e)
            {
                if (SwingUtilities.isRightMouseButton(e))
                {
                    showContextMenu(e.getX(), e.getY());
                }
                else if (SwingUtilities.isLeftMouseButton(e))
                {
                    pressPoint = e.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e)
            {
                if (pressPoint == null || !SwingUtilities.isLeftMouseButton(e) || itemId <= 0)
                    return;
                if (!dragging && pressPoint.distance(e.getPoint()) > 3)
                    startDrag();
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (dragging && dragSource != null)
                {
                    finishDrag(e);
                }
                else if (SwingUtilities.isLeftMouseButton(e) && !dragging)
                {
                    if (handler != null)
                        handler.onLeftClick(LoadoutSlot.this, equipment, index);
                }
                pressPoint = null;
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    private void startDrag()
    {
        dragging = true;
        dragSource = this;
        var root = SwingUtilities.getRoot(this);
        if (root != null)
        {
            originalCursor = root.getCursor();
            Image img = new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
            Cursor blank = Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(0,0), "blank");
            root.setCursor(blank);
        }
        repaint();
    }

    private void finishDrag(MouseEvent e)
    {
        var root = SwingUtilities.getRoot(this);
        if (root != null && originalCursor != null)
            root.setCursor(originalCursor);

        LoadoutSlot dropTarget = locateSlotUnder(e);
        if (dropTarget != null && dropTarget != dragSource)
        {
            LoadoutBuilderPanel panel = LoadoutBuilderPanel.findPanel(this);
            if (panel != null)
                panel.performSlotDrop(dragSource, dropTarget);
        }
        dragging = false;
        dragSource = null;
        repaint();
    }

    private LoadoutSlot locateSlotUnder(MouseEvent e)
    {
        Point screen = e.getLocationOnScreen();
        var root = SwingUtilities.getRoot(this);
        if (root == null) return null;
        Point rootP = new Point(screen);
        SwingUtilities.convertPointFromScreen(rootP, root);
        var c = SwingUtilities.getDeepestComponentAt(root, rootP.x, rootP.y);
        while (c != null && !(c instanceof LoadoutSlot)) c = c.getParent();
        return (LoadoutSlot) c;
    }

    /* ================= Painting ================= */

    @Override public Dimension getPreferredSize() { return SIZE; }
    @Override public Dimension getMinimumSize()   { return SIZE; }
    @Override public Dimension getMaximumSize()   { return SIZE; }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(hover ? hoverBg : baseBg);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (cachedIcon != null)
        {
            int iw = cachedIcon.getWidth();
            int ih = cachedIcon.getHeight();
            int x = (getWidth() - iw) / 2;
            int y = (getHeight() - ih) / 2;
            g2.drawImage(cachedIcon, x, y, null);
        }

        // Quantity top-left
        if (itemId > 0 && quantity > 1)
        {
            String txt = formatQuantity(quantity);
            Color qtyColor = chooseQuantityColor(quantity);
            g2.setFont(AMOUNT_FONT);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(txt);
            int ascent = fm.getAscent();

            int x = AMOUNT_INSET;               // left-aligned
            int y = ascent + AMOUNT_INSET;

            if (SHOW_QTY_BG)
            {
                int bgH = fm.getHeight();
                int bgW = textW + 4;
                g2.setColor(new Color(0,0,0,140));
                g2.fillRoundRect(x - 2, y - ascent - 2, bgW, bgH, 6, 6);
            }
            else
            {
                g2.setColor(Color.BLACK);
                for (int ox = -1; ox <= 1; ox++)
                    for (int oy = -1; oy <= 1; oy++)
                        if (!(ox == 0 && oy == 0))
                            g2.drawString(txt, x + ox, y + oy);
            }

            g2.setColor(qtyColor);
            g2.drawString(txt, x, y);
        }

        g2.setColor(hover ? hoverBorder : borderColor);
        g2.drawRect(0, 0, getWidth()-1, getHeight()-1);

        if (dragging && dragSource == this)
        {
            g2.setColor(new Color(255,255,255,40));
            g2.fillRect(0,0,getWidth(),getHeight());
        }
        g2.dispose();
    }

    private String formatQuantity(int q)
    {
        if (q < 100_000)     return Integer.toString(q);
        if (q < 10_000_000)  return (q / 1000) + "K";
        return (q / 1_000_000) + "M";
    }

    private Color chooseQuantityColor(int q)
    {
        if (q < 100_000)     return Color.WHITE;
        if (q < 10_000_000)  return new Color(0, 255, 128);
        return new Color(0, 255, 255);
    }

    /* ================= Context Menu ================= */

    private void showContextMenu(int x, int y)
    {
        JPopupMenu menu = new JPopupMenu();

        if (itemId <= 0)
        {
            JMenuItem add = new JMenuItem("Set item...");
            add.addActionListener(e -> { if (handler != null) handler.onLeftClick(this, equipment, index); });
            menu.add(add);
        }
        else
        {
            int duplicateCount = countDuplicatesInInventory();
            boolean hasSlotQuantity = quantity > 1;
            boolean hasExternalDuplicates = (duplicateCount > 1);

            addHeader(menu, duplicateCount);
            menu.addSeparator();

            addAddSubmenu(menu);
            addQuickSetSubmenu(menu);

            JMenuItem setAmount = new JMenuItem("Set amount...");
            setAmount.addActionListener(e -> {
                String in = JOptionPane.showInputDialog(this, "Total amount:", Math.max(1, quantity));
                if (in != null)
                {
                    try
                    {
                        int val = Integer.parseInt(in.trim());
                        if (val > 0 && handler != null)
                            handler.onSetTotalAmount(this, val);
                    }
                    catch (NumberFormatException ignored) {}
                }
            });
            menu.add(setAmount);
            menu.addSeparator();

            if (hasSlotQuantity)
            {
                JMenuItem removeOne = new JMenuItem("Remove 1");
                removeOne.addActionListener(e -> decrementQuantityInternal(1));
                menu.add(removeOne);

                JMenuItem removeAllThisStack = new JMenuItem("Remove all");
                removeAllThisStack.addActionListener(e -> clear());
                menu.add(removeAllThisStack);
            }
            else
            {
                if (hasExternalDuplicates && !equipment)
                {
                    JMenuItem removeAllDup = new JMenuItem("Remove all");
                    removeAllDup.addActionListener(e -> {
                        LoadoutBuilderPanel panel = LoadoutBuilderPanel.findPanel(this);
                        if (panel != null)
                            panel.removeAllOccurrences(itemId);
                    });
                    menu.add(removeAllDup);
                }
                JMenuItem removeSingle = new JMenuItem("Remove item");
                removeSingle.addActionListener(e -> clear());
                menu.add(removeSingle);
            }

            menu.addSeparator();

            JMenuItem change = new JMenuItem("Change item...");
            change.addActionListener(e -> { if (handler != null) handler.onLeftClick(this, equipment, index); });
            menu.add(change);
        }

        menu.show(this, x, y);
    }

    private void addHeader(JPopupMenu menu, int duplicateCount)
    {
        String headerTxt = (resolvedName != null ? resolvedName : ("Item " + itemId)) + " (" + itemId + ")";
        if (quantity > 1) headerTxt += " x" + quantity;
        else if (duplicateCount > 1) headerTxt += " (" + duplicateCount + " slots)";
        if (DEBUG_MENU) headerTxt += " [eq=" + equipment + " st=" + stackable + "]";
        JMenuItem header = new JMenuItem(headerTxt);
        header.setEnabled(false);
        menu.add(header);
    }

    private int countDuplicatesInInventory()
    {
        LoadoutBuilderPanel panel = LoadoutBuilderPanel.findPanel(this);
        if (panel == null) return 1;
        int count = 0;
        for (LoadoutSlot s : panel.getInventorySlots())
        {
            if (s.getItemId() == itemId)
                count++;
        }
        return Math.max(count, 1);
    }

    private void addAddSubmenu(JPopupMenu menu)
    {
        JMenu addMenu = new JMenu("Add");
        int[] deltas = {1,5,10,25,50,100};
        for (int d : deltas)
        {
            JMenuItem mi = new JMenuItem("+" + d);
            mi.addActionListener(e -> {
                if (handler != null)
                    handler.onAddAmount(this, d);
            });
            addMenu.add(mi);
        }
        menu.add(addMenu);
    }

    private void addQuickSetSubmenu(JPopupMenu menu)
    {
        JMenu preset = new JMenu("Set quick");
        int[] values = {10,25,50,100,250,1000,5000,10000};
        for (int v : values)
        {
            JMenuItem mi = new JMenuItem(Integer.toString(v));
            mi.addActionListener(e -> {
                if (handler != null)
                    handler.onSetTotalAmount(this, v);
            });
            preset.add(mi);
        }
        menu.add(preset);
    }
}
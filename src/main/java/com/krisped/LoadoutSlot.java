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
 * Loadout slot with:
 *  - Hover highlight
 *  - Stack amount overlay (custom formatting)
 *  - Context menu for modifying amounts (inventory slots)
 *  - Drag & drop between inventory slots
 *
 * Amount formatting:
 *   < 100,000        -> full number
 *   < 10,000,000     -> (value/1000) + 'K' (1,000,000 => 1000K)
 *   >= 10,000,000    -> (value/1,000,000) + 'M'
 */
public class LoadoutSlot extends JComponent
{
    private static final int SLOT_SIZE = 48;
    private static final Dimension SIZE = new Dimension(SLOT_SIZE, SLOT_SIZE);

    private static final int AMOUNT_FONT_SIZE = 13;
    private static final Font AMOUNT_FONT = new Font("SansSerif", Font.BOLD, AMOUNT_FONT_SIZE);
    private static final int AMOUNT_INSET = 2;

    private final ItemManager itemManager;
    private final SlotActionHandler handler;
    private final int index;
    private final boolean equipment;

    private int itemId = -1;
    private int quantity = 0;
    private boolean stackable = false;

    private BufferedImage cachedIcon;
    private String toolTipTextCache;
    private boolean hover;

    private static boolean dragging = false;
    private static LoadoutSlot dragSource = null;
    private static Point dragOffset = new Point(0,0);
    private static BufferedImage dragImage = null;
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
                    startDrag(e);
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

    private void startDrag(MouseEvent e)
    {
        dragging = true;
        dragSource = this;
        dragOffset = e.getPoint();
        dragImage = cachedIcon;
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
        dragImage = null;
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
        while (c != null && !(c instanceof LoadoutSlot))
            c = c.getParent();
        return (LoadoutSlot) c;
    }

    /* ================= Public API ================= */

    public void setItem(int id, int qty)
    {
        itemId = id;
        quantity = Math.max(0, qty);
        stackable = false;
        cachedIcon = null;
        toolTipTextCache = null;

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
        toolTipTextCache = null;
        setToolTipText(null);
        repaint();
    }

    public void incrementQuantityInternal(int delta)
    {
        if (!stackable || itemId <= 0) return;
        quantity = Math.max(1, quantity + delta);
        updateTooltip();
        repaint();
    }

    public void setQuantityInternal(int newQty)
    {
        if (!stackable || itemId <= 0) return;
        quantity = Math.max(1, newQty);
        updateTooltip();
        repaint();
    }

    public void setResolvedItemInfo(String name, BufferedImage icon, boolean stackableFlag)
    {
        toolTipTextCache = (name != null) ? name + " (" + itemId + ")" : null;
        cachedIcon = icon;
        stackable = stackableFlag;

        if (!stackable)
            quantity = itemId > 0 ? 1 : 0;
        else if (quantity <= 0)
            quantity = 1;

        updateTooltip();
        repaint();
    }

    public int  getItemId()     { return itemId; }
    public int  getQuantity()   { return quantity; }
    public boolean isStackable(){ return stackable; }
    public int  getSlotIndex()  { return index; }
    public boolean isEquipment(){ return equipment; }

    private void updateTooltip()
    {
        if (itemId > 0)
        {
            String base = (toolTipTextCache != null) ? toolTipTextCache : ("Item " + itemId);
            if (stackable && quantity > 1) base += " x" + quantity;
            setToolTipText(base);
        }
        else
            setToolTipText(null);
    }

    /* ================= Layout / Paint ================= */

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

        if (stackable && itemId > 0 && quantity > 1)
        {
            String txt = formatQuantity(quantity);
            Color qtyColor = chooseQuantityColor(quantity);

            g2.setFont(AMOUNT_FONT);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(txt);
            int ascent = fm.getAscent();

            int x = getWidth() - textW - AMOUNT_INSET;
            int y = ascent + AMOUNT_INSET;

            g2.setColor(Color.BLACK);
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    if (!(ox == 0 && oy == 0))
                        g2.drawString(txt, x + ox, y + oy);

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
            JMenuItem add = new JMenuItem("Set item (open search)");
            add.addActionListener(e -> {
                if (handler != null)
                    handler.onLeftClick(this, equipment, index);
            });
            menu.add(add);
        }
        else
        {
            if (!equipment)
            {
                JMenu addMenu = new JMenu("Add");
                JMenuItem plus1 = new JMenuItem("+1");
                JMenuItem plus5 = new JMenuItem("+5");
                JMenuItem plus10 = new JMenuItem("+10");
                plus1.addActionListener(e -> { if (handler != null) handler.onAddAmount(this, 1); });
                plus5.addActionListener(e -> { if (handler != null) handler.onAddAmount(this, 5); });
                plus10.addActionListener(e -> { if (handler != null) handler.onAddAmount(this, 10); });
                addMenu.add(plus1);
                addMenu.add(plus5);
                addMenu.add(plus10);
                menu.add(addMenu);

                JMenuItem setAmount = new JMenuItem("Set amount...");
                setAmount.addActionListener(e -> {
                    String in = JOptionPane.showInputDialog(this, "Total amount:", stackable ? quantity : 1);
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
            }

            JMenuItem remove = new JMenuItem("Remove item");
            remove.addActionListener(e -> clear());
            menu.add(remove);
        }

        menu.show(this, x, y);
    }
}
package com.krisped;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.util.Objects;

@Slf4j
public class IconUtil
{
    private final ItemManager itemManager;
    private final ClientThread clientThread;

    public IconUtil(ItemManager itemManager, ClientThread clientThread)
    {
        this.itemManager = Objects.requireNonNull(itemManager);
        this.clientThread = Objects.requireNonNull(clientThread);
    }

    public void setButtonIcon(JButton btn, int itemId, int quantity)
    {
        try
        {
            final int displayQuantity = Math.max(1, quantity);

            clientThread.invoke(() ->
            {
                int canonId;
                try {
                    canonId = itemManager.canonicalize(itemId);
                } catch (Exception ex) {
                    canonId = itemId;
                }

                boolean isStackable;
                try {
                    isStackable = itemManager.getItemComposition(canonId).isStackable();
                }
                catch (Exception ex) {
                    isStackable = false;
                }

                // Force stacking for coins (995) and other known stackables
                if (canonId == 995 || canonId == 6529) { // Coins and noted items
                    isStackable = true;
                }

                // Only show quantity overlay if stackable AND quantity > 1
                boolean showQuantity = isStackable && displayQuantity > 1;

                log.debug("Setting icon for item {} (canon: {}), qty: {}, stackable: {}, showQty: {}",
                        itemId, canonId, displayQuantity, isStackable, showQuantity);

                AsyncBufferedImage img = itemManager.getImage(canonId, displayQuantity, showQuantity);

                SwingUtilities.invokeLater(() ->
                {
                    if (img == null)
                    {
                        btn.setIcon(null);
                        return;
                    }

                    btn.setIcon(new ImageIcon(img));
                    img.onLoaded(() ->
                    {
                        btn.setIcon(new ImageIcon(img));
                        btn.revalidate();
                        btn.repaint();
                    });
                });
            });
        }
        catch (Exception e)
        {
            log.error("Failed to set button icon for item {}", itemId, e);
            btn.setIcon(null);
        }
    }
}
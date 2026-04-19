package com.scrabble.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Preprocesses board images before sending to a vision API.
 *
 * Applied in order:
 *   1. Upscale to at least 1050px wide (70px per cell minimum)
 *   2. Sharpen — improves letter edge definition
 *   3. Contrast boost — makes dark letters pop against coloured tile backgrounds
 *   4. Grid overlay — draws 15×15 cell boundaries with A-O/1-15 edge labels plus
 *      a coordinate tag (e.g. "C7") in the top-left corner of every cell so the
 *      model has an unambiguous anchor anywhere on the board
 */
@Slf4j
@Service
public class ImageEnhancementService {

    private static final int BOARD_SIZE   = 15;
    private static final int MIN_WIDTH_PX = 1050;  // 70px per cell minimum

    public byte[] enhanceForVision(byte[] imageBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return imageBytes;

            img = toRgb(img);
            img = upscaleIfNeeded(img);
            img = sharpen(img);
            img = boostContrast(img);
            img = overlayGrid(img);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            log.debug("Enhanced board image: {}x{} px", img.getWidth(), img.getHeight());
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Image enhancement failed, using original: {}", e.getMessage());
            return imageBytes;
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    /** Ensure TYPE_INT_RGB so ConvolveOp and RescaleOp work reliably. */
    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private BufferedImage upscaleIfNeeded(BufferedImage img) {
        if (img.getWidth() >= MIN_WIDTH_PX) return img;
        double scale = (double) MIN_WIDTH_PX / img.getWidth();
        int w = (int) (img.getWidth() * scale);
        int h = (int) (img.getHeight() * scale);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private BufferedImage sharpen(BufferedImage img) {
        // Moderate unsharp-mask style sharpen kernel (sum = 1, brightness preserved)
        float[] kernel = {
             0f, -0.5f,  0f,
            -0.5f, 3f, -0.5f,
             0f, -0.5f,  0f
        };
        return new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null)
                .filter(img, null);
    }

    private BufferedImage boostContrast(BufferedImage img) {
        // Scale each channel by 1.15, add small brightness lift
        return new RescaleOp(1.15f, 8f, null).filter(img, null);
    }

    /**
     * Draws a 15×15 grid with:
     *  - Thicker black semi-transparent lines (2.5px interior, 3px border)
     *  - Column labels (A-O) along the top edge, row labels (1-15) along the left edge
     *  - A small coordinate tag (e.g. "C7") in the top-left corner of every cell
     */
    private BufferedImage overlayGrid(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, null);

        int w = src.getWidth();
        int h = src.getHeight();
        float cellW = (float) w / BOARD_SIZE;
        float cellH = (float) h / BOARD_SIZE;

        // Grid lines — black, semi-transparent, thicker
        g.setColor(new Color(0, 0, 0, 160));
        g.setStroke(new BasicStroke(2.5f));
        for (int i = 1; i < BOARD_SIZE; i++) {
            int x = Math.round(i * cellW);
            int y = Math.round(i * cellH);
            g.drawLine(x, 0, x, h);
            g.drawLine(0, y, w, y);
        }

        // Border
        g.setColor(new Color(0, 0, 0, 200));
        g.setStroke(new BasicStroke(3f));
        g.drawRect(0, 0, w - 1, h - 1);

        // Edge labels (column A-O top, row 1-15 left) — larger font
        int edgeFontSize = Math.max(10, (int) (cellW * 0.22f));
        Font edgeFont = new Font(Font.SANS_SERIF, Font.BOLD, edgeFontSize);
        g.setFont(edgeFont);
        FontMetrics efm = g.getFontMetrics();

        for (int c = 0; c < BOARD_SIZE; c++) {
            String label = String.valueOf((char) ('A' + c));
            float cx = c * cellW + cellW / 2f;
            drawLabel(g, efm, label, cx, efm.getAscent() + 2);
        }
        for (int r = 0; r < BOARD_SIZE; r++) {
            String label = String.valueOf(r + 1);
            float cy = r * cellH + cellH / 2f + efm.getAscent() / 2f;
            drawLabel(g, efm, label, efm.stringWidth(label) / 2f + 3, cy);
        }

        // Per-cell coordinate tags — tiny font in top-left corner of every cell
        int tagFontSize = Math.max(7, (int) (cellW * 0.14f));
        Font tagFont = new Font(Font.SANS_SERIF, Font.PLAIN, tagFontSize);
        g.setFont(tagFont);
        FontMetrics tfm = g.getFontMetrics();

        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                String tag = String.valueOf((char) ('A' + c)) + (r + 1);
                float tx = c * cellW + 2;
                float ty = r * cellH + tfm.getAscent() + 1;
                // White halo
                g.setColor(new Color(255, 255, 255, 180));
                g.drawString(tag, tx + 1, ty + 1);
                g.drawString(tag, tx - 1, ty - 1);
                // Dark text
                g.setColor(new Color(0, 0, 0, 200));
                g.drawString(tag, tx, ty);
            }
        }

        g.dispose();
        return dst;
    }

    private void drawLabel(Graphics2D g, FontMetrics fm, String text, float cx, float cy) {
        float x = cx - fm.stringWidth(text) / 2f;
        // White halo for readability over any background
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString(text, x + 1, cy + 1);
        g.drawString(text, x - 1, cy - 1);
        // Black text
        g.setColor(new Color(0, 0, 0, 230));
        g.drawString(text, x, cy);
    }
}

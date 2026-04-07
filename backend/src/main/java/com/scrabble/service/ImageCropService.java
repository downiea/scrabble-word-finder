package com.scrabble.service;

import com.scrabble.model.CropRegion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Slf4j
@Service
public class ImageCropService {

    /**
     * Crops a raw image byte array to the given region.
     *
     * @param imageBytes original image bytes
     * @param crop       region expressed as fractions (0.0–1.0) of image dimensions
     * @return cropped image as PNG bytes
     */
    public byte[] crop(byte[] imageBytes, CropRegion crop) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            int x = clamp((int) (crop.getLeft() * imgW), 0, imgW - 1);
            int y = clamp((int) (crop.getTop() * imgH), 0, imgH - 1);
            int w = clamp((int) (crop.getWidth() * imgW), 1, imgW - x);
            int h = clamp((int) (crop.getHeight() * imgH), 1, imgH - y);

            log.debug("Cropping image {}x{} to region x={} y={} w={} h={}", imgW, imgH, x, y, w, h);

            BufferedImage cropped = img.getSubimage(x, y, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to crop image", e);
            throw new RuntimeException("Image crop failed: " + e.getMessage(), e);
        }
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}

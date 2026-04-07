package com.scrabble.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A rectangular crop region expressed as fractions (0.0–1.0) of the image dimensions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CropRegion {
    /** Left edge as fraction of image width. */
    private double left;
    /** Top edge as fraction of image height. */
    private double top;
    /** Width as fraction of image width. */
    private double width;
    /** Height as fraction of image height. */
    private double height;
}

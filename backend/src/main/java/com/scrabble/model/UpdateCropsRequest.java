package com.scrabble.model;

import lombok.Data;

@Data
public class UpdateCropsRequest {
    private CropRegion boardCrop;
    private CropRegion tilesCrop;
}

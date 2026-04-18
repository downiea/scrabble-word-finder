package com.scrabble.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.CropRegion;
import com.scrabble.model.GameConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameConfigService {

    private final ObjectMapper objectMapper;
    private List<GameConfig> configs;
    private Map<String, GameConfig> configById;
    private Path configFilePath;

    @PostConstruct
    public void load() {
        try {
            var resource = new ClassPathResource("game-configs.json");
            // Prefer source path so writes survive Gradle's processResources overwrite
            configFilePath = null;
            Path sourcePath = Paths.get("src/main/resources/game-configs.json");
            if (sourcePath.toFile().exists()) {
                configFilePath = sourcePath;
            } else {
                try {
                    configFilePath = resource.getFile().toPath();
                } catch (Exception ignored) {
                    log.info("game-configs.json is not on the filesystem — crop changes will not be persisted");
                }
            }
            configs = new ArrayList<>(objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {}));
            configById = configs.stream().collect(Collectors.toMap(GameConfig::getId, Function.identity()));
            log.info("Loaded {} game configs", configs.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load game-configs.json", e);
        }
    }

    public List<GameConfig> getAll() {
        return configs;
    }

    public GameConfig getById(String id) {
        return configById.getOrDefault(id, configById.get("unknown"));
    }

    public void updateCrops(String id, CropRegion boardCrop, CropRegion tilesCrop) {
        GameConfig config = configById.get(id);
        if (config == null) throw new RuntimeException("Unknown game config: " + id);
        config.setBoardCrop(boardCrop);
        config.setTilesCrop(tilesCrop);
        if (configFilePath != null) {
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFilePath.toFile(), configs);
                log.info("Persisted crop regions for '{}' to {}", id, configFilePath);
            } catch (Exception e) {
                log.warn("Could not persist game configs to disk: {}", e.getMessage());
            }
        }
    }
}

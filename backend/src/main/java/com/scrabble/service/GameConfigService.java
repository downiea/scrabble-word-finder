package com.scrabble.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scrabble.model.GameConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

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

    @PostConstruct
    public void load() {
        try {
            var resource = new ClassPathResource("game-configs.json");
            configs = objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {});
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
}

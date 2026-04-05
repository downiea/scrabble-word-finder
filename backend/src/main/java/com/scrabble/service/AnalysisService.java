package com.scrabble.service;

import com.scrabble.model.AnalysisResponse;
import com.scrabble.model.BoardState;
import com.scrabble.model.Move;
import com.scrabble.model.MoveSuggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.scrabble.model.Ruleset;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final BoardVisionService boardVisionService;
    private final MoveGenerator moveGenerator;
    private final RankingService rankingService;

    public AnalysisResponse analyse(MultipartFile image, String tiles, Ruleset ruleset) {
        log.info("Analysing board for tiles={} ruleset={}", tiles, ruleset);

        BoardState boardState = boardVisionService.extractBoardState(image);
        List<Move> candidates = moveGenerator.generateMoves(boardState, tiles, ruleset);
        List<MoveSuggestion> suggestions = rankingService.rank(candidates, boardState);

        return AnalysisResponse.builder()
                .boardState(boardState)
                .suggestions(suggestions)
                .warnings(List.of())
                .build();
    }
}

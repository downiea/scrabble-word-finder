package com.scrabble.service;

import com.scrabble.model.BoardState;
import com.scrabble.model.Move;
import com.scrabble.model.MoveSuggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Combines raw score and strategy score to produce a ranked list of suggestions.
 * Deduplicates identical words at the same position before ranking.
 */
@Service
@RequiredArgsConstructor
public class RankingService {

    private static final int TOP_N = 5;

    private final StrategyScorer strategyScorer;

    public List<MoveSuggestion> rank(List<Move> moves, BoardState boardState) {
        AtomicInteger rankCounter = new AtomicInteger(1);

        return moves.stream()
                .distinct()
                .map(move -> {
                    StrategyScorer.ScoredMove scored = strategyScorer.score(move, boardState);
                    int total = move.getRawScore() + scored.strategyDelta();
                    return MoveSuggestion.builder()
                            .word(move.getWord())
                            .startRow(move.getStartRow())
                            .startCol(move.getStartCol())
                            .direction(move.getDirection())
                            .rawScore(move.getRawScore())
                            .strategyScore(scored.strategyDelta())
                            .totalScore(total)
                            .strategyReasons(scored.reasons())
                            .build();
                })
                .sorted(Comparator.comparingInt(MoveSuggestion::getTotalScore).reversed())
                .limit(TOP_N)
                .peek(s -> s.setRank(rankCounter.getAndIncrement()))
                .collect(Collectors.toList());
    }
}

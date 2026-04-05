package com.scrabble.service;

import com.scrabble.model.BoardState;
import com.scrabble.model.Move;
import com.scrabble.strategy.StrategyFactor;
import com.scrabble.strategy.StrategyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies all registered {@link StrategyFactor} instances to a move and
 * aggregates the results into a total strategy score and reasons list.
 *
 * Factors are discovered automatically by Spring — add or remove @Component
 * implementations of StrategyFactor to change which factors are active.
 */
@Service
@RequiredArgsConstructor
public class StrategyScorer {

    private final List<StrategyFactor> factors;

    public record ScoredMove(int strategyDelta, List<String> reasons) {}

    public ScoredMove score(Move move, BoardState boardState) {
        int totalDelta = 0;
        List<String> reasons = new ArrayList<>();

        for (StrategyFactor factor : factors) {
            StrategyResult result = factor.evaluate(move, boardState);
            totalDelta += result.getScoreDelta();
            if (result.getReason() != null) {
                reasons.add(result.getReason());
            }
        }

        return new ScoredMove(totalDelta, reasons);
    }
}

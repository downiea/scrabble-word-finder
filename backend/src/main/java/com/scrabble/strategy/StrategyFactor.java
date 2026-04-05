package com.scrabble.strategy;

import com.scrabble.model.BoardState;
import com.scrabble.model.Move;

/**
 * A pluggable strategic scoring rule.
 *
 * Each factor evaluates a candidate move in context and returns a score delta
 * (positive = good for the player, negative = bad) along with a human-readable
 * explanation. A delta of 0 means this factor is neutral for this move.
 *
 * To add a new factor: implement this interface and annotate with @Component.
 * Spring will automatically discover and include it in the strategy evaluation.
 *
 * To remove a factor: delete the class or remove the @Component annotation.
 */
public interface StrategyFactor {

    /**
     * Evaluate how strategically good or bad this move is.
     *
     * @param move       the candidate move being evaluated
     * @param boardState the current board state (before the move is played)
     * @return the result containing a score delta and reason string
     */
    StrategyResult evaluate(Move move, BoardState boardState);
}

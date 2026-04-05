package com.scrabble.strategy;

import lombok.Value;

@Value
public class StrategyResult {

    int scoreDelta;

    /** Human-readable explanation, or null if this factor had no effect. */
    String reason;

    public static StrategyResult neutral() {
        return new StrategyResult(0, null);
    }

    public static StrategyResult of(int delta, String reason) {
        return new StrategyResult(delta, reason);
    }
}

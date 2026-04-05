package com.scrabble.strategy;

import com.scrabble.model.BoardState;
import com.scrabble.model.Direction;
import com.scrabble.model.Move;
import com.scrabble.model.SquareType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Penalises moves that create open lanes (rows or columns with a played tile
 * at one end but empty squares leading to a TW/DW square), as the opponent
 * can exploit that lane next turn.
 *
 * An "open lane" is defined as: the move extends into or creates a row/column
 * where there is now a clear, unobstructed path of empty squares to a premium square.
 */
@Component
public class AvoidOpeningLaneFactor implements StrategyFactor {

    private static final int OPEN_TW_LANE_PENALTY = -15;
    private static final int OPEN_DW_LANE_PENALTY = -8;

    @Override
    public StrategyResult evaluate(Move move, BoardState boardState) {
        List<String> reasons = new ArrayList<>();
        int delta = 0;

        // Check the perpendicular direction to the move for open lanes
        Direction perp = move.getDirection() == Direction.ACROSS ? Direction.DOWN : Direction.ACROSS;

        int wordLen = move.getWord().length();
        for (int i = 0; i < wordLen; i++) {
            int row = move.getStartRow() + (move.getDirection() == Direction.DOWN ? i : 0);
            int col = move.getStartCol() + (move.getDirection() == Direction.ACROSS ? i : 0);

            // Scan in both directions perpendicular to the word
            for (int sign : new int[]{-1, 1}) {
                int steps = 1;
                while (true) {
                    int r = row + (perp == Direction.DOWN ? sign * steps : 0);
                    int c = col + (perp == Direction.ACROSS ? sign * steps : 0);
                    if (r < 0 || r >= BoardState.SIZE || c < 0 || c >= BoardState.SIZE) break;

                    var cell = boardState.getCell(r, c);
                    if (cell != null && !cell.isEmpty()) break; // lane is blocked

                    SquareType type = BoardState.standardSquareType(r, c);
                    if (type == SquareType.TRIPLE_WORD) {
                        delta += OPEN_TW_LANE_PENALTY;
                        reasons.add("Creates open lane to triple word at (" + r + "," + c + ")");
                        break;
                    } else if (type == SquareType.DOUBLE_WORD) {
                        delta += OPEN_DW_LANE_PENALTY;
                        reasons.add("Creates open lane to double word at (" + r + "," + c + ")");
                        break;
                    }
                    steps++;
                }
            }
        }

        if (reasons.isEmpty()) return StrategyResult.neutral();
        return StrategyResult.of(delta, String.join("; ", reasons));
    }
}

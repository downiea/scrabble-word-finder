package com.scrabble.strategy;

import com.scrabble.model.BoardState;
import com.scrabble.model.Cell;
import com.scrabble.model.Direction;
import com.scrabble.model.Move;
import com.scrabble.model.SquareType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewards moves that land on or directly adjacent to TW/DW squares,
 * blocking the opponent from using them on the next turn.
 *
 * Penalty is applied when the move OPENS a TW/DW square by placing a tile
 * beside it, leaving the premium square accessible to the opponent.
 */
@Component
public class BlockOpponentPremiumFactor implements StrategyFactor {

    private static final int BLOCK_TW_BONUS = 15;
    private static final int BLOCK_DW_BONUS = 8;
    private static final int OPEN_TW_PENALTY = -20;
    private static final int OPEN_DW_PENALTY = -10;

    @Override
    public StrategyResult evaluate(Move move, BoardState boardState) {
        List<String> reasons = new ArrayList<>();
        int delta = 0;

        List<int[]> placedPositions = getPlacedPositions(move);

        for (int[] pos : placedPositions) {
            int row = pos[0];
            int col = pos[1];

            // Check squares adjacent to each placed tile
            int[][] neighbours = {{row - 1, col}, {row + 1, col}, {row, col - 1}, {row, col + 1}};
            for (int[] n : neighbours) {
                if (!inBounds(n[0], n[1])) continue;
                Cell neighbour = boardState.getCell(n[0], n[1]);
                if (neighbour == null || !neighbour.isEmpty()) continue;

                SquareType type = BoardState.standardSquareType(n[0], n[1]);
                if (type == SquareType.TRIPLE_WORD) {
                    // We're placing adjacent to an empty TW — opponent could use it
                    delta += OPEN_TW_PENALTY;
                    reasons.add("Opens triple word square at (" + n[0] + "," + n[1] + ") for opponent");
                } else if (type == SquareType.DOUBLE_WORD) {
                    delta += OPEN_DW_PENALTY;
                    reasons.add("Opens double word square at (" + n[0] + "," + n[1] + ") for opponent");
                }
            }

            // Reward if the tile itself lands on a premium square (opponent can no longer use it)
            SquareType ownSquare = BoardState.standardSquareType(row, col);
            if (ownSquare == SquareType.TRIPLE_WORD) {
                delta += BLOCK_TW_BONUS;
                reasons.add("Blocks triple word square at (" + row + "," + col + ")");
            } else if (ownSquare == SquareType.DOUBLE_WORD) {
                delta += BLOCK_DW_BONUS;
                reasons.add("Blocks double word square at (" + row + "," + col + ")");
            }
        }

        if (reasons.isEmpty()) return StrategyResult.neutral();
        return StrategyResult.of(delta, String.join("; ", reasons));
    }

    private List<int[]> getPlacedPositions(Move move) {
        List<int[]> positions = new ArrayList<>();
        for (int i = 0; i < move.getWord().length(); i++) {
            int row = move.getStartRow() + (move.getDirection() == Direction.DOWN ? i : 0);
            int col = move.getStartCol() + (move.getDirection() == Direction.ACROSS ? i : 0);
            positions.add(new int[]{row, col});
        }
        return positions;
    }

    private boolean inBounds(int row, int col) {
        return row >= 0 && row < BoardState.SIZE && col >= 0 && col < BoardState.SIZE;
    }
}

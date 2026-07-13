/*

    DIY Layout Creator (DIYLC).
    Copyright (c) 2009-2026 held jointly by the individual authors.

    This file is part of DIYLC.

    DIYLC is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    DIYLC is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with DIYLC.  If not, see <http://www.gnu.org/licenses/>.

*/
package org.diylc.editor.compressor;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.diylc.editor.compressor.GridModel.Cell;

/**
 * Routes underside wire runs on the {@link GridModel} lattice with A*. Runs cost
 * {@link #STEP_COST} per lattice step plus {@link #TURN_COST} per direction change (straight
 * runs are easier to solder); they may not leave the board bounds, traverse a foreign net's
 * edge, or pass through a hole occupied by a foreign pin or run.
 *
 * @author Layout Compressor contributors
 */
public class Router {

  public static final int STEP_COST = 10;
  public static final int TURN_COST = 5;

  private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final GridModel grid;
  private final Rectangle bounds;

  /**
   * @param bounds routable area in cell coordinates; wires may not leave the board
   */
  public Router(GridModel grid, Rectangle bounds) {
    this.grid = grid;
    this.bounds = bounds;
  }

  /**
   * Cheapest underside path for the net from source to any of the target cells, or null when
   * unreachable. The path includes both endpoints; it is not claimed in the grid.
   */
  public List<Cell> findPath(int netId, Cell source, Set<Cell> targets) {
    if (targets.contains(source)) {
      return List.of(source);
    }
    if (!inBounds(source) || !grid.canPassHole(netId, source)) {
      return null;
    }

    // search states are (cell, incoming direction) so turn penalties accumulate correctly
    Map<State, Integer> bestCost = new HashMap<State, Integer>();
    Map<State, State> parent = new HashMap<State, State>();
    PriorityQueue<Open> queue = new PriorityQueue<Open>(OPEN_ORDER);

    State start = new State(source, -1);
    bestCost.put(start, 0);
    queue.add(new Open(start, 0, heuristic(source, targets)));

    while (!queue.isEmpty()) {
      Open current = queue.poll();
      if (current.cost > bestCost.getOrDefault(current.state, Integer.MAX_VALUE)) {
        continue;
      }
      Cell cell = current.state.cell();
      if (targets.contains(cell)) {
        return reconstruct(parent, current.state);
      }
      for (int dir = 0; dir < DIRECTIONS.length; dir++) {
        Cell next = new Cell(cell.col() + DIRECTIONS[dir][0], cell.row() + DIRECTIONS[dir][1]);
        if (!inBounds(next) || !grid.canUseEdge(netId, cell, next)
            || !grid.canPassHole(netId, next)) {
          continue;
        }
        int cost = current.cost + STEP_COST;
        if (current.state.direction() != -1 && current.state.direction() != dir) {
          cost += TURN_COST;
        }
        State nextState = new State(next, dir);
        if (cost < bestCost.getOrDefault(nextState, Integer.MAX_VALUE)) {
          bestCost.put(nextState, cost);
          parent.put(nextState, current.state);
          queue.add(new Open(nextState, cost, cost + heuristic(next, targets)));
        }
      }
    }
    return null;
  }

  private boolean inBounds(Cell cell) {
    return cell.col() >= bounds.x && cell.col() <= bounds.x + bounds.width
        && cell.row() >= bounds.y && cell.row() <= bounds.y + bounds.height;
  }

  /** Admissible: smallest manhattan distance to any target, in step costs. */
  private static int heuristic(Cell cell, Set<Cell> targets) {
    int min = Integer.MAX_VALUE;
    for (Cell t : targets) {
      int d = Math.abs(cell.col() - t.col()) + Math.abs(cell.row() - t.row());
      if (d < min) {
        min = d;
      }
    }
    return min * STEP_COST;
  }

  private static List<Cell> reconstruct(Map<State, State> parent, State goal) {
    List<Cell> path = new ArrayList<Cell>();
    State state = goal;
    while (state != null) {
      path.add(state.cell());
      state = parent.get(state);
    }
    Collections.reverse(path);
    return path;
  }

  private record State(Cell cell, int direction) {
  }

  private record Open(State state, int cost, int estimate) {
  }

  private static final Comparator<Open> OPEN_ORDER = Comparator.comparingInt(Open::estimate)
      .thenComparingInt(Open::cost)
      .thenComparingInt((o) -> o.state().cell().col())
      .thenComparingInt((o) -> o.state().cell().row())
      .thenComparingInt((o) -> o.state().direction());
}

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
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    return findPath(netId, source, targets, false);
  }

  /**
   * @param throughForeignWires probe mode for rip-up: foreign runs don't block (pins still
   *        do), so the result shows which nets stand in the way
   */
  private List<Cell> findPath(int netId, Cell source, Set<Cell> targets,
      boolean throughForeignWires) {
    if (targets.contains(source)) {
      return List.of(source);
    }
    if (!inBounds(source) || !holeOpen(netId, source, throughForeignWires)) {
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
        if (!inBounds(next)
            || (!throughForeignWires && !grid.canUseEdge(netId, cell, next))
            || !holeOpen(netId, next, throughForeignWires)) {
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

  /**
   * Routes all nets: pins get net-tagged in the grid, then nets route smallest-extent first
   * (short nets have the least freedom to detour). Pins that can't reach their net on the
   * underside get a top-side jumper to the nearest connected cell, so every net always ends up
   * fully connected.
   */
  public RoutingResult routeAll(List<List<Cell>> netPins) {
    for (int netId = 0; netId < netPins.size(); netId++) {
      for (Cell pin : netPins.get(netId)) {
        grid.setPinNet(pin, netId);
      }
    }

    List<Integer> order = new ArrayList<Integer>();
    for (int netId = 0; netId < netPins.size(); netId++) {
      order.add(netId);
    }
    order.sort(Comparator.comparingInt((netId) -> halfPerimeter(netPins.get((int) netId)))
        .thenComparingInt((netId) -> (int) netId));

    RoutedNet[] routed = new RoutedNet[netPins.size()];
    for (int netId : order) {
      RoutedNet net = routeNet(netId, netPins.get(netId));
      routed[netId] = net;
      for (Cell pin : new ArrayList<Cell>(net.getFailedPins())) {
        if (tryRipUpAndReroute(netId, pin, net, netPins, routed)) {
          net.getFailedPins().remove(pin);
        }
      }
      resolveFailedPins(net, netPins.get(netId));
    }

    RoutingResult result = new RoutingResult();
    Collections.addAll(result.getNets(), routed);
    return result;
  }

  public static final int MAX_RIP_UP = 3;

  /**
   * Tries to connect a failed pin by ripping up the nets blocking its way and re-routing them.
   * Kept only when it doesn't create more jumpers among the ripped nets than it saves here;
   * otherwise all wire state is restored.
   */
  private boolean tryRipUpAndReroute(int netId, Cell pin, RoutedNet net,
      List<List<Cell>> netPins, RoutedNet[] routed) {
    Set<Cell> tree = connectedCells(net, netPins.get(netId));
    if (tree.isEmpty()) {
      return false;
    }
    List<Cell> probe = findPath(netId, pin, tree, true);
    if (probe == null) {
      return false;
    }
    Set<Integer> blockers = blockersAlong(netId, probe);
    if (blockers.isEmpty() || blockers.size() > MAX_RIP_UP) {
      return false;
    }

    GridModel.WireSnapshot snapshot = grid.snapshotWires();
    Map<Integer, RoutedNet> oldNets = new HashMap<Integer, RoutedNet>();
    int oldJumpers = 0;
    for (int blocker : blockers) {
      oldNets.put(blocker, routed[blocker]);
      oldJumpers += routed[blocker].getJumpers().size();
      grid.releaseNet(blocker);
    }

    List<Cell> path = findPath(netId, pin, tree, false);
    if (path == null) {
      grid.restoreWires(snapshot);
      return false;
    }
    net.getRuns().add(path);
    grid.claimRun(netId, path);

    int newJumpers = 0;
    for (int blocker : blockers) {
      RoutedNet rerouted = routeNet(blocker, netPins.get(blocker));
      resolveFailedPins(rerouted, netPins.get(blocker));
      newJumpers += rerouted.getJumpers().size();
      routed[blocker] = rerouted;
    }

    if (newJumpers > oldJumpers) {
      grid.restoreWires(snapshot);
      net.getRuns().remove(net.getRuns().size() - 1);
      for (Map.Entry<Integer, RoutedNet> entry : oldNets.entrySet()) {
        routed[entry.getKey()] = entry.getValue();
      }
      return false;
    }
    return true;
  }

  /** Cells the net's connected part occupies: its tree, or its non-failed pins if unrouted. */
  private static Set<Cell> connectedCells(RoutedNet net, List<Cell> pins) {
    Set<Cell> cells = net.getTreeCells();
    if (cells.isEmpty()) {
      cells = new HashSet<Cell>(pins);
      cells.removeAll(net.getFailedPins());
    }
    return cells;
  }

  private Set<Integer> blockersAlong(int netId, List<Cell> path) {
    Set<Integer> blockers = new HashSet<Integer>();
    for (int i = 0; i < path.size(); i++) {
      Integer holeNet = grid.wireHoleNetAt(path.get(i));
      if (holeNet != null && holeNet != netId) {
        blockers.add(holeNet);
      }
      if (i > 0) {
        Integer edgeNet = grid.wireEdgeNetAt(path.get(i - 1), path.get(i));
        if (edgeNet != null && edgeNet != netId) {
          blockers.add(edgeNet);
        }
      }
    }
    return blockers;
  }

  /** Connects each failed pin with a top-side jumper to the nearest connected cell. */
  private void resolveFailedPins(RoutedNet net, List<Cell> pins) {
    if (net.getFailedPins().isEmpty()) {
      return;
    }
    Set<Cell> connected = net.getTreeCells();
    if (connected.isEmpty()) {
      connected = new HashSet<Cell>(pins);
      connected.removeAll(net.getFailedPins());
    }
    for (Cell pin : new ArrayList<Cell>(net.getFailedPins())) {
      Cell target = nearestCell(pin, connected);
      net.getJumpers().add(new RoutedNet.Jumper(pin, target));
      connected.add(pin);
    }
    net.getFailedPins().clear();
  }

  private static Cell nearestCell(Cell from, Set<Cell> candidates) {
    Cell best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Cell c : candidates) {
      int d = manhattan(from, c);
      if (d < bestDistance || (d == bestDistance && OPEN_CELL_ORDER.compare(c, best) < 0)) {
        bestDistance = d;
        best = c;
      }
    }
    return best;
  }

  private static int halfPerimeter(List<Cell> pins) {
    int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
    int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
    for (Cell pin : pins) {
      minCol = Math.min(minCol, pin.col());
      maxCol = Math.max(maxCol, pin.col());
      minRow = Math.min(minRow, pin.row());
      maxRow = Math.max(maxRow, pin.row());
    }
    return (maxCol - minCol) + (maxRow - minRow);
  }

  private static final Comparator<Cell> OPEN_CELL_ORDER =
      Comparator.comparingInt(Cell::col).thenComparingInt(Cell::row);

  /**
   * Routes one multi-terminal net: seeds the tree with the closest pin pair, then extends it
   * Prim-style, connecting each remaining pin to the nearest tree cell. Successful runs are
   * claimed in the grid; pins that can't reach the tree end up in
   * {@link RoutedNet#getFailedPins()}.
   */
  public RoutedNet routeNet(int netId, List<Cell> pins) {
    RoutedNet net = new RoutedNet(netId);
    Set<Cell> remaining = new LinkedHashSet<Cell>(pins);
    if (remaining.size() < 2) {
      return net;
    }

    List<Cell> pinList = new ArrayList<Cell>(remaining);
    Cell[] seed = closestPair(pinList);
    Set<Cell> tree = new HashSet<Cell>();
    tree.add(seed[0]);
    remaining.remove(seed[0]);

    while (!remaining.isEmpty()) {
      Cell pin = nearestToTree(remaining, tree);
      remaining.remove(pin);
      List<Cell> path = findPath(netId, pin, tree);
      if (path == null) {
        net.getFailedPins().add(pin);
        continue;
      }
      if (path.size() > 1) {
        net.getRuns().add(path);
        grid.claimRun(netId, path);
      }
      tree.addAll(path);
      tree.add(pin);
    }
    return net;
  }

  private static Cell[] closestPair(List<Cell> pins) {
    Cell[] best = {pins.get(0), pins.get(1)};
    int bestDistance = Integer.MAX_VALUE;
    for (int i = 0; i < pins.size() - 1; i++) {
      for (int j = i + 1; j < pins.size(); j++) {
        int d = manhattan(pins.get(i), pins.get(j));
        if (d < bestDistance) {
          bestDistance = d;
          best[0] = pins.get(i);
          best[1] = pins.get(j);
        }
      }
    }
    return best;
  }

  private static Cell nearestToTree(Set<Cell> pins, Set<Cell> tree) {
    Cell best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Cell pin : pins) {
      for (Cell cell : tree) {
        int d = manhattan(pin, cell);
        if (d < bestDistance) {
          bestDistance = d;
          best = pin;
        }
      }
    }
    return best;
  }

  private static int manhattan(Cell a, Cell b) {
    return Math.abs(a.col() - b.col()) + Math.abs(a.row() - b.row());
  }

  private boolean holeOpen(int netId, Cell cell, boolean throughForeignWires) {
    if (!throughForeignWires) {
      return grid.canPassHole(netId, cell);
    }
    Integer pinNet = grid.pinNetAt(cell);
    if (pinNet != null) {
      return pinNet == netId;
    }
    return grid.pinsAt(cell).isEmpty();
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

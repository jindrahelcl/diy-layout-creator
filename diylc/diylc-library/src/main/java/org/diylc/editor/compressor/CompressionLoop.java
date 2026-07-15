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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import org.diylc.core.IDIYComponent;

import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;

/**
 * Simulated annealing on a routed {@link CompressionState}: random small moves (slide, rotate,
 * stretch a lead span, re-route a net, un-jump a jumpered net), applied incrementally. Better
 * states are always kept; worse ones with probability exp(-delta/T) under a geometric cooling
 * schedule driven by iteration progress, so a seed and iteration count give a deterministic
 * result. The global best state is tracked and restored at the end — the loop is
 * anytime-stoppable via the wall-clock budget or the cancel monitor without losing progress.
 * {@link #compact()} is the deterministic complement: a greedy squeeze of boundary parts
 * toward the center, run before annealing (tighter start) and after (random slides rarely
 * finish off boundary outliers).
 *
 * @author Layout Compressor contributors
 */
public class CompressionLoop {

  /** Starting temperature as a fraction of the initial cost. */
  public static final double START_TEMPERATURE_FACTOR = 0.05;

  /** Temperature at the end of the schedule; effectively greedy. */
  public static final double END_TEMPERATURE = 0.5;

  /** Farthest inward jump attempted when pulling a boundary part toward the center. */
  public static final int MAX_PULL_CELLS = 8;

  private static final int[][] SLIDE_DELTAS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  /** How many iterations between progress reports. */
  private static final int PROGRESS_STRIDE = 25;

  /** Sweep caps for {@link #compact()}. */
  private static final int MAX_COMPACT_SWEEPS = 30;
  private static final int STALL_LIMIT = 3;

  private final CompressionState state;
  private final Random random;
  private BooleanSupplier cancelled = () -> false;
  private DoubleConsumer progressListener = (fraction) -> {};

  /** The state must be fully routed (see {@link CompressionState#rerouteAll()}). */
  public CompressionLoop(CompressionState state, long seed) {
    this.state = state;
    this.random = new Random(seed);
  }

  /** Polled every iteration; when true the loop stops and keeps the best state so far. */
  public void setCancelMonitor(BooleanSupplier cancelled) {
    this.cancelled = cancelled;
  }

  /**
   * Receives the loop's progress as a fraction in [0, 1] — whichever of iteration count and
   * wall-clock budget is further along — every {@value #PROGRESS_STRIDE} iterations. May be
   * called from whatever thread runs the loop.
   */
  public void setProgressListener(DoubleConsumer progressListener) {
    this.progressListener = progressListener;
  }

  /** Attempts the given number of random moves; returns the (global best) final cost. */
  public long run(int iterations) {
    return run(iterations, Long.MAX_VALUE);
  }

  /** Like {@link #run(int)} but also stops once the wall-clock budget is spent. */
  public long run(int iterations, long timeBudgetMs) {
    long now = System.currentTimeMillis();
    long deadline = timeBudgetMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeBudgetMs;
    long current = state.cost();
    long best = current;
    CompressionState.Snapshot bestSnapshot = state.snapshot();
    double startTemperature = Math.max(1, current * START_TEMPERATURE_FACTOR);
    for (int i = 0; i < iterations; i++) {
      if (cancelled.getAsBoolean() || System.currentTimeMillis() >= deadline) {
        break;
      }
      if (i % PROGRESS_STRIDE == 0) {
        double iterationFraction = i / (double) iterations;
        double timeFraction = deadline == Long.MAX_VALUE ? 0
            : 1 - (deadline - System.currentTimeMillis()) / (double) timeBudgetMs;
        progressListener.accept(Math.max(iterationFraction, timeFraction));
      }
      double temperature = startTemperature
          * Math.pow(END_TEMPERATURE / startTemperature, i / (double) iterations);
      Long candidate = propose();
      if (candidate == null) {
        continue;
      }
      if (candidate <= current
          || random.nextDouble() < Math.exp((current - candidate) / temperature)) {
        current = candidate;
        if (current < best) {
          best = current;
          bestSnapshot = state.snapshot();
        }
      } else {
        state.undoMove();
      }
    }
    if (current > best) {
      state.restore(bestSnapshot);
    }
    return best;
  }

  /**
   * Deterministic greedy squeeze, two kinds of moves per sweep: single parts touching an edge
   * of the occupied bounding box are pulled toward the center (jumps of up to
   * {@value #MAX_PULL_CELLS} cells, so a part can hop over occupied spots; kept when the cost
   * does not worsen), and whole boundary rows/columns are peeled — all their parts moved
   * inward as one atomic step, kept only when the total cost drops. The peel is what shrinks
   * a board whose edge is shared by several parts, where any single pull just lengthens
   * wires. Sweeps until nothing improved for {@value #STALL_LIMIT} sweeps. The cost never
   * increases, and stopping via the cancel monitor keeps the progress made so far.
   */
  public long compact() {
    long current = state.cost();
    int stalls = 0;
    for (int sweep = 0; sweep < MAX_COMPACT_SWEEPS && stalls < STALL_LIMIT; sweep++) {
      boolean improved = false;
      for (Placement placement : state.getPlacements()) {
        if (cancelled.getAsBoolean()) {
          return current;
        }
        IDIYComponent<?> component = placement.footprint().getComponent();
        for (boolean horizontal : new boolean[] {true, false}) {
          Long pulled = pullInward(component, horizontal);
          if (pulled != null) {
            improved |= pulled < current;
            current = pulled;
          }
        }
      }
      for (int side = 0; side < 4; side++) {
        if (cancelled.getAsBoolean()) {
          return current;
        }
        Long peeled = peelEdge(side, current);
        if (peeled != null) {
          improved = true;
          current = peeled;
        }
      }
      stalls = improved ? 0 : stalls + 1;
    }
    return current;
  }

  /**
   * Atomically moves every part touching one side of the occupied bounding box (0 = left,
   * 1 = right, 2 = top, 3 = bottom) one step inward — each part to its nearest fitting cell —
   * then re-routes any nets whose wires still hold the vacated line. Returns the new cost when
   * it is strictly better; otherwise the snapshot is restored and null returned.
   */
  private Long peelEdge(int side, long current) {
    Rectangle bounds = state.getGrid().occupiedBounds();
    if (bounds == null || (side < 2 ? bounds.width : bounds.height) < 2) {
      return null;
    }
    boolean horizontal = side < 2;
    boolean minSide = side == 0 || side == 2;
    int line = horizontal ? (minSide ? bounds.x : bounds.x + bounds.width)
        : (minSide ? bounds.y : bounds.y + bounds.height);
    int direction = minSide ? 1 : -1;

    List<IDIYComponent<?>> movers = new ArrayList<IDIYComponent<?>>();
    for (Placement placement : state.getPlacements()) {
      Rectangle extent = extentOf(placement);
      if (extent != null
          && (horizontal ? extent.x <= line && line <= extent.x + extent.width
              : extent.y <= line && line <= extent.y + extent.height)) {
        movers.add(placement.footprint().getComponent());
      }
    }
    if (movers.isEmpty()) {
      return null;
    }
    CompressionState.Snapshot before = state.snapshot();
    for (IDIYComponent<?> component : movers) {
      Placement placement = state.placementOf(component);
      boolean moved = false;
      for (int d = 1; d <= MAX_PULL_CELLS && !moved; d++) {
        Cell reference = new Cell(
            placement.reference().col() + (horizontal ? direction * d : 0),
            placement.reference().row() + (horizontal ? 0 : direction * d));
        moved = state.tryPlacement(new Placement(placement.footprint(), reference,
            placement.quarterTurns(), placement.span())) != null;
      }
      if (!moved) {
        state.restore(before);
        return null;
      }
    }
    Set<Integer> lineNets = new HashSet<Integer>();
    for (int i = 0; i <= (horizontal ? bounds.height : bounds.width); i++) {
      Cell cell = horizontal ? new Cell(line, bounds.y + i) : new Cell(bounds.x + i, line);
      Integer netId = state.getGrid().wireHoleNetAt(cell);
      if (netId != null) {
        lineNets.add(netId);
      }
    }
    if (!lineNets.isEmpty()) {
      state.rerouteNets(lineNets);
    }
    long cost = state.cost();
    if (cost < current) {
      return cost;
    }
    state.restore(before);
    return null;
  }

  /**
   * One inward pull attempt along one axis; null when the part is not on that axis's boundary
   * or no acceptable spot was found (the state is then unchanged).
   */
  private Long pullInward(IDIYComponent<?> component, boolean horizontal) {
    Placement placement = state.placementOf(component);
    Rectangle bounds = state.getGrid().occupiedBounds();
    Rectangle extent = extentOf(placement);
    if (bounds == null || extent == null) {
      return null;
    }
    int near = horizontal ? extent.x : extent.y;
    int far = horizontal ? extent.x + extent.width : extent.y + extent.height;
    int boundsNear = horizontal ? bounds.x : bounds.y;
    int boundsFar = horizontal ? bounds.x + bounds.width : bounds.y + bounds.height;
    boolean onNearEdge = near <= boundsNear;
    boolean onFarEdge = far >= boundsFar;
    if (onNearEdge == onFarEdge) {
      // interior on this axis, or spanning it entirely — nothing to pull
      return null;
    }
    int direction = onNearEdge ? 1 : -1;
    int center = (boundsNear + boundsFar) / 2;
    int maxPull = Math.min(MAX_PULL_CELLS, Math.abs(center - (onNearEdge ? near : far)));
    long current = state.cost();
    for (int d = 1; d <= maxPull; d++) {
      Cell reference = new Cell(
          placement.reference().col() + (horizontal ? direction * d : 0),
          placement.reference().row() + (horizontal ? 0 : direction * d));
      Long candidate = state.tryPlacement(new Placement(placement.footprint(), reference,
          placement.quarterTurns(), placement.span()));
      if (candidate == null) {
        continue;
      }
      if (candidate <= current) {
        return candidate;
      }
      state.undoMove();
    }
    return null;
  }

  /** Inclusive cell bounding box of the placement's pins and body. */
  private static Rectangle extentOf(Placement placement) {
    Rectangle extent = null;
    for (Cell cell : placement.pinCells()) {
      Rectangle cellRect = new Rectangle(cell.col(), cell.row(), 0, 0);
      if (extent == null) {
        extent = cellRect;
      } else {
        extent.add(cellRect);
      }
    }
    for (Rectangle body : placement.bodyCells()) {
      if (extent == null) {
        extent = new Rectangle(body);
      } else {
        extent.add(body);
      }
    }
    return extent;
  }

  /** One random move attempt; null when it was infeasible (the state is then unchanged). */
  private Long propose() {
    int kind = random.nextInt(8);
    if (kind < 4) {
      return slide();
    }
    switch (kind) {
      case 4:
        return rotate();
      case 5:
        return stretch();
      case 6:
        return reroute();
      default:
        return unjump();
    }
  }

  private Placement randomPlacement() {
    List<Placement> placements = state.getPlacements();
    return placements.get(random.nextInt(placements.size()));
  }

  private Long slide() {
    Placement placement = randomPlacement();
    int[] delta = SLIDE_DELTAS[random.nextInt(4)];
    Cell reference = new Cell(placement.reference().col() + delta[0],
        placement.reference().row() + delta[1]);
    return state.tryPlacement(new Placement(placement.footprint(), reference,
        placement.quarterTurns(), placement.span()));
  }

  private Long rotate() {
    Placement placement = randomPlacement();
    if (!placement.footprint().isRotatable()) {
      return null;
    }
    int turns = (placement.quarterTurns() + 1 + random.nextInt(3)) % 4;
    return state.tryPlacement(new Placement(placement.footprint(), placement.reference(),
        turns, placement.span()));
  }

  private Long stretch() {
    Placement placement = randomPlacement();
    if (!placement.footprint().isStretchable()) {
      return null;
    }
    int span = placement.span() + (random.nextBoolean() ? 1 : -1);
    if (span < 1) {
      return null;
    }
    return state.tryPlacement(new Placement(placement.footprint(), placement.reference(),
        placement.quarterTurns(), span));
  }

  private Long reroute() {
    if (state.netCount() == 0) {
      return null;
    }
    return state.tryRerouteNet(random.nextInt(state.netCount()));
  }

  private Long unjump() {
    List<Integer> jumpered = new ArrayList<Integer>();
    for (RoutedNet net : state.getRouting().getNets()) {
      if (!net.getJumpers().isEmpty()) {
        jumpered.add(net.getNetId());
      }
    }
    if (jumpered.isEmpty()) {
      return null;
    }
    return state.tryRerouteNet(jumpered.get(random.nextInt(jumpered.size())));
  }
}

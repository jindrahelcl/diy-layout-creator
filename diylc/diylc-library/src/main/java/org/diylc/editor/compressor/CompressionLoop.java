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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;

/**
 * Simulated annealing on a routed {@link CompressionState}: random small moves (slide, rotate,
 * stretch a lead span, re-route a net, un-jump a jumpered net), applied incrementally. Better
 * states are always kept; worse ones with probability exp(-delta/T) under a geometric cooling
 * schedule driven by iteration progress, so a seed and iteration count give a deterministic
 * result. The global best state is tracked and restored at the end — the loop is
 * anytime-stoppable via the wall-clock budget or the cancel monitor without losing progress.
 *
 * @author Layout Compressor contributors
 */
public class CompressionLoop {

  /** Starting temperature as a fraction of the initial cost. */
  public static final double START_TEMPERATURE_FACTOR = 0.05;

  /** Temperature at the end of the schedule; effectively greedy. */
  public static final double END_TEMPERATURE = 0.5;

  private static final int[][] SLIDE_DELTAS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  /** How many iterations between progress reports. */
  private static final int PROGRESS_STRIDE = 25;

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

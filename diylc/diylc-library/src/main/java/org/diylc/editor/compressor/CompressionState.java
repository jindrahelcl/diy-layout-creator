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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;

/**
 * The compression loop's world: current placements on a grid, the nets as pin references that
 * survive component moves, and the routing realized for them. Always fully routed after
 * {@link #rerouteAll()}; the loop mutates placements via {@link #applyPlacement} and scores
 * candidate states with {@link #cost()}.
 *
 * @author Layout Compressor contributors
 */
public class CompressionState {

  /** Cost per hole-column and hole-row of the occupied bounding box. */
  public static final int AREA_WEIGHT = 10;

  /** Cost per top-side jumper; the expensive resource. */
  public static final int JUMPER_WEIGHT = 50;

  /** Cost per pair of jumpers that cross — untangling beats shortening. */
  public static final int CROSSING_WEIGHT = 15;

  /** Cost per cell a stretchable part's span deviates from its natural span. */
  public static final int SPAN_WEIGHT = 3;

  /** Cost per unit of underside wire length. */
  public static final int LENGTH_WEIGHT = 1;

  /** A net terminal that stays valid when its component moves. */
  public record PinRef(IDIYComponent<?> component, int pinIndex) {
  }

  private final GridModel grid;
  private final int routingMargin;
  private final Map<IDIYComponent<?>, Placement> placements =
      new IdentityHashMap<IDIYComponent<?>, Placement>();
  private final List<IDIYComponent<?>> componentOrder = new ArrayList<IDIYComponent<?>>();
  private final List<List<PinRef>> netPins;
  private final Map<IDIYComponent<?>, Map<Integer, Cell>> fixedPins;
  private final Map<IDIYComponent<?>, Set<Integer>> netsOf =
      new IdentityHashMap<IDIYComponent<?>, Set<Integer>>();
  private RoutingResult routing;
  private Undo undo;

  private record Undo(IDIYComponent<?> component, Placement placement,
      GridModel.WireSnapshot wires, List<RoutedNet> nets) {
  }

  /**
   * @param grid grid already holding the placements' claims (as the legalizer left it)
   * @param placements current spots of the movable components
   * @param fixedPins pin cells of immovable on-board components, by component and point index
   * @param netPins each net's terminals; pins of components that are neither placed nor fixed
   *        (remote parts) are silently skipped when resolving cells
   * @param routingMargin extra routable cells around the occupied bounds
   */
  public CompressionState(GridModel grid, List<Placement> placements,
      Map<IDIYComponent<?>, Map<Integer, Cell>> fixedPins, List<List<PinRef>> netPins,
      int routingMargin) {
    this.grid = grid;
    this.routingMargin = routingMargin;
    this.netPins = netPins;
    this.fixedPins = fixedPins;
    for (Placement placement : placements) {
      this.placements.put(placement.footprint().getComponent(), placement);
      componentOrder.add(placement.footprint().getComponent());
    }
    for (int netId = 0; netId < netPins.size(); netId++) {
      for (PinRef pin : netPins.get(netId)) {
        netsOf.computeIfAbsent(pin.component(), (c) -> new HashSet<Integer>()).add(netId);
      }
    }
  }

  public GridModel getGrid() {
    return grid;
  }

  public RoutingResult getRouting() {
    return routing;
  }

  public Placement placementOf(IDIYComponent<?> component) {
    return placements.get(component);
  }

  /** Current placements, in a stable order (the construction order of their components). */
  public List<Placement> getPlacements() {
    List<Placement> result = new ArrayList<Placement>(componentOrder.size());
    for (IDIYComponent<?> component : componentOrder) {
      result.add(placements.get(component));
    }
    return result;
  }

  public int netCount() {
    return netPins.size();
  }

  /** Nets with a terminal on the component; empty for components on no net. */
  public Set<Integer> netsTouching(IDIYComponent<?> component) {
    return netsOf.getOrDefault(component, Collections.emptySet());
  }

  /** Board cell of the pin under the current placements, or null for a remote pin. */
  public Cell cellOf(PinRef pin) {
    Placement placement = placements.get(pin.component());
    if (placement != null) {
      int position = placement.footprint().getPinIndices().indexOf(pin.pinIndex());
      return position < 0 ? null : placement.pinCells().get(position);
    }
    Map<Integer, Cell> fixed = fixedPins.get(pin.component());
    return fixed == null ? null : fixed.get(pin.pinIndex());
  }

  /** Each net's distinct board cells under the current placements. */
  public List<List<Cell>> netCells() {
    List<List<Cell>> cells = new ArrayList<List<Cell>>(netPins.size());
    for (List<PinRef> net : netPins) {
      Set<Cell> netCells = new LinkedHashSet<Cell>();
      for (PinRef pin : net) {
        Cell cell = cellOf(pin);
        if (cell != null) {
          netCells.add(cell);
        }
      }
      cells.add(new ArrayList<Cell>(netCells));
    }
    return cells;
  }

  /**
   * Moves a component to a new placement: its old claims are vacated, the new ones occupied.
   * The caller re-routes the affected nets (at least {@link #netsTouching}).
   */
  public void applyPlacement(Placement placement) {
    IDIYComponent<?> component = placement.footprint().getComponent();
    grid.vacate(component);
    Legalizer.occupy(placement, grid);
    placements.put(component, placement);
  }

  /**
   * Attempts to move a component to the candidate placement, re-routing incrementally: the
   * component's own nets plus any net whose underside run the new pins land on. Returns the
   * cost of the resulting state, or null when the spot is taken (pins and bodies collide;
   * foreign wires don't — they get re-routed). A successful move can be reverted with
   * {@link #undoMove()} until the next attempt.
   */
  public Long tryPlacement(Placement candidate) {
    IDIYComponent<?> component = candidate.footprint().getComponent();
    Placement previous = placements.get(component);
    grid.vacate(component);
    if (!fits(candidate)) {
      Legalizer.occupy(previous, grid);
      retagPins(netsTouching(component));
      return null;
    }
    Undo pending = new Undo(component, previous, grid.snapshotWires(),
        new ArrayList<RoutedNet>(routing.getNets()));
    Legalizer.occupy(candidate, grid);
    placements.put(component, candidate);

    Set<Integer> affected = new HashSet<Integer>(netsTouching(component));
    for (Cell cell : candidate.pinCells()) {
      Integer displaced = grid.wireHoleNetAt(cell);
      if (displaced != null) {
        affected.add(displaced);
      }
    }
    // foreign runs that now pass through the moved part's pad halos must clear out too
    for (Cell cell : grid.haloCellsOf(component)) {
      Integer displaced = grid.wireHoleNetAt(cell);
      if (displaced != null) {
        affected.add(displaced);
      }
    }
    rerouteNets(affected);
    undo = pending;
    return cost();
  }

  /**
   * Rips the net out and routes it from scratch against the current wire state (rip-up of
   * blocking nets included) — the mechanism behind the REROUTE and UNJUMP moves. Returns the
   * resulting cost; revert with {@link #undoMove()}.
   */
  public Long tryRerouteNet(int netId) {
    undo = new Undo(null, null, grid.snapshotWires(),
        new ArrayList<RoutedNet>(routing.getNets()));
    rerouteNets(Set.of(netId));
    return cost();
  }

  /** Reverts the last successful try-move: placement (if one moved), wires, and routing. */
  public void undoMove() {
    if (undo.component() != null) {
      grid.vacate(undo.component());
      Legalizer.occupy(undo.placement(), grid);
      placements.put(undo.component(), undo.placement());
    }
    grid.restoreWires(undo.wires());
    routing.getNets().clear();
    routing.getNets().addAll(undo.nets());
    if (undo.component() != null) {
      retagPins(netsTouching(undo.component()));
    }
    undo = null;
  }

  /** Re-routes just the given nets (releasing their claims first), rip-up included. */
  public void rerouteNets(Set<Integer> netIds) {
    Rectangle bounds = grid.occupiedBounds();
    bounds.grow(routingMargin, routingMargin);
    new Router(grid, bounds).rerouteNets(routing, netCells(), netIds);
  }

  /**
   * True when the placement's cells collide with no pin or body. Wire claims don't count —
   * runs under a body are fine, and runs across a pin cell are the caller's cue to re-route
   * that net. The component itself must already be vacated.
   */
  private boolean fits(Placement candidate) {
    for (Cell cell : candidate.pinCells()) {
      if (cell.col() < 0 || cell.row() < 0 || !cellClear(cell)) {
        return false;
      }
    }
    for (Rectangle body : candidate.bodyCells()) {
      if (body.x < 0 || body.y < 0) {
        return false;
      }
      for (int col = body.x; col <= body.x + body.width; col++) {
        for (int row = body.y; row <= body.y + body.height; row++) {
          if (!cellClear(new Cell(col, row))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean cellClear(Cell cell) {
    return grid.pinsAt(cell).isEmpty() && grid.bodiesAt(cell).isEmpty();
  }

  /** Restores the pin-net tags of the nets' pins after their cells were vacated. */
  private void retagPins(Set<Integer> netIds) {
    List<List<Cell>> cells = netCells();
    for (int netId : netIds) {
      for (Cell cell : cells.get(netId)) {
        grid.setPinNet(cell, netId);
      }
    }
  }

  /** Routes every net from scratch; existing wire claims are dropped first. */
  public void rerouteAll() {
    for (int netId = 0; netId < netPins.size(); netId++) {
      grid.releaseNet(netId);
    }
    Rectangle bounds = grid.occupiedBounds();
    bounds.grow(routingMargin, routingMargin);
    routing = new Router(grid, bounds).routeAll(netCells());
  }

  /**
   * Score of the current, routed state: occupied bounding-box extent (columns + rows) weighted
   * by {@link #AREA_WEIGHT}, jumpers by {@link #JUMPER_WEIGHT}, jumper crossings by
   * {@link #CROSSING_WEIGHT}, span deviations from natural by {@link #SPAN_WEIGHT}, wire
   * length by {@link #LENGTH_WEIGHT}. Lower is better.
   */
  public long cost() {
    Rectangle bounds = grid.occupiedBounds();
    long extent = bounds == null ? 0 : (bounds.width + 1) + (bounds.height + 1);
    long spanDeviation = 0;
    for (IDIYComponent<?> component : componentOrder) {
      Placement placement = placements.get(component);
      int natural = placement.footprint().getNaturalSpanCells();
      if (placement.footprint().isStretchable() && natural > 0) {
        spanDeviation += Math.abs(placement.span() - natural);
      }
    }
    return AREA_WEIGHT * extent + JUMPER_WEIGHT * (long) routing.getJumperCount()
        + CROSSING_WEIGHT * (long) routing.getJumperCrossings()
        + SPAN_WEIGHT * spanDeviation
        + LENGTH_WEIGHT * (long) routing.getTotalWireLength();
  }
}

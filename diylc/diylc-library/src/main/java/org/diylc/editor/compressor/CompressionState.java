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

  /** Cost per unit of underside wire length. */
  public static final int LENGTH_WEIGHT = 1;

  /** A net terminal that stays valid when its component moves. */
  public record PinRef(IDIYComponent<?> component, int pinIndex) {
  }

  private final GridModel grid;
  private final int routingMargin;
  private final Map<IDIYComponent<?>, Placement> placements =
      new IdentityHashMap<IDIYComponent<?>, Placement>();
  private final List<List<PinRef>> netPins;
  private final Map<IDIYComponent<?>, Map<Integer, Cell>> fixedPins;
  private final Map<IDIYComponent<?>, Set<Integer>> netsOf =
      new IdentityHashMap<IDIYComponent<?>, Set<Integer>>();
  private RoutingResult routing;

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

  public List<Placement> getPlacements() {
    return new ArrayList<Placement>(placements.values());
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
   * by {@link #AREA_WEIGHT}, jumpers by {@link #JUMPER_WEIGHT}, wire length by
   * {@link #LENGTH_WEIGHT}. Lower is better.
   */
  public long cost() {
    Rectangle bounds = grid.occupiedBounds();
    long extent = bounds == null ? 0 : (bounds.width + 1) + (bounds.height + 1);
    return AREA_WEIGHT * extent + JUMPER_WEIGHT * (long) routing.getJumperCount()
        + LENGTH_WEIGHT * (long) routing.getTotalWireLength();
  }
}

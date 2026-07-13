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
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.presenter.ContinuityArea;
import org.diylc.netlist.Group;
import org.diylc.netlist.Node;

/**
 * Summary of what the layout compressor would operate on: component classification plus net and
 * pin statistics derived from {@link NetExtractor}'s net model.
 *
 * @author Layout Compressor contributors
 */
public class CompressionSurvey {

  /** Extra routable space around the current placement, in cells. */
  private static final int DRY_RUN_MARGIN = 2;

  private final ComponentClassifier.Classification classification;
  private final List<Group> nets;
  private final List<Footprint> footprints;
  private final int stickyPinCount;
  private RoutingResult routing;
  private int skippedRoutingPins;

  private CompressionSurvey(ComponentClassifier.Classification classification, List<Group> nets,
      List<Footprint> footprints, int stickyPinCount) {
    this.classification = classification;
    this.nets = nets;
    this.footprints = footprints;
    this.stickyPinCount = stickyPinCount;
  }

  public static CompressionSurvey of(Project project, List<ContinuityArea> continuityAreas) {
    return of(project, continuityAreas, null);
  }

  /**
   * @param bodyBoundsProvider optional source of drawn body bounds per component (from
   *        {@code DrawingManager.getComponentArea}); footprints get no body extents without it
   */
  public static CompressionSurvey of(Project project, List<ContinuityArea> continuityAreas,
      Function<IDIYComponent<?>, Rectangle2D> bodyBoundsProvider) {
    ComponentClassifier.Classification classification =
        new ComponentClassifier().classify(project);

    List<Group> nets =
        NetExtractor.extractNets(project, continuityAreas, classification.getRealParts());

    List<Footprint> footprints = new ArrayList<Footprint>();
    int stickyPinCount = 0;
    for (IDIYComponent<?> c : classification.getRealParts()) {
      Rectangle2D bodyBounds = bodyBoundsProvider == null ? null : bodyBoundsProvider.apply(c);
      Footprint footprint = Footprint.of(c, bodyBounds);
      footprints.add(footprint);
      stickyPinCount += footprint.getPinCount();
    }

    CompressionSurvey survey =
        new CompressionSurvey(classification, nets, footprints, stickyPinCount);
    survey.dryRunRouting();
    return survey;
  }

  /**
   * Routes the nets at the current placement on an otherwise empty board: pins snapped to the
   * grid, existing wiring ignored (it's what routing replaces). Estimates the wire length and
   * jumper count the compressor would need without moving anything.
   */
  private void dryRunRouting() {
    GridModel grid = new GridModel();
    for (IDIYComponent<?> c : classification.getRealParts()) {
      for (int i = 0; i < c.getControlPointCount(); i++) {
        if (c.isControlPointSticky(i)) {
          grid.occupyPin(GridModel.snap(c.getControlPoint(i)), c, i);
        }
      }
    }

    // snapping can collapse distinct pins onto one cell (off-grid pitches); a cell can only
    // belong to one net, so later claims are skipped and reported
    List<List<Cell>> netCells = new ArrayList<List<Cell>>();
    Map<Cell, Integer> cellOwner = new HashMap<Cell, Integer>();
    for (int netId = 0; netId < nets.size(); netId++) {
      Set<Cell> cells = new LinkedHashSet<Cell>();
      for (Node node : nets.get(netId).getSortedNodes()) {
        cells.add(GridModel.snap(node.getPoint2D()));
      }
      List<Cell> kept = new ArrayList<Cell>();
      for (Cell cell : cells) {
        Integer owner = cellOwner.putIfAbsent(cell, netId);
        if (owner == null || owner == netId) {
          kept.add(cell);
        } else {
          skippedRoutingPins++;
        }
      }
      netCells.add(kept);
    }

    Rectangle bounds = grid.occupiedBounds();
    if (bounds == null || netCells.isEmpty()) {
      return;
    }
    bounds.grow(DRY_RUN_MARGIN, DRY_RUN_MARGIN);
    routing = new Router(grid, bounds).routeAll(netCells);
  }

  public ComponentClassifier.Classification getClassification() {
    return classification;
  }

  public List<Group> getNets() {
    return nets;
  }

  public int getNetCount() {
    return nets.size();
  }

  /** Total count of pin memberships across all nets. */
  public int getConnectedPinCount() {
    return nets.stream().mapToInt(g -> g.getNodes().size()).sum();
  }

  public int getStickyPinCount() {
    return stickyPinCount;
  }

  /** Pins on real parts that belong to no net. */
  public int getOpenPinCount() {
    return stickyPinCount - getConnectedPinCount();
  }

  public List<Footprint> getFootprints() {
    return footprints;
  }

  public List<IDIYComponent<?>> getOffGridParts() {
    return footprints.stream().filter(f -> !f.isOnGrid()).map(Footprint::getComponent)
        .collect(Collectors.toList());
  }

  public long getOnGridCount() {
    return footprints.stream().filter(Footprint::isOnGrid).count();
  }

  public long getStretchableCount() {
    return footprints.stream().filter(Footprint::isStretchable).count();
  }

  /** Routing estimate at the current placement, or null when there was nothing to route. */
  public RoutingResult getRouting() {
    return routing;
  }

  /** Pins dropped from the dry run because grid snapping collapsed them onto a foreign net. */
  public int getSkippedRoutingPins() {
    return skippedRoutingPins;
  }
}

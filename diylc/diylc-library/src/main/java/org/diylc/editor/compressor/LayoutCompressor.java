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
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.diylc.common.IProjectEditor;
import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.editor.compressor.ComponentClassifier.Classification;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.diylc.editor.compressor.PlacementSeeder.Seed;
import org.diylc.netlist.Group;
import org.diylc.netlist.Node;
import org.diylc.presenter.ContinuityArea;

/**
 * The full normalization pipeline as a single undoable edit: strip old wiring and boards, seed
 * and legalize placement on the 0.1" lattice, route every net, emit wires and a shrink-wrapped
 * perfboard — then verify that the netlist is unchanged before touching the real project. All
 * work happens on a scratch clone; the project passed in is only mutated after verification
 * passes, so a mismatch (or any exception) leaves it untouched.
 *
 * @author Layout Compressor contributors
 */
public class LayoutCompressor implements IProjectEditor {

  /** Extra routing room around the occupied area, in cells. */
  public static final int ROUTING_MARGIN_CELLS = 2;

  /** Outcome stats for the result dialog. */
  public record Stats(Rectangle boardCells, int netCount, int wireLength, int jumperCount,
      int movedParts, int remoteParts, int flyingWires) {
  }

  private final List<ContinuityArea> continuityAreas;
  private final Function<IDIYComponent<?>, Rectangle2D> bodyBoundsProvider;
  private Stats stats;

  public LayoutCompressor(List<ContinuityArea> continuityAreas,
      Function<IDIYComponent<?>, Rectangle2D> bodyBoundsProvider) {
    this.continuityAreas = continuityAreas;
    this.bodyBoundsProvider = bodyBoundsProvider;
  }

  @Override
  public String getEditAction() {
    return "Compress Layout";
  }

  /** Stats of the last successful {@link #edit}, for reporting. */
  public Stats getStats() {
    return stats;
  }

  @Override
  public Set<IDIYComponent<?>> edit(Project project, Set<IDIYComponent<?>> selection) {
    ComponentClassifier classifier = new ComponentClassifier();
    Classification beforeClassification = classifier.classify(project);
    List<Group> nets = NetExtractor.extractNets(project, continuityAreas,
        beforeClassification.getRealParts());
    Set<Set<String>> beforeNets = canonical(nets, beforeClassification.getRealParts(), null);

    // all mutations happen on a scratch clone; the clone preserves component order, which maps
    // originals to their clones (and net nodes with them)
    Project scratch = project.clone();
    Map<IDIYComponent<?>, IDIYComponent<?>> toScratch =
        new IdentityHashMap<IDIYComponent<?>, IDIYComponent<?>>();
    for (int i = 0; i < project.getComponents().size(); i++) {
      toScratch.put(project.getComponents().get(i), scratch.getComponents().get(i));
    }

    Classification classification = classifier.classify(scratch);
    scratch.getComponents().removeAll(classification.getConnectivity());
    scratch.getComponents().removeAll(classification.getBoards());

    // partition real parts: movable ones get placed; locked and off-grid ones stay put
    List<Footprint> movable = new ArrayList<Footprint>();
    List<Footprint> fixed = new ArrayList<Footprint>();
    Set<IDIYComponent<?>> lockedOriginals = project.getLockedComponents();
    for (IDIYComponent<?> original : beforeClassification.getRealParts()) {
      IDIYComponent<?> clone = toScratch.get(original);
      Rectangle2D bodyBounds =
          bodyBoundsProvider == null ? null : bodyBoundsProvider.apply(original);
      Footprint footprint = Footprint.of(clone, bodyBounds);
      if (lockedOriginals.contains(original)) {
        fixed.add(footprint);
      } else {
        movable.add(footprint);
      }
    }

    Seed seed = new PlacementSeeder().seed(movable);
    if (seed.placements().isEmpty()) {
      throw new RuntimeException("No on-grid parts to place; nothing to compress.");
    }

    // locked on-grid parts become immovable obstacles at their current (snapped) cells;
    // off-grid parts stay entirely off the board and get flying wires
    GridModel grid = new GridModel();
    Map<IDIYComponent<?>, Footprint> fixedOnBoard =
        new IdentityHashMap<IDIYComponent<?>, Footprint>();
    Set<IDIYComponent<?>> remote = new HashSet<IDIYComponent<?>>();
    remote.addAll(seed.offGridParts());
    for (Footprint footprint : fixed) {
      if (footprint.isOnGrid()) {
        fixedOnBoard.put(footprint.getComponent(), footprint);
        for (int i = 0; i < footprint.getPinCount(); i++) {
          int pinIndex = footprint.getPinIndices().get(i);
          Cell cell = GridModel
              .snap(footprint.getComponent().getControlPoint(pinIndex));
          grid.occupyPin(cell, footprint.getComponent(), pinIndex);
        }
      } else {
        remote.add(footprint.getComponent());
      }
    }

    Legalizer.Result legalized = new Legalizer().legalize(seed.placements(), grid);

    // where every board pin ended up, keyed by scratch component and control point index
    Map<IDIYComponent<?>, Map<Integer, Cell>> cellOf =
        new IdentityHashMap<IDIYComponent<?>, Map<Integer, Cell>>();
    for (Placement placement : legalized.placements()) {
      Map<Integer, Cell> pinMap = new HashMap<Integer, Cell>();
      List<Cell> pinCells = placement.pinCells();
      for (int i = 0; i < pinCells.size(); i++) {
        pinMap.put(placement.footprint().getPinIndices().get(i), pinCells.get(i));
      }
      cellOf.put(placement.footprint().getComponent(), pinMap);
    }
    for (Footprint footprint : fixedOnBoard.values()) {
      Map<Integer, Cell> pinMap = new HashMap<Integer, Cell>();
      for (int pinIndex : footprint.getPinIndices()) {
        pinMap.put(pinIndex, GridModel.snap(footprint.getComponent().getControlPoint(pinIndex)));
      }
      cellOf.put(footprint.getComponent(), pinMap);
    }

    // board-side routing terminals per net; remote pins are connected by flying wires later
    List<List<Cell>> netCells = new ArrayList<List<Cell>>();
    for (Group net : nets) {
      Set<Cell> cells = new LinkedHashSet<Cell>();
      for (Node node : net.getSortedNodes()) {
        IDIYComponent<?> clone = toScratch.get(node.getComponent());
        Map<Integer, Cell> pinMap = cellOf.get(clone);
        if (pinMap != null && pinMap.containsKey(node.getPointIndex())) {
          cells.add(pinMap.get(node.getPointIndex()));
        }
      }
      netCells.add(new ArrayList<Cell>(cells));
    }

    Rectangle bounds = grid.occupiedBounds();
    bounds.grow(ROUTING_MARGIN_CELLS, ROUTING_MARGIN_CELLS);
    RoutingResult routing = new Router(grid, bounds).routeAll(netCells);

    Set<Cell> allPinCells = new HashSet<Cell>();
    for (Map<Integer, Cell> pinMap : cellOf.values()) {
      allPinCells.addAll(pinMap.values());
    }
    LayoutEmitter emitter = new LayoutEmitter();
    LayoutEmitter.Emission emission = emitter.emit(scratch, legalized.placements(), routing,
        grid.occupiedBounds(), allPinCells);

    // hook up remote (off-grid / locked off-grid) pins with flying wires: each pin to the
    // nearest board pin of its net (pins always carry wire endpoints, mid-run cells may not),
    // or pin to pin when the whole net is remote
    int flyingWires = 0;
    Set<IDIYComponent<?>> emitted = new HashSet<IDIYComponent<?>>(emission.wires());
    if (emission.board() != null) {
      emitted.add(emission.board());
    }
    for (int netId = 0; netId < nets.size(); netId++) {
      Point2D previousRemote = null;
      for (Node node : nets.get(netId).getSortedNodes()) {
        IDIYComponent<?> clone = toScratch.get(node.getComponent());
        if (!remote.contains(clone)) {
          continue;
        }
        Point2D pin = clone.getControlPoint(node.getPointIndex());
        Cell target = nearestCell(pin, netCells.get(netId));
        if (target != null) {
          emitted.add(emitter.emitFlyingWire(scratch, pin, GridModel.toPixels(target)));
          flyingWires++;
        } else if (previousRemote != null) {
          emitted.add(emitter.emitFlyingWire(scratch, pin, previousRemote));
          flyingWires++;
        }
        previousRemote = pin;
      }
    }

    // verification gate: netlist before == netlist after, using the compressor's own node
    // rule; on mismatch the real project has not been touched
    Classification afterClassification = classifier.classify(scratch);
    List<Group> afterNets =
        NetExtractor.extractNets(scratch, List.of(), afterClassification.getRealParts());
    Set<Set<String>> after =
        canonical(afterNets, afterClassification.getRealParts(), null);
    if (!beforeNets.equals(after)) {
      throw new RuntimeException("Netlist verification failed — the project was left "
          + "untouched. " + describeDiff(nets, beforeClassification.getRealParts(), afterNets,
              afterClassification.getRealParts()));
    }

    project.getComponents().clear();
    project.getComponents().addAll(scratch.getComponents());
    project.getLockedComponents().clear();
    project.getLockedComponents().addAll(scratch.getLockedComponents());
    project.getGroupsEx().clear();
    project.getGroupsEx().addAll(scratch.getGroupsEx());

    stats = new Stats(emission.board() == null ? null : grid.occupiedBounds(), nets.size(),
        routing.getTotalWireLength(), routing.getJumperCount(), legalized.placements().size(),
        remote.size(), flyingWires);
    return emitted;
  }

  private static Cell nearestCell(Point2D pin, Iterable<Cell> candidates) {
    Cell snapped = GridModel.snap(pin);
    Cell best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Cell cell : candidates) {
      int distance = Math.abs(cell.col() - snapped.col()) + Math.abs(cell.row() - snapped.row());
      if (distance < bestDistance) {
        bestDistance = distance;
        best = cell;
      }
    }
    return best;
  }

  /**
   * Canonical form of the nets: each net a set of "partIndex:pinIndex" keys, indices into the
   * real-part list — stable across cloning, independent of node names and coordinates.
   */
  private static Set<Set<String>> canonical(List<Group> nets, List<IDIYComponent<?>> realParts,
      Map<Set<String>, Group> groupIndex) {
    Map<IDIYComponent<?>, Integer> index = new IdentityHashMap<IDIYComponent<?>, Integer>();
    for (int i = 0; i < realParts.size(); i++) {
      index.put(realParts.get(i), i);
    }
    Set<Set<String>> canonical = new HashSet<Set<String>>();
    for (Group net : nets) {
      Set<String> keys = new HashSet<String>();
      for (Node node : net.getNodes()) {
        keys.add(index.get(node.getComponent()) + ":" + node.getPointIndex());
      }
      canonical.add(keys);
      if (groupIndex != null) {
        groupIndex.put(keys, net);
      }
    }
    return canonical;
  }

  private static String describeDiff(List<Group> before, List<IDIYComponent<?>> beforeParts,
      List<Group> after, List<IDIYComponent<?>> afterParts) {
    Map<Set<String>, Group> beforeIndex = new HashMap<Set<String>, Group>();
    Map<Set<String>, Group> afterIndex = new HashMap<Set<String>, Group>();
    Set<Set<String>> beforeKeys = canonical(before, beforeParts, beforeIndex);
    Set<Set<String>> afterKeys = canonical(after, afterParts, afterIndex);

    StringBuilder sb = new StringBuilder();
    for (Set<String> key : beforeKeys) {
      if (!afterKeys.contains(key)) {
        sb.append("Lost net: ").append(describeNet(beforeIndex.get(key))).append(". ");
      }
    }
    for (Set<String> key : afterKeys) {
      if (!beforeKeys.contains(key)) {
        sb.append("New net: ").append(describeNet(afterIndex.get(key))).append(". ");
      }
    }
    return sb.toString();
  }

  private static String describeNet(Group net) {
    StringBuilder sb = new StringBuilder();
    for (Node node : net.getSortedNodes()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(node.getComponent().getName()).append('.').append(node.getPointIndex());
    }
    return sb.toString();
  }
}

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

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.diylc.common.ComponentType;
import org.diylc.common.LineStyle;
import org.diylc.components.boards.PerfBoard;
import org.diylc.components.connectivity.Jumper;
import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.diylc.presenter.ComponentProcessor;

/**
 * Turns the compressed state back into DIYLC components: moves placed parts onto their lattice
 * cells (rotating through the component's transformer so orientation properties stay in sync),
 * realizes underside runs as dashed dark {@link Jumper}s and top-side jumpers as solid red
 * ones, and shrink-wraps a {@link PerfBoard} around the result, board first in z-order.
 *
 * @author Layout Compressor contributors
 */
public class LayoutEmitter {

  public static final Color UNDERSIDE_COLOR = new Color(70, 70, 70);
  public static final Color TOP_JUMPER_COLOR = Color.red;
  public static final int BOARD_MARGIN_CELLS = 1;

  /** Components created by the emitter: wires plus the board (null when nothing to wrap). */
  public record Emission(List<IDIYComponent<?>> wires, PerfBoard board) {
  }

  /**
   * Applies placements and adds wires and the board to the project. {@code boardCells} is the
   * cell-space bounding box to wrap (typically the grid's occupied bounds); the board gets a
   * one-cell margin beyond it.
   */
  public Emission emit(Project project, List<Placement> placements, RoutingResult routing,
      Rectangle boardCells) {
    for (Placement placement : placements) {
      move(placement);
    }

    Set<String> names = new HashSet<String>();
    for (IDIYComponent<?> component : project.getComponents()) {
      names.add(component.getName());
    }

    List<IDIYComponent<?>> wires = new ArrayList<IDIYComponent<?>>();
    if (routing != null) {
      for (RoutedNet net : routing.getNets()) {
        for (List<Cell> run : net.getRuns()) {
          for (int[] segment : straightSegments(run)) {
            wires.add(wire(GridModel.toPixels(run.get(segment[0])),
                GridModel.toPixels(run.get(segment[1])), UNDERSIDE_COLOR, LineStyle.DASHED,
                nextName(names)));
          }
        }
        for (RoutedNet.Jumper jumper : net.getJumpers()) {
          wires.add(wire(GridModel.toPixels(jumper.from()), GridModel.toPixels(jumper.to()),
              TOP_JUMPER_COLOR, LineStyle.SOLID, nextName(names)));
        }
      }
    }
    project.getComponents().addAll(wires);

    PerfBoard board = null;
    if (boardCells != null) {
      board = new PerfBoard();
      board.setName(nextName(names, "Board"));
      board.setControlPoint(GridModel.toPixels(new Cell(boardCells.x - BOARD_MARGIN_CELLS,
          boardCells.y - BOARD_MARGIN_CELLS)), 0);
      board.setControlPoint(GridModel.toPixels(
          new Cell(boardCells.x + boardCells.width + BOARD_MARGIN_CELLS,
              boardCells.y + boardCells.height + BOARD_MARGIN_CELLS)), 1);
      project.getComponents().add(0, board);
    }
    return new Emission(wires, board);
  }

  /** A top-side flying wire between two arbitrary pixel points (off-grid part hookup). */
  public IDIYComponent<?> emitFlyingWire(Project project, Point2D from, Point2D to) {
    Set<String> names = new HashSet<String>();
    for (IDIYComponent<?> component : project.getComponents()) {
      names.add(component.getName());
    }
    IDIYComponent<?> wire = wire(from, to, TOP_JUMPER_COLOR, LineStyle.SOLID, nextName(names));
    project.getComponents().add(wire);
    return wire;
  }

  private static void move(Placement placement) {
    Footprint footprint = placement.footprint();
    IDIYComponent<?> component = footprint.getComponent();
    List<Cell> pinCells = placement.pinCells();
    if (footprint.isStretchable()) {
      component.setControlPoint(GridModel.toPixels(pinCells.get(0)),
          footprint.getPinIndices().get(0));
      component.setControlPoint(GridModel.toPixels(pinCells.get(1)),
          footprint.getPinIndices().get(1));
      return;
    }

    // rotate in place around pin 0, then translate rigidly so every control point (sticky or
    // not) keeps the part's internal geometry
    Point2D pin0 = component.getControlPoint(footprint.getPinIndices().get(0));
    if (placement.quarterTurns() != 0) {
      rotate(component, pin0, placement.quarterTurns());
      pin0 = component.getControlPoint(footprint.getPinIndices().get(0));
    }
    Point2D target = GridModel.toPixels(pinCells.get(0));
    double dx = target.getX() - pin0.getX();
    double dy = target.getY() - pin0.getY();
    for (int i = 0; i < component.getControlPointCount(); i++) {
      Point2D p = component.getControlPoint(i);
      component.setControlPoint(new Point2D.Double(p.getX() + dx, p.getY() + dy), i);
    }
  }

  @SuppressWarnings("unchecked")
  private static void rotate(IDIYComponent<?> component, Point2D center, int quarterTurns) {
    ComponentType type = ComponentProcessor.getInstance()
        .extractComponentTypeFrom((Class<? extends IDIYComponent<?>>) component.getClass());
    if (type == null || type.getTransformer() == null
        || !type.getTransformer().canRotate(component)) {
      return;
    }
    int turns = ((quarterTurns % 4) + 4) % 4;
    Point2D fixedCenter = new Point2D.Double(center.getX(), center.getY());
    for (int t = 0; t < turns; t++) {
      type.getTransformer().rotate(component, fixedCenter, 1);
    }
  }

  private static IDIYComponent<?> wire(Point2D from, Point2D to, Color color, LineStyle style,
      String name) {
    Jumper jumper = new Jumper();
    jumper.setName(name);
    jumper.setControlPoint(from, 0);
    jumper.setControlPoint(to, 1);
    jumper.setLeadColor(color);
    jumper.setStyle(style);
    return jumper;
  }

  private static String nextName(Set<String> names) {
    return nextName(names, "W");
  }

  private static String nextName(Set<String> names, String prefix) {
    int i = 1;
    while (names.contains(prefix + i)) {
      i++;
    }
    names.add(prefix + i);
    return prefix + i;
  }

  /**
   * Indices into the run marking maximal straight stretches: each pair is the segment's first
   * and last cell index.
   */
  private static List<int[]> straightSegments(List<Cell> run) {
    List<int[]> segments = new ArrayList<int[]>();
    if (run.size() < 2) {
      return segments;
    }
    int start = 0;
    int lastDc = run.get(1).col() - run.get(0).col();
    int lastDr = run.get(1).row() - run.get(0).row();
    for (int i = 2; i < run.size(); i++) {
      int dc = run.get(i).col() - run.get(i - 1).col();
      int dr = run.get(i).row() - run.get(i - 1).row();
      if (dc != lastDc || dr != lastDr) {
        segments.add(new int[] {start, i - 1});
        start = i - 1;
        lastDc = dc;
        lastDr = dr;
      }
    }
    segments.add(new int[] {start, run.size() - 1});
    return segments;
  }
}

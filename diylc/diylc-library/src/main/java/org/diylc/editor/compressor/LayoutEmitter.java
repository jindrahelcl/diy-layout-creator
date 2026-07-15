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
import org.diylc.components.connectivity.CopperTrace;
import org.diylc.components.connectivity.Jumper;
import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.diylc.presenter.ComponentProcessor;

/**
 * Turns the compressed state back into DIYLC components: moves placed parts onto their lattice
 * cells (rotating through the component's transformer so orientation properties stay in sync),
 * realizes underside runs as {@link CopperTrace}s and top-side jumpers as solid red
 * {@link Jumper}s, and shrink-wraps a {@link PerfBoard} around the result. Z-order places the
 * board lowest, then traces, then the placed parts, with jumpers on top of everything.
 *
 * @author Layout Compressor contributors
 */
public class LayoutEmitter {

  public static final Color TOP_JUMPER_COLOR = Color.red;
  public static final int BOARD_MARGIN_CELLS = 1;

  /** Components created by the emitter: wires plus the board (null when nothing to wrap). */
  public record Emission(List<IDIYComponent<?>> wires, PerfBoard board) {
  }

  /** Same as {@link #emit(Project, List, RoutingResult, Rectangle, Set, int)}, with the default margin. */
  public Emission emit(Project project, List<Placement> placements, RoutingResult routing,
      Rectangle boardCells, Set<Cell> pinCells) {
    return emit(project, placements, routing, boardCells, pinCells, BOARD_MARGIN_CELLS);
  }

  /**
   * Applies placements and adds wires and the board to the project. {@code boardCells} is the
   * cell-space bounding box to wrap (typically the grid's occupied bounds); the board gets a
   * {@code marginCells}-cell margin beyond it. {@code pinCells} holds every cell occupied by a
   * pin: DIYLC only connects wires at their endpoints, so emitted segments must break wherever a
   * run touches a pin or another wire of the net, even mid-straight.
   */
  public Emission emit(Project project, List<Placement> placements, RoutingResult routing,
      Rectangle boardCells, Set<Cell> pinCells, int marginCells) {
    for (Placement placement : placements) {
      move(placement);
    }

    Set<String> names = new HashSet<String>();
    for (IDIYComponent<?> component : project.getComponents()) {
      names.add(component.getName());
    }

    List<IDIYComponent<?>> traces = new ArrayList<IDIYComponent<?>>();
    List<IDIYComponent<?>> jumpers = new ArrayList<IDIYComponent<?>>();
    if (routing != null) {
      for (RoutedNet net : routing.getNets()) {
        Set<Cell> connectionCells = new HashSet<Cell>(pinCells);
        for (List<Cell> run : net.getRuns()) {
          connectionCells.add(run.get(0));
          connectionCells.add(run.get(run.size() - 1));
        }
        for (RoutedNet.Jumper jumper : net.getJumpers()) {
          connectionCells.add(jumper.from());
          connectionCells.add(jumper.to());
        }
        for (List<Cell> run : net.getRuns()) {
          for (int[] segment : segments(run, connectionCells)) {
            traces.add(trace(GridModel.toPixels(run.get(segment[0])),
                GridModel.toPixels(run.get(segment[1])), nextName(names, "Trace")));
          }
        }
        for (RoutedNet.Jumper jumper : net.getJumpers()) {
          jumpers.add(wire(GridModel.toPixels(jumper.from()), GridModel.toPixels(jumper.to()),
              nextName(names)));
        }
      }
    }
    // traces sit under everything (underside copper); jumpers sit over everything (top-side
    // flying leads), so each goes to its own end of the z-order list rather than being appended
    // together.
    project.getComponents().addAll(0, traces);
    project.getComponents().addAll(jumpers);
    List<IDIYComponent<?>> wires = new ArrayList<IDIYComponent<?>>(traces);
    wires.addAll(jumpers);

    PerfBoard board = null;
    if (boardCells != null) {
      board = new PerfBoard();
      board.setName(nextName(names, "Board"));
      board.setControlPoint(GridModel.toPixels(new Cell(boardCells.x - marginCells,
          boardCells.y - marginCells)), 0);
      board.setControlPoint(GridModel.toPixels(
          new Cell(boardCells.x + boardCells.width + marginCells,
              boardCells.y + boardCells.height + marginCells)), 1);
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
    IDIYComponent<?> wire = wire(from, to, nextName(names));
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

  private static IDIYComponent<?> trace(Point2D from, Point2D to, String name) {
    CopperTrace trace = new CopperTrace();
    trace.setName(name);
    trace.setControlPoint(from, 0);
    trace.setControlPoint(to, 1);
    return trace;
  }

  private static IDIYComponent<?> wire(Point2D from, Point2D to, String name) {
    Jumper jumper = new Jumper();
    jumper.setName(name);
    jumper.setControlPoint(from, 0);
    jumper.setControlPoint(to, 1);
    jumper.setLeadColor(TOP_JUMPER_COLOR);
    jumper.setStyle(LineStyle.SOLID);
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
   * Indices into the run marking emitted wire pieces: each pair is the piece's first and last
   * cell index. A piece ends at a direction change and at every connection cell (pin or
   * junction with another wire) — wires only connect at their endpoints.
   */
  private static List<int[]> segments(List<Cell> run, Set<Cell> connectionCells) {
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
      if (dc != lastDc || dr != lastDr || connectionCells.contains(run.get(i - 1))) {
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

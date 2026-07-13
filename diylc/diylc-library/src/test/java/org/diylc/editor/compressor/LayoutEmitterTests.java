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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.diylc.common.LineStyle;
import org.diylc.components.boards.PerfBoard;
import org.diylc.components.connectivity.Jumper;
import org.diylc.components.connectivity.PCBTerminalBlock;
import org.diylc.components.passive.Resistor;
import org.diylc.core.Project;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.LayoutEmitter.Emission;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.junit.Test;

public class LayoutEmitterTests {

  private static Resistor resistorAt(double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  @Test
  public void movesStretchablePartOntoLattice() {
    Resistor resistor = resistorAt(333, 444);
    Placement placement = new Placement(Footprint.of(resistor), new Cell(5, 5), 0, 4);
    Project project = new Project();
    project.getComponents().add(resistor);

    new LayoutEmitter().emit(project, List.of(placement), null, null, Set.of());

    assertEquals(new Point2D.Double(100, 100), resistor.getControlPoint(0));
    assertEquals(new Point2D.Double(180, 100), resistor.getControlPoint(1));
  }

  @Test
  public void rotatesFixedPartThroughItsTransformer() {
    PCBTerminalBlock block = new PCBTerminalBlock();
    // default pins run vertically at 0.2" pitch; one clockwise turn makes them run left
    Placement placement = new Placement(Footprint.of(block), new Cell(5, 5), 1, 0);
    Project project = new Project();
    project.getComponents().add(block);

    new LayoutEmitter().emit(project, List.of(placement), null, null, Set.of());

    assertEquals(new Point2D.Double(100, 100), block.getControlPoint(0));
    assertEquals(new Point2D.Double(60, 100), block.getControlPoint(1));
    assertEquals(new Point2D.Double(20, 100), block.getControlPoint(2));
  }

  @Test
  public void splitsRunsIntoStraightDashedSegments() {
    RoutedNet net = new RoutedNet(0);
    net.getRuns().add(Arrays.asList(new Cell(0, 0), new Cell(1, 0), new Cell(2, 0),
        new Cell(2, 1), new Cell(2, 2)));
    RoutingResult routing = new RoutingResult();
    routing.getNets().add(net);
    Project project = new Project();

    Emission emission = new LayoutEmitter().emit(project, List.of(), routing, null, Set.of());

    assertEquals(2, emission.wires().size());
    Jumper first = (Jumper) emission.wires().get(0);
    Jumper second = (Jumper) emission.wires().get(1);
    assertEquals(new Point2D.Double(0, 0), first.getControlPoint(0));
    assertEquals(new Point2D.Double(40, 0), first.getControlPoint(1));
    assertEquals(new Point2D.Double(40, 0), second.getControlPoint(0));
    assertEquals(new Point2D.Double(40, 40), second.getControlPoint(1));
    assertEquals(LineStyle.DASHED, first.getStyle());
    assertEquals(LayoutEmitter.UNDERSIDE_COLOR, first.getLeadColor());
  }

  @Test
  public void runsBreakAtMidSegmentPins() {
    // a straight run passes through a same-net pin at (2,0); wires only connect at their
    // endpoints, so the run must be emitted as two jumpers meeting on that pin
    RoutedNet net = new RoutedNet(0);
    net.getRuns().add(Arrays.asList(new Cell(0, 0), new Cell(1, 0), new Cell(2, 0),
        new Cell(3, 0), new Cell(4, 0)));
    RoutingResult routing = new RoutingResult();
    routing.getNets().add(net);
    Project project = new Project();

    Emission emission =
        new LayoutEmitter().emit(project, List.of(), routing, null, Set.of(new Cell(2, 0)));

    assertEquals(2, emission.wires().size());
    assertEquals(new Point2D.Double(40, 0), emission.wires().get(0).getControlPoint(1));
    assertEquals(new Point2D.Double(40, 0), emission.wires().get(1).getControlPoint(0));
    assertEquals(new Point2D.Double(80, 0), emission.wires().get(1).getControlPoint(1));
  }

  @Test
  public void runsBreakWhereBranchesAttach() {
    // the second run tees into the middle of the first at (2,0), so the first run must break
    // there to give the branch a shared endpoint
    RoutedNet net = new RoutedNet(0);
    net.getRuns().add(Arrays.asList(new Cell(0, 0), new Cell(1, 0), new Cell(2, 0),
        new Cell(3, 0), new Cell(4, 0)));
    net.getRuns().add(Arrays.asList(new Cell(2, 2), new Cell(2, 1), new Cell(2, 0)));
    RoutingResult routing = new RoutingResult();
    routing.getNets().add(net);
    Project project = new Project();

    Emission emission = new LayoutEmitter().emit(project, List.of(), routing, null, Set.of());

    assertEquals(3, emission.wires().size());
    assertEquals(new Point2D.Double(40, 0), emission.wires().get(0).getControlPoint(1));
    assertEquals(new Point2D.Double(40, 0), emission.wires().get(1).getControlPoint(0));
  }

  @Test
  public void topJumpersAreSolidRed() {
    RoutedNet net = new RoutedNet(0);
    net.getJumpers().add(new RoutedNet.Jumper(new Cell(0, 0), new Cell(3, 4)));
    RoutingResult routing = new RoutingResult();
    routing.getNets().add(net);
    Project project = new Project();

    Emission emission = new LayoutEmitter().emit(project, List.of(), routing, null, Set.of());

    assertEquals(1, emission.wires().size());
    Jumper jumper = (Jumper) emission.wires().get(0);
    assertEquals(LineStyle.SOLID, jumper.getStyle());
    assertEquals(LayoutEmitter.TOP_JUMPER_COLOR, jumper.getLeadColor());
    assertEquals(new Point2D.Double(60, 80), jumper.getControlPoint(1));
  }

  @Test
  public void boardWrapsEverythingAndComesFirst() {
    Resistor resistor = resistorAt(333, 444);
    Placement placement = new Placement(Footprint.of(resistor), new Cell(5, 5), 0, 5);
    Project project = new Project();
    project.getComponents().add(resistor);

    Emission emission = new LayoutEmitter().emit(project, List.of(placement), null,
        new Rectangle(5, 5, 5, 0), Set.of());

    assertTrue(project.getComponents().get(0) instanceof PerfBoard);
    PerfBoard board = emission.board();
    assertEquals(new Point2D.Double(80, 80), board.getControlPoint(0));
    assertEquals(new Point2D.Double(220, 120), board.getControlPoint(1));
  }
}

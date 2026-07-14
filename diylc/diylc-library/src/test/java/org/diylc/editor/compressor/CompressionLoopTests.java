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

import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.List;

import org.diylc.components.passive.Resistor;
import org.diylc.editor.compressor.CompressionState.PinRef;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.junit.Test;

public class CompressionLoopTests {

  private static Resistor resistorAt(double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  /** Two resistors far apart, joined pin-to-pin by two nets — lots of room to improve. */
  private static CompressionState sprawlingState(Resistor a, Resistor b) {
    Placement pa = new Placement(Footprint.of(a), new Cell(1, 1), 0, 4);
    Placement pb = new Placement(Footprint.of(b), new Cell(1, 9), 0, 4);
    Legalizer.Result legalized = new Legalizer().legalize(List.of(pa, pb));
    List<List<PinRef>> nets = List.of(
        List.of(new PinRef(a, 0), new PinRef(b, 0)),
        List.of(new PinRef(a, 1), new PinRef(b, 1)));
    CompressionState state = new CompressionState(legalized.grid(), legalized.placements(),
        new HashMap<>(), nets, 1);
    state.rerouteAll();
    return state;
  }

  @Test
  public void greedyDescentPullsSprawlingPartsTogether() {
    Resistor a = resistorAt(100, 100);
    Resistor b = resistorAt(100, 900);
    CompressionState state = sprawlingState(a, b);
    long initial = state.cost();
    assertEquals(156, initial);

    long finalCost = new CompressionLoop(state, 42).run(300);

    // b can slide all the way up to the row below a: extent 5+2 cols/rows, wire length 2
    assertTrue("cost should drop well below " + initial + ", got " + finalCost,
        finalCost < 100);
    assertEquals(finalCost, state.cost());
    assertEquals(0, state.getRouting().getJumperCount());
    for (RoutedNet net : state.getRouting().getNets()) {
      assertTrue(net.getFailedPins().isEmpty());
    }
  }

  @Test
  public void loopIsDeterministicForASeed() {
    long first = new CompressionLoop(
        sprawlingState(resistorAt(100, 100), resistorAt(100, 900)), 7).run(150);
    long second = new CompressionLoop(
        sprawlingState(resistorAt(100, 100), resistorAt(100, 900)), 7).run(150);

    assertEquals(first, second);
  }

  @Test
  public void greedyNeverAcceptsAWorseState() {
    CompressionState state = sprawlingState(resistorAt(100, 100), resistorAt(100, 900));
    long initial = state.cost();

    long finalCost = new CompressionLoop(state, 1).run(50);

    assertTrue(finalCost <= initial);
  }
}

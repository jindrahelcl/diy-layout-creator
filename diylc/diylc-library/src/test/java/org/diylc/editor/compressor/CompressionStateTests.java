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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.diylc.core.IDIYComponent;
import org.diylc.components.passive.Resistor;
import org.diylc.editor.compressor.CompressionState.PinRef;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.junit.Test;

public class CompressionStateTests {

  private static Resistor resistorAt(double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  /**
   * Two resistors, one above the other, connected pin-to-pin by two vertical nets:
   *
   * <pre>
   *   a0 ---a--- a1        net 0: a0-b0 (straight run, length 2)
   *   |          |         net 1: a1-b1 (straight run, length 2)
   *   b0 ---b--- b1
   * </pre>
   */
  private static CompressionState twoResistorState(Resistor a, Resistor b) {
    Placement pa = new Placement(Footprint.of(a), new Cell(1, 1), 0, 4);
    Placement pb = new Placement(Footprint.of(b), new Cell(1, 3), 0, 4);
    Legalizer.Result legalized = new Legalizer().legalize(List.of(pa, pb));
    List<List<PinRef>> nets = List.of(
        List.of(new PinRef(a, 0), new PinRef(b, 0)),
        List.of(new PinRef(a, 1), new PinRef(b, 1)));
    return new CompressionState(legalized.grid(), legalized.placements(), new HashMap<>(),
        nets, 1);
  }

  @Test
  public void resolvesPinCellsThroughPlacementsAndFixedPins() {
    Resistor placed = resistorAt(100, 100);
    Resistor fixed = resistorAt(300, 300);
    Resistor remote = resistorAt(500, 500);
    Placement placement = new Placement(Footprint.of(placed), new Cell(5, 5), 0, 4);
    Map<IDIYComponent<?>, Map<Integer, Cell>> fixedPins = new HashMap<>();
    fixedPins.put(fixed, Map.of(0, new Cell(2, 8)));
    List<List<PinRef>> nets = List.of(
        List.of(new PinRef(placed, 0), new PinRef(fixed, 0), new PinRef(remote, 0)),
        List.of(new PinRef(placed, 1)));
    CompressionState state = new CompressionState(new GridModel(), List.of(placement),
        fixedPins, nets, 1);

    assertEquals(new Cell(5, 5), state.cellOf(new PinRef(placed, 0)));
    assertEquals(new Cell(9, 5), state.cellOf(new PinRef(placed, 1)));
    assertEquals(new Cell(2, 8), state.cellOf(new PinRef(fixed, 0)));
    assertNull(state.cellOf(new PinRef(remote, 0)));
    // the remote pin drops out of the net's board cells
    assertEquals(List.of(new Cell(5, 5), new Cell(2, 8)), state.netCells().get(0));
    assertEquals(Set.of(0), state.netsTouching(fixed));
    assertEquals(Set.of(0, 1), state.netsTouching(placed));
    assertTrue(state.netsTouching(resistorAt(0, 0)).isEmpty());
  }

  @Test
  public void costCombinesExtentJumpersAndWireLength() {
    CompressionState state = twoResistorState(resistorAt(100, 100), resistorAt(100, 300));

    state.rerouteAll();

    assertEquals(0, state.getRouting().getJumperCount());
    assertEquals(4, state.getRouting().getTotalWireLength());
    // bounds cols 1..5, rows 1..3: extent (5 + 3) * 10, plus 4 length
    assertEquals(84, state.cost());
  }

  @Test
  public void rerouteAllIsIdempotent() {
    CompressionState state = twoResistorState(resistorAt(100, 100), resistorAt(100, 300));

    state.rerouteAll();
    long first = state.cost();
    state.rerouteAll();

    assertEquals(first, state.cost());
  }

  @Test
  public void applyPlacementMovesTheComponent() {
    Resistor a = resistorAt(100, 100);
    Resistor b = resistorAt(100, 300);
    CompressionState state = twoResistorState(a, b);
    state.rerouteAll();

    // push b two rows down: both nets get longer, the old cells free up
    state.applyPlacement(new Placement(state.placementOf(b).footprint(), new Cell(1, 5), 0, 4));
    state.rerouteAll();

    assertEquals(new Cell(1, 5), state.cellOf(new PinRef(b, 0)));
    assertEquals(8, state.getRouting().getTotalWireLength());
    // bounds cols 1..5, rows 1..5: extent (5 + 5) * 10, plus 8 length
    assertEquals(108, state.cost());
    assertTrue(state.getGrid().pinsAt(new Cell(1, 3)).isEmpty());
  }
}

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.diylc.components.connectivity.PCBTerminalBlock;
import org.diylc.components.passive.Resistor;
import org.diylc.components.semiconductors.DIL_IC;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.Legalizer.Result;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.junit.Test;

public class LegalizerTests {

  private static Resistor resistorAt(double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  private static Placement resistorSeed(int col, int row) {
    return new Placement(Footprint.of(resistorAt(100, 100)), new Cell(col, row), 0, 5);
  }

  @Test
  public void nonOverlappingSeedsStayPut() {
    Placement a = resistorSeed(5, 5);
    Placement b = resistorSeed(5, 7);

    Result result = new Legalizer().legalize(Arrays.asList(a, b));

    assertEquals(a, result.placements().get(0));
    assertEquals(b, result.placements().get(1));
  }

  @Test
  public void overlappingSeedsAreSeparated() {
    Placement a = resistorSeed(5, 5);
    Placement b = resistorSeed(5, 5);

    Result result = new Legalizer().legalize(Arrays.asList(a, b));

    // first seed keeps its spot, second moves to the nearest free spot on the ring above
    assertEquals(new Cell(5, 5), result.placements().get(0).reference());
    assertEquals(new Cell(4, 4), result.placements().get(1).reference());
    Set<Cell> pins = new HashSet<Cell>(result.placements().get(0).pinCells());
    pins.retainAll(result.placements().get(1).pinCells());
    assertTrue(pins.isEmpty());
  }

  @Test
  public void largerPartWinsContestedSpot() {
    // resistor listed first, but the IC has the bigger footprint and gets placed first
    Placement resistor = resistorSeed(10, 10);
    Placement ic = new Placement(Footprint.of(new DIL_IC()), new Cell(10, 10), 0, 0);

    Result result = new Legalizer().legalize(Arrays.asList(resistor, ic));

    assertEquals(new Cell(10, 10), result.placements().get(1).reference());
    assertTrue(!result.placements().get(0).reference().equals(new Cell(10, 10)));
  }

  @Test
  public void rotatesInPlaceWhenRowIsBlocked() {
    // terminal block pins span cells (5,5) (5,7) (5,9); a foreign pin at (5,7) blocks the
    // default vertical orientation but the rotated one fits at the same reference
    GridModel grid = new GridModel();
    grid.occupyPin(new Cell(5, 7), resistorAt(0, 0), 0);
    Placement block =
        new Placement(Footprint.of(new PCBTerminalBlock()), new Cell(5, 5), 0, 0);

    Result result = new Legalizer().legalize(List.of(block), grid);

    assertEquals(new Cell(5, 5), result.placements().get(0).reference());
    assertEquals(1, result.placements().get(0).quarterTurns());
  }

  @Test
  public void stretchableSpanShrinksWhenBlocked() {
    GridModel grid = new GridModel();
    grid.occupyPin(new Cell(10, 5), resistorAt(0, 0), 0);

    Result result = new Legalizer().legalize(List.of(resistorSeed(5, 5)), grid);

    Placement placed = result.placements().get(0);
    assertEquals(new Cell(5, 5), placed.reference());
    assertEquals(0, placed.quarterTurns());
    assertEquals(4, placed.span());
  }

  @Test
  public void everySeedGetsPlaced() {
    List<Placement> seeds = new ArrayList<Placement>();
    for (int i = 0; i < 10; i++) {
      seeds.add(resistorSeed(5, 5));
    }

    Result result = new Legalizer().legalize(seeds);

    Set<Cell> allPins = new HashSet<Cell>();
    for (Placement placement : result.placements()) {
      for (Cell cell : placement.pinCells()) {
        assertTrue("pin cell used twice: " + cell, allPins.add(cell));
        assertTrue(cell.col() >= 0 && cell.row() >= 0);
      }
    }
    assertEquals(20, allPins.size());
  }

  @Test
  public void bodyCellsBlockOtherParts() {
    // a resistor from (5,5) to (10,5) also occupies the strip between its pins, so a second
    // resistor seeded at (7,5) cannot stay on row 5 and moves to the ring above
    Placement first = resistorSeed(5, 5);
    Placement second = new Placement(Footprint.of(resistorAt(100, 100)), new Cell(7, 5), 0, 5);

    Result result = new Legalizer().legalize(Arrays.asList(first, second));

    assertEquals(new Cell(5, 5), result.placements().get(0).reference());
    assertEquals(new Cell(6, 4), result.placements().get(1).reference());
  }
}

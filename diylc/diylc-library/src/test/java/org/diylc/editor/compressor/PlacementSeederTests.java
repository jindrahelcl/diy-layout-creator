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

import org.diylc.components.passive.Resistor;
import org.diylc.components.semiconductors.TransistorTO92;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;
import org.diylc.editor.compressor.PlacementSeeder.Seed;
import org.junit.Test;

public class PlacementSeederTests {

  private static Resistor resistorAt(double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  @Test
  public void sprawlingLayoutShrinksPreservingRelativeOrder() {
    Footprint left = Footprint.of(resistorAt(100, 100));
    Footprint right = Footprint.of(resistorAt(2000, 1500));

    Seed seed = new PlacementSeeder().seed(Arrays.asList(left, right));

    assertEquals(2, seed.placements().size());
    Placement first = seed.placements().get(0);
    Placement second = seed.placements().get(1);
    // relative order preserved on both axes
    assertTrue(second.reference().col() > first.reference().col());
    assertTrue(second.reference().row() > first.reference().row());
    // and the spread is much tighter than the original 95x70 cells
    assertTrue(second.reference().col() - first.reference().col() < 20);
    assertTrue(second.reference().row() - first.reference().row() < 20);
  }

  @Test
  public void tightLayoutIsNotInflated() {
    Footprint a = Footprint.of(resistorAt(100, 100));
    Footprint b = Footprint.of(resistorAt(100, 120));

    Seed seed = new PlacementSeeder().seed(Arrays.asList(a, b));

    assertEquals(new Cell(5, 5), seed.placements().get(0).reference());
    assertEquals(new Cell(5, 6), seed.placements().get(1).reference());
  }

  @Test
  public void offGridPartsAreLeftOut() {
    Footprint resistor = Footprint.of(resistorAt(100, 100));
    TransistorTO92 transistor = new TransistorTO92();
    Footprint offGrid = Footprint.of(transistor);

    Seed seed = new PlacementSeeder().seed(Arrays.asList(resistor, offGrid));

    assertEquals(1, seed.placements().size());
    assertEquals(List.of(transistor), seed.offGridParts());
  }

  @Test
  public void stretchablePartKeepsItsSpan() {
    Footprint footprint = Footprint.of(resistorAt(100, 100));

    Seed seed = new PlacementSeeder().seed(List.of(footprint));

    assertEquals(5, seed.placements().get(0).span());
  }

  @Test
  public void placementComputesPinCellsWithRotationAndSpan() {
    Footprint footprint = Footprint.of(resistorAt(100, 100));

    Placement horizontal = new Placement(footprint, new Cell(3, 4), 0, 5);
    assertEquals(Arrays.asList(new Cell(3, 4), new Cell(8, 4)), horizontal.pinCells());

    Placement vertical = new Placement(footprint, new Cell(3, 4), 1, 5);
    assertEquals(Arrays.asList(new Cell(3, 4), new Cell(3, 9)), vertical.pinCells());
  }

  @Test
  public void placementRotatesBodyCells() {
    Resistor resistor = resistorAt(100, 100);
    // body from px 120..180 x 90..110: cells 1..4 x 0..0 relative to pin 0
    Footprint footprint = Footprint.of(resistor,
        new java.awt.geom.Rectangle2D.Double(120, 90, 60, 20));

    Placement flat = new Placement(footprint, new Cell(10, 10), 0, 5);
    assertEquals(new Rectangle(11, 10, 3, 0), flat.bodyCells());

    Placement turned = new Placement(footprint, new Cell(10, 10), 1, 5);
    assertEquals(new Rectangle(10, 11, 0, 3), turned.bodyCells());
  }

  @Test
  public void cellAreaCoversPinsAndBody() {
    Footprint resistor = Footprint.of(resistorAt(100, 100));
    // pins at cells 0 and 5: 6x1 cells
    assertEquals(6, PlacementSeeder.cellArea(resistor));
  }
}
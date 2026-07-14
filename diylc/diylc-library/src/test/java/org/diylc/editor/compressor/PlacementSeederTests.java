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
  public void seedNormalizesSpanToNaturalLength() {
    // obscenely stretched resistor (2" pin to pin) seeds at its natural span: the 0.5" body
    // with leads bent straight down at its ends = 5 cells
    Resistor stretched = new Resistor();
    stretched.setControlPoint(new Point2D.Double(100, 100), 0);
    stretched.setControlPoint(new Point2D.Double(500, 100), 1);

    Seed seed = new PlacementSeeder().seed(List.of(Footprint.of(stretched)));

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
    // default resistor body is 0.5" x 0.125" = 5 x 1.25 cells centered between the pins: at
    // span 5 it covers the full pin-to-pin range in a single row, plus the lead strip
    Footprint footprint = Footprint.of(resistorAt(100, 100));

    Placement flat = new Placement(footprint, new Cell(10, 10), 0, 5);
    assertEquals(Arrays.asList(new Rectangle(11, 10, 3, 0), new Rectangle(10, 10, 5, 0)),
        flat.bodyCells());

    Placement turned = new Placement(footprint, new Cell(10, 10), 1, 5);
    assertEquals(Arrays.asList(new Rectangle(10, 11, 0, 3), new Rectangle(10, 10, 0, 5)),
        turned.bodyCells());
  }

  @Test
  public void radialBodyKeepsItsSizeWhateverTheSpan() {
    org.diylc.components.passive.TantalumCapacitor capacitor =
        new org.diylc.components.passive.TantalumCapacitor();
    capacitor.setControlPoint(new java.awt.geom.Point2D.Double(100, 100), 0);
    capacitor.setControlPoint(new java.awt.geom.Point2D.Double(200, 100), 1);
    Footprint footprint = Footprint.of(capacitor);

    // squeezed to span 1 the fat round body still blocks holes around the pins, not just the
    // (empty) strip between them
    Placement tight = new Placement(footprint, new Cell(10, 10), 0, 1);
    assertEquals(1, tight.bodyCells().size());
    Rectangle body = tight.bodyCells().get(0);
    assertTrue("body should span rows around the pin row", body.height >= 1);
    assertTrue("body should cover the pins", body.width >= 1);
  }

  @Test
  public void cellAreaCoversPinsAndBody() {
    Footprint resistor = Footprint.of(resistorAt(100, 100));
    // pins at cells 0 and 5: 6x1 cells
    assertEquals(6, PlacementSeeder.cellArea(resistor));
  }
}
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Point2D;

import org.diylc.components.passive.Resistor;
import org.diylc.editor.compressor.GridModel.Cell;
import org.junit.Test;

public class GridModelTests {

  // 0.1" cell = 20 px at DIYLC's 200 px/inch

  @Test
  public void snapsToNearestCell() {
    assertEquals(new Cell(39, 37), GridModel.snap(new Point2D.Double(780, 740)));
    assertEquals(new Cell(39, 37), GridModel.snap(new Point2D.Double(783, 737)));
    assertEquals(new Cell(40, 37), GridModel.snap(new Point2D.Double(791, 740)));
    assertEquals(new Cell(-2, 0), GridModel.snap(new Point2D.Double(-42, 3)));
  }

  @Test
  public void onGridWithinTolerance() {
    assertTrue(GridModel.isOnGrid(new Point2D.Double(780, 740)));
    assertTrue(GridModel.isOnGrid(new Point2D.Double(784, 736)));
    assertFalse(GridModel.isOnGrid(new Point2D.Double(789, 740)));
    assertFalse(GridModel.isOnGrid(new Point2D.Double(780, 750)));
  }

  @Test
  public void convertsCellBackToPixels() {
    Point2D pixels = GridModel.toPixels(new Cell(39, 37));
    assertEquals(780d, pixels.getX(), 0.001);
    assertEquals(740d, pixels.getY(), 0.001);
  }

  @Test
  public void tracksOccupancyAndBounds() {
    GridModel model = new GridModel();
    Resistor resistor = new Resistor();

    assertNull(model.occupiedBounds());
    assertTrue(model.isFree(new Cell(5, 5)));

    model.occupyPin(new Cell(5, 5), resistor, 0);
    model.occupyBody(new Cell(6, 5), resistor);
    model.occupyBody(new Cell(7, 5), resistor);
    model.occupyPin(new Cell(8, 5), resistor, 1);

    assertFalse(model.isFree(new Cell(5, 5)));
    assertFalse(model.isFree(new Cell(6, 5)));
    assertEquals(1, model.pinsAt(new Cell(5, 5)).size());
    assertEquals(resistor, model.pinsAt(new Cell(5, 5)).get(0).component());
    assertTrue(model.bodiesAt(new Cell(6, 5)).contains(resistor));

    assertEquals(new Rectangle(5, 5, 3, 0), model.occupiedBounds());
  }

  @Test
  public void wireRunsClaimEdgesAndHolesPerNet() {
    GridModel model = new GridModel();
    // net 0 runs horizontally through (2,2)..(4,2)
    model.claimRun(0, java.util.List.of(new Cell(2, 2), new Cell(3, 2), new Cell(4, 2)));

    // same net may reuse its edges and holes; a foreign net may not
    assertTrue(model.canUseEdge(0, new Cell(2, 2), new Cell(3, 2)));
    assertFalse(model.canUseEdge(1, new Cell(2, 2), new Cell(3, 2)));
    assertFalse(model.canUseEdge(1, new Cell(3, 2), new Cell(2, 2)));
    assertTrue(model.canPassHole(0, new Cell(3, 2)));
    assertFalse(model.canPassHole(1, new Cell(3, 2)));

    // an edge crossing the run's holes is free, only the hole blocks
    assertTrue(model.canUseEdge(1, new Cell(3, 1), new Cell(3, 2)));

    model.releaseNet(0);
    assertTrue(model.canUseEdge(1, new Cell(2, 2), new Cell(3, 2)));
    assertTrue(model.canPassHole(1, new Cell(3, 2)));
  }

  @Test
  public void pinsBlockForeignRuns() {
    GridModel model = new GridModel();
    Resistor resistor = new Resistor();
    model.occupyPin(new Cell(5, 5), resistor, 0);
    model.occupyPin(new Cell(6, 5), resistor, 1);
    model.setPinNet(new Cell(5, 5), 0);

    assertTrue(model.canPassHole(0, new Cell(5, 5)));
    assertFalse(model.canPassHole(1, new Cell(5, 5)));
    // pin without net assignment blocks every run
    assertFalse(model.canPassHole(0, new Cell(6, 5)));
    assertFalse(model.canPassHole(1, new Cell(6, 5)));
  }
}

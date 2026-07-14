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
import static org.junit.Assert.assertTrue;

import org.diylc.editor.compressor.GridModel.Cell;
import org.junit.Test;

public class RoutingResultTests {

  private static boolean cross(int a, int b, int c, int d, int e, int f, int g, int h) {
    return RoutingResult.segmentsCross(new Cell(a, b), new Cell(c, d), new Cell(e, f),
        new Cell(g, h));
  }

  @Test
  public void detectsProperCrossing() {
    assertTrue(cross(0, 0, 4, 4, 0, 4, 4, 0));
  }

  @Test
  public void disjointSegmentsDontCross() {
    assertFalse(cross(0, 0, 2, 0, 0, 2, 2, 2));
  }

  @Test
  public void sharedEndpointIsNotACrossing() {
    assertFalse(cross(0, 0, 4, 4, 4, 4, 8, 0));
  }

  @Test
  public void endpointOnOtherWireCounts() {
    // T-touch: one jumper ends on top of another's wire
    assertTrue(cross(0, 0, 4, 0, 2, -2, 2, 0));
  }

  @Test
  public void collinearOverlapCounts() {
    assertTrue(cross(0, 0, 4, 0, 2, 0, 6, 0));
    assertFalse(cross(0, 0, 2, 0, 3, 0, 5, 0));
  }

  @Test
  public void countsCrossingPairsAcrossNets() {
    RoutedNet net0 = new RoutedNet(0);
    net0.getJumpers().add(new RoutedNet.Jumper(new Cell(0, 0), new Cell(4, 4)));
    RoutedNet net1 = new RoutedNet(1);
    net1.getJumpers().add(new RoutedNet.Jumper(new Cell(0, 4), new Cell(4, 0)));
    net1.getJumpers().add(new RoutedNet.Jumper(new Cell(10, 0), new Cell(10, 4)));
    RoutingResult result = new RoutingResult();
    result.getNets().add(net0);
    result.getNets().add(net1);

    // only the two diagonals cross; the far vertical jumper crosses nothing
    assertEquals(1, result.getJumperCrossings());
  }
}

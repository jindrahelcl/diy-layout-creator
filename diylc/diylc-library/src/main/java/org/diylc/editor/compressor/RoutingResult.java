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

import java.util.ArrayList;
import java.util.List;

import org.diylc.editor.compressor.GridModel.Cell;

/**
 * Result of routing all nets of a board: per-net wiring plus the aggregate metrics the
 * compression loop optimizes (jumpers are the expensive resource).
 *
 * @author Layout Compressor contributors
 */
public class RoutingResult {

  private final List<RoutedNet> nets = new ArrayList<RoutedNet>();

  public List<RoutedNet> getNets() {
    return nets;
  }

  public int getJumperCount() {
    return nets.stream().mapToInt((n) -> n.getJumpers().size()).sum();
  }

  public int getTotalWireLength() {
    return nets.stream().mapToInt(RoutedNet::getWireLength).sum();
  }

  /** Pairs of jumpers (any nets) whose straight wires touch or cross on the top side. */
  public int getJumperCrossings() {
    List<RoutedNet.Jumper> jumpers = new ArrayList<RoutedNet.Jumper>();
    for (RoutedNet net : nets) {
      jumpers.addAll(net.getJumpers());
    }
    int crossings = 0;
    for (int i = 0; i < jumpers.size(); i++) {
      for (int j = i + 1; j < jumpers.size(); j++) {
        if (segmentsCross(jumpers.get(i).from(), jumpers.get(i).to(), jumpers.get(j).from(),
            jumpers.get(j).to())) {
          crossings++;
        }
      }
    }
    return crossings;
  }

  /**
   * True when the two segments touch anywhere except at a shared endpoint — proper crossings,
   * endpoints lying on the other wire, and collinear overlaps all count (the wires would lie
   * on each other).
   */
  public static boolean segmentsCross(Cell p1, Cell p2, Cell q1, Cell q2) {
    if (p1.equals(q1) || p1.equals(q2) || p2.equals(q1) || p2.equals(q2)) {
      return false;
    }
    long d1 = orientation(q1, q2, p1);
    long d2 = orientation(q1, q2, p2);
    long d3 = orientation(p1, p2, q1);
    long d4 = orientation(p1, p2, q2);
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
        && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
      return true;
    }
    return (d1 == 0 && onSegment(q1, q2, p1)) || (d2 == 0 && onSegment(q1, q2, p2))
        || (d3 == 0 && onSegment(p1, p2, q1)) || (d4 == 0 && onSegment(p1, p2, q2));
  }

  private static long orientation(Cell a, Cell b, Cell c) {
    return (long) (b.col() - a.col()) * (c.row() - a.row())
        - (long) (b.row() - a.row()) * (c.col() - a.col());
  }

  /** Whether c (already collinear with a-b) lies within the segment's bounding box. */
  private static boolean onSegment(Cell a, Cell b, Cell c) {
    return Math.min(a.col(), b.col()) <= c.col() && c.col() <= Math.max(a.col(), b.col())
        && Math.min(a.row(), b.row()) <= c.row() && c.row() <= Math.max(a.row(), b.row());
  }
}

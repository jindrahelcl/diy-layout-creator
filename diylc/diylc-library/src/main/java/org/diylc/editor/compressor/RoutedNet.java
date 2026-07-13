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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.diylc.editor.compressor.GridModel.Cell;

/**
 * One net's realized wiring: underside runs (bare wire, claimed in the grid) plus top-side
 * jumpers (insulated wire, may cross anything). Pins that could not be connected at all are
 * listed as failed.
 *
 * @author Layout Compressor contributors
 */
public class RoutedNet {

  /** A top-side insulated jumper wire between two holes. */
  public record Jumper(Cell from, Cell to) {
  }

  private final int netId;
  private final List<List<Cell>> runs = new ArrayList<List<Cell>>();
  private final List<Jumper> jumpers = new ArrayList<Jumper>();
  private final List<Cell> failedPins = new ArrayList<Cell>();

  public RoutedNet(int netId) {
    this.netId = netId;
  }

  public int getNetId() {
    return netId;
  }

  public List<List<Cell>> getRuns() {
    return runs;
  }

  public List<Jumper> getJumpers() {
    return jumpers;
  }

  public List<Cell> getFailedPins() {
    return failedPins;
  }

  /** All cells belonging to this net's tree so far (runs and jumper endpoints). */
  public Set<Cell> getTreeCells() {
    Set<Cell> cells = new HashSet<Cell>();
    for (List<Cell> run : runs) {
      cells.addAll(run);
    }
    for (Jumper jumper : jumpers) {
      cells.add(jumper.from());
      cells.add(jumper.to());
    }
    return cells;
  }

  public int getWireLength() {
    int length = 0;
    for (List<Cell> run : runs) {
      length += run.size() - 1;
    }
    return length;
  }
}

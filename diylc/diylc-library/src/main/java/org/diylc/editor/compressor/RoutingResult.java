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
}

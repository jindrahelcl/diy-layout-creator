/*

    DIY Layout Creator (DIYLC).
    Copyright (c) 2009-2025 held jointly by the individual authors.

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

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.diylc.components.connectivity.PCBTerminalBlock;
import org.diylc.components.passive.Resistor;
import org.diylc.core.Project;
import org.diylc.presenter.ContinuityArea;
import org.diylc.netlist.Group;
import org.junit.Test;

public class NetExtractorTests {

  /**
   * PCBTerminalBlock reports no control point node names, so DIYLC's own netlist extraction
   * drops nets that end at it. The compressor's net model must keep them.
   */
  @Test
  public void terminalBlockPinsFormNets() {
    Project project = new Project();
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(200, 100), 1);
    PCBTerminalBlock block = new PCBTerminalBlock();
    block.setControlPoint(new Point2D.Double(200, 100), 0);
    project.getComponents().add(resistor);
    project.getComponents().add(block);

    List<Group> nets = NetExtractor.extractNets(project, new ArrayList<ContinuityArea>(),
        Arrays.asList(resistor, block));

    assertEquals(1, nets.size());
    assertEquals(2, nets.get(0).getNodes().size());
  }
}

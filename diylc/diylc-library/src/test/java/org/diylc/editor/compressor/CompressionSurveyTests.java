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
import static org.junit.Assert.assertNotNull;

import java.awt.geom.Point2D;
import java.util.ArrayList;

import org.diylc.components.connectivity.HookupWire;
import org.diylc.components.passive.Resistor;
import org.diylc.core.Project;
import org.diylc.presenter.ContinuityArea;
import org.junit.Test;

public class CompressionSurveyTests {

  @Test
  public void dryRunRoutesNetsAtCurrentPlacement() {
    Project project = new Project();
    Resistor r1 = new Resistor();
    r1.setControlPoint(new Point2D.Double(100, 100), 0);
    r1.setControlPoint(new Point2D.Double(300, 100), 1);
    Resistor r2 = new Resistor();
    r2.setControlPoint(new Point2D.Double(500, 100), 0);
    r2.setControlPoint(new Point2D.Double(700, 100), 1);
    // hookup wire conducts end-to-end and connects r1.1 with r2.0
    HookupWire wire = new HookupWire();
    wire.setControlPoint(new Point2D.Double(300, 100), 0);
    wire.setControlPoint(new Point2D.Double(500, 100), 3);
    project.getComponents().add(r1);
    project.getComponents().add(r2);
    project.getComponents().add(wire);

    CompressionSurvey survey =
        CompressionSurvey.of(project, new ArrayList<ContinuityArea>());

    assertEquals(1, survey.getNetCount());
    assertNotNull(survey.getRouting());
    // r1.1 at cell (15,5) to r2.0 at cell (25,5): straight run of 10 steps
    assertEquals(10, survey.getRouting().getTotalWireLength());
    assertEquals(0, survey.getRouting().getJumperCount());
    assertEquals(0, survey.getSkippedRoutingPins());
  }
}

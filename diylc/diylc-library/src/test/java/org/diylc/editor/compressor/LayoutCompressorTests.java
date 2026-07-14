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
import static org.junit.Assert.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.diylc.appframework.miscutils.ConfigurationManager;
import org.diylc.components.boards.PerfBoard;
import org.diylc.components.connectivity.Jumper;
import org.diylc.components.passive.Resistor;
import org.diylc.components.semiconductors.TransistorTO92;
import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.netlist.Group;
import org.diylc.netlist.Node;
import org.diylc.presenter.ContinuityArea;
import org.junit.BeforeClass;
import org.junit.Test;

public class LayoutCompressorTests {

  @BeforeClass
  public static void initConfiguration() {
    // verification draws the scratch project through a Presenter, which needs this
    ConfigurationManager.getInstance().initialize("diylc-test");
  }

  private static Resistor resistor(String name, double x, double y) {
    Resistor resistor = new Resistor();
    resistor.setName(name);
    resistor.setControlPoint(new Point2D.Double(x, y), 0);
    resistor.setControlPoint(new Point2D.Double(x + 100, y), 1);
    return resistor;
  }

  private static Jumper connect(Point2D from, Point2D to) {
    Jumper jumper = new Jumper();
    jumper.setName("conn" + from + to);
    jumper.setControlPoint(from, 0);
    jumper.setControlPoint(to, 1);
    return jumper;
  }

  private static IDIYComponent<?> byName(Project project, String name) {
    for (IDIYComponent<?> component : project.getComponents()) {
      if (name.equals(component.getName())) {
        return component;
      }
    }
    return null;
  }

  /** True if some net connects the two given pins. */
  private static boolean connected(Project project, IDIYComponent<?> c1, int p1,
      IDIYComponent<?> c2, int p2) {
    List<IDIYComponent<?>> realParts =
        new ComponentClassifier().classify(project).getRealParts();
    List<Group> nets = NetExtractor.extractNets(project, ContinuityScanner.scan(project),
        realParts);
    for (Group net : nets) {
      boolean has1 = false;
      boolean has2 = false;
      for (Node node : net.getNodes()) {
        has1 |= node.getComponent() == c1 && node.getPointIndex() == p1;
        has2 |= node.getComponent() == c2 && node.getPointIndex() == p2;
      }
      if (has1 && has2) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void compressesSprawlingProjectPreservingTheNet() {
    Resistor r1 = resistor("R1", 100, 100);
    Resistor r2 = resistor("R2", 2000, 1500);
    Project project = new Project();
    project.getComponents().add(r1);
    project.getComponents().add(r2);
    project.getComponents().add(connect(r1.getControlPoint(1), r2.getControlPoint(0)));

    LayoutCompressor compressor =
        new LayoutCompressor(new ArrayList<ContinuityArea>(), null);
    Set<IDIYComponent<?>> emitted = compressor.edit(project, Set.of());

    // board first, old connector gone, new wires present
    assertTrue(project.getComponents().get(0) instanceof PerfBoard);
    IDIYComponent<?> newR1 = byName(project, "R1");
    IDIYComponent<?> newR2 = byName(project, "R2");
    assertNotNull(newR1);
    assertNotNull(newR2);
    for (IDIYComponent<?> part : List.of(newR1, newR2)) {
      for (int i = 0; i < part.getControlPointCount(); i++) {
        assertTrue("pin off lattice: " + part.getControlPoint(i),
            GridModel.isOnGrid(part.getControlPoint(i)));
      }
    }
    assertTrue(connected(project, newR1, 1, newR2, 0));
    assertTrue(emitted.size() > 0);

    LayoutCompressor.Stats stats = compressor.getStats();
    assertEquals(2, stats.movedParts());
    assertEquals(0, stats.remoteParts());
    assertEquals(1, stats.netCount());
    assertTrue(stats.wireLength() >= 1);
    // everything fits a small board now
    assertTrue(stats.boardCells().width < 30 && stats.boardCells().height < 30);
  }

  @Test
  public void offGridPartStaysPutAndGetsFlyingWire() {
    Resistor r1 = resistor("R1", 100, 100);
    TransistorTO92 transistor = new TransistorTO92();
    transistor.setName("Q1");
    Point2D transistorPin = transistor.getControlPoint(0);
    Point2D pinBefore = new Point2D.Double(transistorPin.getX(), transistorPin.getY());
    Project project = new Project();
    project.getComponents().add(r1);
    project.getComponents().add(transistor);
    project.getComponents().add(connect(r1.getControlPoint(1), transistorPin));

    LayoutCompressor compressor =
        new LayoutCompressor(new ArrayList<ContinuityArea>(), null);
    compressor.edit(project, Set.of());

    IDIYComponent<?> newQ1 = byName(project, "Q1");
    assertEquals(pinBefore, newQ1.getControlPoint(0));
    assertTrue(connected(project, byName(project, "R1"), 1, newQ1, 0));
    assertEquals(1, compressor.getStats().remoteParts());
    assertEquals(1, compressor.getStats().flyingWires());
  }

  @Test
  public void lockedPartIsNotMoved() {
    Resistor r1 = resistor("R1", 100, 120);
    Resistor r2 = resistor("R2", 100, 100);
    Project project = new Project();
    project.getComponents().add(r1);
    project.getComponents().add(r2);
    project.getComponents().add(connect(r1.getControlPoint(0), r2.getControlPoint(0)));
    project.getLockedComponents().add(r2);

    LayoutCompressor compressor =
        new LayoutCompressor(new ArrayList<ContinuityArea>(), null);
    compressor.edit(project, Set.of());

    IDIYComponent<?> newR2 = byName(project, "R2");
    assertEquals(new Point2D.Double(100, 100), newR2.getControlPoint(0));
    assertEquals(new Point2D.Double(200, 100), newR2.getControlPoint(1));
    assertTrue(connected(project, byName(project, "R1"), 0, newR2, 0));
    assertEquals(1, compressor.getStats().movedParts());
  }
}

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
import static org.junit.Assert.assertTrue;

import org.diylc.components.boards.VeroBoard;
import org.diylc.components.connectivity.HookupWire;
import org.diylc.components.connectivity.Jumper;
import org.diylc.components.connectivity.PCBTerminalBlock;
import org.diylc.components.connectivity.SolderPad;
import org.diylc.components.connectivity.TraceCut;
import org.diylc.components.connectivity.Turret;
import org.diylc.components.electromechanical.MiniToggleSwitch;
import org.diylc.components.misc.Label;
import org.diylc.components.passive.Resistor;
import org.diylc.components.semiconductors.DIL_IC;
import org.diylc.core.Project;
import org.junit.Test;

public class ComponentClassifierTests {

  @Test
  public void classifiesComponentsIntoCompressionCategories() {
    Project project = new Project();
    project.getComponents().add(new VeroBoard());
    project.getComponents().add(new Resistor());
    project.getComponents().add(new DIL_IC());
    project.getComponents().add(new MiniToggleSwitch());
    project.getComponents().add(new HookupWire());
    project.getComponents().add(new Jumper());
    project.getComponents().add(new TraceCut());
    project.getComponents().add(new SolderPad());
    project.getComponents().add(new Label());

    ComponentClassifier.Classification classification =
        new ComponentClassifier().classify(project);

    assertEquals(3, classification.getRealParts().size());
    assertEquals(4, classification.getConnectivity().size());
    assertEquals(1, classification.getBoards().size());
    assertEquals(1, classification.getDecorations().size());
  }

  @Test
  public void connectionHardwareCountsAsRealParts() {
    Project project = new Project();
    PCBTerminalBlock terminalBlock = new PCBTerminalBlock();
    Turret turret = new Turret();
    project.getComponents().add(terminalBlock);
    project.getComponents().add(turret);

    ComponentClassifier.Classification classification =
        new ComponentClassifier().classify(project);

    assertTrue(classification.getRealParts().contains(terminalBlock));
    assertTrue(classification.getRealParts().contains(turret));
    assertEquals(0, classification.getConnectivity().size());
  }
}

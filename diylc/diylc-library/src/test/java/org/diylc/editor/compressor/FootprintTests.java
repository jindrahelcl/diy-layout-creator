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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.geom.Point2D;
import java.util.Arrays;

import org.diylc.components.connectivity.PCBTerminalBlock;
import org.diylc.components.passive.Resistor;
import org.diylc.components.semiconductors.DIL_IC;
import org.diylc.components.semiconductors.TransistorTO92;
import org.diylc.editor.compressor.GridModel.Cell;
import org.junit.Test;

public class FootprintTests {

  @Test
  public void leadedPassiveIsOnGridAndStretchable() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(200, 100), 1);

    Footprint footprint = Footprint.of(resistor);

    assertEquals(Arrays.asList(new Cell(0, 0), new Cell(5, 0)), footprint.getPinOffsets());
    assertTrue(footprint.isOnGrid());
    assertTrue(footprint.isStretchable());
  }

  @Test
  public void skewedLeadedPassiveIsOffGrid() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(150, 110), 1);

    Footprint footprint = Footprint.of(resistor);

    assertFalse(footprint.isOnGrid());
  }

  @Test
  public void dilIcIsOnGridFixedShape() {
    DIL_IC ic = new DIL_IC();

    Footprint footprint = Footprint.of(ic);

    assertEquals(8, footprint.getPinCount());
    assertTrue(footprint.isOnGrid());
    assertFalse(footprint.isStretchable());
    // two rows 0.3" apart, pins 0.1" apart
    assertTrue(footprint.getPinOffsets().contains(new Cell(0, 3)));
    assertTrue(footprint.getPinOffsets().contains(new Cell(3, 0)));
  }

  @Test
  public void to92WithDefaultHalfPitchIsOffGrid() {
    TransistorTO92 transistor = new TransistorTO92();

    Footprint footprint = Footprint.of(transistor);

    assertEquals(3, footprint.getPinCount());
    assertFalse(footprint.isOnGrid());
  }

  @Test
  public void terminalBlockIsOnGrid() {
    PCBTerminalBlock block = new PCBTerminalBlock();

    Footprint footprint = Footprint.of(block);

    assertEquals(3, footprint.getPinCount());
    assertTrue(footprint.isOnGrid());
    assertFalse(footprint.isStretchable());
    // 0.2" pitch = 2 cells
    assertEquals(Arrays.asList(new Cell(0, 0), new Cell(0, 2), new Cell(0, 4)),
        footprint.getPinOffsets());
  }

  @Test
  public void rotatesOffsetsInQuarterTurns() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(200, 100), 1);

    Footprint footprint = Footprint.of(resistor);

    assertEquals(Arrays.asList(new Cell(0, 0), new Cell(0, 5)), footprint.rotatedOffsets(1));
    assertEquals(Arrays.asList(new Cell(0, 0), new Cell(-5, 0)), footprint.rotatedOffsets(2));
    assertEquals(footprint.getPinOffsets(), footprint.rotatedOffsets(4));
    assertEquals(footprint.rotatedOffsets(3), footprint.rotatedOffsets(-1));
  }
}

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
  public void skewedLeadedPassiveStillFitsGridBecauseSpanIsFlexible() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(150, 110), 1);

    Footprint footprint = Footprint.of(resistor);

    assertTrue(footprint.isOnGrid());
    assertTrue(footprint.isStretchable());
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
  public void stretchableBodyComesFromComponentModelNotOutline() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(200, 100), 1);

    // default resistor body is 0.5" x 0.125": 5 x 1.25 cells, centered between the pins;
    // provided outline bounds (which include the leads) are ignored for stretchable parts
    Footprint footprint =
        Footprint.of(resistor, new java.awt.geom.Rectangle2D.Double(110, 90, 80, 20));

    assertEquals(5.0, footprint.getBodyLengthCells(), 0.01);
    assertEquals(1.25, footprint.getBodyWidthCells(), 0.01);
    assertEquals(new java.awt.Rectangle(0, 0, 5, 0), footprint.getBodyCells());
    assertEquals(footprint.getBodyCells(), Footprint.of(resistor).getBodyCells());
  }

  @Test
  public void naturalSpanIsBodyLengthForAxialAndPinSpacingForRadial() {
    Resistor resistor = new Resistor();
    resistor.setControlPoint(new Point2D.Double(100, 100), 0);
    resistor.setControlPoint(new Point2D.Double(500, 100), 1);
    // 0.5" body -> 5 cells, regardless of the drawn 2" span
    assertEquals(5, Footprint.of(resistor).getNaturalSpanCells());

    org.diylc.components.passive.TantalumCapacitor capacitor =
        new org.diylc.components.passive.TantalumCapacitor();
    capacitor.setControlPoint(new Point2D.Double(100, 100), 0);
    capacitor.setControlPoint(new Point2D.Double(300, 100), 1);
    // radial: designed lead spacing 0.1" -> 1 cell, not the fat body's diameter
    assertEquals(1, Footprint.of(capacitor).getNaturalSpanCells());
  }

  @Test
  public void radialBodyBlocksHolesAroundThePins() {
    org.diylc.components.passive.TantalumCapacitor capacitor =
        new org.diylc.components.passive.TantalumCapacitor();
    capacitor.setControlPoint(new Point2D.Double(100, 100), 0);
    capacitor.setControlPoint(new Point2D.Double(120, 100), 1);

    Footprint footprint = Footprint.of(capacitor);

    assertTrue(footprint.isStretchable());
    // the fat round body must claim more than the strip between the two pins
    assertTrue(footprint.getBodyWidthCells() > 1);
    java.awt.Rectangle body = footprint.getBodyCells();
    assertTrue(body.height >= 1);
  }

  @Test
  public void coveredCellsClaimEveryIntrudedHole() {
    // a 2-cell-wide body centered between two holes reaches exactly to the neighbors' cell
    // boundaries: only the two holes under it are claimed
    assertEquals(new java.awt.Rectangle(0, 0, 1, 0),
        Footprint.coveredCells(0.5, 0, 1.0, 0.1));
    // a 1.25-cell body centered on a hole intrudes only 0.125 cells into the neighbors —
    // within tolerance, single row
    assertEquals(new java.awt.Rectangle(0, 0, 0, 0),
        Footprint.coveredCells(0, 0, 0.625, 0.625));
    // a 2.5-cell round body centered on a hole intrudes deep into the neighbors: 3x3 holes
    assertEquals(new java.awt.Rectangle(-1, -1, 2, 2),
        Footprint.coveredCells(0, 0, 1.25, 1.25));
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

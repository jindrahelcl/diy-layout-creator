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

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.diylc.components.AbstractLeadedComponent;
import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.GridModel.Cell;

/**
 * A component's shape on the {@link GridModel} lattice: its sticky pins as cell offsets
 * relative to the first pin. Components whose pin geometry doesn't fit the 0.1" lattice are
 * flagged off-grid — the compressor leaves those in place and reaches them with wires.
 * Two-lead components with flexible lead length (resistors etc.) are flagged stretchable so
 * the placer may vary their span.
 *
 * @author Layout Compressor contributors
 */
public class Footprint {

  private final IDIYComponent<?> component;
  private final List<Integer> pinIndices;
  private final List<Cell> pinOffsets;
  private final boolean onGrid;
  private final boolean stretchable;
  private final Rectangle bodyCells;

  private Footprint(IDIYComponent<?> component, List<Integer> pinIndices, List<Cell> pinOffsets,
      boolean onGrid, boolean stretchable, Rectangle bodyCells) {
    this.component = component;
    this.pinIndices = pinIndices;
    this.pinOffsets = pinOffsets;
    this.onGrid = onGrid;
    this.stretchable = stretchable;
    this.bodyCells = bodyCells;
  }

  public static Footprint of(IDIYComponent<?> component) {
    return of(component, null);
  }

  public static Footprint of(IDIYComponent<?> component, Rectangle2D bodyBoundsPx) {
    List<Integer> pinIndices = new ArrayList<Integer>();
    List<Point2D> pinPoints = new ArrayList<Point2D>();
    for (int i = 0; i < component.getControlPointCount(); i++) {
      if (component.isControlPointSticky(i)) {
        pinIndices.add(i);
        pinPoints.add(component.getControlPoint(i));
      }
    }

    List<Cell> pinOffsets = new ArrayList<Cell>();
    boolean onGrid = true;
    if (!pinPoints.isEmpty()) {
      Point2D first = pinPoints.get(0);
      for (Point2D p : pinPoints) {
        double dx = p.getX() - first.getX();
        double dy = p.getY() - first.getY();
        long col = Math.round(dx / GridModel.CELL_SIZE_PX);
        long row = Math.round(dy / GridModel.CELL_SIZE_PX);
        if (Math.abs(dx - col * GridModel.CELL_SIZE_PX) > GridModel.SNAP_TOLERANCE_PX
            || Math.abs(dy - row * GridModel.CELL_SIZE_PX) > GridModel.SNAP_TOLERANCE_PX) {
          onGrid = false;
        }
        pinOffsets.add(new Cell((int) col, (int) row));
      }
    }

    boolean stretchable =
        component instanceof AbstractLeadedComponent && pinIndices.size() == 2;

    // a stretchable part always fits the lattice: the placer picks a new span anyway,
    // so only fixed-shape pin geometry can be genuinely off-grid
    if (stretchable) {
      onGrid = true;
    }

    Rectangle bodyCells = null;
    if (bodyBoundsPx != null && !pinPoints.isEmpty()) {
      bodyCells = coveredCells(bodyBoundsPx, pinPoints.get(0));
    }

    return new Footprint(component, Collections.unmodifiableList(pinIndices),
        Collections.unmodifiableList(pinOffsets), onGrid, stretchable, bodyCells);
  }

  /** Lattice holes the body rectangle covers, in cells relative to the reference pin. */
  private static Rectangle coveredCells(Rectangle2D boundsPx, Point2D referencePin) {
    int minCol = (int) Math.ceil((boundsPx.getMinX() - referencePin.getX()) / GridModel.CELL_SIZE_PX);
    int maxCol = (int) Math.floor((boundsPx.getMaxX() - referencePin.getX()) / GridModel.CELL_SIZE_PX);
    int minRow = (int) Math.ceil((boundsPx.getMinY() - referencePin.getY()) / GridModel.CELL_SIZE_PX);
    int maxRow = (int) Math.floor((boundsPx.getMaxY() - referencePin.getY()) / GridModel.CELL_SIZE_PX);
    if (minCol > maxCol || minRow > maxRow) {
      return null;
    }
    return new Rectangle(minCol, minRow, maxCol - minCol, maxRow - minRow);
  }

  /** Pin offsets rotated by the given number of 90-degree clockwise turns. */
  public List<Cell> rotatedOffsets(int quarterTurns) {
    int turns = ((quarterTurns % 4) + 4) % 4;
    List<Cell> result = new ArrayList<Cell>(pinOffsets);
    for (int t = 0; t < turns; t++) {
      List<Cell> rotated = new ArrayList<Cell>(result.size());
      for (Cell c : result) {
        rotated.add(new Cell(-c.row(), c.col()));
      }
      result = rotated;
    }
    return result;
  }

  public IDIYComponent<?> getComponent() {
    return component;
  }

  /** Sticky control point indices, parallel to {@link #getPinOffsets()}. */
  public List<Integer> getPinIndices() {
    return pinIndices;
  }

  public List<Cell> getPinOffsets() {
    return pinOffsets;
  }

  public int getPinCount() {
    return pinIndices.size();
  }

  public boolean isOnGrid() {
    return onGrid;
  }

  public boolean isStretchable() {
    return stretchable;
  }

  /**
   * Lattice holes blocked by the component body, in cells relative to the first pin, or null
   * when unknown (no drawn area available) or when the body covers no holes.
   */
  public Rectangle getBodyCells() {
    return bodyCells;
  }
}

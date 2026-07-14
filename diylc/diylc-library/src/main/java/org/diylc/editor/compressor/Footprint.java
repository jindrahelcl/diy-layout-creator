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

import org.diylc.common.ComponentType;
import org.diylc.components.AbstractLeadedComponent;
import org.diylc.components.passive.AbstractRadialComponent;
import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.presenter.ComponentProcessor;

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

  /**
   * Bodies are shrunk by this much per side (in cells; 0.15 cells = 3 px = 0.015") before
   * claiming holes: nominal drawn sizes, border strokes and renderer rounding shouldn't count
   * as intrusion into a neighboring cell, or a standard 0.125"-wide resistor would block three
   * rows. Anything intruding deeper genuinely collides.
   */
  private static final double BODY_TOLERANCE_CELLS = 0.15;

  private final IDIYComponent<?> component;
  private final List<Integer> pinIndices;
  private final List<Cell> pinOffsets;
  private final boolean onGrid;
  private final boolean stretchable;
  private final boolean rotatable;
  private final Rectangle bodyCells;
  private final double bodyLengthCells;
  private final double bodyWidthCells;
  private final int naturalSpanCells;

  private Footprint(IDIYComponent<?> component, List<Integer> pinIndices, List<Cell> pinOffsets,
      boolean onGrid, boolean stretchable, boolean rotatable, Rectangle bodyCells,
      double bodyLengthCells, double bodyWidthCells, int naturalSpanCells) {
    this.component = component;
    this.pinIndices = pinIndices;
    this.pinOffsets = pinOffsets;
    this.onGrid = onGrid;
    this.stretchable = stretchable;
    this.rotatable = rotatable;
    this.bodyCells = bodyCells;
    this.bodyLengthCells = bodyLengthCells;
    this.bodyWidthCells = bodyWidthCells;
    this.naturalSpanCells = naturalSpanCells;
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

    // a stretchable part's leads (and thus its drawn outline) tell nothing about the body, so
    // its physical body size comes from the component model instead: the body shape is drawn
    // centered between the pins, length along the lead axis, width across it
    double bodyLengthCells = 0;
    double bodyWidthCells = 0;
    if (stretchable) {
      Rectangle2D bodyShape = bodyShapeBounds(component);
      if (bodyShape != null) {
        bodyLengthCells = bodyShape.getWidth() / GridModel.CELL_SIZE_PX;
        bodyWidthCells = bodyShape.getHeight() / GridModel.CELL_SIZE_PX;
      }
    }
    int naturalSpanCells = naturalSpan(component, stretchable, bodyLengthCells);

    Rectangle bodyCells = null;
    if (stretchable && bodyLengthCells > 0 && bodyWidthCells > 0 && pinOffsets.size() == 2) {
      // body centered between the original pins, axis along the dominant pin direction
      Cell second = pinOffsets.get(1);
      boolean horizontal = Math.abs(second.col()) >= Math.abs(second.row());
      bodyCells = coveredCells(second.col() / 2.0, second.row() / 2.0,
          (horizontal ? bodyLengthCells : bodyWidthCells) / 2,
          (horizontal ? bodyWidthCells : bodyLengthCells) / 2);
    } else if (bodyBoundsPx != null && !pinPoints.isEmpty()) {
      bodyCells = coveredCells(bodyBoundsPx, pinPoints.get(0));
    }

    boolean rotatable = stretchable || hasRotationTransformer(component);

    return new Footprint(component, Collections.unmodifiableList(pinIndices),
        Collections.unmodifiableList(pinOffsets), onGrid, stretchable, rotatable, bodyCells,
        bodyLengthCells, bodyWidthCells, naturalSpanCells);
  }

  private static Rectangle2D bodyShapeBounds(IDIYComponent<?> component) {
    if (!(component instanceof AbstractLeadedComponent<?>)) {
      return null;
    }
    try {
      return ((AbstractLeadedComponent<?>) component).getBodyShapeBounds();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The span (in cells) the part wants when nothing is in the way: radial parts sit at their
   * designed lead spacing; axial parts at the first holes clear of the body, with the leads
   * bent straight down. 0 when unknown — the placer then keeps the layout's original span.
   */
  private static int naturalSpan(IDIYComponent<?> component, boolean stretchable,
      double bodyLengthCells) {
    if (!stretchable) {
      return 0;
    }
    if (component instanceof AbstractRadialComponent<?>) {
      try {
        double spacingPx =
            ((AbstractRadialComponent<?>) component).getPinSpacing().convertToPixels();
        return Math.max(1, (int) Math.round(spacingPx / GridModel.CELL_SIZE_PX));
      } catch (Exception e) {
        return 0;
      }
    }
    if (bodyLengthCells > 0) {
      // small slack absorbs renderer rounding (getClosestOdd) so a 0.5" body wants span 5
      return Math.max(1, (int) Math.ceil(bodyLengthCells - 0.1));
    }
    return 0;
  }

  @SuppressWarnings("unchecked")
  private static boolean hasRotationTransformer(IDIYComponent<?> component) {
    try {
      ComponentType type = ComponentProcessor.getInstance()
          .extractComponentTypeFrom((Class<? extends IDIYComponent<?>>) component.getClass());
      return type != null && type.getTransformer() != null
          && type.getTransformer().canRotate(component);
    } catch (Exception e) {
      return false;
    }
  }

  private static Rectangle coveredCells(Rectangle2D boundsPx, Point2D referencePin) {
    return coveredCells((boundsPx.getCenterX() - referencePin.getX()) / GridModel.CELL_SIZE_PX,
        (boundsPx.getCenterY() - referencePin.getY()) / GridModel.CELL_SIZE_PX,
        boundsPx.getWidth() / 2 / GridModel.CELL_SIZE_PX,
        boundsPx.getHeight() / 2 / GridModel.CELL_SIZE_PX);
  }

  /**
   * Lattice holes blocked by a body rectangle (in cell coordinates): every hole whose 0.1"
   * square region the (tolerance-shrunk) body intersects, not just holes the body covers —
   * two bodies that physically overlap by more than the tolerance are then guaranteed to
   * share a blocked hole. Null when empty.
   */
  static Rectangle coveredCells(double centerCol, double centerRow, double halfCols,
      double halfRows) {
    double effectiveHalfCols = Math.max(0, halfCols - BODY_TOLERANCE_CELLS);
    double effectiveHalfRows = Math.max(0, halfRows - BODY_TOLERANCE_CELLS);
    int minCol = (int) Math.ceil(centerCol - effectiveHalfCols - 0.5);
    int maxCol = (int) Math.floor(centerCol + effectiveHalfCols + 0.5);
    int minRow = (int) Math.ceil(centerRow - effectiveHalfRows - 0.5);
    int maxRow = (int) Math.floor(centerRow + effectiveHalfRows + 0.5);
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
   * True when the placer may try 90-degree orientations: either the part is stretchable (its
   * geometry is fully derived from its two lead points) or its component type declares a
   * transformer that can rotate it.
   */
  public boolean isRotatable() {
    return rotatable;
  }

  /**
   * Lattice holes blocked by the component body, in cells relative to the first pin, or null
   * when unknown (no drawn area available) or when the body covers no holes.
   */
  public Rectangle getBodyCells() {
    return bodyCells;
  }

  /**
   * Physical body extent along the lead axis in cells (fractional), or 0 when unknown. Only
   * set for stretchable parts; the body keeps this size whatever span the placer picks.
   */
  public double getBodyLengthCells() {
    return bodyLengthCells;
  }

  /** Physical body extent across the lead axis in cells (fractional), or 0 when unknown. */
  public double getBodyWidthCells() {
    return bodyWidthCells;
  }

  /**
   * The lead span (in cells) this part wants when unconstrained — designed lead spacing for
   * radial parts, body length for axial ones — or 0 when unknown. Spans are normalized to
   * this at seeding; deviations cost {@link CompressionState#SPAN_WEIGHT} each.
   */
  public int getNaturalSpanCells() {
    return naturalSpanCells;
  }
}

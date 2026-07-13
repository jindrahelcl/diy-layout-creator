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
import java.util.ArrayList;
import java.util.List;

import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.GridModel.Cell;

/**
 * Seeds the initial placement: scales the original component positions down onto the
 * {@link GridModel} lattice so the author's relative arrangement survives, sized by the total
 * footprint area rather than the sprawling input canvas. The seeded placements may overlap —
 * legalization resolves that. Off-grid parts are left out; they stay where they are and get
 * reached by wires.
 *
 * @author Layout Compressor contributors
 */
public class PlacementSeeder {

  /** Extra room beyond the sum of footprint areas, to leave space for routing. */
  public static final double AREA_SLACK = 2.0;

  /**
   * One component's spot on the lattice: reference cell (first sticky pin), rotation, and the
   * chosen lead span for stretchable parts (ignored otherwise).
   */
  public record Placement(Footprint footprint, Cell reference, int quarterTurns, int span) {

    /** Absolute cells of all sticky pins under this placement. */
    public List<Cell> pinCells() {
      List<Cell> offsets;
      if (footprint.isStretchable()) {
        offsets = rotate(List.of(new Cell(0, 0), new Cell(span, 0)), quarterTurns);
      } else {
        offsets = footprint.rotatedOffsets(quarterTurns);
      }
      List<Cell> cells = new ArrayList<Cell>(offsets.size());
      for (Cell offset : offsets) {
        cells.add(new Cell(reference.col() + offset.col(), reference.row() + offset.row()));
      }
      return cells;
    }

    /**
     * Absolute cells covered by the body, or null when the footprint has no body extent. A
     * stretchable part's body is the strip between its two pins, so it follows the chosen
     * span rather than the footprint's original extent.
     */
    public Rectangle bodyCells() {
      Rectangle body;
      if (footprint.isStretchable()) {
        if (span < 2) {
          return null;
        }
        body = new Rectangle(1, 0, span - 2, 0);
      } else {
        body = footprint.getBodyCells();
        if (body == null) {
          return null;
        }
      }
      Rectangle rotated = rotateRect(body, quarterTurns);
      return new Rectangle(reference.col() + rotated.x, reference.row() + rotated.y,
          rotated.width, rotated.height);
    }

    private static List<Cell> rotate(List<Cell> offsets, int quarterTurns) {
      int turns = ((quarterTurns % 4) + 4) % 4;
      List<Cell> result = offsets;
      for (int t = 0; t < turns; t++) {
        List<Cell> next = new ArrayList<Cell>(result.size());
        for (Cell c : result) {
          next.add(new Cell(-c.row(), c.col()));
        }
        result = next;
      }
      return result;
    }

    private static Rectangle rotateRect(Rectangle rect, int quarterTurns) {
      int turns = ((quarterTurns % 4) + 4) % 4;
      Rectangle result = new Rectangle(rect);
      for (int t = 0; t < turns; t++) {
        result = new Rectangle(-(result.y + result.height), result.x, result.height,
            result.width);
      }
      return result;
    }
  }

  /** Seeded placements for on-grid parts plus the off-grid parts that stay in place. */
  public record Seed(List<Placement> placements, List<IDIYComponent<?>> offGridParts) {
  }

  public Seed seed(List<Footprint> footprints) {
    List<Footprint> onGrid = new ArrayList<Footprint>();
    List<IDIYComponent<?>> offGrid = new ArrayList<IDIYComponent<?>>();
    for (Footprint footprint : footprints) {
      if (footprint.isOnGrid() && footprint.getPinCount() > 0) {
        onGrid.add(footprint);
      } else {
        offGrid.add(footprint.getComponent());
      }
    }
    if (onGrid.isEmpty()) {
      return new Seed(List.of(), offGrid);
    }

    List<Point2D> references = new ArrayList<Point2D>();
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    long totalCells = 0;
    for (Footprint footprint : onGrid) {
      Point2D reference =
          footprint.getComponent().getControlPoint(footprint.getPinIndices().get(0));
      references.add(reference);
      minX = Math.min(minX, reference.getX());
      minY = Math.min(minY, reference.getY());
      maxX = Math.max(maxX, reference.getX());
      maxY = Math.max(maxY, reference.getY());
      totalCells += cellArea(footprint);
    }

    double currentAreaPx = (maxX - minX) * (maxY - minY);
    double targetAreaPx =
        totalCells * AREA_SLACK * GridModel.CELL_SIZE_PX * GridModel.CELL_SIZE_PX;
    double scale =
        currentAreaPx <= 0 ? 1.0 : Math.min(1.0, Math.sqrt(targetAreaPx / currentAreaPx));

    List<Placement> placements = new ArrayList<Placement>();
    for (int i = 0; i < onGrid.size(); i++) {
      Footprint footprint = onGrid.get(i);
      Point2D original = references.get(i);
      Point2D scaled = new Point2D.Double(minX + (original.getX() - minX) * scale,
          minY + (original.getY() - minY) * scale);
      placements.add(new Placement(footprint, GridModel.snap(scaled), 0, seedSpan(footprint)));
    }
    return new Seed(placements, offGrid);
  }

  /** Original lead span in cells (dominant axis, at least 1); 0 for fixed-shape parts. */
  private static int seedSpan(Footprint footprint) {
    if (!footprint.isStretchable()) {
      return 0;
    }
    Cell second = footprint.getPinOffsets().get(1);
    return Math.max(1, Math.max(Math.abs(second.col()), Math.abs(second.row())));
  }

  /** Lattice cells the footprint needs: inclusive bounding box of pins and body. */
  static long cellArea(Footprint footprint) {
    Rectangle bounds = null;
    for (Cell pin : footprint.getPinOffsets()) {
      Rectangle pinRect = new Rectangle(pin.col(), pin.row(), 0, 0);
      if (bounds == null) {
        bounds = pinRect;
      } else {
        bounds.add(pinRect);
      }
    }
    if (footprint.getBodyCells() != null) {
      if (bounds == null) {
        bounds = new Rectangle(footprint.getBodyCells());
      } else {
        bounds.add(footprint.getBodyCells());
      }
    }
    if (bounds == null) {
      return 1;
    }
    return (long) (bounds.width + 1) * (bounds.height + 1);
  }
}
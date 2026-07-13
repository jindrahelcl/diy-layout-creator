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

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.diylc.core.IDIYComponent;
import org.diylc.utils.Constants;

/**
 * The compressor's world model: a 0.1" hole lattice, anchored at the project origin like
 * DIYLC's own grid. Cells are addressed by integer column/row; pixel coordinates follow
 * DIYLC's convention of {@link Constants#PIXELS_PER_INCH}. Tracks which cells are occupied by
 * component pins and bodies.
 *
 * @author Layout Compressor contributors
 */
public class GridModel {

  public static final double CELL_SIZE_PX = Constants.PIXELS_PER_INCH * 0.1d;

  /** Same tolerance the netlist builder uses to consider points connected. */
  public static final double SNAP_TOLERANCE_PX = 4d;

  public record Cell(int col, int row) {
  }

  public record Pin(IDIYComponent<?> component, int pointIndex) {
  }

  private final Map<Cell, List<Pin>> pins = new HashMap<Cell, List<Pin>>();
  private final Map<Cell, Set<IDIYComponent<?>>> bodies =
      new HashMap<Cell, Set<IDIYComponent<?>>>();

  public static Cell snap(Point2D point) {
    return new Cell((int) Math.round(point.getX() / CELL_SIZE_PX),
        (int) Math.round(point.getY() / CELL_SIZE_PX));
  }

  /** True if the point lies within {@link #SNAP_TOLERANCE_PX} of a lattice point, per axis. */
  public static boolean isOnGrid(Point2D point) {
    Point2D lattice = toPixels(snap(point));
    return Math.abs(point.getX() - lattice.getX()) <= SNAP_TOLERANCE_PX
        && Math.abs(point.getY() - lattice.getY()) <= SNAP_TOLERANCE_PX;
  }

  public static Point2D toPixels(Cell cell) {
    return new Point2D.Double(cell.col() * CELL_SIZE_PX, cell.row() * CELL_SIZE_PX);
  }

  public void occupyPin(Cell cell, IDIYComponent<?> component, int pointIndex) {
    pins.computeIfAbsent(cell, (c) -> new ArrayList<Pin>()).add(new Pin(component, pointIndex));
  }

  public void occupyBody(Cell cell, IDIYComponent<?> component) {
    bodies.computeIfAbsent(cell, (c) -> new HashSet<IDIYComponent<?>>()).add(component);
  }

  public List<Pin> pinsAt(Cell cell) {
    return pins.getOrDefault(cell, Collections.emptyList());
  }

  public Set<IDIYComponent<?>> bodiesAt(Cell cell) {
    return bodies.getOrDefault(cell, Collections.emptySet());
  }

  public boolean isFree(Cell cell) {
    return pinsAt(cell).isEmpty() && bodiesAt(cell).isEmpty();
  }

  /** Bounding box of all occupied cells in cell coordinates, or null if nothing is occupied. */
  public Rectangle occupiedBounds() {
    Rectangle bounds = null;
    for (Cell cell : pins.keySet()) {
      bounds = include(bounds, cell);
    }
    for (Cell cell : bodies.keySet()) {
      bounds = include(bounds, cell);
    }
    return bounds;
  }

  private static Rectangle include(Rectangle bounds, Cell cell) {
    Rectangle cellRect = new Rectangle(cell.col(), cell.row(), 0, 0);
    if (bounds == null) {
      return cellRect;
    }
    bounds.add(cellRect);
    return bounds;
  }
}

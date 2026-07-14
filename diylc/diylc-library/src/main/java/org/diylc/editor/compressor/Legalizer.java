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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.diylc.editor.compressor.GridModel.Cell;
import org.diylc.editor.compressor.PlacementSeeder.Placement;

/**
 * Resolves overlaps among seeded placements: components are placed largest-first, each by a
 * spiral search around its seeded spot for the nearest legal position, trying the original
 * orientation and span first, then other rotations (when the part can rotate) and other lead
 * spans (when the part is stretchable). The lattice is unbounded, so the search always
 * succeeds — the canvas grows instead of failing.
 *
 * @author Layout Compressor contributors
 */
public class Legalizer {

  /** How much a stretchable part's span may grow beyond its seeded span. */
  public static final int MAX_SPAN_GROWTH = 2;

  /** Safety net for the spiral search; never reached with sane inputs. */
  private static final int MAX_RADIUS = 1000;

  /** Legalized placements (in the input order) and the grid they occupy. */
  public record Result(List<Placement> placements, GridModel grid) {
  }

  public Result legalize(List<Placement> seeded) {
    return legalize(seeded, new GridModel());
  }

  /**
   * Legalizes onto a grid that may already hold immovable obstacles (locked or off-grid
   * parts): their cells are simply never free.
   */
  public Result legalize(List<Placement> seeded, GridModel grid) {
    List<Integer> order = new ArrayList<Integer>();
    for (int i = 0; i < seeded.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingLong(
        (Integer i) -> -PlacementSeeder.cellArea(seeded.get(i).footprint())));

    Placement[] legalized = new Placement[seeded.size()];
    for (int index : order) {
      Placement placed = place(seeded.get(index), grid);
      occupy(placed, grid);
      legalized[index] = placed;
    }
    return new Result(List.of(legalized), grid);
  }

  private Placement place(Placement seed, GridModel grid) {
    List<Integer> turns = turnOptions(seed.footprint());
    List<Integer> spans = spanOptions(seed);
    for (int radius = 0; radius <= MAX_RADIUS; radius++) {
      for (Cell offset : ring(radius)) {
        Cell reference = new Cell(seed.reference().col() + offset.col(),
            seed.reference().row() + offset.row());
        for (int turn : turns) {
          for (int span : spans) {
            Placement candidate = new Placement(seed.footprint(), reference, turn, span);
            if (fits(candidate, grid)) {
              return candidate;
            }
          }
        }
      }
    }
    throw new IllegalStateException(
        "No legal spot within radius " + MAX_RADIUS + " for " + seed.footprint().getComponent());
  }

  private static List<Integer> turnOptions(Footprint footprint) {
    return footprint.isRotatable() ? List.of(0, 1, 2, 3) : List.of(0);
  }

  /** Seeded span first, then shrinking to 1, then growing a little; fixed parts have no choice. */
  private static List<Integer> spanOptions(Placement seed) {
    if (!seed.footprint().isStretchable()) {
      return List.of(seed.span());
    }
    List<Integer> spans = new ArrayList<Integer>();
    for (int s = seed.span(); s >= 1; s--) {
      spans.add(s);
    }
    for (int s = seed.span() + 1; s <= seed.span() + MAX_SPAN_GROWTH; s++) {
      spans.add(s);
    }
    return spans;
  }

  private static boolean fits(Placement candidate, GridModel grid) {
    for (Cell cell : candidate.pinCells()) {
      if (cell.col() < 0 || cell.row() < 0 || !grid.isFree(cell)) {
        return false;
      }
    }
    for (Rectangle body : candidate.bodyCells()) {
      if (body.x < 0 || body.y < 0) {
        return false;
      }
      for (int col = body.x; col <= body.x + body.width; col++) {
        for (int row = body.y; row <= body.y + body.height; row++) {
          if (!grid.isFree(new Cell(col, row))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /** Claims the placement's pin and body cells in the grid. */
  public static void occupy(Placement placed, GridModel grid) {
    List<Cell> pinCells = placed.pinCells();
    List<Integer> pinIndices = placed.footprint().getPinIndices();
    for (int i = 0; i < pinCells.size(); i++) {
      grid.occupyPin(pinCells.get(i), placed.footprint().getComponent(), pinIndices.get(i));
    }
    for (Rectangle body : placed.bodyCells()) {
      for (int col = body.x; col <= body.x + body.width; col++) {
        for (int row = body.y; row <= body.y + body.height; row++) {
          Cell cell = new Cell(col, row);
          if (!pinCells.contains(cell)) {
            grid.occupyBody(cell, placed.footprint().getComponent());
          }
        }
      }
    }
  }

  /** Cells at exactly the given Chebyshev radius, in deterministic scan order. */
  private static List<Cell> ring(int radius) {
    if (radius == 0) {
      return List.of(new Cell(0, 0));
    }
    List<Cell> cells = new ArrayList<Cell>();
    for (int row = -radius; row <= radius; row++) {
      for (int col = -radius; col <= radius; col++) {
        if (Math.abs(row) == radius || Math.abs(col) == radius) {
          cells.add(new Cell(col, row));
        }
      }
    }
    return cells;
  }
}

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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

  /** Relaxation sweeps pulling parts toward their net mates after scaling. */
  public static final int RELAX_SWEEPS = 2;

  /** How far toward the net-mate centroid a part moves per sweep (0..1). */
  public static final double RELAX_PULL = 0.5;

  /**
   * Nets larger than this don't pull: big nets are power/ground rails whose centroid is just
   * the middle of the board — only small signal nets carry placement intent.
   */
  public static final int RELAX_MAX_NET = 4;

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
     * Absolute cell rectangles blocked by the body; empty when the footprint has no body
     * extent. A stretchable part blocks the lead strip between its two pins plus its physical
     * body — a fixed-size rectangle centered between the pins (radial parts are much fatter
     * than the strip), which keeps its size whatever span is chosen.
     */
    public List<Rectangle> bodyCells() {
      List<Rectangle> body = new ArrayList<Rectangle>(2);
      if (footprint.isStretchable()) {
        if (span >= 2) {
          body.add(new Rectangle(1, 0, span - 2, 0));
        }
        if (footprint.getBodyLengthCells() > 0 && footprint.getBodyWidthCells() > 0) {
          Rectangle physical = Footprint.coveredCells(span / 2.0, 0,
              footprint.getBodyLengthCells() / 2, footprint.getBodyWidthCells() / 2);
          if (physical != null) {
            body.add(physical);
          }
        }
      } else if (footprint.getBodyCells() != null) {
        body.add(footprint.getBodyCells());
      }
      List<Rectangle> result = new ArrayList<Rectangle>(body.size());
      for (Rectangle rect : body) {
        Rectangle rotated = rotateRect(rect, quarterTurns);
        result.add(new Rectangle(reference.col() + rotated.x, reference.row() + rotated.y,
            rotated.width, rotated.height));
      }
      return result;
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
    return seed(footprints, List.of());
  }

  /**
   * @param nets components sharing a net, used to pull connected parts together during
   *        relaxation; members that aren't seeded here (remote, locked) are ignored
   */
  public Seed seed(List<Footprint> footprints, List<List<IDIYComponent<?>>> nets) {
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

    List<Point2D> scaled = new ArrayList<Point2D>();
    for (Point2D original : references) {
      scaled.add(new Point2D.Double(minX + (original.getX() - minX) * scale,
          minY + (original.getY() - minY) * scale));
    }
    relax(onGrid, scaled, nets);
    reinflate(scaled, Math.min(targetAreaPx,
        (maxX - minX) * scale * (maxY - minY) * scale));

    List<Placement> placements = new ArrayList<Placement>();
    for (int i = 0; i < onGrid.size(); i++) {
      Footprint footprint = onGrid.get(i);
      placements
          .add(new Placement(footprint, GridModel.snap(scaled.get(i)), 0, seedSpan(footprint)));
    }
    return new Seed(placements, offGrid);
  }

  /**
   * Pulls each part toward the centroid of its small-net mates, {@link #RELAX_SWEEPS} times.
   * The scaling above preserves the author's arrangement globally; this tightens it locally so
   * connected parts (a bypass cap and its IC, a divider pair) start out adjacent instead of
   * relying on the annealer's one-cell slides to find each other. Updates are sequential in
   * list order, so the result is deterministic.
   */
  private static void relax(List<Footprint> onGrid, List<Point2D> positions,
      List<List<IDIYComponent<?>>> nets) {
    Map<IDIYComponent<?>, Integer> indexOf = new IdentityHashMap<IDIYComponent<?>, Integer>();
    for (int i = 0; i < onGrid.size(); i++) {
      indexOf.put(onGrid.get(i).getComponent(), i);
    }
    List<List<Integer>> neighbors = new ArrayList<List<Integer>>();
    for (int i = 0; i < onGrid.size(); i++) {
      neighbors.add(new ArrayList<Integer>());
    }
    for (List<IDIYComponent<?>> net : nets) {
      List<Integer> members = new ArrayList<Integer>();
      for (IDIYComponent<?> component : net) {
        Integer index = indexOf.get(component);
        if (index != null && !members.contains(index)) {
          members.add(index);
        }
      }
      if (members.size() < 2 || members.size() > RELAX_MAX_NET) {
        continue;
      }
      for (int i : members) {
        for (int j : members) {
          if (i != j) {
            neighbors.get(i).add(j);
          }
        }
      }
    }

    for (int sweep = 0; sweep < RELAX_SWEEPS; sweep++) {
      for (int i = 0; i < positions.size(); i++) {
        List<Integer> mates = neighbors.get(i);
        if (mates.isEmpty()) {
          continue;
        }
        double cx = 0;
        double cy = 0;
        for (int mate : mates) {
          cx += positions.get(mate).getX();
          cy += positions.get(mate).getY();
        }
        cx /= mates.size();
        cy /= mates.size();
        Point2D p = positions.get(i);
        p.setLocation(p.getX() + RELAX_PULL * (cx - p.getX()),
            p.getY() + RELAX_PULL * (cy - p.getY()));
      }
    }
  }

  /**
   * Scales the positions back up after relaxation, to the {@link #AREA_SLACK} target or the
   * pre-relaxation extent, whichever is smaller. Pulling net mates together shrinks the layout
   * below the density the scaling aimed for — seeding that dense floods the router with
   * jumpers the annealer can't recover from — but inflating a layout beyond its pre-relaxation
   * size is just as bad, since the loop's one-cell slides can't win that area back either.
   * This keeps the relaxed relative structure at the original seeding density.
   */
  private static void reinflate(List<Point2D> positions, double targetAreaPx) {
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (Point2D p : positions) {
      minX = Math.min(minX, p.getX());
      minY = Math.min(minY, p.getY());
      maxX = Math.max(maxX, p.getX());
      maxY = Math.max(maxY, p.getY());
    }
    double areaPx = (maxX - minX) * (maxY - minY);
    if (areaPx <= 0) {
      return;
    }
    double scale = Math.max(1.0, Math.sqrt(targetAreaPx / areaPx));
    for (Point2D p : positions) {
      p.setLocation(minX + (p.getX() - minX) * scale, minY + (p.getY() - minY) * scale);
    }
  }

  /**
   * Seeded lead span in cells: the footprint's natural span — the single biggest space win,
   * since source layouts often have wildly stretched leads — falling back to the original
   * span (dominant axis, at least 1) when the natural span is unknown; 0 for fixed parts.
   */
  private static int seedSpan(Footprint footprint) {
    if (!footprint.isStretchable()) {
      return 0;
    }
    if (footprint.getNaturalSpanCells() > 0) {
      return footprint.getNaturalSpanCells();
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
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

import org.diylc.components.passive.Resistor;
import org.diylc.editor.compressor.GridModel.Cell;
import org.junit.Test;

public class RouterTests {

  private static final Rectangle BOUNDS = new Rectangle(0, 0, 9, 9);

  @Test
  public void routesStraightLine() {
    Router router = new Router(new GridModel(), BOUNDS);

    List<Cell> path = router.findPath(0, new Cell(1, 5), Set.of(new Cell(6, 5)));

    assertEquals(6, path.size());
    assertEquals(new Cell(1, 5), path.get(0));
    assertEquals(new Cell(6, 5), path.get(5));
    for (Cell c : path) {
      assertEquals(5, c.row());
    }
  }

  @Test
  public void detoursAroundForeignPin() {
    GridModel grid = new GridModel();
    grid.occupyPin(new Cell(3, 5), new Resistor(), 0);
    Router router = new Router(grid, BOUNDS);

    List<Cell> path = router.findPath(0, new Cell(1, 5), Set.of(new Cell(6, 5)));

    // diagonal steps dodge the pin without needing extra cells
    assertEquals(6, path.size());
    assertTrue(!path.contains(new Cell(3, 5)));
  }

  @Test
  public void detoursAroundForeignRun() {
    GridModel grid = new GridModel();
    // net 1 runs vertically across the whole route corridor at col 3, rows 3..7
    grid.claimRun(1, List.of(new Cell(3, 3), new Cell(3, 4), new Cell(3, 5), new Cell(3, 6),
        new Cell(3, 7)));
    Router router = new Router(grid, BOUNDS);

    List<Cell> path = router.findPath(0, new Cell(1, 5), Set.of(new Cell(6, 5)));

    for (Cell c : path) {
      assertTrue(c.col() != 3 || c.row() < 3 || c.row() > 7);
    }
  }

  @Test
  public void returnsNullWhenWalledOff() {
    GridModel grid = new GridModel();
    // net 1 spans the full board height at col 3: no way around within bounds
    for (int row = 0; row <= 9; row++) {
      grid.claimRun(1, List.of(new Cell(3, row)));
    }
    Router router = new Router(grid, BOUNDS);

    assertNull(router.findPath(0, new Cell(1, 5), Set.of(new Cell(6, 5))));
  }

  @Test
  public void staysInsideBounds() {
    Router router = new Router(new GridModel(), new Rectangle(0, 0, 9, 0));
    // single-row board: the only path is the straight corridor
    List<Cell> path = router.findPath(0, new Cell(0, 0), Set.of(new Cell(9, 0)));

    assertEquals(10, path.size());
  }

  @Test
  public void prefersFewerTurns() {
    Router router = new Router(new GridModel(), BOUNDS);

    // three diagonal steps and one straight reach (5,4); fewest turns wins among equal cost
    List<Cell> path = router.findPath(0, new Cell(1, 1), Set.of(new Cell(5, 4)));

    int turns = 0;
    for (int i = 2; i < path.size(); i++) {
      int dc1 = path.get(i - 1).col() - path.get(i - 2).col();
      int dr1 = path.get(i - 1).row() - path.get(i - 2).row();
      int dc2 = path.get(i).col() - path.get(i - 1).col();
      int dr2 = path.get(i).row() - path.get(i - 1).row();
      if (dc1 != dc2 || dr1 != dr2) {
        turns++;
      }
    }
    assertEquals(5, path.size());
    assertEquals(1, turns);
  }

  @Test
  public void routesDontCrossForeignDiagonals() {
    GridModel grid = new GridModel();
    // net 0 claims the main diagonal; the opposite diagonals of its squares are off limits,
    // so net 1 must go around the claimed run's end, not squeeze across it
    grid.claimRun(0, List.of(new Cell(0, 0), new Cell(1, 1), new Cell(2, 2)));
    Router router = new Router(grid, new Rectangle(0, 0, 3, 3));

    List<Cell> path = router.findPath(1, new Cell(2, 0), Set.of(new Cell(0, 2)));

    assertNotNull(path);
    assertTrue(!path.contains(new Cell(1, 1)));
    for (int i = 1; i < path.size(); i++) {
      assertNull(grid.wireCrossingNetAt(path.get(i - 1), path.get(i)));
    }
  }

  @Test
  public void reachesNearestOfMultipleTargets() {
    Router router = new Router(new GridModel(), BOUNDS);

    List<Cell> path =
        router.findPath(0, new Cell(0, 0), Set.of(new Cell(9, 9), new Cell(2, 0)));

    assertEquals(new Cell(2, 0), path.get(path.size() - 1));
  }

  @Test
  public void routesThreePinNetAsConnectedTree() {
    GridModel grid = new GridModel();
    Router router = new Router(grid, BOUNDS);

    RoutedNet net =
        routePins(router, 0, new Cell(2, 2), new Cell(8, 2), new Cell(5, 6));

    assertTrue(net.getFailedPins().isEmpty());
    assertEquals(2, net.getRuns().size());
    Set<Cell> tree = net.getTreeCells();
    assertTrue(tree.contains(new Cell(2, 2)));
    assertTrue(tree.contains(new Cell(8, 2)));
    assertTrue(tree.contains(new Cell(5, 6)));
    // minimal tree: 6 steps between the seed pair + 4 up to the third pin
    assertEquals(10, net.getWireLength());
    // the tree is claimed: a foreign run may not pass through it
    assertFalse(grid.canPassHole(1, new Cell(5, 2)));
  }

  @Test
  public void collectsUnreachablePinAsFailed() {
    GridModel grid = new GridModel();
    // wall net 1 across the full board height at col 6
    for (int row = 0; row <= 9; row++) {
      grid.claimRun(1, List.of(new Cell(6, row)));
    }
    Router router = new Router(grid, BOUNDS);

    RoutedNet net =
        routePins(router, 0, new Cell(1, 1), new Cell(4, 1), new Cell(9, 1));

    assertEquals(List.of(new Cell(9, 1)), net.getFailedPins());
    assertEquals(1, net.getRuns().size());
  }

  @Test
  public void singlePinNetNeedsNoRouting() {
    Router router = new Router(new GridModel(), BOUNDS);

    RoutedNet net = routePins(router, 0, new Cell(4, 4));

    assertTrue(net.getRuns().isEmpty());
    assertTrue(net.getFailedPins().isEmpty());
  }

  private static RoutedNet routePins(Router router, int netId, Cell... pins) {
    return router.routeNet(netId, List.of(pins));
  }

  @Test
  public void crossingNetsOnBoundedBoardNeedExactlyOneJumper() {
    GridModel grid = new GridModel();
    Router router = new Router(grid, new Rectangle(0, 0, 4, 4));
    // net 0 spans the full width, net 1 the full height: they must cross somewhere
    List<List<Cell>> nets = List.of(
        List.of(new Cell(0, 2), new Cell(4, 2)),
        List.of(new Cell(2, 0), new Cell(2, 4)));

    RoutingResult result = router.routeAll(nets);

    assertEquals(1, result.getJumperCount());
    for (RoutedNet net : result.getNets()) {
      assertTrue(net.getFailedPins().isEmpty());
    }
    // one net routed on the underside with minimal length, the other jumped
    assertEquals(4, result.getTotalWireLength());
  }

  @Test
  public void parallelNetsNeedNoJumpers() {
    Router router = new Router(new GridModel(), new Rectangle(0, 0, 4, 4));
    List<List<Cell>> nets = List.of(
        List.of(new Cell(0, 1), new Cell(4, 1)),
        List.of(new Cell(0, 3), new Cell(4, 3)));

    RoutingResult result = router.routeAll(nets);

    assertEquals(0, result.getJumperCount());
    assertEquals(8, result.getTotalWireLength());
  }

  @Test
  public void ripsUpBlockingNetInsteadOfJumping() {
    // same crossing topology as the jumper fixture, but one extra row: after ripping up the
    // horizontal net, it can detour under the vertical one — no jumper needed at all
    Router router = new Router(new GridModel(), new Rectangle(0, 0, 4, 5));
    List<List<Cell>> nets = List.of(
        List.of(new Cell(0, 2), new Cell(4, 2)),
        List.of(new Cell(2, 0), new Cell(2, 4)));

    RoutingResult result = router.routeAll(nets);

    assertEquals(0, result.getJumperCount());
    // vertical net straight (4) + horizontal net detouring diagonally around it (6)
    assertEquals(10, result.getTotalWireLength());
  }

  @Test
  public void netsMayEndOnAdjacentPinsOfOtherNets() {
    GridModel grid = new GridModel();
    Router router = new Router(grid, new Rectangle(0, 0, 9, 9));
    // an IC-like pin row: adjacent pins belong to different nets
    List<List<Cell>> nets = List.of(
        List.of(new Cell(2, 2), new Cell(7, 5)),
        List.of(new Cell(2, 3), new Cell(7, 6)));

    RoutingResult result = router.routeAll(nets);

    assertEquals(0, result.getJumperCount());
    for (RoutedNet net : result.getNets()) {
      assertTrue(net.getFailedPins().isEmpty());
    }
  }
}

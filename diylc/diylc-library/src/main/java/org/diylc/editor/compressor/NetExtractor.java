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

import java.util.ArrayList;
import java.util.List;

import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.presenter.ContinuityArea;
import org.diylc.netlist.Group;
import org.diylc.netlist.Netlist;
import org.diylc.netlist.NetlistBuilder;
import org.diylc.netlist.Node;
import org.diylc.presenter.Connection;

/**
 * Extracts the nets the compressor must preserve. Unlike
 * {@link NetlistBuilder#extractNetlists}, builds its own node set from every sticky control
 * point of the given real parts, regardless of control point node names — components like
 * {@code PCBTerminalBlock} deliberately report no node names and would otherwise silently drop
 * their nets.
 *
 * @author Layout Compressor contributors
 */
public class NetExtractor {

  public static List<Group> extractNets(Project project, List<ContinuityArea> continuityAreas,
      List<IDIYComponent<?>> realParts) {
    List<Node> nodes = new ArrayList<Node>();
    for (IDIYComponent<?> c : realParts) {
      for (int i = 0; i < c.getControlPointCount(); i++) {
        if (c.isControlPointSticky(i)) {
          nodes.add(new Node(c, i));
        }
      }
    }
    List<Connection> connections = NetlistBuilder.getDirectConnections(project);
    Netlist netlist =
        NetlistBuilder.buildNetlist(project.getComponents(), nodes, continuityAreas, connections);
    return netlist.getSortedGroups();
  }
}

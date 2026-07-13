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
package org.diylc.swing.plugins.compressor;

import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.AbstractAction;

import org.diylc.common.IPlugInPort;
import org.diylc.editor.compressor.LayoutCompressor;
import org.diylc.editor.compressor.LayoutEmitter;
import org.diylc.presenter.ComponentArea;
import org.diylc.presenter.ContinuityArea;
import org.diylc.presenter.DrawingManager;
import org.diylc.swing.ISwingUI;
import org.diylc.utils.IconLoader;

/**
 * Runs the layout compressor as a single undoable edit and reports the outcome. The editor
 * verifies netlist equality itself and aborts without touching the project on mismatch (the
 * presenter then shows its generic error dialog and logs the diff).
 *
 * @author Layout Compressor contributors
 */
public class CompressAction extends AbstractAction {

  private static final long serialVersionUID = 1L;

  private static final String TITLE = "Layout Compressor";

  private final IPlugInPort plugInPort;
  private final ISwingUI swingUI;

  public CompressAction(IPlugInPort plugInPort, ISwingUI swingUI) {
    super();
    this.plugInPort = plugInPort;
    this.swingUI = swingUI;
    putValue(AbstractAction.NAME, "Compress Layout");
    putValue(AbstractAction.SMALL_ICON, IconLoader.FitToSize.getIcon());
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    DrawingManager drawingManager = plugInPort.getDrawingManager();
    List<ContinuityArea> continuityAreas = drawingManager.getContinuityAreas();
    LayoutCompressor compressor = new LayoutCompressor(continuityAreas, (c) -> {
      ComponentArea area = drawingManager.getComponentArea(c);
      return area == null || area.getOutlineArea() == null ? null
          : area.getOutlineArea().getBounds2D();
    });

    plugInPort.applyEditor(compressor);

    LayoutCompressor.Stats stats = compressor.getStats();
    if (stats == null) {
      // the presenter already showed its error dialog; nothing was changed
      return;
    }
    StringBuilder sb = new StringBuilder();
    if (stats.boardCells() != null) {
      // occupied extent is inclusive, and the board adds a one-hole margin on each side
      int holesAcross = stats.boardCells().width + 1 + 2 * LayoutEmitter.BOARD_MARGIN_CELLS;
      int holesDown = stats.boardCells().height + 1 + 2 * LayoutEmitter.BOARD_MARGIN_CELLS;
      sb.append("Compressed onto a ").append(holesAcross).append(" x ").append(holesDown)
          .append(" hole board.\n");
    }
    sb.append(stats.movedParts()).append(" parts placed");
    if (stats.remoteParts() > 0) {
      sb.append(", ").append(stats.remoteParts())
          .append(" off-grid parts left in place (").append(stats.flyingWires())
          .append(" flying wires)");
    }
    sb.append(".\n");
    sb.append(stats.netCount()).append(" nets routed: ").append(stats.wireLength())
        .append(" underside wire steps, ").append(stats.jumperCount())
        .append(" top-side jumpers.\n\nUndo restores the original layout.");
    swingUI.showMessage(sb.toString(), TITLE, ISwingUI.INFORMATION_MESSAGE);
  }
}

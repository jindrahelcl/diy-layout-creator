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

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;

import org.diylc.swingframework.TextDialog;

import org.diylc.common.IPlugInPort;
import org.diylc.common.ITask;
import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.CompressionSurvey;
import org.diylc.presenter.ComponentArea;
import org.diylc.presenter.ContinuityArea;
import org.diylc.presenter.DrawingManager;
import org.diylc.swing.ISwingUI;
import org.diylc.utils.IconLoader;

/**
 * Shows a survey of what the layout compressor would operate on: component classification and
 * net statistics. The actual compression will be built on top of this analysis.
 *
 * @author Layout Compressor contributors
 */
public class CompressLayoutAction extends AbstractAction {

  private static final long serialVersionUID = 1L;

  private static final String TITLE = "Layout Compressor";

  private final IPlugInPort plugInPort;
  private final ISwingUI swingUI;

  public CompressLayoutAction(IPlugInPort plugInPort, ISwingUI swingUI) {
    super();
    this.plugInPort = plugInPort;
    this.swingUI = swingUI;
    putValue(AbstractAction.NAME, "Compress Layout (Preview)");
    putValue(AbstractAction.SMALL_ICON, IconLoader.FitToSize.getIcon());
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    swingUI.executeBackgroundTask(new ITask<CompressionSurvey>() {

      @Override
      public CompressionSurvey doInBackground() throws Exception {
        DrawingManager drawingManager = plugInPort.getDrawingManager();
        List<ContinuityArea> continuityAreas = drawingManager.getContinuityAreas();
        return CompressionSurvey.of(plugInPort.getCurrentProject(), continuityAreas, (c) -> {
          ComponentArea area = drawingManager.getComponentArea(c);
          return area == null || area.getOutlineArea() == null ? null
              : area.getOutlineArea().getBounds2D();
        });
      }

      @Override
      public void failed(Exception e) {
        swingUI.showMessage("Failed to analyze the layout: " + e.getMessage(), TITLE,
            ISwingUI.ERROR_MESSAGE);
      }

      @Override
      public void complete(CompressionSurvey survey) {
        new TextDialog(swingUI.getOwnerFrame().getRootPane(), generateHtml(survey), TITLE,
            new Dimension(560, 480)).setVisible(true);
      }
    }, true);
  }

  private static String generateHtml(CompressionSurvey survey) {
    StringBuilder sb = new StringBuilder("<html><body>");
    sb.append("<h2>Layout Compressor &mdash; Survey</h2>");
    sb.append("<table cellspacing='0' cellpadding='4' border='0'>");
    appendCategory(sb, "Real parts (will be placed)",
        survey.getClassification().getRealParts());
    appendCategory(sb, "Connectivity (will be re-routed)",
        survey.getClassification().getConnectivity());
    appendCategory(sb, "Boards (will be replaced)", survey.getClassification().getBoards());
    appendCategory(sb, "Decorations (left alone)", survey.getClassification().getDecorations());
    sb.append("</table>");
    sb.append("<h3>Nets</h3>");
    sb.append("<p>").append(survey.getNetCount())
        .append(" nets (electrical junctions) connecting ").append(survey.getConnectedPinCount())
        .append(" of the ").append(survey.getStickyPinCount())
        .append(" pins on real parts; ").append(survey.getOpenPinCount())
        .append(" pins unconnected.</p>");
    sb.append("<h3>Footprints</h3>");
    sb.append("<p>").append(survey.getOnGridCount()).append(" parts fit the 0.1&quot; grid (")
        .append(survey.getStretchableCount()).append(" with stretchable leads); ")
        .append(survey.getOffGridParts().size()).append(" off-grid");
    if (!survey.getOffGridParts().isEmpty()) {
      sb.append(": ")
          .append(survey.getOffGridParts().stream().map(IDIYComponent::getName).sorted()
              .collect(Collectors.joining(", ")))
          .append(" &mdash; these stay in place and get connected by wires");
    }
    sb.append(".</p>");
    sb.append("</body></html>");
    return sb.toString();
  }

  private static void appendCategory(StringBuilder sb, String title,
      List<IDIYComponent<?>> components) {
    sb.append("<tr><td valign='top'><b>").append(title).append(":</b></td><td valign='top'>")
        .append(components.size()).append("</td><td valign='top'>")
        .append(components.stream().map(IDIYComponent::getName).sorted()
            .collect(Collectors.joining(", ")))
        .append("</td></tr>");
  }
}

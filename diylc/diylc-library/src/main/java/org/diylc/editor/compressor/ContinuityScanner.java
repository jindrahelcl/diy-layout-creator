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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;

import org.diylc.appframework.miscutils.InMemoryConfigurationManager;
import org.diylc.common.DrawOption;
import org.diylc.common.DummyView;
import org.diylc.core.Project;
import org.diylc.presenter.ContinuityArea;
import org.diylc.presenter.Presenter;

/**
 * Computes a project's continuity areas without the GUI. DIYLC derives conductive surfaces
 * (traces, pads, strips) while drawing, so the scanner draws the project through a private
 * {@link Presenter} onto a throwaway image — the image size doesn't matter, every component's
 * draw executes regardless of the raster clip — and collects the areas from its
 * {@code DrawingManager}.
 *
 * @author Layout Compressor contributors
 */
public class ContinuityScanner {

  public static List<ContinuityArea> scan(Project project) {
    Presenter presenter =
        new Presenter(new DummyView(), InMemoryConfigurationManager.getInstance());
    presenter.loadProject(project, true, null);
    BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = image.createGraphics();
    presenter.draw(g2d, EnumSet.of(DrawOption.ANTIALIASING), null, null, null, null);
    g2d.dispose();
    return presenter.getDrawingManager().getContinuityAreas();
  }
}

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
package org.diylc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;

import org.diylc.appframework.miscutils.ConfigurationManager;
import org.diylc.appframework.miscutils.InMemoryConfigurationManager;
import org.diylc.swingframework.export.DrawingExporter;

import org.diylc.core.IDIYComponent;
import org.diylc.editor.compressor.LayoutCompressor;
import org.diylc.presenter.ComponentArea;
import org.diylc.presenter.ContinuityArea;
import org.diylc.presenter.DrawingManager;
import org.diylc.presenter.Presenter;
import org.diylc.swing.plugins.file.ProjectDrawingProvider;
import org.junit.Test;

/**
 * End-to-end compressor run on a real traced layout (copper traces, DIL IC, terminal block,
 * leaded passives), reproducing the conditions of the in-app "Compress Layout" action:
 * project loaded from file, continuity areas and body bounds taken from a real draw pass.
 * Historic regression: runs passing through same-net pins used to be emitted as single
 * segments, losing the mid-run connections and failing netlist verification.
 */
public class CompressorSmokeTest {

  @Test
  public void compressesTracedLayoutPreservingNetlist() throws Exception {
    ConfigurationManager.getInstance().initialize("diylc-test");
    MockView view = new MockView();
    Presenter presenter = new Presenter(view, InMemoryConfigurationManager.getInstance());
    presenter.loadProjectFromFile("src/test/resources/compressor/traced-layout.diy");

    // force a full draw so component areas and continuity areas get populated, like in the app
    ProjectDrawingProvider drawingProvider =
        new ProjectDrawingProvider(presenter, false, true, false);
    File png = File.createTempFile("compressor-smoke", ".png");
    png.deleteOnExit();
    DrawingExporter.getInstance().exportPNG(drawingProvider, png);

    DrawingManager drawingManager = presenter.getDrawingManager();
    List<ContinuityArea> continuityAreas = drawingManager.getContinuityAreas();
    assertTrue("continuity areas should be populated", continuityAreas.size() > 0);

    LayoutCompressor compressor = new LayoutCompressor(continuityAreas, (c) -> {
      ComponentArea area = drawingManager.getComponentArea(c);
      return area == null || area.getOutlineArea() == null ? null
          : area.getOutlineArea().getBounds2D();
    });
    // the editor verifies netlist equality itself and throws on any mismatch
    compressor.edit(presenter.getCurrentProject(), new HashSet<IDIYComponent<?>>());

    LayoutCompressor.Stats stats = compressor.getStats();
    assertNotNull(stats);
    assertTrue(stats.movedParts() >= 5);
    assertTrue(stats.boardCells().width < 40 && stats.boardCells().height < 40);
  }
}

package org.diylc;

import java.io.File;
import java.util.HashSet;
import java.util.List;

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

/** Temporary reproduction of the in-app "Compress Layout" failure on aaa.diy. Do not commit. */
public class CompressAaaDebugTest {

  @Test
  public void reproduce() throws Exception {
    org.diylc.appframework.miscutils.ConfigurationManager.getInstance().initialize("diylc-test");
    MockView view = new MockView();
    Presenter presenter = new Presenter(view, InMemoryConfigurationManager.getInstance());
    presenter.loadProjectFromFile("c:\\Users\\Jindra\\Projects\\diylc\\aaa.diy");

    // force a full draw so component areas and continuity areas get populated, like in the app
    ProjectDrawingProvider drawingProvider =
        new ProjectDrawingProvider(presenter, false, true, false);
    File png = File.createTempFile("aaa-compress-debug", ".png");
    png.deleteOnExit();
    DrawingExporter.getInstance().exportPNG(drawingProvider, png);

    DrawingManager drawingManager = presenter.getDrawingManager();
    List<ContinuityArea> continuityAreas = drawingManager.getContinuityAreas();
    System.out.println("continuity areas: " + continuityAreas.size());

    LayoutCompressor compressor = new LayoutCompressor(continuityAreas, (c) -> {
      ComponentArea area = drawingManager.getComponentArea(c);
      return area == null || area.getOutlineArea() == null ? null
          : area.getOutlineArea().getBounds2D();
    });
    try {
      compressor.edit(presenter.getCurrentProject(), new HashSet<IDIYComponent<?>>());
      System.out.println("SUCCESS: " + compressor.getStats());
    } catch (Exception e) {
      System.out.println("COMPRESSION FAILED:");
      e.printStackTrace(System.out);
      throw e;
    }
  }
}

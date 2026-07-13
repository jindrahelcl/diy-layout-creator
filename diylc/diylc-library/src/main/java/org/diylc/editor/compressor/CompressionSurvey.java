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

import java.util.List;

import org.diylc.core.IDIYComponent;
import org.diylc.core.Project;
import org.diylc.presenter.ContinuityArea;
import org.diylc.netlist.Group;

/**
 * Summary of what the layout compressor would operate on: component classification plus net and
 * pin statistics derived from {@link NetExtractor}'s net model.
 *
 * @author Layout Compressor contributors
 */
public class CompressionSurvey {

  private final ComponentClassifier.Classification classification;
  private final List<Group> nets;
  private final int stickyPinCount;

  private CompressionSurvey(ComponentClassifier.Classification classification, List<Group> nets,
      int stickyPinCount) {
    this.classification = classification;
    this.nets = nets;
    this.stickyPinCount = stickyPinCount;
  }

  public static CompressionSurvey of(Project project, List<ContinuityArea> continuityAreas) {
    ComponentClassifier.Classification classification =
        new ComponentClassifier().classify(project);

    List<Group> nets =
        NetExtractor.extractNets(project, continuityAreas, classification.getRealParts());

    int stickyPinCount = 0;
    for (IDIYComponent<?> c : classification.getRealParts()) {
      for (int i = 0; i < c.getControlPointCount(); i++) {
        if (c.isControlPointSticky(i)) {
          stickyPinCount++;
        }
      }
    }

    return new CompressionSurvey(classification, nets, stickyPinCount);
  }

  public ComponentClassifier.Classification getClassification() {
    return classification;
  }

  public List<Group> getNets() {
    return nets;
  }

  public int getNetCount() {
    return nets.size();
  }

  /** Total count of pin memberships across all nets. */
  public int getConnectedPinCount() {
    return nets.stream().mapToInt(g -> g.getNodes().size()).sum();
  }

  public int getStickyPinCount() {
    return stickyPinCount;
  }

  /** Pins on real parts that belong to no net. */
  public int getOpenPinCount() {
    return stickyPinCount - getConnectedPinCount();
  }
}

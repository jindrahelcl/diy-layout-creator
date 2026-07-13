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
package org.diylc.swing.plugins.compressor;

import java.util.EnumSet;

import org.diylc.common.EventType;
import org.diylc.common.IPlugIn;
import org.diylc.common.IPlugInPort;
import org.diylc.swing.ISwingUI;

/**
 * Entry point for the layout compressor: packs components onto the smallest perfboard that
 * preserves the circuit, wiring the underside with bare runs and the top with jumpers.
 *
 * @author Layout Compressor contributors
 */
public class CompressorPlugin implements IPlugIn {

  private static final String EDIT_TITLE = "Edit";

  private final ISwingUI swingUI;

  public CompressorPlugin(ISwingUI swingUI) {
    this.swingUI = swingUI;
  }

  @Override
  public void connect(IPlugInPort plugInPort) {
    swingUI.injectMenuAction(null, EDIT_TITLE);
    swingUI.injectMenuAction(new CompressLayoutAction(plugInPort, swingUI), EDIT_TITLE);
  }

  @Override
  public EnumSet<EventType> getSubscribedEventTypes() {
    return EnumSet.noneOf(EventType.class);
  }

  @Override
  public void processMessage(EventType eventType, Object... params) {}
}

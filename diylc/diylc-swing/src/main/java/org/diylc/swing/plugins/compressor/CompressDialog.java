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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * Progress window for a running compression: phase label, progress bar, and two ways out —
 * "Finish Now" stops the compacting loop early and keeps the best layout found so far, while
 * "Cancel" (or closing the window) aborts the whole run leaving the project untouched. The
 * flags are polled by the compressor from its worker thread; {@link #reportProgress} may be
 * called from any thread.
 *
 * @author Layout Compressor contributors
 */
public class CompressProgressDialog extends JDialog {

  private static final long serialVersionUID = 1L;

  private final JLabel phaseLabel;
  private final JProgressBar progressBar;
  private final JButton finishButton;

  private volatile boolean finishRequested;
  private volatile boolean aborted;

  public CompressProgressDialog(Frame owner) {
    super(owner, "Layout Compressor", false);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {

      @Override
      public void windowClosing(WindowEvent e) {
        aborted = true;
      }
    });

    phaseLabel = new JLabel("Starting...");
    progressBar = new JProgressBar(0, 100);

    finishButton = new JButton("Finish Now");
    finishButton.setToolTipText("Stop optimizing and keep the best layout found so far");
    finishButton.addActionListener((e) -> {
      finishRequested = true;
      finishButton.setEnabled(false);
    });
    JButton cancelButton = new JButton("Cancel");
    cancelButton.setToolTipText("Abort and leave the project unchanged");
    cancelButton.addActionListener((e) -> aborted = true);

    JPanel content = new JPanel(new BorderLayout(0, 8));
    content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    content.add(phaseLabel, BorderLayout.NORTH);
    content.add(progressBar, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(finishButton);
    buttons.add(cancelButton);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);

    setSize(360, 130);
    setLocationRelativeTo(owner);
  }

  /** True once the user asked to stop the loop early, keeping the best result so far. */
  public boolean isFinishRequested() {
    return finishRequested;
  }

  /** True once the user asked to abort the whole compression. */
  public boolean isAborted() {
    return aborted;
  }

  /** Thread-safe progress update: phase label plus overall fraction in [0, 1]. */
  public void reportProgress(String phase, double fraction) {
    SwingUtilities.invokeLater(() -> {
      phaseLabel.setText(phase);
      progressBar.setValue((int) Math.round(fraction * 100));
    });
  }
}

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
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * One dialog window for the whole "Compress Layout" flow, cycling through content-pane states as
 * the run progresses: parameters ({@link #showParams}, before the run), progress
 * ({@link #showProgress}, during), and the result summary ({@link #showReport}). On the progress
 * screen, "Finish Now" stops the compacting loop early and keeps the best layout found so far,
 * while "Cancel" (or closing the window) aborts the whole run leaving the project untouched. The
 * abort/finish flags are polled by the compressor from its worker thread; {@link #reportProgress}
 * may be called from any thread — everything else is event-thread only.
 *
 * @author Layout Compressor contributors
 */
public class CompressDialog extends JDialog {

  private static final long serialVersionUID = 1L;

  private final JSpinner timeBudgetSpinner;
  private final JLabel phaseLabel = new JLabel("Starting...");
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JButton finishButton;

  private volatile boolean finishRequested;
  private volatile boolean aborted;
  private Runnable windowCloseAction = this::dispose;

  public CompressDialog(Frame owner, long defaultTimeBudgetMs) {
    super(owner, "Layout Compressor", false);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {

      @Override
      public void windowClosing(WindowEvent e) {
        windowCloseAction.run();
      }
    });

    timeBudgetSpinner = new JSpinner(
        new SpinnerNumberModel((int) Math.max(1, defaultTimeBudgetMs / 1000), 1, 60, 1));

    finishButton = new JButton("Finish Now");
    finishButton.setToolTipText("Stop optimizing and keep the best layout found so far");
    finishButton.addActionListener((e) -> {
      finishRequested = true;
      finishButton.setEnabled(false);
    });

    setLocationRelativeTo(owner);
  }

  /** Time budget chosen on the params screen, in milliseconds. */
  public long getTimeBudgetMs() {
    return ((Integer) timeBudgetSpinner.getValue()) * 1000L;
  }

  /**
   * Shows the parameter screen: the time budget spinner. {@code onCompress} runs when the user
   * clicks "Compress"; {@code onCancel} runs on "Cancel" or the window close box, after the
   * dialog has already been disposed.
   */
  public void showParams(Runnable onCompress, Runnable onCancel) {
    windowCloseAction = () -> {
      dispose();
      onCancel.run();
    };

    JPanel form = new JPanel(new GridLayout(1, 2, 8, 8));
    form.add(new JLabel("Time budget (seconds):"));
    form.add(timeBudgetSpinner);

    JButton compressButton = new JButton("Compress");
    compressButton.addActionListener((e) -> onCompress.run());
    JButton cancelButton = new JButton("Cancel");
    cancelButton.addActionListener((e) -> {
      dispose();
      onCancel.run();
    });
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(compressButton);
    buttons.add(cancelButton);

    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    content.add(form, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
    setSize(320, 130);
    setLocationRelativeTo(getOwner());
  }

  /** Shows the progress screen: phase label, progress bar, "Finish Now" and "Cancel". */
  public void showProgress() {
    windowCloseAction = () -> aborted = true;
    finishRequested = false;
    finishButton.setEnabled(true);

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
    setLocationRelativeTo(getOwner());
  }

  /** Shows the final report screen: result summary text and a "Close" button. */
  public void showReport(String text) {
    windowCloseAction = this::dispose;

    JTextArea reportArea = new JTextArea(text);
    reportArea.setEditable(false);
    reportArea.setFocusable(false);
    reportArea.setOpaque(false);
    reportArea.setFont(phaseLabel.getFont());

    JButton closeButton = new JButton("Close");
    closeButton.addActionListener((e) -> dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.add(closeButton);

    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    content.add(reportArea, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
    pack();
    setLocationRelativeTo(getOwner());
    getRootPane().setDefaultButton(closeButton);
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

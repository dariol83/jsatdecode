package eu.dariolucia.jsatdecode.inspector;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** EDT-owned modal presentation of the independently managed radio tests. */
final class DropSampleTestDialogs {
    private static final Color ATTENTION = new Color(175, 30, 30);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC);

    private DropSampleTestDialogs() { }

    static ProgressDialog progress(Window owner, int total, Runnable cancel) {
        requireEdt();
        return new ProgressDialog(owner, total, cancel);
    }

    static void showResults(Window owner, List<DropSampleResult> rows, boolean cancelled) {
        results(owner, rows, cancelled).setVisible(true);
    }

    static JDialog results(Window owner, List<DropSampleResult> rows, boolean cancelled) {
        requireEdt();
        return new ResultsDialog(owner, List.copyOf(rows), cancelled);
    }

    /** Remains modal after cancellation until the management worker has released the source. */
    static final class ProgressDialog extends JDialog {
        private final JLabel run = new JLabel("Preparing test sequence");
        private final JLabel parameters = new JLabel("Connecting to the configured PlutoPlus IP address");
        private final JLabel phase = new JLabel("CONNECTING");
        private final JLabel elapsed = new JLabel("Elapsed: 0.000 s");
        private final JProgressBar overall;
        private final JButton cancel = new JButton("Cancel");
        private final Runnable cancelAction;
        private boolean cancelling;
        private boolean finished;

        private ProgressDialog(Window owner, int total, Runnable cancelAction) {
            super(owner, "PlutoPlus drop samples test", ModalityType.APPLICATION_MODAL);
            if (total < 1) throw new IllegalArgumentException("Test sequence must contain at least one run");
            this.cancelAction = Objects.requireNonNull(cancelAction);
            overall = new JProgressBar(0, total);
            overall.setStringPainted(true);
            overall.setString("0 / " + total + " runs completed");
            JPanel content = new JPanel(new BorderLayout(10, 12));
            content.setBorder(new EmptyBorder(14, 16, 14, 16));
            JPanel details = new JPanel(new GridLayout(0, 1, 0, 7));
            details.add(run);
            details.add(parameters);
            details.add(phase);
            details.add(elapsed);
            content.add(details, BorderLayout.NORTH);
            content.add(overall, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttons.add(cancel);
            content.add(buttons, BorderLayout.SOUTH);
            setContentPane(content);
            cancel.addActionListener(event -> requestCancel());
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { requestCancel(); }
            });
            setResizable(false);
            pack();
            setSize(Math.max(540, getWidth()), getHeight());
            setLocationRelativeTo(owner);
        }

        void update(DropSampleTest.Progress progress) {
            requireEdt();
            if (finished) return;
            DropSampleTest.Parameters settings = progress.parameters();
            run.setText("Run " + progress.runId() + " / " + progress.total());
            parameters.setText(String.format(Locale.ROOT,
                    "Duration: %d s    Data rate: %,d samples/s    Buffer: %,d samples",
                    settings.durationSeconds(), settings.dataRate(), settings.bufferSize()));
            if (!cancelling) phase.setText(progress.state().name());
            elapsed.setText(String.format(Locale.ROOT, "Elapsed: %.3f s", progress.elapsedSeconds()));
            int completed = Math.max(0, progress.runId() - 1);
            if (progress.state() == DropSampleTest.State.COMPLETE || progress.state() == DropSampleTest.State.FAILED)
                completed++;
            overall.setMaximum(progress.total());
            overall.setValue(Math.min(progress.total(), completed));
            overall.setString(completed + " / " + progress.total() + " runs completed");
        }

        void finish() {
            requireEdt();
            finished = true;
            dispose();
        }

        /** Opens the modal dialog on the EDT unless management already completed the test. */
        @Override public void setVisible(boolean visible) {
            requireEdt();
            if (!visible || !finished) super.setVisible(visible);
        }

        private void requestCancel() {
            requireEdt();
            if (cancelling || finished) return;
            cancelling = true;
            cancel.setEnabled(false);
            phase.setText("Cancelling; waiting for the SDR connection to close...");
            cancelAction.run();
        }
    }

    private static final class ResultsDialog extends JDialog {
        private final List<DropSampleResult> rows;
        private final JButton save = new JButton("Save CSV...");
        private final JButton close = new JButton("Close");
        private final JLabel saveStatus = new JLabel(" ");
        private boolean saving;

        private ResultsDialog(Window owner, List<DropSampleResult> rows, boolean cancelled) {
            super(owner, "PlutoPlus drop samples test results", ModalityType.APPLICATION_MODAL);
            this.rows = rows;
            JPanel content = new JPanel(new BorderLayout(8, 10));
            content.setBorder(new EmptyBorder(12, 12, 12, 12));
            long completed = rows.stream().filter(row -> row.status() == DropSampleResult.Status.COMPLETE).count();
            long attention = rows.stream().filter(DropSampleResult::attentionRequired).count();
            JLabel summary = new JLabel((cancelled ? "Cancelled. " : "Finished. ") + completed
                    + " completed runs; " + attention + " require attention.");
            content.add(summary, BorderLayout.NORTH);
            JTable table = new ResultsTable(rows);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(1660, 390));
            content.add(scroll, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(8, 8));
            JLabel explanation = new JLabel("<html>Red rows indicate a significant deficit or a failed run. "
                    + "Hover over a row for details.<br>Measured samples and all derived metrics include the final evaluation read. "
                    + "Significant loss exceeds max(0.1% expected samples, 2 ms at the configured rate).<br>Loss is estimated from host timing; this test cannot identify "
                    + "the hardware cause of a deficit.</html>");
            footer.add(explanation, BorderLayout.NORTH);
            footer.add(saveStatus, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(save);
            buttons.add(close);
            footer.add(buttons, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            setContentPane(content);
            save.addActionListener(event -> saveCsv());
            close.addActionListener(event -> { if (!saving) dispose(); });
            getRootPane().setDefaultButton(close);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { if (!saving) dispose(); }
            });
            pack();
            Rectangle screen = owner == null ? GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds() : owner.getGraphicsConfiguration().getBounds();
            setSize(Math.min(getWidth(), Math.max(640, screen.width - 60)),
                    Math.min(getHeight(), Math.max(300, screen.height - 80)));
            setLocationRelativeTo(owner);
        }

        private void saveCsv() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export drop samples test results");
            chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
            chooser.setSelectedFile(Path.of("drop-samples-results-" + FILE_TIME.format(Instant.now()) + ".csv").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (!selected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                selected = selected.resolveSibling(selected.getFileName() + ".csv");
            boolean overwrite = Files.exists(selected);
            if (overwrite && JOptionPane.showConfirmDialog(this, "Replace the existing file?\n" + selected,
                    "Confirm CSV replacement", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
                return;
            Path destination = selected;
            saving = true;
            save.setEnabled(false);
            close.setEnabled(false);
            saveStatus.setText("Saving " + destination.getFileName() + "...");
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    StandardOpenOption[] options = overwrite
                            ? new StandardOpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                            : new StandardOpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW};
                    try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8, options)) {
                        DropSampleResult.writeCsv(writer, rows);
                    }
                    return null;
                }

                @Override protected void done() {
                    saving = false;
                    save.setEnabled(true);
                    close.setEnabled(true);
                    try {
                        get();
                        saveStatus.setText("Saved " + destination);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        saveFailed(failure);
                    } catch (ExecutionException failure) {
                        saveFailed(failure.getCause());
                    }
                }

                private void saveFailed(Throwable failure) {
                    saveStatus.setText("CSV export failed");
                    JOptionPane.showMessageDialog(ResultsDialog.this,
                            "Could not save " + destination + ":\n" + failure.getMessage(),
                            "CSV export failed", JOptionPane.ERROR_MESSAGE);
                }
            }.execute();
        }
    }

    private static final class ResultsTable extends JTable {
        private final List<DropSampleResult> rows;

        private ResultsTable(List<DropSampleResult> rows) {
            super(new ResultsModel(rows));
            this.rows = rows;
            setAutoCreateRowSorter(true);
            setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            setFillsViewportHeight(true);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setRowHeight(Math.max(22, getRowHeight() + 4));
            NumericRenderer numbers = new NumericRenderer();
            for (Class<?> type : new Class<?>[] {Number.class, Integer.class, Long.class, Double.class})
                setDefaultRenderer(type, numbers);
            int[] widths = {65, 125, 160, 155, 170, 180, 180, 175, 145, 170, 145};
            for (int index = 0; index < widths.length; index++) getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }

        /** Applies attention colors using the model index, including when the user sorts rows. */
        @Override public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component component = super.prepareRenderer(renderer, row, column);
            boolean attention = rows.get(convertRowIndexToModel(row)).attentionRequired();
            component.setForeground(attention ? isRowSelected(row) ? new Color(255, 210, 210) : ATTENTION
                    : isRowSelected(row) ? getSelectionForeground() : getForeground());
            return component;
        }

        /** Gives the full status and diagnostic reason for the hovered result row. */
        @Override public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) return null;
            DropSampleResult result = rows.get(convertRowIndexToModel(row));
            return result.status() + (result.detail().isBlank() ? "" : ": " + result.detail());
        }
    }

    private static final class ResultsModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Run ID", "Test duration (s)", "Data rate (samples/s)",
                "Buffer size (samples)", "Effective duration (ms)", "Theoretical # samples", "Measured # samples",
                "Average samples/s", "% dropped samples", "Estimated lost signal", "Status"};
        private final List<DropSampleResult> rows;

        private ResultsModel(List<DropSampleResult> rows) { this.rows = rows; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return column <= 3 ? Integer.class : column == 6 ? Long.class : column == 10 ? String.class : Double.class;
        }
        @Override public Object getValueAt(int row, int column) {
            DropSampleResult result = rows.get(row);
            return switch (column) {
                case 0 -> result.runId();
                case 1 -> result.testDurationSeconds();
                case 2 -> result.dataRate();
                case 3 -> result.bufferSize();
                case 4 -> result.effectiveDurationMs();
                case 5 -> result.theoreticalSamples();
                case 6 -> result.measuredSamples();
                case 7 -> result.averageSamplesPerSecond();
                case 8 -> result.droppedPercent();
                case 9 -> result.estimatedLostSignalMs();
                case 10 -> result.significantLoss() ? "Significant deficit" : result.status().name();
                default -> throw new IndexOutOfBoundsException(column);
            };
        }
    }

    private static final class NumericRenderer extends DefaultTableCellRenderer {
        private final DecimalFormat integer = new DecimalFormat("#,##0");
        private final DecimalFormat decimal = new DecimalFormat("#,##0.000");
        private final DecimalFormat percent = new DecimalFormat("0.0000");

        private NumericRenderer() { setHorizontalAlignment(SwingConstants.RIGHT); }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            int modelColumn = table.convertColumnIndexToModel(column);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) setText("\u2014");
            else if (modelColumn == 9) {
                double milliseconds = number.doubleValue();
                setText(milliseconds > 0 && milliseconds < 1
                        ? decimal.format(milliseconds * 1000) + " \u00b5s" : decimal.format(milliseconds) + " ms");
            } else setText((modelColumn <= 3 || modelColumn == 6 ? integer : modelColumn == 8 ? percent : decimal).format(number));
            return this;
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Test dialogs require the Swing EDT");
    }
}

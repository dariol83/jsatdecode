package eu.dariolucia.jsatdecode.inspector;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Optional real Swing test of cancellation, numeric sorting and deficit/error presentation; no radio. */
public final class DropSampleTestDesktopTest {
    private DropSampleTestDesktopTest() { }

    /**
     * Exercises both modal dialogs with synthetic progress and results, including cancellation
     * before joined source completion and row rendering after sorting.
     *
     * @param args optional output path for a results-dialog screenshot
     * @throws Exception if dialog lifecycle, rendering or screenshot output fails
     */
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Desktop test requires a graphical display");
        AtomicReference<JFrame> owner = new AtomicReference<>();
        AtomicReference<DropSampleTestDialogs.ProgressDialog> progress = new AtomicReference<>();
        AtomicReference<JDialog> results = new AtomicReference<>();
        AtomicReference<BufferedImage> screenshot = new AtomicReference<>();
        AtomicInteger cancellations = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("Drop samples dialog desktop test");
                owner.set(frame);
                frame.setSize(640, 400);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                progress.set(DropSampleTestDialogs.progress(frame, 3, cancellations::incrementAndGet));
                SwingUtilities.invokeLater(() -> progress.get().setVisible(true));
            });
            awaitVisible(progress.get());
            SwingUtilities.invokeAndWait(() -> {
                DropSampleTestDialogs.ProgressDialog dialog = progress.get();
                check(dialog.getModalityType() == Dialog.ModalityType.APPLICATION_MODAL, "Progress dialog is not modal");
                dialog.update(new DropSampleTest.Progress(2, 3, new DropSampleTest.Parameters(10, 65536, 1_000_000),
                        DropSampleTest.State.COUNTING, 3.25));
                check(labelContaining(dialog, "Run 2 / 3") != null, "Current run is missing");
                check(labelContaining(dialog, "Duration: 10 s") != null, "Run settings are missing");
                check(labelContaining(dialog, "COUNTING") != null, "Run state is missing");
                JProgressBar bar = descendant(dialog, JProgressBar.class);
                check(bar.getMaximum() == 3 && bar.getValue() == 1, "Completed-run progress is incorrect");
                AbstractButton cancel = button(dialog, "Cancel");
                cancel.doClick();
                check(cancellations.get() == 1 && !cancel.isEnabled(), "Cancel was not delivered exactly once");
                check(dialog.isShowing(), "Cancellation prematurely released the modal dialog");
                dialog.update(new DropSampleTest.Progress(2, 3, new DropSampleTest.Parameters(10, 65536, 1_000_000),
                        DropSampleTest.State.COUNTING, 3.5));
                check(labelContaining(dialog, "Cancelling;") != null, "Progress overwrote the cancellation message");
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                check(cancellations.get() == 1 && dialog.isShowing(), "Window close bypassed joined cancellation");
                dialog.finish();
                check(!dialog.isDisplayable(), "Finished progress dialog remained open");
                dialog.setVisible(true);
                check(!dialog.isShowing(), "Late modal-show callback reopened a completed dialog");
                results.set(DropSampleTestDialogs.results(owner.get(), syntheticResults(), true));
                SwingUtilities.invokeLater(() -> results.get().setVisible(true));
            });
            awaitVisible(results.get());
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = results.get();
                check(dialog.getModalityType() == Dialog.ModalityType.APPLICATION_MODAL, "Results dialog is not modal");
                JTable table = descendant(dialog, JTable.class);
                check(table.getRowCount() == 3 && table.getColumnCount() >= 10, "Required results are missing");
                check(table.getColumnClass(0) == Integer.class && table.getColumnClass(8) == Double.class,
                        "Result values do not have numeric sorting types");
                check(button(dialog, "Save CSV...").isEnabled(), "CSV export control is missing");
                check(labelContaining(dialog, "Cancelled.") != null, "Partial sequence was not identified");
                table.getRowSorter().toggleSortOrder(0);
                table.getRowSorter().toggleSortOrder(0);
                check(table.getValueAt(0, 0).equals(12), "Run IDs were sorted lexically rather than numerically");
                for (int row = 0; row < table.getRowCount(); row++) {
                    int id = (Integer) table.getValueAt(row, 0);
                    Component renderer = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                    Color color = renderer.getForeground();
                    boolean red = color.getRed() > color.getGreen() * 2 && color.getRed() > color.getBlue() * 2;
                    check(red == (id != 2), "Attention rendering used the wrong row after sorting");
                    if (id == 12) {
                        JLabel missing = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 4), row, 4);
                        check(missing.getText().equals("\u2014"), "Unavailable metrics were rendered as a numeric value");
                        Rectangle cell = table.getCellRect(row, 0, true);
                        String tooltip = table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED,
                                System.currentTimeMillis(), 0, cell.x + 2, cell.y + 2, 0, false));
                        check(tooltip.contains("Mock SDR failure"), "Failure reason is missing from row tooltip");
                    }
                    JLabel lostTime = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 9), row, 9);
                    check(id == 12 || lostTime.getText().endsWith(" ms"), "Estimated lost signal is missing its duration unit");
                }
                dialog.validate();
                BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try { dialog.paintAll(graphics); }
                finally { graphics.dispose(); }
                screenshot.set(image);
                button(dialog, "Close").doClick();
                check(!dialog.isDisplayable(), "Results close did not dispose the dialog");
                JDialog empty = DropSampleTestDialogs.results(owner.get(), List.of(), true);
                try {
                    check(descendant(empty, JTable.class).getRowCount() == 0, "Cancelled empty suite invented results");
                    check(labelContaining(empty, "Cancelled. 0 completed runs") != null,
                            "Cancellation before the first result is unclear");
                    check(button(empty, "Save CSV...").isEnabled(), "Empty partial results cannot be exported");
                } finally { empty.dispose(); }
            });
            if (args.length > 0) {
                Path output = Path.of(args[0]).toAbsolutePath();
                Files.createDirectories(output.getParent());
                ImageIO.write(screenshot.get(), "png", output.toFile());
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (progress.get() != null) progress.get().finish();
                if (results.get() != null) results.get().dispose();
                if (owner.get() != null) owner.get().dispose();
            });
        }
        System.out.println("Drop samples desktop test passed: modal cancellation/join, progress, typed sorting, deficit/error rendering and partial results.");
    }

    private static List<DropSampleResult> syntheticResults() {
        return List.of(
                new DropSampleResult(2, 5, 1_000_000, 4096, 5000, 5_000_000, 5_000_000,
                        1_000_000, 0, 0, DropSampleResult.Status.COMPLETE, "No persistent sample deficit"),
                new DropSampleResult(10, 5, 1_000_000, 4096, 5000, 5_000_000, 4_000_000,
                        800_000, 20, 1000, DropSampleResult.Status.COMPLETE, "Significant persistent deficit"),
                new DropSampleResult(12, 10, 2_000_000, 65536, Double.NaN, Double.NaN, 0,
                        Double.NaN, Double.NaN, Double.NaN, DropSampleResult.Status.FAILED, "Mock SDR failure"));
    }

    private static void awaitVisible(JDialog dialog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        AtomicReference<Boolean> showing = new AtomicReference<>(false);
        do {
            SwingUtilities.invokeAndWait(() -> showing.set(dialog.isShowing()));
            if (showing.get()) return;
            Thread.sleep(15);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Modal dialog did not become visible");
    }

    private static JLabel labelContaining(Container root, String text) {
        for (Component component : descendants(root))
            if (component instanceof JLabel label && label.getText().contains(text)) return label;
        return null;
    }

    private static AbstractButton button(Container root, String text) {
        for (Component component : descendants(root))
            if (component instanceof AbstractButton button && button.getText().equals(text)) return button;
        throw new AssertionError("Missing button: " + text);
    }

    private static <T extends Component> T descendant(Container root, Class<T> type) {
        for (Component component : descendants(root)) if (type.isInstance(component)) return type.cast(component);
        throw new AssertionError("Missing component: " + type.getSimpleName());
    }

    private static List<Component> descendants(Container root) {
        ArrayList<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

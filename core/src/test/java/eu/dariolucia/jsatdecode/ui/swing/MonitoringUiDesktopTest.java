package eu.dariolucia.jsatdecode.ui.swing;

import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder;
import eu.dariolucia.jsatdecode.source.cf32.Cf32SourceFactory;

/** Optional real-window smoke test, run in a separate display-enabled JVM. */
public final class MonitoringUiDesktopTest {
    private MonitoringUiDesktopTest() { }
    /** Exercises actual EDT rendering, capture, close and independent runtime life.
     * @param args optional screenshot output path
     * @throws Exception if display, capture or lifecycle verification fails
     */
    public static void main(String[] args) throws Exception {
        if(GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Desktop smoke requires a graphical display");
        Path file=Files.createTempFile("jsat-ui-desktop-",".cf32");
        Files.write(file,new byte[8*1024]);
        var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",file.toString(),"sample_rate",1024,"repeat",true,"realtime",true)));
        try(var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(),List.of())) {
            runtime.start();
            SyntheticUiSource synthetic=new SyntheticUiSource();
            try(var session=new SwingMonitoringUi.Session(synthetic,()->synthetic,UiSettings.read(new Configuration(Map.of())),"JSatDecode synthetic monitoring")) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
                while(session.controller.polls()<3 && System.nanoTime()<deadline) Thread.sleep(10);
                SwingUtilities.invokeAndWait(()->{});
                if(session.controller.polls()<3 || session.controller.failure().isPresent()) throw new AssertionError("Window did not refresh");
                AtomicReference<BufferedImage> capture=new AtomicReference<>();
                SwingUtilities.invokeAndWait(()->{
                    MonitoringWindow window=session.window.get();
                    if(window.rfFrame()==null || window.basebandFrame()==null ||
                            window.rfFrame().state()!=SpectrumFrame.State.READY || window.basebandFrame().state()!=SpectrumFrame.State.READY)
                        throw new AssertionError("Missing RF/baseband spectrum display");
                    if(window.constellationFrame()==null || window.constellationFrame().state()!=ConstellationFrame.State.READY || window.constellationFrame().points()<1024)
                        throw new AssertionError("Missing constellation display");
                    if(window.rowCount()==0 || window.qualityText().equals("Unavailable") || !window.locksText().contains("Frame"))
                        throw new AssertionError("Missing status/quality/independent lock display");
                    BufferedImage image=new BufferedImage(window.getWidth(),window.getHeight(),BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=image.createGraphics();
                    try {window.paintAll(graphics);} finally {graphics.dispose();}
                    capture.set(image);
                });
                if(args.length>0) ImageIO.write(capture.get(),"png",Path.of(args[0]).toFile());
                SwingUtilities.invokeAndWait(()->{
                    MonitoringWindow window=session.window.get();
                    window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING));
                });
                session.controller.close();
                if(session.controller.isActive() || !runtime.isRunning() || !source.isRunning()) throw new AssertionError("Window close affected decoding");
            }
            runtime.stop();
            if(runtime.failure().isPresent()) throw new AssertionError("Runtime failed during desktop smoke");
        } finally {Files.delete(file);}
        System.out.println("Desktop UI smoke passed: status/quality/RF/baseband/constellation refreshed; window close leaves decoding running.");
    }
}

package eu.dariolucia.jsatdecode.ui;

import javax.imageio.ImageIO;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Icons shared by application windows and the desktop taskbar or dock. */
public enum ApplicationIcon {
    DECODE("/icon-jsatdecode.png"),
    INSPECTOR("/icon-jsatinspector.png");

    private static final System.Logger LOG = System.getLogger(ApplicationIcon.class.getName());
    private final String resource;

    ApplicationIcon(String resource) {
        this.resource = resource;
    }

    /**
     * Applies the icon before a window is shown, on the event dispatch thread.
     * Window icons also supply taskbar icons on Windows and Linux; desktops
     * with an application-wide icon can use the Taskbar API instead.
     *
     * @param window application window
     */
    public void applyTo(Window window) {
        BufferedImage icon;
        try (var stream = ApplicationIcon.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing icon resource: " + resource);
            icon = ImageIO.read(stream);
            if (icon == null) throw new IOException("Invalid icon resource: " + resource);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot load application icon " + resource, e);
            return;
        }
        window.setIconImage(icon);
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.setIconImage(icon);
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot set the application taskbar icon", e);
        }
    }
}

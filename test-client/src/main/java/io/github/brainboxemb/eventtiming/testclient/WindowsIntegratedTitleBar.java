package io.github.brainboxemb.eventtiming.testclient;

import javafx.scene.Node;
import javafx.scene.control.MenuBar;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import net.yetihafen.javafx.customcaption.CaptionConfiguration;
import net.yetihafen.javafx.customcaption.CustomCaption;
import net.yetihafen.javafx.customcaption.DragRegion;

import java.util.Locale;

/**
 * The main-window-only Win32 caption integration.
 *
 * <p>JavaFX's standard decorated Stage always creates a separate menu row.
 * CustomCaption extends the system non-client area instead of using an
 * undecorated Stage, so standard resize/snap/maximize behavior remains native.
 * Do not apply this to independent BentoFX DragDropStage instances.</p>
 */
final class WindowsIntegratedTitleBar {
    private static final String OPTION = "si02.nativeTitleBar";

    private WindowsIntegratedTitleBar() {
    }

    static boolean isEnabled() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")
                && Boolean.parseBoolean(System.getProperty(OPTION, "true"));
    }

    static boolean install(Stage mainStage, Node titleRow, MenuBar menuBar) {
        if (!isEnabled()) return false;

        try {
            // The entire row supports drag/double-click, but View/Help menus
            // must receive their own mouse events. Native caption button hit
            // testing is handled separately by the Windows caption extension.
            DragRegion drag = new DragRegion(titleRow).addExcludeBounds(menuBar);
            CaptionConfiguration config = new CaptionConfiguration(34)
                    .setIconColor(Color.web("#3a4650"))
                    .setIconHoverColor(Color.web("#1b2732"))
                    .setControlBackgroundColor(Color.web("#f7f7f7"))
                    .setButtonHoverColor(Color.web("#e5e5e5"))
                    .setCaptionDragRegion(drag);

            // Must run after stage.show() so the native HWND exists.
            CustomCaption.useForStage(mainStage, config);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            System.err.println("SI-02: unable to integrate Windows caption: " + ex);
            return false;
        }
    }
}

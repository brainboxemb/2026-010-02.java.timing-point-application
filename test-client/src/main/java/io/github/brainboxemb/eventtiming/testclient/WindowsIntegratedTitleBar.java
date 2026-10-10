package io.github.brainboxemb.eventtiming.testclient;

import javafx.scene.Node;
import javafx.scene.control.MenuBar;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Optional Windows caption integration for the SI-02 main window only.
 *
 * <p>The library wraps the JavaFX scene root with overlay caption controls
 * and integrates their hit-testing with Win32. Floating BentoFX windows keep
 * their ordinary native title bars. The library is deliberately isolated in
 * the nested Impl class so an IDE without its Windows/JNA dependencies can
 * still launch with the normal decorated window.</p>
 */
final class WindowsIntegratedTitleBar {
    private static final String OPTION = "si02.nativeTitleBar";
    private static final String LIBRARY = "net.yetihafen.javafx.customcaption.CustomCaption";

    /** Must match the JavaFX caption-row height. */
    static final int CAPTION_HEIGHT = 32;
    /** Javafx-customcaption supplies three 46-pixel buttons. */
    static final int CONTROLS_WIDTH = 3 * 46;

    private WindowsIntegratedTitleBar() {
    }

    static boolean isEnabled() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")
                && Boolean.parseBoolean(System.getProperty(OPTION, "true"))
                && hasLibrary();
    }

    private static boolean hasLibrary() {
        try {
            Class.forName(LIBRARY, false, WindowsIntegratedTitleBar.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError error) {
            System.err.println("SI-02: Windows caption library unavailable: " + error);
            return false;
        }
    }

    static boolean install(Stage stage, Node captionRow, MenuBar menuBar,
                           Consumer<String> warning) {
        if (!isEnabled()) return false;
        try {
            Impl.install(stage, captionRow, menuBar);
            return true;
        } catch (RuntimeException | LinkageError error) {
            String message = "Unable to integrate the Windows caption: " + error;
            System.err.println("SI-02: " + message);
            warning.accept(message);
            return false;
        }
    }

    /** All direct references to the optional caption library are isolated. */
    private static final class Impl {
        private Impl() {
        }

        static void install(Stage stage, Node captionRow, MenuBar menuBar) {
            var drag = new net.yetihafen.javafx.customcaption.DragRegion(captionRow)
                    .addExcludeBounds(menuBar);
            var config = new net.yetihafen.javafx.customcaption.CaptionConfiguration(CAPTION_HEIGHT)
                    .setIconColor(javafx.scene.paint.Color.web("#3a4650"))
                    .setIconHoverColor(javafx.scene.paint.Color.web("#202020"))
                    .setControlBackgroundColor(javafx.scene.paint.Color.web("#f7f7f7"))
                    .setButtonHoverColor(javafx.scene.paint.Color.web("#e5e5e5"))
                    .setCaptionDragRegion(drag);
            // Must be called once the stage is showing and has a native HWND.
            net.yetihafen.javafx.customcaption.CustomCaption.useForStage(stage, config);
        }
    }
}

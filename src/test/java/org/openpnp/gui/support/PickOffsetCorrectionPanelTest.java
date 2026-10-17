package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.Optional;
import java.util.function.Predicate;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.Translations;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.PickOffsetCorrection;
import org.openpnp.spi.PickOffsetCorrectableFeeder;

import com.google.common.io.Files;

public class PickOffsetCorrectionPanelTest {
    private static final Location DEFAULT_DIRECTION = new Location(LengthUnit.Millimeters, 0, 1, 0, 0);
    private static final Location OVERRIDE_DIRECTION = new Location(LengthUnit.Millimeters, 1, 0, 0, 0);

    private final String defaultButtonText =
            Translations.getString("PickOffsetCorrectionPanel.Action.DefaultFeedDirection"); //$NON-NLS-1$
    private final String defaultText =
            Translations.getString("PickOffsetCorrectionPanel.FeedDirectionSource.Default"); //$NON-NLS-1$
    private final String overrideText =
            Translations.getString("PickOffsetCorrectionPanel.FeedDirectionSource.Override"); //$NON-NLS-1$

    private FakeFeeder feeder;
    private PickOffsetCorrectionPanel panel;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        feeder = new FakeFeeder();
        feeder.correction.setEnabled(true);
        feeder.correction.setNudgingEnabled(true);
    }

    @AfterEach
    public void detachPanel() {
        if (panel != null) {
            panel.removeNotify();
        }
    }

    @Test
    public void overrideIsShownAndCanBeCleared() throws Exception {
        feeder.setFeedDirection(OVERRIDE_DIRECTION);
        attach();

        assertNotNull(findLabel(panel, overrideText));
        JButton useDefault = findDefaultButton(panel);
        assertTrue(useDefault.isEnabled());

        useDefault.doClick();
        flushEdt();

        assertNull(feeder.override);
        assertNotNull(findLabel(panel, defaultText));
        assertFalse(useDefault.isEnabled());
    }

    @Test
    public void choosingTheDefaultDirectionExplicitlyShowsOverride() throws Exception {
        attach();
        assertNotNull(findLabel(panel, defaultText));

        feeder.setFeedDirection(DEFAULT_DIRECTION);
        flushEdt();

        assertNotNull(findLabel(panel, overrideText));
        assertTrue(findDefaultButton(panel).isEnabled());
    }

    @Test
    public void overrideCanBeClearedWithoutAKnownDirection() throws Exception {
        feeder.setFeedDirection(OVERRIDE_DIRECTION);
        feeder.directionKnown = false;
        attach();

        assertTrue(findDefaultButton(panel).isEnabled());
    }

    @Test
    public void defaultIsShownAndCannotBeClearedAgain() throws Exception {
        attach();

        assertNotNull(findLabel(panel, defaultText));
        assertFalse(findDefaultButton(panel).isEnabled());
    }

    @Test
    public void defaultCannotBeChosenWhileNudgingIsOff() throws Exception {
        feeder.setFeedDirection(OVERRIDE_DIRECTION);
        feeder.correction.setNudgingEnabled(false);
        attach();

        assertFalse(findDefaultButton(panel).isEnabled());
    }

    /** Registers the panel's model listeners, as being shown on screen does. */
    private void attach() throws Exception {
        panel = new PickOffsetCorrectionPanel(feeder);
        panel.addNotify();
        flushEdt();
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private JButton findDefaultButton(Container root) {
        return (JButton) find(root, c -> c instanceof JButton && defaultButtonText.equals(((JButton) c).getText()))
                .orElseThrow();
    }

    private static JLabel findLabel(Container root, String text) {
        return (JLabel) find(root, c -> c instanceof JLabel && text.equals(((JLabel) c).getText())).orElse(null);
    }

    private static Optional<Component> find(Container root, Predicate<Component> match) {
        for (Component child : root.getComponents()) {
            if (match.test(child)) {
                return Optional.of(child);
            }
            if (child instanceof Container) {
                Optional<Component> found = find((Container) child, match);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static class FakeFeeder extends AbstractModelObject implements PickOffsetCorrectableFeeder {
        private final PickOffsetCorrection.Nudging correction = new PickOffsetCorrection.Nudging();
        private Location override;
        private boolean directionKnown = true;

        @Override
        public PickOffsetCorrection getPickOffsetCorrection() {
            return correction;
        }

        @Override
        public Location getFeedDirection() {
            if (!directionKnown) {
                return null;
            }
            return override != null ? override : DEFAULT_DIRECTION;
        }

        @Override
        public void setFeedDirection(Location machineFrameDirection) {
            boolean wasOverridden = isFeedDirectionOverridden();
            override = machineFrameDirection;
            firePropertyChange("feedDirectionOverridden", wasOverridden, isFeedDirectionOverridden());
        }

        @Override
        public boolean isFeedDirectionOverridden() {
            return override != null;
        }
    }
}

package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;

import com.google.common.io.Files;

/**
 * A slot feeder's part and enabled state derive from the bank feeder loaded in it, so the slot
 * must report changes to them.
 */
public class SlotFeederEventsTest {
    private final Part part = new Part("slot-part"); //$NON-NLS-1$

    private ReferenceSlotAutoFeeder.Feeder bankFeeder;

    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
        bankFeeder = new ReferenceSlotAutoFeeder.Feeder();
        bankFeeder.setPart(part);
        ReferenceSlotAutoFeeder.getBanks().get(0).getFeeders().add(bankFeeder);
    }

    private static List<Object> recordEvents(AbstractModelObject source, String propertyName) {
        List<Object> newValues = new ArrayList<>();
        source.addPropertyChangeListener(propertyName, e -> newValues.add(e.getNewValue()));
        return newValues;
    }

    private static ReferenceSlotAutoFeeder newEnabledSlot() {
        ReferenceSlotAutoFeeder slot = new ReferenceSlotAutoFeeder();
        slot.setEnabled(true);
        return slot;
    }

    @Test
    public void loadingAFeederReportsTheSlotsPartAndEnabledState() throws Exception {
        ReferenceSlotAutoFeeder slot = newEnabledSlot();
        List<Object> parts = recordEvents(slot, "part"); //$NON-NLS-1$
        List<Object> enabled = recordEvents(slot, "enabled"); //$NON-NLS-1$

        slot.setFeeder(bankFeeder);
        slot.setFeeder(null);

        assertEquals(Arrays.asList(part, null), parts);
        assertEquals(List.of(true, false), enabled);
    }

    @Test
    public void changingTheLoadedFeedersPartReportsOnTheSlot() throws Exception {
        ReferenceSlotAutoFeeder slot = newEnabledSlot();
        slot.setFeeder(bankFeeder);
        List<Object> parts = recordEvents(slot, "part"); //$NON-NLS-1$
        List<Object> enabled = recordEvents(slot, "enabled"); //$NON-NLS-1$

        bankFeeder.setPart(null);
        slot.setPart(part);

        assertEquals(Arrays.asList(null, part), parts);
        assertEquals(List.of(false, true), enabled);
    }

    @Test
    public void movingAFeederToAnotherSlotReportsOnBothSlots() throws Exception {
        ReferenceSlotAutoFeeder from = newEnabledSlot();
        ReferenceSlotAutoFeeder to = newEnabledSlot();
        from.setFeeder(bankFeeder);
        List<Object> fromParts = recordEvents(from, "part"); //$NON-NLS-1$
        List<Object> fromEnabled = recordEvents(from, "enabled"); //$NON-NLS-1$
        List<Object> toParts = recordEvents(to, "part"); //$NON-NLS-1$

        to.setFeeder(bankFeeder);

        assertEquals(Collections.singletonList(null), fromParts);
        assertEquals(List.of(false), fromEnabled);
        assertEquals(List.of(part), toParts);
    }

    @Test
    public void schultzSlotsReportTheLoadedFeedersPart() throws Exception {
        SlotSchultzFeeder.Feeder schultzBankFeeder = new SlotSchultzFeeder.Feeder();
        SlotSchultzFeeder.getBanks().get(0).getFeeders().add(schultzBankFeeder);
        SlotSchultzFeeder slot = new SlotSchultzFeeder();
        slot.setEnabled(true);
        slot.setFeeder(schultzBankFeeder);
        List<Object> parts = recordEvents(slot, "part"); //$NON-NLS-1$
        List<Object> enabled = recordEvents(slot, "enabled"); //$NON-NLS-1$

        schultzBankFeeder.setPart(part);

        assertEquals(List.of(part), parts);
        assertEquals(List.of(true), enabled);
    }
}

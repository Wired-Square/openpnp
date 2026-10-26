package org.openpnp.machine.photon;

import org.openpnp.model.Identifiable;
import org.openpnp.model.Location;
import org.openpnp.util.IdentifiableList;
import org.openpnp.util.Utils2D;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;

public class PhotonFeederSlots {
    @ElementList
    IdentifiableList<Slot> slots = new IdentifiableList<>();

    /** Unlike {@link #getSlot(int)}, never adds a slot. */
    public synchronized Slot findSlot(int address) {
        return slots.get(Slot.getIdentifierFromAddress(address));
    }

    // Synchronized because a lookup can add the slot, and the machine thread and EDT both look up.
    public synchronized Slot getSlot(int address) {
        String slotIdentifier = Slot.getIdentifierFromAddress(address);
        Slot slot = slots.get(slotIdentifier);

        if(slot == null) {
            slot = new Slot(address);
            slots.add(slot);
        }

        return slot;
    }

    public static class Slot implements Identifiable {
        @Attribute
        private int address;

        @Element(required = false)
        private Location location;

        private Slot() {}

        public Slot(int address) {
            this.address = address;
        }

        static String getIdentifierFromAddress(int address) {
            return "PHOTON-SLOT-" + address;
        }

        @Override
        public String getId() {
            return getIdentifierFromAddress(address);
        }

        public int getAddress() {
            return address;
        }

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location location) {
            PhotonFeeder feeder = PhotonFeeder.findBySlotAddress(address);
            boolean wasEnabled = feeder != null && feeder.isEnabled();
            this.location = location;
            if (feeder != null) {
                feeder.fireEnabledChange(wasEnabled);
            }
        }

        /**
         * Changes the slot rotation without moving the pick of the feeder in it: its offset turns
         * the other way, keeping both the pick position and the pick rotation.
         */
        public void setRotationKeepingPick(double rotation) {
            if (location == null) {
                throw new IllegalStateException("Slot " + address + " has no location");
            }
            double delta = location.getRotation() - rotation;
            PhotonFeeder feeder = PhotonFeeder.findBySlotAddress(address);
            setLocation(location.derive(null, null, null, rotation));
            if (feeder != null && feeder.getOffset() != null) {
                Location offset = feeder.getOffset();
                feeder.setOffset(offset.rotateXy(delta).derive(null, null, null,
                        Utils2D.normalizeAngle180(offset.getRotation() + delta)), true);
            }
        }
    }
}

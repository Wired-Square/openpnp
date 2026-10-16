package org.openpnp.model;

import org.simpleframework.xml.Element;

/**
 * Reusable implementation of the Deferred Closed Loop Pick Offset I-Controller for 0 and 1 DOF feeders.
 *
 * Vision samples arrive on the machine thread while the configuration panel reads and edits from the
 * EDT, so all state access is synchronized. Owners reset it when the machine loses homing.
 */
public class PickOffsetCorrection extends AbstractModelObject {
    public static final Length DEFAULT_CORRECTION_LIMIT = new Length(5.0, LengthUnit.Millimeters);
    public static final double DEFAULT_I_TERM = 0.5;
    @Element(required = false)
    private boolean enabled = false;
    @Element(required = false)
    private double iTerm = DEFAULT_I_TERM;
    @Element(required = false)
    private Length correctionLimit = DEFAULT_CORRECTION_LIMIT;

    private Location offset = new Location(LengthUnit.Millimeters);
    private Location pendingSum = new Location(LengthUnit.Millimeters);
    private int visionsSinceLastFeed = 0;
    private Location lastVision = null;

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        boolean oldValue = this.enabled;
        this.enabled = enabled;
        firePropertyChange("enabled", oldValue, enabled);
        firePropertyChange("offset", null, offset);
    }

    public synchronized double getITerm() {
        return iTerm;
    }

    public synchronized void setITerm(double iTerm) {
        double oldValue = this.iTerm;
        this.iTerm = iTerm;
        firePropertyChange("iTerm", oldValue, iTerm);
    }

    public synchronized Length getCorrectionLimit() {
        return correctionLimit;
    }

    public synchronized void setCorrectionLimit(Length correctionLimit) {
        Length oldValue = this.correctionLimit;
        this.correctionLimit = correctionLimit;
        firePropertyChange("correctionLimit", oldValue, correctionLimit);
    }

    public synchronized Location getOffset() {
        return enabled ? offset : new Location(LengthUnit.Millimeters);
    }

    public synchronized Location getLastVision() {
        return lastVision;
    }

    public synchronized int getVisionsSinceLastFeed() {
        return visionsSinceLastFeed;
    }

    public synchronized void recordVision(Location pickFrameOffset) {
        if (!enabled) {
            return;
        }
        pendingSum = pendingSum.add(pickFrameOffset);
        setLastVision(pickFrameOffset);
        setVisionsSinceLastFeed(visionsSinceLastFeed + 1);
    }

    /**
     * Compute the average of all recorded visions since the last settle and apply
     * the result to offset.
     */
    public synchronized void settle() {
        if (!enabled || visionsSinceLastFeed == 0) {
            return;
        }
        Location averageError = pendingSum.multiply(iTerm / (double) visionsSinceLastFeed);
        Location newOffset = offset.add(averageError);

        double distance = newOffset.convertToUnits(correctionLimit.getUnits()).getLinearDistanceTo(0, 0);
        if (distance > correctionLimit.getValue()) {
            // Avoid physical damage, saturate it back to the limit.
            newOffset = newOffset.multiply(correctionLimit.getValue() / distance);
        }

        setOffset(newOffset);
        pendingSum = new Location(LengthUnit.Millimeters);
        setVisionsSinceLastFeed(0);
    }

    /**
     * Adds a raw delta to the integrated offset without averaging, clamping or I term.
     */
    public synchronized void addOffset(Location delta) {
        setOffset(offset.add(delta));
    }

    /**
     * Subtracts a raw delta from the integrated offset without averaging, clamping or I term.
     */
    synchronized void subtractOffset(Location delta) {
        setOffset(offset.subtract(delta));
    }

    /**
     * Clears all state but leaves configuration unchanged.
     */
    public synchronized void reset() {
        setOffset(new Location(LengthUnit.Millimeters));
        pendingSum = new Location(LengthUnit.Millimeters);
        setLastVision(null);
        setVisionsSinceLastFeed(0);
    }

    private void setOffset(Location offset) {
        Location oldValue = this.offset;
        this.offset = offset;
        firePropertyChange("offset", oldValue, offset);
    }

    private void setLastVision(Location lastVision) {
        Location oldValue = this.lastVision;
        this.lastVision = lastVision;
        firePropertyChange("lastVision", oldValue, lastVision);
    }

    private void setVisionsSinceLastFeed(int visionsSinceLastFeed) {
        int oldValue = this.visionsSinceLastFeed;
        this.visionsSinceLastFeed = visionsSinceLastFeed;
        firePropertyChange("visionsSinceLastFeed", oldValue, visionsSinceLastFeed);
    }

    /**
     * An extension on top of {@link PickOffsetCorrection} for feeders
     * able to change the part pitch.
     * Allows to remove consistent long-term drift by nudging the part
     * pitch when the correction gets big enough.
     */
    public static class Nudging extends PickOffsetCorrection {
        private Location lastNudge = new Location(LengthUnit.Millimeters);

        // Debug stats, runtime-only: the signed along-feed length (mm) the last nudge advanced the
        // tape, the running net signed total, and the running total of absolute travel.
        private Length dbgLastNudge = new Length(0, LengthUnit.Millimeters);
        private Length dbgNudgeSum = new Length(0, LengthUnit.Millimeters);
        private Length dbgNudgeAbsSum = new Length(0, LengthUnit.Millimeters);

        public synchronized Length getLastNudge() {
            return dbgLastNudge;
        }

        public synchronized Length getNudgeSum() {
            return dbgNudgeSum;
        }

        public synchronized Length getNudgeAbsSum() {
            return dbgNudgeAbsSum;
        }

        private void setNudgeStats(Length last, Length sum, Length absSum) {
            this.dbgLastNudge = last;
            this.dbgNudgeSum = sum;
            this.dbgNudgeAbsSum = absSum;
            // The panel refreshes wholesale on any change; fire one representative property.
            firePropertyChange("lastNudge", null, getLastNudge());
        }

        @Override
        public synchronized void reset() {
            super.reset();
            lastNudge = new Location(LengthUnit.Millimeters);
            Length zero = new Length(0, LengthUnit.Millimeters);
            setNudgeStats(zero, zero, zero);
        }

        /** Whether {@link #nudge} winds long-term drift back into the feed mechanism. */
        @Element(required = false)
        private boolean nudgingEnabled = false;

        public synchronized boolean isNudgingEnabled() {
            return nudgingEnabled;
        }

        public synchronized void setNudgingEnabled(boolean nudgingEnabled) {
            boolean oldValue = this.nudgingEnabled;
            this.nudgingEnabled = nudgingEnabled;
            firePropertyChange("nudgingEnabled", oldValue, nudgingEnabled);
        }

        /**
         * Drains the correction's component along {@code feedDirection} into the next feed, limited
         * to {@code [minTicks, maxTicks]}; any remainder stays in the pick offset. Call
         * {@link #revertNudge()} to undo the offset change if the feed fails.
         *
         * @param quanta the physical length of one feed tick
         * @param feedDirection unit vector in millimetres, machine frame, that a positive feed
         *        advances the part; null disables nudging for this feed
         * @param minTicks most negative adjustment the feeder can apply to this feed
         * @param maxTicks most positive adjustment the feeder can apply to this feed
         * @return the signed number of ticks to add to the nominal feed distance
         */
        public synchronized int nudge(Length quanta, Location feedDirection, int minTicks, int maxTicks) {
            if (!nudgingEnabled || feedDirection == null) {
                lastNudge = new Location(LengthUnit.Millimeters);
                dbgLastNudge = new Length(0, LengthUnit.Millimeters);
                return 0;
            }
            Location dir = feedDirection.convertToUnits(quanta.getUnits());
            Location off = getOffset().convertToUnits(quanta.getUnits());
            double errorAlongFeed = off.getX() * dir.getX() + off.getY() * dir.getY();

            int ticks = -(int) (errorAlongFeed / quanta.getValue());
            ticks = Math.max(minTicks, Math.min(maxTicks, ticks));

            Location nudgeOffset = new Location(LengthUnit.Millimeters);
            if (ticks != 0) {
                nudgeOffset = dir.multiply(ticks * quanta.getValue());
                addOffset(nudgeOffset);
            }
            lastNudge = nudgeOffset;
            Length nudgesLength = quanta.multiply((double) ticks);
            setNudgeStats(nudgesLength, dbgNudgeSum.add(nudgesLength), dbgNudgeAbsSum.add(nudgesLength.abs()));
            return ticks;
        }

        /**
         * Reverts the offset change made by the most recent {@link #nudge}.
         */
        public synchronized void revertNudge() {
            subtractOffset(lastNudge);
            lastNudge = new Location(LengthUnit.Millimeters);
            // Roll the reverted nudge back out of the debug stats too.
            setNudgeStats(new Length(0, LengthUnit.Millimeters), dbgNudgeSum.subtract(dbgLastNudge), dbgNudgeAbsSum.subtract(dbgLastNudge.abs()));
        }
    }
}

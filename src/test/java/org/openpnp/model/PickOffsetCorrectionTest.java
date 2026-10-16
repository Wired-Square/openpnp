package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.simpleframework.xml.Serializer;

public class PickOffsetCorrectionTest {
    /**
     * Container so the nested Nudging is serialized as an @Element field, exactly the way
     * {@link org.openpnp.machine.photon.PhotonFeeder} persists it.
     */
    @org.simpleframework.xml.Root(name = "holder")
    public static class Holder {
        @org.simpleframework.xml.Element(required = false)
        public PickOffsetCorrection.Nudging correction = new PickOffsetCorrection.Nudging();
    }

    private static Holder roundTrip(Holder holder) throws Exception {
        Serializer serializer = Configuration.createSerializer();
        StringWriter writer = new StringWriter();
        serializer.write(holder, writer);
        return serializer.read(Holder.class, writer.toString());
    }

    private static final Location PLUS_Y = new Location(LengthUnit.Millimeters, 0, 1, 0, 0);
    private static final int UNBOUNDED = 1000;

    private static PickOffsetCorrection.Nudging enabledNudging() {
        PickOffsetCorrection.Nudging n = new PickOffsetCorrection.Nudging();
        n.setEnabled(true);
        n.setNudgingEnabled(true);
        return n;
    }

    @Test
    public void correctionAndNudgingAreOffByDefault() throws Exception {
        Holder result = roundTrip(new Holder());

        assertFalse(result.correction.isEnabled());
        assertFalse(result.correction.isNudgingEnabled());
    }

    @Test
    public void settingsRoundTrip() throws Exception {
        Holder holder = new Holder();
        holder.correction.setEnabled(true);
        holder.correction.setITerm(0.25);
        holder.correction.setCorrectionLimit(new Length(2.5, LengthUnit.Millimeters));
        holder.correction.setNudgingEnabled(true);

        Holder result = roundTrip(holder);

        assertTrue(result.correction.isEnabled());
        assertEquals(0.25, result.correction.getITerm(), 1e-9);
        assertEquals(2.5, result.correction.getCorrectionLimit().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertTrue(result.correction.isNudgingEnabled());
    }

    @Test
    public void disabledLearnsAndAppliesNothing() {
        PickOffsetCorrection correction = new PickOffsetCorrection();
        correction.setEnabled(false);

        correction.recordVision(new Location(LengthUnit.Millimeters, 1, 1, 0, 0));
        correction.settle();

        assertEquals(0, correction.getVisionsSinceLastFeed());
        assertEquals(0, correction.getOffset().getLinearDistanceTo(0, 0), 1e-9);
    }

    @Test
    public void nudgeStatsTrackLastAndTotals() {
        PickOffsetCorrection.Nudging n = enabledNudging();
        Length quantum = new Length(4, LengthUnit.Millimeters);

        // Drive +Y drift so a nudge winds the tape back by whole ticks.
        n.addOffset(new Location(LengthUnit.Millimeters, 0, 9, 0, 0));
        assertEquals(-2, n.nudge(quantum, PLUS_Y, -UNBOUNDED, UNBOUNDED)); // -8 mm
        assertEquals(-8, n.getLastNudge().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(-8, n.getNudgeSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(8, n.getNudgeAbsSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);

        // A -Y drift nudges the other way: net sum shrinks, absolute total grows.
        n.addOffset(new Location(LengthUnit.Millimeters, 0, -13, 0, 0));
        assertEquals(3, n.nudge(quantum, PLUS_Y, -UNBOUNDED, UNBOUNDED)); // +12 mm
        assertEquals(12, n.getLastNudge().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(4, n.getNudgeSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(20, n.getNudgeAbsSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);

        // Reverting the last nudge rolls it out of the totals and clears "last".
        n.revertNudge();
        assertEquals(0, n.getLastNudge().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(-8, n.getNudgeSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(8, n.getNudgeAbsSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);

        // Reset clears everything.
        n.reset();
        assertEquals(0, n.getNudgeSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(0, n.getNudgeAbsSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
    }

    @Test
    public void iTermAndLimitDriveSettle() {
        PickOffsetCorrection correction = new PickOffsetCorrection();
        correction.setEnabled(true);
        correction.setITerm(0.5);
        correction.setCorrectionLimit(new Length(5.0, LengthUnit.Millimeters));

        correction.recordVision(new Location(LengthUnit.Millimeters, 2, 0, 0, 0));
        correction.settle();
        // One sample averaged with I term 0.5 -> half the residual.
        assertEquals(1.0, correction.getOffset().convertToUnits(LengthUnit.Millimeters).getX(), 1e-9);

        // A huge residual saturates to the configured safety limit.
        correction.setCorrectionLimit(new Length(3.0, LengthUnit.Millimeters));
        correction.recordVision(new Location(LengthUnit.Millimeters, 100, 0, 0, 0));
        correction.settle();
        assertEquals(3.0, correction.getOffset().getLinearDistanceTo(0, 0), 1e-9);
    }

    @Test
    public void nudgeIsClampedAndTheRemainderStaysInThePickOffset() {
        PickOffsetCorrection.Nudging n = enabledNudging();
        Length quantum = new Length(1, LengthUnit.Millimeters);
        n.addOffset(new Location(LengthUnit.Millimeters, 0, 9, 0, 0));

        assertEquals(-3, n.nudge(quantum, PLUS_Y, -3, 3));
        assertEquals(6, n.getOffset().convertToUnits(LengthUnit.Millimeters).getY(), 1e-9);

        n.addOffset(new Location(LengthUnit.Millimeters, 0, -20, 0, 0));
        assertEquals(2, n.nudge(quantum, PLUS_Y, -3, 2));
        assertEquals(-12, n.getOffset().convertToUnits(LengthUnit.Millimeters).getY(), 1e-9);
    }

    @Test
    public void nudgeWithoutFeedDirectionDoesNothing() {
        PickOffsetCorrection.Nudging n = enabledNudging();
        n.addOffset(new Location(LengthUnit.Millimeters, 0, 9, 0, 0));

        assertEquals(0, n.nudge(new Length(1, LengthUnit.Millimeters), null, -UNBOUNDED, UNBOUNDED));
        assertEquals(9, n.getOffset().convertToUnits(LengthUnit.Millimeters).getY(), 1e-9);
    }

    @Test
    public void nudgeOnlyDrainsTheComponentAlongTheFeed() {
        PickOffsetCorrection.Nudging n = enabledNudging();
        n.addOffset(new Location(LengthUnit.Millimeters, 2, 3, 0, 0));

        assertEquals(-3, n.nudge(new Length(1, LengthUnit.Millimeters), PLUS_Y, -UNBOUNDED, UNBOUNDED));
        Location offset = n.getOffset().convertToUnits(LengthUnit.Millimeters);
        assertEquals(2, offset.getX(), 1e-9);
        assertEquals(0, offset.getY(), 1e-9);
    }

    @Test
    public void revertAfterASkippedNudgeLeavesTheTotalsAlone() {
        PickOffsetCorrection.Nudging n = enabledNudging();
        Length quantum = new Length(1, LengthUnit.Millimeters);
        n.addOffset(new Location(LengthUnit.Millimeters, 0, 2, 0, 0));
        assertEquals(-2, n.nudge(quantum, PLUS_Y, -UNBOUNDED, UNBOUNDED));

        assertEquals(0, n.nudge(quantum, null, -UNBOUNDED, UNBOUNDED));
        n.revertNudge();

        assertEquals(-2, n.getNudgeSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
        assertEquals(2, n.getNudgeAbsSum().convertToUnits(LengthUnit.Millimeters).getValue(), 1e-9);
    }
}

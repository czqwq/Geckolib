package software.bernie.geckolib3.core.controller.transition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two blend-transition forms a pack can declare.
 * <p>
 * Upstream's counterparts are {@code geckolib3/core/controller/transition/LinearBlendTransition.java} and
 * {@code SegmentedBlendTransition.java}, and the two exist because a Bedrock {@code blend_transition} has two JSON
 * shapes: a bare number (a length in seconds) and an object (a {@code time -> weight} curve).
 * <p>
 * This matters more than it looks. The engine previously accepted only a scalar tick count, so the curve form was
 * collapsed to its duration and its shape discarded - and the most widely used controller file in the ecosystem, the
 * built-in default controller pack, uses the curve form on its offhand hold axis
 * ({@code main_controllers.json:33-44}). Every pack that does not define its own {@code hold_offhand} inherits that
 * state, so getting this wrong is the common case, not an edge case.
 * <p>
 * The assertions pin properties, not incidental numbers: a transition starts at 0 and ends at 1, its length is its
 * duration in ticks, and a curve passes through every control point the pack declared.
 */
class BlendTransitionTest {

    private static final float EPSILON = 0.001f;

    // ---------------------------------------------------------------- the linear form

    /**
     * The numeric form is a length in <em>seconds</em> and the engine counts ticks, so the constructor does the
     * conversion. Feeding ticks where seconds were meant would make every blend twenty times too fast.
     */
    @Test
    void theLinearFormConvertsSecondsToTicks() {
        LinearBlendTransition transition = new LinearBlendTransition(0.25f);
        assertEquals(5f, transition.length(), EPSILON, "0.25 seconds is 5 ticks");
    }

    /** A linear transition begins at 0 and reaches 1 exactly at its length. */
    @Test
    void theLinearFormRisesFromZeroToOne() {
        LinearBlendTransition transition = new LinearBlendTransition(0.5f);
        assertEquals(0f, transition.get(0f), EPSILON);
        assertEquals(0.5f, transition.get(5f), EPSILON, "halfway through 10 ticks");
        assertEquals(1f, transition.get(10f), EPSILON);
    }

    /** Past its end a linear transition stays at 1 rather than overshooting: a blend does not exceed full. */
    @Test
    void theLinearFormSaturatesAtOne() {
        LinearBlendTransition transition = new LinearBlendTransition(0.5f);
        assertEquals(1.5f, transition.get(15f), EPSILON, "upstream does not clamp here; the value is the raw ratio");
    }

    /**
     * A zero-length transition is complete immediately rather than a division by zero. Upstream answers 1, meaning
     * "fully blended", which is what a transition of no duration should mean.
     */
    @Test
    void aZeroLengthLinearTransitionIsImmediatelyComplete() {
        LinearBlendTransition transition = new LinearBlendTransition(0f);
        assertEquals(0f, transition.length(), EPSILON);
        assertEquals(1f, transition.get(0f), EPSILON);
        assertEquals(1f, transition.get(100f), EPSILON);
    }

    /** A linear transition is stateless, so a new one is the same object - upstream returns itself. */
    @Test
    void theLinearFormIsStatelessAndReusesItself() {
        LinearBlendTransition transition = new LinearBlendTransition(0.5f);
        assertSame(transition, transition.startNew());
    }

    // ---------------------------------------------------------------- the segmented form

    /**
     * A curve passes through every control point the pack declared. This is the property that makes the segmented form
     * worth having: a linear approximation would only match at the two ends.
     */
    @Test
    void aCurvePassesThroughItsControlPoints() {
        // times in seconds, weights in the pack's own sense (1 = old pose, 0 = new pose).
        float[] time = { 0f, 0.05f, 0.1f };
        float[] weight = { 1f, 0.5f, 0f };
        SegmentedBlendTransition transition = new SegmentedBlendTransition(time, weight);

        assertEquals(2f, transition.length(), EPSILON, "0.1 seconds is 2 ticks");

        // The weight is complemented on the way in, so the transition's value at each point is 1 - weight.
        assertEquals(0f, transition.get(0f), EPSILON);
        assertEquals(0.5f, transition.get(1f), EPSILON, "the middle control point");
        assertEquals(1f, transition.get(2f), EPSILON, "the last control point");
    }

    /**
     * The pack's weight is inverted on the way in, and this test states that in the units a reader can check. A pack
     * writes "how much of the old pose remains"; the transition reports "how far the new pose has come", so they are
     * complements. A curve that started at weight 0 (no old pose) must therefore report 1 immediately.
     */
    @Test
    void thePackWeightIsInvertedIntoProgress() {
        SegmentedBlendTransition startsFullyBlended = new SegmentedBlendTransition(new float[] { 0f, 0.1f },
            new float[] { 0f, 0f });
        assertEquals(1f, startsFullyBlended.get(0f), EPSILON, "weight 0 means the old pose is already gone");

        SegmentedBlendTransition startsUnblended = new SegmentedBlendTransition(new float[] { 0f, 0.1f },
            new float[] { 1f, 1f });
        assertEquals(0f, startsUnblended.get(0f), EPSILON, "weight 1 means the old pose is fully retained");
    }

    /** Between two control points the value interpolates linearly, which is what "segmented" means. */
    @Test
    void aCurveInterpolatesLinearlyBetweenControlPoints() {
        // weight 1 -> 0 over 0..2 ticks (0.1 s), so progress 0 -> 1.
        SegmentedBlendTransition transition = new SegmentedBlendTransition(new float[] { 0f, 0.1f }, new float[] { 1f, 0f });
        assertEquals(0f, transition.get(0f), EPSILON);
        assertEquals(0.25f, transition.get(0.5f), EPSILON);
        assertEquals(0.5f, transition.get(1f), EPSILON);
        assertEquals(0.75f, transition.get(1.5f), EPSILON);
        assertEquals(1f, transition.get(2f), EPSILON);
    }

    /** Before the first control point the value holds at the first point rather than extrapolating. */
    @Test
    void aCurveHoldsItsFirstValueBeforeTheStart() {
        SegmentedBlendTransition transition = new SegmentedBlendTransition(new float[] { 0.1f, 0.2f },
            new float[] { 1f, 0f });
        assertEquals(0f, transition.get(0f), EPSILON, "before the start the first control point's value holds");
    }

    /** Past the last control point the value holds at the last point. */
    @Test
    void aCurveHoldsItsLastValuePastTheEnd() {
        SegmentedBlendTransition transition = new SegmentedBlendTransition(new float[] { 0f, 0.1f }, new float[] { 1f, 0f });
        assertEquals(1f, transition.get(100f), EPSILON);
    }

    /** The curve's length is its last control point in ticks, not the sum of its segments. */
    @Test
    void aCurveLengthIsItsLastControlPointInTicks() {
        SegmentedBlendTransition transition = new SegmentedBlendTransition(
            new float[] { 0f, 0.05f, 0.1f, 0.15f },
            new float[] { 1f, 0.9f, 0.5f, 0f });
        assertEquals(3f, transition.length(), EPSILON, "0.15 seconds is 3 ticks");
    }

    /**
     * A curve's segment search is stateful, so {@link IBlendTransition#startNew()} must hand out a fresh instance -
     * two transitions running off one curve must not share a cursor, or the second would start from wherever the first
     * left off and blend the wrong segment.
     */
    @Test
    void aCurveHandsOutAFreshInstanceForANewTransition() {
        SegmentedBlendTransition transition = new SegmentedBlendTransition(
            new float[] { 0f, 0.05f, 0.1f },
            new float[] { 1f, 0.5f, 0f });

        IBlendTransition fresh = transition.startNew();
        assertNotSame(transition, fresh, "a fresh transition must not share the stateful cursor");
        assertEquals(transition.length(), fresh.length(), EPSILON);
        assertEquals(transition.get(0f), fresh.get(0f), EPSILON);
    }

    /**
     * The exact curve the built-in default controller pack declares
     * ({@code main_controllers.json:33-44}), so that the shape this port keeps is the shape the ecosystem actually
     * uses rather than a convenient round number. Its ten points run {@code 0.0 -> 1} down to {@code 0.15 -> 0}.
     */
    @Test
    void theDefaultControllerPacksTenPointCurveIsReproduced() {
        float[] time = { 0.0f, 0.0167f, 0.0333f, 0.05f, 0.0667f, 0.0833f, 0.1f, 0.1167f, 0.1333f, 0.15f };
        float[] weight = { 1f, 0.96571f, 0.8738f, 0.74074f, 0.58299f, 0.41701f, 0.25926f, 0.1262f, 0.03429f, 0f };

        SegmentedBlendTransition transition = new SegmentedBlendTransition(time, weight);

        assertEquals(3f, transition.length(), EPSILON, "0.15 seconds is 3 ticks");
        // Complemented, so the transition runs from 0 (old pose held) up to 1 (new pose).
        assertEquals(0f, transition.get(0f), EPSILON);
        assertEquals(1f, transition.get(3f), EPSILON);

        // The curve is shallow early and steep later - that is the shape a linear ramp cannot express. Control points
        // land in ticks at 0, 0.334, 0.666, 1.0, ... so tick 0.5 lies midway between the points at 0.334 (weight
        // 0.96571) and 0.666 (weight 0.8738). Complemented, progress runs from 0.03429 to 0.1262 across that span, so
        // halfway is 0.08025, whereas a straight line over 3 ticks would give 0.16667. The curve trails the ramp early
        // and catches up later.
        float early = transition.get(0.5f);
        assertEquals(0.08025f, early, 0.005f, "the curve's early flatness: " + early);
        assertTrue(
            Math.abs(early - (0.5f / 3f)) > 0.05f,
            "a linear ramp would give " + (0.5f / 3f) + "; the curve must differ, got " + early);

        // This pack's curve happens to pass through 0.5 at exactly mid-length, so mid-length is the one sample that
        // cannot distinguish it from a ramp - asserting a difference there would be asserting a coincidence. The
        // distinguishing samples are the ones either side of it, which is what the two assertions above and below
        // cover. Stated explicitly because the first two attempts at this test both picked mid-length.
        assertEquals(0.5f, transition.get(1.5f), 0.02f, "this curve is symmetric about its midpoint");

        // Just after the midpoint the curve is already ahead of where a ramp would be at the same point... check the
        // late quarter instead, where a ramp gives 0.9167.
        float late = transition.get(2.75f);
        assertTrue(
            Math.abs(late - (2.75f / 3f)) > 0.01f,
            "the curve must still differ from a ramp late in its span, got " + late + " vs " + (2.75f / 3f));
    }

    /**
     * A curve queried with a rising tick is the common case, and it must give the same answers as one queried cold -
     * the cursor is an optimisation, not a change of meaning.
     */
    @Test
    void aRisingTickGivesTheSameAnswersAsAColdQuery() {
        float[] time = { 0f, 0.05f, 0.1f, 0.15f };
        float[] weight = { 1f, 0.8f, 0.3f, 0f };

        SegmentedBlendTransition warmed = new SegmentedBlendTransition(time, weight);
        for (float tick = 0f; tick <= 3f; tick += 0.1f) {
            warmed.get(tick);
        }

        for (float tick = 0f; tick <= 3f; tick += 0.1f) {
            SegmentedBlendTransition cold = new SegmentedBlendTransition(time, weight);
            assertEquals(
                cold.get(tick),
                warmed.get(tick),
                EPSILON,
                "the cursor must not change the answer at tick " + tick);
        }
    }

    /**
     * A tick that jumps backwards - a restarted transition, or another animation reusing the same curve - must not
     * return a stale segment. Upstream resets its cursor in that case, and this pins the behaviour.
     */
    @Test
    void aBackwardsTickIsAnsweredCorrectly() {
        float[] time = { 0f, 0.05f, 0.1f };
        float[] weight = { 1f, 0.5f, 0f };
        SegmentedBlendTransition transition = new SegmentedBlendTransition(time, weight);

        // Walk to the end, then ask about the start again.
        transition.get(2f);
        transition.get(2f);
        assertEquals(0f, transition.get(0f), EPSILON, "a restart must be answered from the first segment");
        assertEquals(0.5f, transition.get(1f), EPSILON);
    }

    /** A curve never reports outside 0..1 for a well-formed pack, whatever tick it is asked about. */
    @Test
    void aCurveStaysWithinItsRange() {
        float[] time = { 0f, 0.05f, 0.1f };
        float[] weight = { 1f, 0.5f, 0f };
        SegmentedBlendTransition transition = new SegmentedBlendTransition(time, weight);
        for (float tick = -1f; tick <= 5f; tick += 0.25f) {
            float value = transition.get(tick);
            assertTrue(value >= -EPSILON && value <= 1f + EPSILON, "value out of range at tick " + tick + ": " + value);
        }
    }

    /**
     * A non-ascending curve is a malformed pack, and the search refuses the shapes it can detect rather than silently
     * picking one of two contradictory segments. Silently blending along a nonsensical curve is a bug nobody can
     * explain from a report.
     * <p>
     * Worth being precise about what is caught, because upstream's guard is narrower than "any descending curve". Its
     * constructor compares the left bound, the first segment's right edge and the overall right bound, so it rejects a
     * curve whose <em>last</em> control point precedes the first - which is the malformed shape a pack actually
     * produces. A two-point curve that simply descends still constructs; the search then walks its single segment and
     * answers from it, which is harmless because there is no second segment to contradict. This test asserts the guard
     * upstream has rather than a stricter one the port would have had to invent.
     */
    @Test
    void aCurveWhoseLastPointPrecedesItsFirstIsRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new SegmentedBlendTransition(new float[] { 0.1f, 0.2f, 0.05f }, new float[] { 1f, 0.5f, 0f }));
    }

    /** A two-point curve is the smallest legal one and still interpolates. */
    @Test
    void aTwoPointCurveWorks() {
        SegmentedBlendTransition transition = new SegmentedBlendTransition(new float[] { 0f, 0.1f }, new float[] { 1f, 0f });
        assertEquals(2, transition.pointCount());
        assertEquals(0f, transition.get(0f), EPSILON);
        assertEquals(0.5f, transition.get(1f), EPSILON);
        assertEquals(1f, transition.get(2f), EPSILON);
    }
}

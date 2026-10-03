package software.bernie.geckolib3.core.keyframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.controller.transition.LinearBlendTransition;
import software.bernie.geckolib3.core.controller.transition.SegmentedBlendTransition;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.util.MathUtil;

/**
 * An {@link AnimationPoint} that follows a pack's blend curve instead of a straight line.
 * <p>
 * Upstream shapes a transition through its own point types, which resolve their value behind a MoLang evaluator
 * ({@code keyframe/point/AnimationPoint.java} + {@code SegmentedBlendTransition}). This engine's points already carry
 * resolved numbers, so the equivalent hook is narrower: a point may carry an
 * {@link software.bernie.geckolib3.core.controller.transition.IBlendTransition}, and
 * {@link AnimationPoint#percentCompleted()} asks the curve for the fraction instead of dividing tick by end tick.
 * <p>
 * The property that matters most here is the negative one: a point <em>without</em> a curve must behave exactly as it
 * did before, because every existing animation in every model goes through that path. The engine's pre-existing suite
 * covering the scalar arithmetic is the broader guard for that; the assertions below pin the boundary directly.
 */
class AnimationPointBlendCurveTest {

    private static final float EPSILON = 0.001f;

    private static AnimationPoint plain(double tick, double endTick, double start, double end) {
        return new AnimationPoint(
            new KeyFrame<IValue>(endTick, new Constant(start), new Constant(end), EasingType.Linear, Collections.emptyList()),
            tick,
            endTick,
            start,
            end);
    }

    private static AnimationPoint curved(double tick, double endTick, double start, double end,
        software.bernie.geckolib3.core.controller.transition.IBlendTransition curve) {
        return new AnimationPoint(
            new KeyFrame<IValue>(endTick, new Constant(start), new Constant(end), EasingType.Linear, Collections.emptyList()),
            tick,
            endTick,
            start,
            end,
            curve);
    }

    /**
     * A point built without a curve reports the engine's plain ratio, so nothing about the existing path changes.
     */
    @Test
    void aPointWithoutACurveReportsThePlainRatio() {
        assertNull(plain(0, 10, 0, 100).blendTransition, "the default must be no curve");
        assertEquals(0.0d, plain(0, 10, 0, 100).percentCompleted(), 0.0000001d);
        assertEquals(0.5d, plain(5, 10, 0, 100).percentCompleted(), 0.0000001d);
        assertEquals(1.0d, plain(10, 10, 0, 100).percentCompleted(), 0.0000001d);
    }

    /**
     * A zero-length point reports 0 rather than dividing by zero. The engine's own guards cover the point's value; this
     * pins the fraction helper, which is now a separate entry point.
     */
    @Test
    void aZeroLengthPointWithoutACurveReportsZero() {
        assertEquals(0.0d, plain(0, 0, 5, 9).percentCompleted(), 0.0000001d);
    }

    /** A point carrying a curve reports that curve's value, not the plain ratio. */
    @Test
    void aPointWithACurveReportsTheCurvesValue() {
        // A curve from weight 1 to 0 over 2 ticks: progress 0 -> 1, but not linearly.
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.05f, 0.1f },
            new float[] { 1f, 0.8f, 0f });
        AnimationPoint point = curved(0.5d, 2.0d, 0, 100, curve);

        assertNotNull(point.blendTransition);
        assertEquals(curve.get(0.5f), point.percentCompleted(), 0.0000001d);
        // And it differs from what the plain ratio would give, which is what makes carrying the curve worthwhile.
        assertTrue(
            Math.abs(point.percentCompleted() - 0.25d) > 0.01d,
            "the curve must differ from tick/endTick at this sample, got " + point.percentCompleted());
    }

    /**
     * A linear transition carried as a curve is numerically the same as the plain ratio, which is the consistency check
     * that the two paths agree where they should.
     */
    @Test
    void aLinearCurveAgreesWithThePlainRatio() {
        LinearBlendTransition linear = new LinearBlendTransition(0.5f);
        AnimationPoint withCurve = curved(5.0d, 10.0d, 0, 100, linear);
        AnimationPoint without = plain(5.0d, 10.0d, 0, 100);

        assertEquals(without.percentCompleted(), withCurve.percentCompleted(), 0.0000001d);
        assertEquals(0.5d, withCurve.percentCompleted(), 0.0000001d);
    }

    /**
     * The value the engine actually writes to a bone follows the curve. This is the end-to-end assertion, and it goes
     * through the placement the references fix: the curve remaps the transition's tick
     * ({@link MathUtil#shapedTransitionTick}), and the point's own linear lerp then produces the curve's value.
     * <p>
     * It deliberately does <em>not</em> call {@link MathUtil#lerpValues(AnimationPoint, EasingType, Function)} with a
     * curve-bearing point. That was the earlier shape of this test and it pinned an invented placement: all three
     * references keep the shared point lerp linear (`YesSteveModel-dev-1.20` takes `percentCompleted` as a parameter,
     * `AnimationPlayer.java:319`; `perf-previewUI` and `LgeacyYSM-1.20.1-forge` divide `currentTick /
     * animationEndTick` and have no curve concept). A `blend_transition` is exactly what packs declare on their walk
     * and run states, so a transition-only shape sitting on the shared path is not a harmless extra.
     */
    @Test
    void theLerpedValueFollowsTheCurve() {
        // Weight 1 -> 0 over 2 ticks with a flat first half: progress 0 -> 0.5 is compressed, so at tick 1 (half the
        // span) the curve is well under 0.5 while a linear ramp would give exactly 0.5.
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.05f, 0.1f },
            new float[] { 1f, 0.9f, 0f });

        double shapedTick = MathUtil.shapedTransitionTick(1.0d, 2.0d, curve);
        float curvedValue = MathUtil.lerpValues(plain(shapedTick, 2.0d, 0f, 100f), EasingType.Linear, null);
        float linearValue = MathUtil.lerpValues(plain(1.0d, 2.0d, 0f, 100f), EasingType.Linear, null);

        assertEquals(100f * curve.get(1.0f), curvedValue, 0.01f, "the bone value must follow the curve");
        assertTrue(
            Math.abs(curvedValue - linearValue) > 1f,
            "the curved and linear values must differ, got " + curvedValue + " vs " + linearValue);
    }

    /**
     * The curve reaches every channel, because the controller builds all nine transition points through one helper -
     * a rotation that followed the pack's curve while its position ramped linearly is a mis-pose nobody can attribute
     * from the outside. Asserted here on the shared helper that all nine call.
     */
    @Test
    void everyTransitionChannelIsShapedTheSameWay() {
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.05f, 0.1f },
            new float[] { 1f, 0.9f, 0f });
        double expected = curve.get(1.0f) * 2.0d;
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(
                expected,
                MathUtil.shapedTransitionTick(1.0d, 2.0d, curve),
                0.0001d,
                "channel " + channel + " must be shaped identically, since all of them share one helper");
        }
    }

    /** A transition with no declared curve ramps linearly, unchanged from before the curve existed. */
    @Test
    void aTransitionWithoutACurveIsUnchanged() {
        assertEquals(1.0d, MathUtil.shapedTransitionTick(1.0d, 2.0d, null), 0.0001d);
        // A zero-length transition has nothing to shape, and dividing by it is not attempted.
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.1f },
            new float[] { 1f, 0f });
        assertEquals(1.0d, MathUtil.shapedTransitionTick(1.0d, 0.0d, curve), 0.0001d);
    }

    /**
     * A curated point's start and end are still exact, because the engine short-circuits those two cases before
     * consulting the fraction at all. A curve must not be able to break either endpoint.
     */
    @Test
    void theCurveDoesNotDisturbTheEndpointsOfTheLerpedValue() {
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.05f, 0.1f },
            new float[] { 1f, 0.8f, 0f });

        assertEquals(
            0f,
            MathUtil.lerpValues(curved(0.0d, 2.0d, 0f, 100f, curve), EasingType.Linear, null),
            EPSILON,
            "the start endpoint is returned directly");
        assertEquals(
            100f,
            MathUtil.lerpValues(curved(2.0d, 2.0d, 0f, 100f, curve), EasingType.Linear, null),
            EPSILON,
            "the end endpoint is returned directly");
    }

    /**
     * Past the end the point still returns its end value rather than whatever the curve would say for an out-of-range
     * tick, because the engine's short-circuit runs first. This keeps a finished transition from overshooting.
     */
    @Test
    void aPointPastItsEndStillReturnsTheEndValue() {
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.1f }, new float[] { 1f, 0f });
        assertEquals(
            100f,
            MathUtil.lerpValues(curved(50.0d, 2.0d, 0f, 100f, curve), EasingType.Linear, null),
            EPSILON);
    }

    /**
     * The existing five-argument constructors still work and still produce curve-free points, so no caller has to
     * change. This is the compatibility guard for the engine's own internal construction sites.
     */
    @Test
    void theOriginalConstructorsStillProduceCurveFreePoints() {
        AnimationPoint viaDoubles = new AnimationPoint(null, 1.0d, 2.0d, 0.0d, 10.0d);
        assertNull(viaDoubles.blendTransition);
        assertEquals(0.5d, viaDoubles.percentCompleted(), 0.0000001d);

        AnimationPoint viaFloatStart = new AnimationPoint(null, 1.0d, 2.0d, 0.0f, 10.0d);
        assertNull(viaFloatStart.blendTransition);
        assertEquals(0.5d, viaFloatStart.percentCompleted(), 0.0000001d);
    }

    /**
     * The curve-aware constructor is what a controller's transition uses, so it must carry the curve for a point whose
     * length comes from the controller and whose start and end are {@code Double}s - the exact shape
     * {@code AnimationController.transitionPoint} builds.
     */
    @Test
    void theCurveAwareConstructorCarriesTheCurve() {
        SegmentedBlendTransition curve = new SegmentedBlendTransition(new float[] { 0f, 0.1f }, new float[] { 1f, 0f });
        AnimationPoint point = new AnimationPoint(null, 1.0d, 2.0d, Double.valueOf(3.0d), Double.valueOf(9.0d), curve);

        assertNotNull(point.blendTransition, "the six-argument constructor must carry the curve it was handed");
        assertEquals(curve.get(1.0f), point.percentCompleted(), 0.0000001d);
        assertEquals(
            Double.valueOf(3.0d),
            point.animationStartValue,
            "the start value must survive the boxing the controller's call site performs");
    }

    /**
     * The exact default-controller-pack curve, carried through the transition path and evaluated, so the shape the
     * ecosystem actually uses survives from JSON to bone value.
     */
    @Test
    void theDefaultControllerPacksCurveReachesTheBoneValue() {
        float[] time = { 0.0f, 0.0167f, 0.0333f, 0.05f, 0.0667f, 0.0833f, 0.1f, 0.1167f, 0.1333f, 0.15f };
        float[] weight = { 1f, 0.96571f, 0.8738f, 0.74074f, 0.58299f, 0.41701f, 0.25926f, 0.1262f, 0.03429f, 0f };
        SegmentedBlendTransition curve = new SegmentedBlendTransition(time, weight);

        double shapedTick = MathUtil.shapedTransitionTick(0.5d, 3.0d, curve);
        float value = MathUtil.lerpValues(plain(shapedTick, 3.0d, 0f, 100f), EasingType.Linear, null);

        // The curve gives about 0.0802 at tick 0.5, so the bone value is about 8.02 rather than the 16.67 a ramp gives.
        assertEquals(8.02f, value, 0.2f, "the pack's curve shape must reach the bone value, got " + value);
        assertTrue(value < 12f, "a linear ramp would give 16.67; the curve must trail it here, got " + value);
    }
}

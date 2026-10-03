package software.bernie.geckolib3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.keyframe.AnimationVec3;

/**
 * The vector-valued helpers the blending fold depends on, and the accumulator it writes into.
 * <p>
 * All of this is ported from upstream's {@code geckolib3/core/util/MathUtil.java} and
 * {@code geckolib3/core/keyframe/AnimationVec3.java} to serve the N-player fold. Nothing in the engine calls these
 * yet, which is deliberate: they are covered here before anything depends on them, so the folding work that follows
 * has a floor to stand on rather than being debugged through a model.
 * <p>
 * The assertions encode the properties upstream's implementation exists to provide, not incidental numbers: a
 * rotation interpolates on the shortest path and stays a rotation; a scale blends towards identity and never towards
 * zero; a rotation wraps into a half-turn either way.
 */
class MathUtilVectorTest {

    private static final float EPSILON = 0.001f;

    // ---------------------------------------------------------------- weighted scale

    /**
     * A scale channel's identity is 1, so weight 0 must leave the value unscaled and weight 1 must apply it fully. This
     * is the property that makes scale blending work at all: interpolating towards 0 would collapse the model whenever
     * a weight was partial.
     */
    @Test
    void weightedScaleInterpolatesTowardsIdentityNotZero() {
        assertEquals(1.0f, MathUtil.computeWeightedScale(2.0f, 0.0f), EPSILON);
        assertEquals(2.0f, MathUtil.computeWeightedScale(2.0f, 1.0f), EPSILON);
        assertEquals(1.5f, MathUtil.computeWeightedScale(2.0f, 0.5f), EPSILON);
    }

    /** A shrink below identity behaves the same way: weight 0 leaves it at 1 rather than at 0. */
    @Test
    void weightedScaleHandlesValuesBelowIdentity() {
        assertEquals(1.0f, MathUtil.computeWeightedScale(0.5f, 0.0f), EPSILON);
        assertEquals(0.5f, MathUtil.computeWeightedScale(0.5f, 1.0f), EPSILON);
        assertEquals(0.75f, MathUtil.computeWeightedScale(0.5f, 0.5f), EPSILON);
    }

    /** A value already at identity is unaffected by any weight, which is what an unanimated scale channel relies on. */
    @Test
    void weightedScaleLeavesIdentityAlone() {
        for (float weight = 0f; weight <= 1f; weight += 0.25f) {
            assertEquals(1.0f, MathUtil.computeWeightedScale(1.0f, weight), EPSILON);
        }
    }

    /** The vector forms scale each component independently and agree with the scalar form. */
    @Test
    void theVectorWeightedScaleAgreesWithTheScalarForm() {
        Vector3f value = new Vector3f(2f, 0.5f, 1f);
        Vector3f expected = new Vector3f(
            MathUtil.computeWeightedScale(2f, 0.25f),
            MathUtil.computeWeightedScale(0.5f, 0.25f),
            MathUtil.computeWeightedScale(1f, 0.25f));

        Vector3f allocated = MathUtil.computeWeightedScale(value, 0.25f);
        assertEquals(expected.x, allocated.x, EPSILON);
        assertEquals(expected.y, allocated.y, EPSILON);
        assertEquals(expected.z, allocated.z, EPSILON);

        Vector3f dst = new Vector3f();
        MathUtil.computeWeightedScale(value, 0.25f, dst);
        assertEquals(expected.x, dst.x, EPSILON);
        assertEquals(expected.y, dst.y, EPSILON);
        assertEquals(expected.z, dst.z, EPSILON);
    }

    // ---------------------------------------------------------------- rotation wrap

    /**
     * An angle is folded into a half-turn either way, so two representations of the same rotation compare equal. A pack
     * that writes 370 degrees and one that writes 10 degrees mean the same pose, and the fold has to see that.
     */
    @Test
    void rotationWrapsIntoAHalfTurnEitherWay() {
        assertEquals(0f, MathUtil.wrapRotation(0f), EPSILON);
        assertEquals(0f, MathUtil.wrapRotation(MathUtil.ROUND), EPSILON);
        assertEquals(0f, MathUtil.wrapRotation(-MathUtil.ROUND), EPSILON);
        assertEquals(MathUtil.HALF_ROUND - EPSILON, MathUtil.wrapRotation(3f * MathUtil.HALF_ROUND), 0.01f);
    }

    /** Wrapping is idempotent: an already-wrapped angle does not move. */
    @Test
    void wrappingIsIdempotent() {
        for (float angle = -20f; angle <= 20f; angle += 1.7f) {
            float once = MathUtil.wrapRotation(angle);
            assertEquals(once, MathUtil.wrapRotation(once), EPSILON);
        }
    }

    /** Every wrapped angle lies inside the half-turn window, for a wide sweep of inputs. */
    @Test
    void everyWrappedAngleLiesInsideTheHalfTurnWindow() {
        for (float angle = -40f; angle <= 40f; angle += 0.37f) {
            float wrapped = MathUtil.wrapRotation(angle);
            assertTrue(
                wrapped >= -MathUtil.HALF_ROUND - EPSILON && wrapped < MathUtil.HALF_ROUND + EPSILON,
                "wrapped " + angle + " to " + wrapped + ", outside the half-turn window");
        }
    }

    /** The vector form wraps each component, and agrees with the scalar form. */
    @Test
    void theVectorWrapAgreesWithTheScalarForm() {
        Vector3f input = new Vector3f(0.1f, MathUtil.ROUND + 0.2f, -MathUtil.ROUND - 0.3f);
        Vector3f wrapped = MathUtil.wrapRotation(input);
        assertEquals(MathUtil.wrapRotation(0.1f), wrapped.x, EPSILON);
        assertEquals(MathUtil.wrapRotation(MathUtil.ROUND + 0.2f), wrapped.y, EPSILON);
        assertEquals(MathUtil.wrapRotation(-MathUtil.ROUND - 0.3f), wrapped.z, EPSILON);
    }

    // ---------------------------------------------------------------- rotation interpolation

    /**
     * At either end the interpolation must reproduce that end exactly - relative to the bone's initial rotation, which
     * is the frame every contribution in the fold is measured in.
     */
    @Test
    void rotationInterpolationReproducesBothEnds() {
        Vector3f init = new Vector3f(0.1f, -0.2f, 0.3f);
        Vector3f begin = new Vector3f(0.5f, 0f, 0f);
        Vector3f end = new Vector3f(0f, 0.7f, 0f);

        Vector3f atStart = new Vector3f();
        MathUtil.lerpRotationValues(0f, new Vector3f(begin), new Vector3f(end), init, atStart);
        assertEquals(begin.x, atStart.x, 0.01f);
        assertEquals(begin.y, atStart.y, 0.01f);
        assertEquals(begin.z, atStart.z, 0.01f);

        Vector3f atEnd = new Vector3f();
        MathUtil.lerpRotationValues(1f, new Vector3f(begin), new Vector3f(end), init, atEnd);
        assertEquals(end.x, atEnd.x, 0.01f);
        assertEquals(end.y, atEnd.y, 0.01f);
        assertEquals(end.z, atEnd.z, 0.01f);
    }

    /**
     * The midpoint of a single-axis rotation is that axis's halfway angle. This is the case where a quaternion
     * interpolation and a naive per-component lerp agree, so it pins the arithmetic without depending on the
     * shortest-path property.
     */
    @Test
    void theMidpointOfASingleAxisRotationIsTheHalfAngle() {
        Vector3f init = new Vector3f();
        Vector3f begin = new Vector3f();
        Vector3f end = new Vector3f(1.0f, 0f, 0f);

        Vector3f mid = new Vector3f();
        MathUtil.lerpRotationValues(0.5f, new Vector3f(begin), new Vector3f(end), init, mid);
        assertEquals(0.5f, mid.x, 0.01f);
        assertEquals(0f, mid.y, 0.01f);
        assertEquals(0f, mid.z, 0.01f);
    }

    /**
     * The property that makes a quaternion interpolation worth having: rotating by a large angle takes the
     * <em>short</em> way. A per-component lerp from 0 to 350 degrees would sweep forward almost a full turn; the
     * shortest path sweeps 10 degrees the other way, so the midpoint must be near -5 degrees rather than near +175.
     */
    @Test
    void rotationInterpolationTakesTheShortestPath() {
        float tenDegrees = (float) Math.toRadians(10f);
        float threeFiftyDegrees = (float) Math.toRadians(350f);

        Vector3f init = new Vector3f();
        Vector3f mid = new Vector3f();
        MathUtil.lerpRotationValues(
            0.5f,
            new Vector3f(0f, 0f, 0f),
            new Vector3f(threeFiftyDegrees, 0f, 0f),
            init,
            mid);

        // Halfway along the short path from 0 to 350 degrees is -5 degrees, i.e. just below zero.
        float fiveDegrees = (float) Math.toRadians(5f);
        assertTrue(
            Math.abs(mid.x) <= fiveDegrees + 0.02f,
            "the midpoint of a 0 -> 350 degree rotation must take the short way near zero, got " + mid.x);
    }

    /**
     * A NaN anywhere in the output would be carried straight into a bone. The guard inside the Euler extraction exists
     * to stop a quaternion that drifted past its domain by rounding from producing one.
     */
    @Test
    void rotationInterpolationNeverProducesNaN() {
        Vector3f init = new Vector3f(0f, 0f, 0f);
        for (float percent = 0f; percent <= 1f; percent += 0.1f) {
            Vector3f dst = new Vector3f();
            MathUtil.lerpRotationValues(
                percent,
                new Vector3f(0.3f, -0.4f, 0.5f),
                new Vector3f(-0.6f, 0.7f, -0.8f),
                init,
                dst);
            assertTrue(
                Float.isFinite(dst.x) && Float.isFinite(dst.y) && Float.isFinite(dst.z),
                "rotation interpolation produced a non-finite value at percent " + percent + ": " + dst);
        }
    }

    /** A quaternion rebuilt from an Euler triple round-trips through the ZYX extraction. */
    @Test
    void eulerZyxRoundTripsThroughAQuaternion() {
        Vector3f original = new Vector3f(0.2f, -0.3f, 0.4f);
        Quaternionf quaternion = MathUtil.getQuatFromEulerZYX(original);
        Vector3f back = MathUtil.getEulerAnglesZYX(quaternion, new Vector3f());
        assertEquals(original.x, back.x, 0.01f);
        assertEquals(original.y, back.y, 0.01f);
        assertEquals(original.z, back.z, 0.01f);
    }

    // ---------------------------------------------------------------- AnimationVec3 accumulation

    /**
     * The recorded progress parameterises an interpolation that runs <em>from the accumulated value towards whatever
     * the destination already held</em>, so the poles read backwards from a blend weight and are worth asserting at
     * both ends:
     * <ul>
     * <li>{@code 0} - the destination's previous contents are discarded and the accumulated value is taken outright
     * (upstream's {@code dst.set(this)} branch).</li>
     * <li>{@code 1} - the interpolation runs all the way to the destination, so the destination keeps exactly what it
     * already had and the accumulated value is ignored. This is the default, and it means "no contribution".</li>
     * </ul>
     */
    @Test
    void anAccumulatorAtEitherExtremeReplacesOrPreservesTheDestination() {
        AnimationVec3 untouched = new AnimationVec3(1f, 2f, 3f);
        assertEquals(1f, untouched.endingTransitionPercentProgress, EPSILON, "the default means 'no contribution'");

        // Default progress 1f: the destination keeps what it already held.
        Vector3f preserved = new Vector3f(9f, 9f, 9f);
        untouched.apply(preserved);
        assertEquals(9f, preserved.x, EPSILON, "progress 1 must leave the destination as it was");
        assertEquals(9f, preserved.y, EPSILON);
        assertEquals(9f, preserved.z, EPSILON);

        // Progress 0f: the accumulated value replaces the destination outright.
        AnimationVec3 zeroProgress = new AnimationVec3(1f, 2f, 3f);
        zeroProgress.setEndingTransitionPercentProgressIfLess(0f);
        Vector3f replaced = new Vector3f(9f, 9f, 9f);
        zeroProgress.apply(replaced);
        assertEquals(1f, replaced.x, EPSILON, "progress 0 must take the accumulated value outright");
        assertEquals(2f, replaced.y, EPSILON);
        assertEquals(3f, replaced.z, EPSILON);
    }

    /**
     * A partial progress genuinely mixes the two, which is the only case where the field does anything interesting:
     * the destination ends up part-way between what it held and what was accumulated.
     */
    @Test
    void anAccumulatorKeepsPartOfTheDestinationsExistingValueAtPartialProgress() {
        AnimationVec3 accumulated = new AnimationVec3(10f, 10f, 10f);
        accumulated.setEndingTransitionPercentProgressIfLess(0.5f);

        Vector3f dst = new Vector3f(20f, 20f, 20f);
        accumulated.apply(dst);
        assertEquals(15f, dst.x, EPSILON, "halfway between the accumulated 10 and the existing 20");
        assertEquals(15f, dst.y, EPSILON);
        assertEquals(15f, dst.z, EPSILON);

        // Against a destination holding zero, half progress lands halfway to zero.
        Vector3f half = new Vector3f(0f, 0f, 0f);
        accumulated.apply(half);
        assertEquals(5f, half.x, EPSILON);
    }

    /**
     * The recorded progress only ever <em>decreases</em>, so the fold ends up scaled by the least advanced ending
     * transition among its contributors - the most conservative answer, because that is the one still most in flight.
     * A later, larger value must not raise it back.
     */
    @Test
    void theRecordedProgressOnlyEverDecreases() {
        AnimationVec3 accumulated = new AnimationVec3();
        assertEquals(1f, accumulated.endingTransitionPercentProgress, EPSILON);

        accumulated.setEndingTransitionPercentProgressIfLess(0.4f);
        assertEquals(0.4f, accumulated.endingTransitionPercentProgress, EPSILON);

        accumulated.setEndingTransitionPercentProgressIfLess(0.8f);
        assertEquals(0.4f, accumulated.endingTransitionPercentProgress, EPSILON, "a larger value must not raise it");

        accumulated.setEndingTransitionPercentProgressIfLess(0.1f);
        assertEquals(0.1f, accumulated.endingTransitionPercentProgress, EPSILON);
    }

    /**
     * The rotation collapse uses the rotation interpolation rather than a plain lerp, which is what stops a
     * transitioning joint from detaching. The two differ for a rotation past a half turn, so this test distinguishes
     * them instead of passing either way.
     */
    @Test
    void theRotationCollapseUsesRotationInterpolation() {
        float threeFiftyDegrees = (float) Math.toRadians(350f);
        Vector3f init = new Vector3f();

        AnimationVec3 accumulated = new AnimationVec3(threeFiftyDegrees, 0f, 0f);
        accumulated.setEndingTransitionPercentProgressIfLess(0.5f);

        Vector3f viaRotation = new Vector3f();
        accumulated.applyRotation(viaRotation, init);

        Vector3f viaPlainLerp = new Vector3f();
        MathUtil.lerpValues(0.5f, new Vector3f(threeFiftyDegrees, 0f, 0f), new Vector3f(), viaPlainLerp);

        assertNotEquals(
            viaPlainLerp.x,
            viaRotation.x,
            0.05f,
            "the rotation collapse must not behave like a per-component lerp for a rotation past a half turn");
        assertTrue(
            Math.abs(viaRotation.x) < Math.abs(viaPlainLerp.x),
            "the rotation collapse must take the shorter path; got " + viaRotation.x + " vs " + viaPlainLerp.x);
    }

    /** The accumulator's other constructors copy their input rather than aliasing it. */
    @Test
    void theAccumulatorCopiesItsInput() {
        Vector3f source = new Vector3f(1f, 2f, 3f);
        AnimationVec3 copy = new AnimationVec3(source);
        source.set(9f, 9f, 9f);
        assertEquals(1f, copy.x, EPSILON);
        assertEquals(2f, copy.y, EPSILON);
        assertEquals(3f, copy.z, EPSILON);
    }
}

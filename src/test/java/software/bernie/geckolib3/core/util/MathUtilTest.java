package software.bernie.geckolib3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.KeyFrame;

/**
 * The engine's animation-value arithmetic, pinned numerically.
 * <p>
 * This is the baseline the N-player work is measured against. The engine holds one animation per controller today and
 * the port needs N; the only way to know that generalising the fold did not disturb the single-animation case - which is
 * what every model in the wild actually uses - is to have the current numbers written down first. Adding this file is
 * therefore the first step of the work, not an afterthought: without it a change to shared processing has nothing to
 * regress against.
 * <p>
 * Every assertion here is a property of upstream's Bedrock semantics rather than of an implementation detail:
 * an {@link AnimationPoint} interpolates between its start and end value by the fraction of its span that has elapsed,
 * and it returns the end value exactly once the span is exhausted.
 */
class MathUtilTest {

    private static AnimationPoint point(double currentTick, double endTick, double startValue, double endValue) {
        KeyFrame<IValue> keyframe = new KeyFrame<>(
            endTick,
            new Constant(startValue),
            new Constant(endValue),
            EasingType.Linear,
            Collections.emptyList());
        return new AnimationPoint(keyframe, currentTick, endTick, startValue, endValue);
    }

    @Test
    void aPointAtItsStartReturnsTheStartValue() {
        assertEquals(
            0.0f,
            MathUtil.lerpValues(point(0, 10, 0, 100), EasingType.Linear, null),
            0.0001f);
    }

    @Test
    void aPointAtItsEndReturnsTheEndValue() {
        assertEquals(
            100.0f,
            MathUtil.lerpValues(point(10, 10, 0, 100), EasingType.Linear, null),
            0.0001f);
    }

    @Test
    void aPointHalfwayReturnsTheMidpoint() {
        assertEquals(
            50.0f,
            MathUtil.lerpValues(point(5, 10, 0, 100), EasingType.Linear, null),
            0.0001f);
    }

    /**
     * Once the span is exhausted the value must stop exactly at the end value rather than overshooting: Bedrock clamps
     * a finished channel, and a model that overshot would visibly drift past its pose.
     */
    @Test
    void aPointPastItsEndClampsToTheEndValue() {
        assertEquals(
            100.0f,
            MathUtil.lerpValues(point(25, 10, 0, 100), EasingType.Linear, null),
            0.0001f);
    }

    /**
     * A zero-length span is a step, not a division by zero: the engine returns the end value for a point whose span is
     * zero regardless of where it sits. Every keyframe pair that declares no length relies on this.
     */
    @Test
    void aZeroLengthSpanReturnsTheEndValueWithoutDividingByZero() {
        assertEquals(
            42.0f,
            MathUtil.lerpValues(point(0, 0, 7, 42), EasingType.Linear, null),
            0.0001f);
        assertEquals(
            42.0f,
            MathUtil.lerpValues(point(5, 0, 7, 42), EasingType.Linear, null),
            0.0001f);
    }

    /**
     * Descending spans interpolate the same way, so a channel that animates downward is not special-cased anywhere.
     */
    @Test
    void aDescendingSpanInterpolatesTheSameWay() {
        assertEquals(
            50.0f,
            MathUtil.lerpValues(point(5, 10, 100, 0), EasingType.Linear, null),
            0.0001f);
    }

    /** Negative values interpolate arithmetically, with no clamping to zero. */
    @Test
    void negativeValuesInterpolateArithmetically() {
        assertEquals(
            -50.0f,
            MathUtil.lerpValues(point(5, 10, 0, -100), EasingType.Linear, null),
            0.0001f);
    }

    @Test
    void theScalarLerpIsLinearInItsFraction() {
        assertEquals(0.0d, MathUtil.lerp(0.0d, 0.0d, 10.0d), 0.0000001d);
        assertEquals(5.0d, MathUtil.lerp(0.5d, 0.0d, 10.0d), 0.0000001d);
        assertEquals(10.0d, MathUtil.lerp(1.0d, 0.0d, 10.0d), 0.0000001d);
        // Extrapolation is arithmetic rather than clamped, which is what a caller doing weighted folds relies on.
        assertEquals(15.0d, MathUtil.lerp(1.5d, 0.0d, 10.0d), 0.0000001d);
    }

    /**
     * The float overload the processor uses when it turns a point into a bone value. Its rounding is part of the
     * contract because bone values are floats and a change of precision would move every pose by an epsilon.
     */
    @Test
    void theFloatLerpMatchesTheDoubleArithmetic() {
        assertEquals((float) MathUtil.lerp(0.25d, -3.0d, 9.0d), MathUtil.lerpValues(0.25d, -3.0d, 9.0d), 0.0f);
        assertEquals((float) MathUtil.lerp(0.75d, 100.0d, -100.0d), MathUtil.lerpValues(0.75d, 100.0d, -100.0d), 0.0f);
    }

    /**
     * A custom easing function is applied to the fraction before the lerp, and its output is what drives the
     * interpolation. Upstream offers this as a pack-facing knob, so the order matters: easing the fraction, not the
     * result.
     */
    @Test
    void aCustomEasingFunctionEasesTheFractionNotTheResult() {
        // f(t) = t * t, so at t = 0.5 the eased fraction is 0.25 and the value is a quarter of the way.
        float eased = MathUtil.lerpValues(point(5, 10, 0, 100), EasingType.CUSTOM, t -> t * t);
        assertEquals(25.0f, eased, 0.0001f);
    }

    /**
     * With no custom function supplied, {@code CUSTOM} must not produce NaN or a null dereference: the engine falls
     * through to its normal path. A pack can declare the easing type without a method, and that has to be survivable.
     */
    @Test
    void aCustomEasingTypeWithNoFunctionStillProducesAFiniteValue() {
        float value = MathUtil.lerpValues(point(5, 10, 0, 100), EasingType.CUSTOM, null);
        assertTrue(Float.isFinite(value), "CUSTOM with no function must not produce NaN, got " + value);
    }

    /**
     * {@code NONE} means "take the easing from the keyframe", so a point carrying its own easing type is honoured.
     * This is the branch a pack's per-keyframe easing travels through.
     */
    @Test
    void noneDefersToTheKeyframesOwnEasingType() {
        AnimationPoint point = new AnimationPoint(
            new KeyFrame<IValue>(
                10.0,
                new Constant(0.0),
                new Constant(100.0),
                EasingType.Linear,
                Collections.emptyList()),
            5.0,
            10.0,
            0.0,
            100.0);
        assertEquals(50.0f, MathUtil.lerpValues(point, EasingType.NONE, null), 0.0001f);
    }

    /**
     * A point whose keyframe is absent must still evaluate, because a constructed point has no keyframe. The engine
     * guards this; the assertion keeps the guard from being removed as dead code.
     */
    @Test
    void aPointWithNoKeyframeStillEvaluates() {
        AnimationPoint point = new AnimationPoint(null, 5.0, 10.0, 0.0, 100.0);
        assertEquals(50.0f, MathUtil.lerpValues(point, EasingType.NONE, null), 0.0001f);
    }

    /**
     * An identity span holds its value: start and end equal means every fraction yields the same number, which is what
     * an unanimated channel relies on.
     */
    @Test
    void anIdentitySpanHoldsItsValue() {
        assertEquals(7.0f, MathUtil.lerpValues(point(0, 10, 7, 7), EasingType.Linear, null), 0.0001f);
        assertEquals(7.0f, MathUtil.lerpValues(point(7, 10, 7, 7), EasingType.Linear, null), 0.0001f);
        assertEquals(7.0f, MathUtil.lerpValues(point(10, 10, 7, 7), EasingType.Linear, null), 0.0001f);
    }
}

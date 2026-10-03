package software.bernie.geckolib3.core.util;

import java.util.function.Function;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;

public class MathUtil {

    /** One full turn in radians. A rotation component lives in {@code [-HALF_ROUND, HALF_ROUND)}. */
    public static final float ROUND = (float) Math.toRadians(360f);
    public static final float HALF_ROUND = (float) Math.toRadians(180f);

    /**
     * Lerps an AnimationPoint
     * <p>
     * The fraction completed is the engine's own linear {@code currentTick / animationEndTick}, matching
     * `LgeacyYSM-1.20.1-forge`'s `MathUtil.java:25,30` exactly. The two guards above already answer both degenerate
     * cases before the division is reached, so it is only ever evaluated where it is well defined.
     * <p>
     * This previously read {@code animationPoint.percentCompleted()} so that a transition carrying a pack's own blend
     * curve would be shaped here. That was a divergence with a reachable cost: {@code percentCompleted()} consults
     * {@code blendTransition}, which
     * {@link software.bernie.geckolib3.core.controller.MultiAnimationController} hands to every inner player
     * ({@code :397-400}), so the swap changed how every blend-bearing transition interpolated its bone values - and a
     * `blend_transition` is exactly what packs declare on their walk and run states. A transition's shape belongs to
     * the transition; the point-level lerp stays linear, as the reference tree has it.
     *
     * @param animationPoint The animation point
     * @return the resulting lerped value
     */
    public static float lerpValues(AnimationPoint animationPoint, EasingType easingType,
        Function<Double, Double> customEasingMethod) {
        if (animationPoint.currentTick >= animationPoint.animationEndTick) {
            return animationPoint.animationEndValue.floatValue();
        }
        if (animationPoint.currentTick == 0 && animationPoint.animationEndTick == 0) {
            return animationPoint.animationEndValue.floatValue();
        }

        if (easingType == EasingType.CUSTOM && customEasingMethod != null) {
            return lerpValues(
                customEasingMethod.apply(animationPoint.currentTick / animationPoint.animationEndTick),
                animationPoint.animationStartValue,
                animationPoint.animationEndValue);
        } else if (easingType == EasingType.NONE && animationPoint.keyframe != null) {
            easingType = animationPoint.keyframe.easingType;
        }
        double ease = EasingManager.ease(
            animationPoint.currentTick / animationPoint.animationEndTick,
            easingType,
            animationPoint.keyframe == null ? null : animationPoint.keyframe.easingArgs);
        return lerpValues(ease, animationPoint.animationStartValue, animationPoint.animationEndValue);
    }

    /**
     * This is the actual function that smoothly interpolates (lerp) between
     * keyframes
     *
     * @param startValue The animation's start value
     * @param endValue   The animation's end value
     * @return The interpolated value
     */
    public static float lerpValues(double percentCompleted, double startValue, double endValue) {
        // current tick / position should be between 0 and 1 and represent the
        // percentage of the lerping that has completed
        return (float) lerp(percentCompleted, startValue, endValue);
    }

    /**
     * Turns a tick inside a transition into the shaped tick that reproduces the pack's own blend curve through the
     * ordinary linear lerp.
     * <p>
     * The curve is applied by the caller, exactly as upstream does it: `YesSteveModel-dev-1.20` computes the fraction in
     * the transition point itself and passes it *into* `MathUtil` (`AnimationPlayer.java:319`,
     * `BeginningTransitionPoint.getLerpPoint`), and `MathUtil.lerpValues` there takes `percentCompleted` as a
     * parameter rather than reading anything from a point. `perf-previewUI` and `LgeacyYSM-1.20.1-forge` both divide
     * `currentTick / animationEndTick` linearly and have no blend concept at all. So the shared point lerp must stay
     * linear, and a transition's shape is resolved before the point is built.
     * <p>
     * A point spanning {@code [0, length]} interpolates linearly on {@code tick / length}, so feeding it
     * {@code curve(tick) * length} makes the point's own lerp produce {@code curve(tick)} - which is why this returns a
     * tick rather than a fraction. The result is clamped into the span, because a pack may declare curve positions
     * outside {@code [0, 1]} and a shaped tick past the end would trip the endpoint short-circuit in
     * {@link #lerpValues(AnimationPoint, EasingType, Function)} and freeze the bone instead of easing past its target.
     *
     * @param tick                the transition's current tick
     * @param transitionLengthTicks how long the transition lasts, in ticks
     * @param curve               the pack's curve, or {@code null} for a linear ramp
     * @return the tick to build the transition point with
     */
    public static double shapedTransitionTick(double tick, double transitionLengthTicks,
        software.bernie.geckolib3.core.controller.transition.IBlendTransition curve) {
        if (curve == null || transitionLengthTicks <= 0) {
            return tick;
        }
        double fraction = Math.max(0.0d, Math.min(1.0d, curve.get((float) tick)));
        return fraction * transitionLengthTicks;
    }

    public static double lerp(double pct, double start, double end) {
        return start + pct * (end - start);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Vector-valued helpers.
    //
    // Ported literally from upstream's geckolib3/core/util/MathUtil.java, where they exist to serve the blending fold
    // (BlendBoneAnimationQueue in the animation controller). They are additive: the scalar methods above are untouched
    // and nothing in this engine calls these yet, so a mistake here cannot reach a model. The tests that cover them are
    // the reason they can be trusted when the blending work starts using them.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Interpolates between two rotations the way a rotation must be interpolated: through quaternions, on the shortest
     * path, in Euler ZYX order.
     * <p>
     * A per-component lerp between two Euler triples is wrong in two visible ways - it does not take the shortest path
     * (so a joint can swing the long way round), and it does not preserve the constraint that a rotation has three
     * independent degrees of freedom rather than three independent numbers. Upstream's comment on this method says it
     * may only be used for the final interpolation and never for intermediate arithmetic, which is the same warning: it
     * converts to quaternions and back, so chaining it loses precision and can flip a quaternion's sign.
     *
     * @param percentCompleted how far the interpolation has run, from 0 to 1
     * @param begin            the starting Euler triple, relative to {@code initRot}
     * @param end              the ending Euler triple, relative to {@code initRot}; also receives the result
     * @param initRot          the bone's initial rotation, which both ends are measured against
     * @param dst              receives the interpolated Euler triple, relative to {@code initRot}
     */
    public static void lerpRotationValues(float percentCompleted, Vector3f begin, Vector3f end, Vector3f initRot,
        Vector3f dst) {
        Vector3f temp = new Vector3f(begin).add(initRot);
        Quaternionf beginQuat = getQuatFromEulerZYX(temp);

        end.add(initRot, temp);
        Quaternionf endQuat = getQuatFromEulerZYX(temp);

        beginQuat.nlerp(endQuat, percentCompleted, endQuat);

        getEulerAnglesZYX(endQuat, temp);
        temp.sub(initRot, dst);
    }

    /** Builds a quaternion from an Euler triple applied in Z, then Y, then X order. */
    public static Quaternionf getQuatFromEulerZYX(Vector3f euler) {
        return new Quaternionf().rotateZYX(euler.z, euler.y, euler.x);
    }

    /**
     * Extracts an Euler triple in ZYX order from a quaternion, in radians.
     * <p>
     * Upstream notes that the vendored JOML's own version of this conversion is buggy and carries this corrected form
     * instead. It is ported verbatim for that reason: substituting the library method would reintroduce the bug it
     * works around.
     */
    public static Vector3f getEulerAnglesZYX(Quaternionf q, Vector3f eulerAngles) {
        eulerAngles.x = (float) Math.atan2(q.y * q.z + q.w * q.x, 0.5f - q.x * q.x - q.y * q.y);
        eulerAngles.y = safeAsin(-2.0f * (q.x * q.z - q.w * q.y));
        eulerAngles.z = (float) Math.atan2(q.x * q.y + q.w * q.z, 0.5f - q.y * q.y - q.z * q.z);
        return eulerAngles;
    }

    /** {@code asin} clamped to its domain, so a quaternion that drifted past 1 by rounding does not return NaN. */
    private static float safeAsin(float value) {
        if (value <= -1.0f) {
            return (float) -Math.PI / 2f;
        }
        if (value >= 1.0f) {
            return (float) Math.PI / 2f;
        }
        return (float) Math.asin(value);
    }

    /** Component-wise interpolation between two vectors. */
    public static Vector3f lerpValues(float percentCompleted, Vector3f begin, Vector3f end) {
        return new Vector3f(
            lerpValues(percentCompleted, begin.x(), end.x()),
            lerpValues(percentCompleted, begin.y(), end.y()),
            lerpValues(percentCompleted, begin.z(), end.z()));
    }

    /** Component-wise interpolation between two vectors, written into {@code dst}. */
    public static void lerpValues(float percentCompleted, Vector3f begin, Vector3f end, Vector3f dst) {
        dst.set(
            lerpValues(percentCompleted, begin.x(), end.x()),
            lerpValues(percentCompleted, begin.y(), end.y()),
            lerpValues(percentCompleted, begin.z(), end.z()));
    }

    /** Folds an Euler triple into {@code [-HALF_ROUND, HALF_ROUND)}. */
    public static Vector3f wrapRotation(Vector3f value) {
        return new Vector3f(wrapRotation(value.x), wrapRotation(value.y), wrapRotation(value.z));
    }

    /** Folds one angle in radians into {@code [-HALF_ROUND, HALF_ROUND)}. */
    public static float wrapRotation(float value) {
        float f = value % ROUND;
        if (f >= HALF_ROUND) {
            f -= ROUND;
        }
        if (f < -HALF_ROUND) {
            f += ROUND;
        }
        return f;
    }

    /**
     * Scales a vector towards identity by a blend weight.
     * <p>
     * A scale channel's identity is {@code 1}, not {@code 0}, so a weighted contribution is
     * {@code 1 + (value - 1) * weight} - at weight 0 the value is 1 (no scaling) and at weight 1 it is the channel's
     * own value. Interpolating towards zero instead would collapse the model to nothing whenever a weight was partial,
     * which is the classic scale-blending bug.
     */
    public static float computeWeightedScale(float value, float weight) {
        return 1f + (value - 1f) * weight;
    }

    public static Vector3f computeWeightedScale(Vector3f value, float weight) {
        return new Vector3f(
            computeWeightedScale(value.x, weight),
            computeWeightedScale(value.y, weight),
            computeWeightedScale(value.z, weight));
    }

    public static void computeWeightedScale(Vector3f value, float weight, Vector3f dst) {
        dst.set(
            computeWeightedScale(value.x, weight),
            computeWeightedScale(value.y, weight),
            computeWeightedScale(value.z, weight));
    }
}

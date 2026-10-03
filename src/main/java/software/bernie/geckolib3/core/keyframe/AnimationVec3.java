package software.bernie.geckolib3.core.keyframe;

import org.joml.Vector3f;

import software.bernie.geckolib3.core.util.MathUtil;

/**
 * A three-component animation value that also remembers how far an <em>ending</em> transition has progressed.
 * <p>
 * A literal port of upstream's {@code geckolib3/core/keyframe/AnimationVec3.java}. It is the accumulator the blending
 * fold writes into: {@code BlendBoneAnimationQueue.pollRotationPoint} and its position and scale siblings build one of
 * these per bone per frame, adding each animation player's contribution weighted by that player's blend weight, and
 * then {@link #apply}/{@link #applyRotation} collapse it against the value the bone already holds.
 * <p>
 * The {@code endingTransitionPercentProgress} field is the subtle part and is why this is a subclass rather than a bare
 * {@link Vector3f}: when several players contribute and one of them is still finishing its <em>outgoing</em>
 * transition, that player's contribution must fade towards what the bone already had. The fold therefore tracks the
 * <em>smallest</em> such progress across all contributors
 * ({@link #setEndingTransitionPercentProgressIfLess}, mirroring upstream's method of the same name) and rescales the
 * whole accumulated vector by it at the end.
 * <p>
 * The field reads backwards from a blend weight, and getting it wrong is easy - so state it exactly. The interpolation
 * runs <em>from this accumulated value towards whatever the destination already held</em>, parameterised by this
 * field:
 * <ul>
 * <li>{@code 0} - the destination's previous contents are discarded and this accumulated value is taken outright
 * (upstream's {@code dst.set(this)} branch).</li>
 * <li>{@code 1} - the destination keeps exactly what it already had and this accumulated value is ignored entirely,
 * because the interpolation runs all the way to the destination.</li>
 * <li>in between - a mixture, weighted by this field.</li>
 * </ul>
 * The default of {@code 1f} therefore means "no contribution", not "full contribution"; in the fold it is lowered to
 * the smallest progress among the contributors, and a contributor with no ending transition in flight leaves it at the
 * <em>upper</em> pole so it does not disturb values the other contributors produced.
 * <p>
 * Ported ahead of its consumer deliberately. Nothing in the engine references this class yet, so a mistake in it cannot
 * reach a model; the blending work that uses it lands only once this is covered by tests.
 */
public class AnimationVec3 extends Vector3f {

    /** How far the outgoing transition of some contributor has progressed; {@code 1f} means none is in flight. */
    public float endingTransitionPercentProgress = 1f;

    public AnimationVec3() {}

    public AnimationVec3(float x, float y, float z) {
        super(x, y, z);
    }

    public AnimationVec3(Vector3f point) {
        super(point);
    }

    /**
     * Lowers the recorded progress, so the whole fold ends up scaled by the <em>least</em> advanced ending transition
     * among its contributors - the most conservative answer, because that is the one still most in flight.
     */
    public void setEndingTransitionPercentProgressIfLess(float endingTransitionPercentProgress) {
        if (endingTransitionPercentProgress < this.endingTransitionPercentProgress) {
            this.endingTransitionPercentProgress = endingTransitionPercentProgress;
        }
    }

    /** Collapses this accumulated value onto {@code dst}, lerping from whatever {@code dst} already held. */
    public void apply(Vector3f dst) {
        float progress = this.endingTransitionPercentProgress;
        if (progress == 0f) {
            dst.set(this);
        } else {
            MathUtil.lerpValues(progress, this, dst, dst);
        }
    }

    /**
     * Collapses this accumulated rotation onto {@code dst} the way a rotation must be collapsed: through the
     * shortest-path quaternion interpolation in {@link MathUtil#lerpRotationValues}, relative to the bone's initial
     * rotation, rather than a per-component lerp. A per-component lerp between two Euler triples takes a path that is
     * not the shortest one and visibly detaches a joint mid-transition.
     */
    public void applyRotation(Vector3f dst, Vector3f initRot) {
        float progress = this.endingTransitionPercentProgress;
        if (progress == 0f) {
            dst.set(this);
        } else {
            MathUtil.lerpRotationValues(progress, this, dst, initRot, dst);
        }
    }
}

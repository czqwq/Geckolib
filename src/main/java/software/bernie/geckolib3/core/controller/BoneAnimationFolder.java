package software.bernie.geckolib3.core.controller;

import java.util.List;

import org.joml.Vector3f;

import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.AnimationVec3;
import software.bernie.geckolib3.core.util.MathUtil;

/**
 * Folds several animation players' contributions to one bone into a single value per channel.
 * <p>
 * This is the piece the engine was missing. Upstream plays every animation a controller state lists, at once, each
 * weighted by its own condition, and combines them per bone
 * ({@code BedrockAnimationController.BlendBoneAnimationQueue}, {@code :422-673}). This engine's
 * {@link AnimationController} plays one animation and emits one {@link AnimationPoint} per channel, so a state that
 * lists eight clips - which the built-in default controller pack declares, {@code ["parallel0" … "parallel7"]} - can
 * only ever have one of them drawn.
 * <p>
 * The fold is deliberately a standalone, dependency-free unit: it takes already-resolved values and weights and returns
 * a combined value. It does not touch the engine's queues, its controller state or the draw path, so the arithmetic can
 * be tested exhaustively before anything depends on it. That separation exists because the failure mode of getting this
 * wrong is silent - a bone that sits slightly wrong, only on models whose packs use multi-animation states.
 * <p>
 * The three channels fold differently, and each difference is upstream's:
 * <ul>
 * <li><b>Rotation</b> accumulates by weighted addition per component
 * ({@code BedrockAnimationController:517}, {@code target.fma(weight, pointValue)}), and the accumulated result is then
 * applied through the shortest-path rotation interpolation rather than assigned directly
 * ({@link AnimationVec3#applyRotation}).</li>
 * <li><b>Position</b> also accumulates by weighted addition ({@code :583}).</li>
 * <li><b>Scale</b> does <em>not</em> add. It multiplies, and each contribution is first re-centred on identity so that
 * a weight of zero means "leave the scale alone" rather than "scale to nothing"
 * ({@code :649-653}, {@code MathUtil.computeWeightedScale}).</li>
 * </ul>
 * A contribution whose weight is zero is skipped entirely rather than added as a zero vector. For rotation and position
 * that is an optimisation with no effect on the result; for scale it is required, because multiplying by a
 * de-weighted scale is not the identity.
 */
public final class BoneAnimationFolder {

    /** The identity scale, which the scale channel folds towards. */
    private static final Vector3f IDENTITY_SCALE = new Vector3f(1f, 1f, 1f);

    private BoneAnimationFolder() {}

    /**
     * One player's contribution: the values it wants for a bone, and how much of them it wants.
     * <p>
     * A weight of zero means "this player does not apply", which is how upstream represents a state entry whose
     * condition was false ({@code BedrockAnimationController.ConditionHolder:391-416}).
     * <p>
     * A {@code null} channel means "this player does not drive that channel at all", and it is kept as null rather than
     * replaced by a neutral value. That distinction is load-bearing rather than tidy: a player that drives only
     * rotation must not make the fold report a position, because the caller writes every channel the fold reports and
     * would otherwise overwrite a position some other player or the bone itself had established. Rotation and position
     * read a null as the zero vector and scale reads it as identity, but only <em>after</em> presence has been decided.
     */
    public static final class Contribution {
        public final Vector3f rotation;
        public final Vector3f position;
        public final Vector3f scale;
        public final float weight;

        public Contribution(Vector3f rotation, Vector3f position, Vector3f scale, float weight) {
            this.rotation = rotation;
            this.position = position;
            this.scale = scale;
            this.weight = weight;
        }
    }

    /**
     * The folded result for one bone, in the same shape the engine's per-bone queues carry.
     * <p>
     * {@code null} for a channel means "no player contributed to it", which is distinct from "contributed zero": the
     * caller must leave that channel as the bone already has it rather than writing a value.
     */
    public static final class FoldedBone {
        public final Vector3f rotation;
        public final Vector3f position;
        public final Vector3f scale;

        FoldedBone(Vector3f rotation, Vector3f position, Vector3f scale) {
            this.rotation = rotation;
            this.position = position;
            this.scale = scale;
        }
    }

    /**
     * Folds every contribution into one result per channel.
     *
     * @param contributions the players' contributions, in whatever order the controller holds them; the fold is
     *                      commutative for rotation and position and multiplicative for scale, so order does not change
     *                      the result
     * @return the folded value per channel, with {@code null} for a channel nothing contributed to
     */
    public static FoldedBone fold(List<Contribution> contributions) {
        AnimationVec3 rotation = new AnimationVec3();
        AnimationVec3 position = new AnimationVec3();
        Vector3f scale = new Vector3f(IDENTITY_SCALE);
        boolean anyRotation = false;
        boolean anyPosition = false;
        boolean anyScale = false;

        for (Contribution contribution : contributions) {
            if (contribution == null || contribution.weight == 0f) {
                continue;
            }
            float weight = contribution.weight;

            // Presence is decided from the source, before any neutral value is substituted: a player that drives only
            // rotation must not make the fold report a position.
            if (contribution.rotation != null) {
                // Rotation and position accumulate by weighted addition. A weight of exactly 1 is the common case - a
                // state whose single entry has no condition - and upstream keeps it as a plain add rather than special
                // casing it, so it is not special cased here either.
                rotation.fma(weight, contribution.rotation);
                anyRotation = true;
            }
            if (contribution.position != null) {
                position.fma(weight, contribution.position);
                anyPosition = true;
            }
            if (contribution.scale != null) {
                // Scale multiplies, and each contribution is de-weighted towards identity first, so a partial weight
                // interpolates the scale rather than shrinking the bone.
                if (weight == 1f) {
                    scale.mul(contribution.scale);
                } else {
                    scale.mul(MathUtil.computeWeightedScale(contribution.scale, weight));
                }
                anyScale = true;
            }
        }

        return new FoldedBone(
            anyRotation ? rotation : null,
            anyPosition ? position : null,
            anyScale ? scale : null);
    }

    /**
     * The rotation a bone should end up with, given what it already had.
     * <p>
     * The accumulated rotation is measured relative to the bone's initial rotation and is applied through the
     * shortest-path rotation interpolation ({@link AnimationVec3#applyRotation}), which is what stops a transitioning
     * joint from swinging the long way round. {@code initialRotation} is the bone's own initial rotation and
     * {@code currentRotation} is where the bone stands now.
     */
    public static Vector3f applyRotation(Vector3f folded, Vector3f initialRotation, Vector3f currentRotation) {
        if (folded == null) {
            return null;
        }
        Vector3f dst = new Vector3f(currentRotation);
        AnimationVec3 accumulated = folded instanceof AnimationVec3
            ? (AnimationVec3) folded
            : new AnimationVec3(folded);
        accumulated.applyRotation(dst, initialRotation);
        return dst;
    }

    /**
     * A convenience that reads one player's contribution out of an {@link AnimationPoint} triple.
     * <p>
     * The engine resolves a channel to a number at build time
     * ({@code AnimationPoint} carries {@code animationStartValue}/{@code animationEndValue} as {@code Double}s and
     * {@link MathUtil#lerpValues(AnimationPoint, software.bernie.geckolib3.core.easing.EasingType, java.util.function.Function)}
     * collapses them), so a contribution is just the lerped numbers. This exists so a caller does not have to
     * re-implement the three-point read for every channel.
     */
    static Vector3f readRotation(AnimationPoint x, AnimationPoint y, AnimationPoint z,
        software.bernie.geckolib3.core.easing.EasingType easingType,
        java.util.function.Function<Double, Double> customEasing) {
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Vector3f(
            MathUtil.lerpValues(x, easingType, customEasing),
            MathUtil.lerpValues(y, easingType, customEasing),
            MathUtil.lerpValues(z, easingType, customEasing));
    }
}

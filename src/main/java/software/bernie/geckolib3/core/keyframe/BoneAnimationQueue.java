/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.keyframe;

import software.bernie.geckolib3.core.processor.IBone;

public class BoneAnimationQueue {

    public final IBone bone;
    public AnimationPointQueue rotationXQueue = new AnimationPointQueue();
    public AnimationPointQueue rotationYQueue = new AnimationPointQueue();
    public AnimationPointQueue rotationZQueue = new AnimationPointQueue();
    public AnimationPointQueue positionXQueue = new AnimationPointQueue();
    public AnimationPointQueue positionYQueue = new AnimationPointQueue();
    public AnimationPointQueue positionZQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleXQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleYQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleZQueue = new AnimationPointQueue();

    /**
     * How much the animation that filled this queue contributes where several play at once.
     * <p>
     * Upstream's field of the same name, set by the animation player from the animation's own {@code blend_weight}
     * ({@code geckolib3/core/keyframe/BoneAnimationQueue.java:29}, {@code :107-113}) and read by the per-bone fold
     * ({@code BedrockAnimationController:509}, {@code :521}, {@code :575}, {@code :587}, {@code :641}, {@code :657}).
     * It is carried here rather than passed to the fold directly because the fold reads whole queues, so the weight has
     * to travel with the values it weights - otherwise a bone's rotation and its weight could come from different
     * players.
     * <p>
     * Defaults to 1, upstream's fallback for an animation that declares no {@code blend_weight}
     * ({@code AnimationPlayer:318}).
     */
    public float blendWeight = 1f;

    public BoneAnimationQueue(IBone bone) {
        this.bone = bone;
    }

    /** Upstream clamps a negative weight to zero rather than letting it invert the contribution
     * ({@code BoneAnimationQueue:111-113}). */
    public void setBlendWeight(float blendWeight) {
        this.blendWeight = blendWeight > 0 ? blendWeight : 0;
    }

    public float getBlendWeight() {
        return this.blendWeight;
    }
}

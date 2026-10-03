/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.keyframe;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.controller.transition.IBlendTransition;

public class AnimationPoint {

    /**
     * The current tick in the animation to lerp from
     */
    public final Double currentTick;
    /**
     * The tick that the current animation should end at
     */
    public final Double animationEndTick;
    /**
     * The Animation start value.
     */
    public final Double animationStartValue;
    /**
     * The Animation end value.
     */
    public final Double animationEndValue;

    /**
     * The current keyframe.
     */

    public final KeyFrame<IValue> keyframe;

    /**
     * How this point's interpolation is shaped over its span, or {@code null} for the engine's default linear ramp.
     * <p>
     * A Bedrock pack may declare a {@code blend_transition} as a <em>curve</em> rather than a length, and upstream
     * builds a segmented transition for that form
     * ({@code geckolib3/core/controller/transition/SegmentedBlendTransition.java}). This engine's default is a straight
     * line - {@code currentTick / animationEndTick} - so carrying the curve here is what lets a transition follow the
     * pack's shape without changing how every other point is interpolated: {@code null} means "behave exactly as
     * before", and only a point built for a curved transition sets it.
     */
    public final IBlendTransition blendTransition;

    public AnimationPoint(KeyFrame<IValue> keyframe, Double currentTick, Double animationEndTick,
        Double animationStartValue, Double animationEndValue) {
        this(keyframe, currentTick, animationEndTick, animationStartValue, animationEndValue, null);
    }

    public AnimationPoint(KeyFrame<IValue> keyframe, Double currentTick, Double animationEndTick,
        Double animationStartValue, Double animationEndValue, IBlendTransition blendTransition) {
        this.keyframe = keyframe;
        this.currentTick = currentTick;
        this.animationEndTick = animationEndTick;
        this.animationStartValue = animationStartValue;
        this.animationEndValue = animationEndValue;
        this.blendTransition = blendTransition;
    }

    public AnimationPoint(KeyFrame<IValue> keyframe, double tick, double animationEndTick, float animationStartValue,
        double animationEndValue) {
        this(keyframe, Double.valueOf(tick), animationEndTick, Double.valueOf(animationStartValue), animationEndValue);
    }

    /**
     * How far this point has progressed, as a fraction, shaped by the pack's blend curve when it declares one.
     * <p>
     * The single place the curve is consulted, so a caller cannot accidentally interpolate linearly for a point that
     * carries a shape.
     */
    public double percentCompleted() {
        if (blendTransition != null) {
            return blendTransition.get(currentTick.floatValue());
        }
        if (animationEndTick == 0.0d) {
            return 0.0d;
        }
        return currentTick / animationEndTick;
    }

    @Override
    public String toString() {
        return "Tick: " + currentTick
            + " | End Tick: "
            + animationEndTick
            + " | Start Value: "
            + animationStartValue
            + " | End Value: "
            + animationEndValue;
    }
}

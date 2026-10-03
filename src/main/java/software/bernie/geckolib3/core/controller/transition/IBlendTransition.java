package software.bernie.geckolib3.core.controller.transition;

/**
 * How a blend's progress is shaped over its length.
 * <p>
 * A literal port of upstream's {@code geckolib3/core/controller/transition/IBlendTransition.java}. A Bedrock pack's
 * {@code blend_transition} comes in two JSON forms and upstream picks a different implementation for each: a bare number
 * is a linear length in seconds ({@link LinearBlendTransition}), and an object is a {@code time -> weight} curve
 * interpolated between its declared points ({@link SegmentedBlendTransition}).
 * <p>
 * The port previously kept only a scalar tick count, which is enough for the numeric form and discards the shape of the
 * curve form. That matters because the most widely used controller file in the ecosystem - the built-in default
 * controller pack - declares a ten-point curve (`builtin/misc/4_default_controllers/controller/main_controllers.json:33-44`,
 * {@code 0.0 -> 1} down to {@code 0.15 -> 0}) on its offhand hold axis, so collapsing it to its duration gives every
 * pack that does not define its own {@code hold_offhand} the wrong transition profile.
 * <p>
 * These are additive and referenced by nothing yet; they exist so the curve can be tested before anything consumes it.
 */
public interface IBlendTransition {

    /** The blend value at {@code tick} ticks into the transition. */
    float get(float tick);

    /** The transition's length in ticks. */
    float length();

    /**
     * A fresh instance for a new transition.
     * <p>
     * Upstream needs this because its segmented form is stateful: {@code SegmentedBlendTransition} hands out one
     * instance per transition so its segment search position does not leak between them, whereas the linear form is
     * stateless and returns itself. The port keeps the same contract so a consumer can call it unconditionally.
     */
    default IBlendTransition startNew() {
        return this;
    }
}

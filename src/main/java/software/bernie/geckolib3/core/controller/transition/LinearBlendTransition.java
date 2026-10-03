package software.bernie.geckolib3.core.controller.transition;

/**
 * A blend whose progress rises linearly over its length.
 * <p>
 * A literal port of upstream's {@code geckolib3/core/controller/transition/LinearBlendTransition.java}, which is what
 * upstream builds from the numeric form of a {@code blend_transition}. Note the unit conversion: the JSON value is a
 * length in <em>seconds</em> and the engine counts ticks, so the constructor scales by 20
 * ({@code BlendTransition.Adapter:31-33} sets the seconds; upstream's own constructor does the {@code * 20}).
 */
public class LinearBlendTransition implements IBlendTransition {

    private final float ticks;

    public LinearBlendTransition(float lengthSeconds) {
        this.ticks = lengthSeconds * 20f;
    }

    @Override
    public float get(float tick) {
        // A zero-length transition is complete immediately rather than a division by zero. Upstream answers 1 here,
        // which means "fully blended" - the same thing a transition of no duration should mean.
        return ticks != 0f ? tick / ticks : 1f;
    }

    @Override
    public float length() {
        return ticks;
    }
}

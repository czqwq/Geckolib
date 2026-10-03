package software.bernie.geckolib3.core.controller.transition;

import java.util.ArrayList;
import java.util.List;

import software.bernie.geckolib3.util.OrderedSegmentSearcher;

/**
 * A blend whose progress follows a pack-declared curve rather than a straight line.
 * <p>
 * A literal port of upstream's {@code geckolib3/core/controller/transition/SegmentedBlendTransition.java}, which is
 * what upstream builds from the object form of a {@code blend_transition}: a map of {@code time -> weight}, with the
 * weight interpolated linearly between the declared points.
 * <p>
 * Two details are easy to get wrong and both are upstream's. First, the points are <em>inverted</em>: the constructor
 * stores {@code 1 - position[i]} as each segment's start. The pack writes the curve as "how much of the old pose
 * remains", while this value is used as "how far the new pose has come", so the two are complements. Second, times are
 * in seconds and ticks are counted, so every point is scaled by 20 - the same conversion {@link LinearBlendTransition}
 * performs for the numeric form.
 * <p>
 * {@link #startNew()} hands out a fresh instance rather than {@code this}, because the segment search is stateful
 * (a cursor) and two transitions running from one curve must not share it.
 */
public class SegmentedBlendTransition implements IBlendTransition {

    private final List<Segment> segments;
    private final OrderedSegmentSearcher<Segment> segmentSearcher;

    /**
     * @param time     control-point times in seconds, ascending
     * @param position control-point weights, one per time, in the pack's own sense (1 = old pose, 0 = new pose)
     */
    public SegmentedBlendTransition(float[] time, float[] position) {
        List<Segment> built = new ArrayList<>(Math.max(0, time.length - 1));
        for (int i = 0; i < time.length - 1; i++) {
            // The weight is complemented here, once, so get() can interpolate a plain rising value.
            built.add(
                new Segment(
                    time[i] * 20f,
                    time[i + 1] * 20f,
                    1f - position[i],
                    1f - position[i + 1]));
        }
        this.segments = built;
        this.segmentSearcher = new OrderedSegmentSearcher<>(this.segments, 0f, s -> s.endTick);
    }

    private SegmentedBlendTransition(List<Segment> segments) {
        this.segments = segments;
        this.segmentSearcher = new OrderedSegmentSearcher<>(this.segments, 0f, s -> s.endTick);
    }

    @Override
    public float get(float tick) {
        Segment segment = segmentSearcher.search(tick);
        if (tick <= segment.startTick) {
            return segment.startPosition;
        } else if (tick >= segment.endTick) {
            return segment.startPosition + segment.positionDelta;
        }
        float progress = (tick - segment.startTick) / segment.totalTick;
        return segment.startPosition + segment.positionDelta * progress;
    }

    @Override
    public float length() {
        return segmentSearcher.rightBound();
    }

    @Override
    public SegmentedBlendTransition startNew() {
        return new SegmentedBlendTransition(segments);
    }

    /** How many control points the curve was built from, for a caller that wants to report the shape it kept. */
    public int pointCount() {
        return segments.size() + 1;
    }

    private static class Segment {
        public final float startTick;
        public final float totalTick;
        public final float endTick;
        public final float startPosition;
        public final float positionDelta;

        Segment(float startTick, float endTick, float startPosition, float endPosition) {
            this.startTick = startTick;
            this.totalTick = endTick - startTick;
            this.endTick = endTick;
            this.startPosition = startPosition;
            this.positionDelta = endPosition - startPosition;
        }
    }
}

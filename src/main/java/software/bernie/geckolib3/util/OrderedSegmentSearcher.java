package software.bernie.geckolib3.util;

import java.util.List;

/**
 * Finds which of an ascending sequence of segments contains a point, remembering where the last search landed.
 * <p>
 * A literal port of upstream's {@code geckolib3/util/OrderedSegmentSearcher.java}. It exists because blend curves are
 * queried once per frame with a monotonically rising tick, so the common case is "still in the segment I was in last
 * time" or "one segment further" - and the cursor turns that into constant work rather than a scan. It also handles the
 * two cases a naive scan gets wrong: a tick that jumps backwards (a restarted transition, or a different animation
 * reusing the same curve) resets to the start, and a tick past the end clamps to the last segment.
 * <p>
 * The ascending-order precondition is the caller's, and upstream checks it in three places rather than assuming it:
 * at construction, when a backward jump lands on the first segment, and while advancing. Those checks throw rather than
 * returning a wrong segment, because a non-ascending curve means the pack's data is malformed and silently picking one
 * of two contradictory segments would produce a blend nobody can explain.
 * <p>
 * Ported as part of the blend-curve work and referenced by nothing else yet.
 *
 * @param <T> the segment type
 */
public class OrderedSegmentSearcher<T> {

    private final List<T> segments;
    private final float leftBound;
    private final float rightBound;
    private final ToFloatFunction<T> rightBoundGetter;

    private int currentIndex;
    private float currentLeft;
    private float currentRight;

    public OrderedSegmentSearcher(List<T> segments, float leftBound, ToFloatFunction<T> rightBoundGetter) {
        this.segments = segments;
        this.leftBound = leftBound;
        this.rightBound = rightBoundGetter.apply(segments.get(segments.size() - 1));
        this.rightBoundGetter = rightBoundGetter;

        this.currentIndex = 0;
        this.currentLeft = leftBound;
        this.currentRight = this.rightBoundGetter.apply(segments.get(0));
        if (leftBound > this.currentRight || leftBound > this.rightBound || this.currentRight > this.rightBound) {
            throw new IllegalArgumentException("segments are not in ascending order");
        }
    }

    /** The segment containing {@code point}, advancing or resetting the cursor as needed. */
    public T search(final float point) {
        if (segments.size() == 1) {
            return segments.get(0);
        }

        if (point < currentLeft) {
            // The tick went backwards. Upstream restarts its cursor at the beginning; when it is already there, the
            // first segment is the answer and there is nothing to reset.
            T firstSegment = segments.get(0);
            if (currentIndex == 0) {
                return firstSegment;
            }

            currentIndex = 0;
            currentLeft = leftBound;
            currentRight = rightBoundGetter.apply(firstSegment);
            if (point >= currentRight) {
                return search(point);
            } else {
                return firstSegment;
            }
        }

        if (point == currentLeft || point < currentRight || currentRight == rightBound) {
            return segments.get(currentIndex);
        }

        if (point >= rightBound) {
            // Past the end: clamp to the final segment rather than overrunning the list.
            T lastSegment = segments.get(segments.size() - 1);
            float left = rightBoundGetter.apply(segments.get(segments.size() - 2));
            float right = rightBoundGetter.apply(lastSegment);
            if (left > right) {
                throw new IllegalArgumentException("segments are not in ascending order");
            }
            currentIndex = segments.size() - 1;
            currentLeft = left;
            currentRight = right;
            return lastSegment;
        }

        float left = currentRight;
        for (int index = currentIndex + 1;; ++index) {
            T segment = segments.get(index);
            float right = rightBoundGetter.apply(segment);
            if (left > right) {
                throw new IllegalArgumentException("segments are not in ascending order");
            }
            if (point < right) {
                currentIndex = index;
                currentLeft = left;
                currentRight = right;
                return segment;
            }
            left = right;
        }
    }

    public float leftBound() {
        return leftBound;
    }

    public float rightBound() {
        return rightBound;
    }

    @FunctionalInterface
    public interface ToFloatFunction<T> {
        float apply(T t);
    }
}

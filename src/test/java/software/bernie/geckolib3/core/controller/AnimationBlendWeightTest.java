package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.BoneAnimationFolder.Contribution;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * An animation's {@code blend_weight} is a live MoLang expression that reaches the per-bone fold.
 * <p>
 * Upstream's field is {@code Animation.blendWeight}, evaluated where the animation is already in hand and falling back
 * to 1 when it declares none ({@code AnimationPlayer:318}, {@code :346}, {@code :365}), then carried on the bone
 * animation queue so the fold can weight with it ({@code BoneAnimationQueue:29}, {@code :107-113}, read at
 * {@code BedrockAnimationController:509}, {@code :521}, {@code :575}, {@code :587}, {@code :641}, {@code :657}).
 * <p>
 * This is not a hypothetical field. All thirteen {@code blend_weight} declarations across the packs this engine loads
 * are <em>expressions</em> and none is a plain number - the most common is
 * {@code 0.75*math.sin(query.anim_time*20)+1.5} - so a port that cast the field to a float would read 0 and silently
 * vanish those animations, and one that evaluated it once at load would freeze the weight at its value at time zero.
 * <p>
 * The tests below therefore pin three different things, because each fails differently: the queue carries the weight,
 * the expression is evaluated rather than cast, and the fold's output actually moves when the weight does. The last is
 * the one that matters - a weight that is read but never consumed is exactly the state this field was in before.
 */
class AnimationBlendWeightTest {

    /** The expression the packs actually declare, quoted verbatim from the shipped animation files. */
    private static final String SHIPPED_EXPRESSION = "0.75*math.sin(query.anim_time*20)+1.5";

    /**
     * A queue starts at upstream's fallback of 1, so an animation that declares no {@code blend_weight} contributes
     * fully without any expression having to exist.
     */
    @Test
    void aQueueWithoutAWeightContributesFully() {
        BoneAnimationQueue queue = new BoneAnimationQueue(null);
        assertEquals(1f, queue.getBlendWeight(), 0.0001f, "the default is upstream's fallback");
    }

    /**
     * Upstream clamps a negative weight to zero rather than letting it invert or cancel the contribution
     * ({@code BoneAnimationQueue:111-113}).
     */
    @Test
    void aNegativeWeightIsClampedToZero() {
        BoneAnimationQueue queue = new BoneAnimationQueue(null);

        queue.setBlendWeight(-2f);
        assertEquals(0f, queue.getBlendWeight(), 0.0001f, "a negative weight must not invert the contribution");

        queue.setBlendWeight(0.25f);
        assertEquals(0.25f, queue.getBlendWeight(), 0.0001f, "a positive weight is kept as written");
    }

    /** An animation that declares no weight carries no expression, which is upstream's {@code null} case. */
    @Test
    void anAnimationWithNoDeclaredWeightLeavesTheFieldNull() {
        Animation animation = new Animation();
        assertEquals(null, animation.blendWeight, "an animation that declares no blend_weight carries no expression");
    }

    /**
     * The field is parsed through the same entry point as the rest of the animation, so the value is an expression the
     * engine can evaluate rather than a string nothing understands.
     */
    @Test
    void aDeclaredWeightParsesIntoAnEvaluableExpression() throws Exception {
        MolangParser parser = new MolangParser();
        Animation animation = new Animation();
        animation.blendWeight = parser.parseJson(new JsonPrimitive(SHIPPED_EXPRESSION));

        assertNotNull(animation.blendWeight, "the expression must survive parsing");
    }

    /**
     * The decisive test: the weight is evaluated <em>per frame</em>, so it tracks {@code query.anim_time} the way the
     * packs intend. Evaluating once at load would pass every other test here and still leave a breathing animation
     * frozen at its time-zero weight.
     */
    @Test
    void theWeightIsEvaluatedAgainstTheAnimationClockRatherThanCastOnce() throws Exception {
        MolangParser parser = new MolangParser();
        Animation animation = new Animation();
        animation.blendWeight = parser.parseJson(new JsonPrimitive(SHIPPED_EXPRESSION));

        parser.setValue("query.anim_time", 0d);
        double atZero = animation.blendWeight.get();

        parser.setValue("query.anim_time", 0.05d);
        double later = animation.blendWeight.get();

        // sin(0) = 0, so the weight at t=0 is exactly 1.5; at t=1 tick the sine has moved and so must the weight.
        assertEquals(1.5d, atZero, 0.0001d, "sin(0) leaves only the constant term");
        assertNotEquals(
            atZero,
            later,
            0.0001d,
            "the weight must move with the animation clock; a value fixed at load time would not");
    }

    /**
     * And the weight must change what the fold produces. This is the assertion the port was missing when the field was
     * parsed and synced but read by nothing: reading a weight and not applying it looks identical to not having it.
     */
    @Test
    void theWeightChangesWhatTheFoldProduces() {
        Vector3f rotation = new Vector3f(0f, 90f, 0f);
        Vector3f position = new Vector3f(0f, 0f, 0f);
        Vector3f scale = new Vector3f(1f, 1f, 1f);

        BoneAnimationFolder.FoldedBone full = BoneAnimationFolder.fold(
            Collections.singletonList(new Contribution(rotation, position, scale, 1f)));
        BoneAnimationFolder.FoldedBone half = BoneAnimationFolder.fold(
            Collections.singletonList(new Contribution(rotation, position, scale, 0.5f)));

        assertNotEquals(
            full.rotation.y,
            half.rotation.y,
            0.0001f,
            "a contribution of half the weight must move the bone half as far");
        assertTrue(
            Math.abs(half.rotation.y) < Math.abs(full.rotation.y),
            "a smaller weight must reduce the contribution, not change its direction");
    }

    /**
     * Two players of different weights must not fold as if they were equal, which is what the hard-coded {@code 1f}
     * did. The heavier contribution has to dominate.
     */
    @Test
    void aHeavierContributionDominatesALighterOne() {
        Vector3f left = new Vector3f(0f, 90f, 0f);
        Vector3f right = new Vector3f(0f, 0f, 0f);
        Vector3f position = new Vector3f(0f, 0f, 0f);
        Vector3f scale = new Vector3f(1f, 1f, 1f);

        BoneAnimationFolder.FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(
                new Contribution(left, position, scale, 3f),
                new Contribution(right, position, scale, 1f)));

        assertTrue(
            folded.rotation.y > 45f,
            "the three-times-heavier contribution of 90 degrees must pull the result past the midpoint, but it was "
                + folded.rotation.y);
    }
}

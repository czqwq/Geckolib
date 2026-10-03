package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.controller.BoneAnimationFolder.Contribution;
import software.bernie.geckolib3.core.controller.BoneAnimationFolder.FoldedBone;
import software.bernie.geckolib3.core.keyframe.AnimationVec3;

/**
 * The per-bone fold that lets one controller play several animations at once.
 * <p>
 * Upstream's counterpart is {@code BedrockAnimationController.BlendBoneAnimationQueue} ({@code :422-673}), which folds
 * every applied animation player's contribution per bone, weighted by each player's condition. This engine's
 * {@link AnimationController} plays one animation, so this fold is the piece that has to exist for a state listing
 * eight clips - the built-in default controller pack's {@code ["parallel0" … "parallel7"]} - to play all eight.
 * <p>
 * The tests below are written to distinguish the three channels, because they fold differently and getting one wrong
 * produces a plausible-looking pose rather than an obvious failure:
 * <ul>
 * <li>rotation and position add weightedly;</li>
 * <li>scale multiplies, each contribution de-weighted towards identity.</li>
 * </ul>
 * The weight-zero and empty cases matter as much as the arithmetic: "no contribution" must be distinguishable from
 * "contributed zero", or a channel no player drives would be overwritten with a zero and the bone would snap to rest.
 */
class BoneAnimationFolderTest {

    private static final float EPSILON = 0.0001f;

    private static Contribution contribution(Vector3f rotation, Vector3f position, Vector3f scale, float weight) {
        return new Contribution(rotation, position, scale, weight);
    }

    private static Contribution rotationOnly(float x, float y, float z, float weight) {
        return contribution(new Vector3f(x, y, z), null, null, weight);
    }

    // ---------------------------------------------------------------- the single-player case

    /**
     * One player at full weight must reproduce that player's values exactly, per channel. This is the case every model
     * in the wild hits, because almost every controller state lists one clip, so it is the assertion that the fold has
     * not broken ordinary rendering.
     */
    @Test
    void onePlayerAtFullWeightReproducesItsValues() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Collections.singletonList(
                contribution(new Vector3f(1f, 2f, 3f), new Vector3f(4f, 5f, 6f), new Vector3f(2f, 3f, 4f), 1f)));

        assertNotNull(folded.rotation);
        assertEquals(1f, folded.rotation.x, EPSILON);
        assertEquals(2f, folded.rotation.y, EPSILON);
        assertEquals(3f, folded.rotation.z, EPSILON);

        assertNotNull(folded.position);
        assertEquals(4f, folded.position.x, EPSILON);
        assertEquals(5f, folded.position.y, EPSILON);
        assertEquals(6f, folded.position.z, EPSILON);

        assertNotNull(folded.scale);
        assertEquals(2f, folded.scale.x, EPSILON);
        assertEquals(3f, folded.scale.y, EPSILON);
        assertEquals(4f, folded.scale.z, EPSILON);
    }

    // ---------------------------------------------------------------- rotation and position add

    /**
     * Two players at full weight add. This is the eight-parallel-axis case: each axis animates a different set of
     * bones, and where two do drive the same bone their rotations compose.
     */
    @Test
    void twoPlayersAtFullWeightAddTheirRotations() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(rotationOnly(1f, 0f, 0f, 1f), rotationOnly(0f, 2f, 0f, 1f)));

        assertNotNull(folded.rotation);
        assertEquals(1f, folded.rotation.x, EPSILON);
        assertEquals(2f, folded.rotation.y, EPSILON);
        assertEquals(0f, folded.rotation.z, EPSILON);
    }

    /** Position adds the same way rotation does. */
    @Test
    void twoPlayersAtFullWeightAddTheirPositions() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(
                contribution(null, new Vector3f(1f, 3f, 0f), null, 1f),
                contribution(null, new Vector3f(0f, -1f, 2f), null, 1f)));

        assertNotNull(folded.position);
        assertEquals(1f, folded.position.x, EPSILON);
        assertEquals(2f, folded.position.y, EPSILON);
        assertEquals(2f, folded.position.z, EPSILON);
        assertNull(folded.rotation, "nothing contributed rotation, so it must stay null");
    }

    /**
     * A partial weight scales that player's contribution rather than replacing anything. Upstream uses this for a state
     * entry whose condition is a fraction (a pack writes {@code 1 - v.main_eat} to cross-fade two clips).
     */
    @Test
    void aPartialWeightScalesThatPlayersContribution() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(rotationOnly(0f, 0f, 0f, 1f), rotationOnly(10f, 20f, 30f, 0.25f)));

        assertNotNull(folded.rotation);
        assertEquals(2.5f, folded.rotation.x, EPSILON);
        assertEquals(5f, folded.rotation.y, EPSILON);
        assertEquals(7.5f, folded.rotation.z, EPSILON);
    }

    /**
     * A weight of zero means "this player does not apply", so it is skipped entirely. For rotation and position that is
     * equivalent to adding nothing, but stating it separately pins the intent and guards the scale case below.
     */
    @Test
    void aZeroWeightPlayerContributesNothing() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(rotationOnly(5f, 5f, 5f, 1f), rotationOnly(100f, 100f, 100f, 0f)));

        assertNotNull(folded.rotation);
        assertEquals(5f, folded.rotation.x, EPSILON);
        assertEquals(5f, folded.rotation.y, EPSILON);
        assertEquals(5f, folded.rotation.z, EPSILON);
    }

    // ---------------------------------------------------------------- scale multiplies

    /**
     * Scale is the channel that folds differently: two players at full weight <em>multiply</em>, they do not add.
     * Adding would turn a model wearing two pieces of armour into a giant, and a pack that scales the same bone from
     * two parallel axes expects the effects to compound.
     */
    @Test
    void twoPlayersAtFullWeightMultiplyTheirScales() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(
                contribution(null, null, new Vector3f(2f, 2f, 2f), 1f),
                contribution(null, null, new Vector3f(3f, 4f, 5f), 1f)));

        assertNotNull(folded.scale);
        assertEquals(6f, folded.scale.x, EPSILON);
        assertEquals(8f, folded.scale.y, EPSILON);
        assertEquals(10f, folded.scale.z, EPSILON);
    }

    /**
     * A single player at full weight reproduces its scale exactly, which is the ordinary case: multiplying the identity
     * by the value. If the fold had re-centred a full-weight contribution, this would come out wrong.
     */
    @Test
    void onePlayerAtFullWeightReproducesItsScale() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Collections.singletonList(contribution(null, null, new Vector3f(0.5f, 1.5f, 2f), 1f)));

        assertNotNull(folded.scale);
        assertEquals(0.5f, folded.scale.x, EPSILON);
        assertEquals(1.5f, folded.scale.y, EPSILON);
        assertEquals(2f, folded.scale.z, EPSILON);
    }

    /**
     * A partial-weight scale is de-weighted towards identity, so it interpolates the scale instead of shrinking the
     * bone. This is the classic scale-blending bug: a naive lerp towards zero collapses the model to nothing whenever a
     * weight is partial, and the failure is loud but only on packs using fractional weights.
     */
    @Test
    void aPartialWeightScaleInterpolatesTowardsIdentityNotZero() {
        // Weight 0: the scale must vanish entirely (identity), not halve the bone.
        FoldedBone none = BoneAnimationFolder.fold(
            Collections.singletonList(contribution(null, null, new Vector3f(4f, 4f, 4f), 0f)));
        assertNull(none.scale, "a zero-weight player contributes no scale at all");

        // Weight 0.5 on a scale of 3: the result is 1 + (3 - 1) * 0.5 = 2.
        FoldedBone half = BoneAnimationFolder.fold(
            Collections.singletonList(contribution(null, null, new Vector3f(3f, 3f, 3f), 0.5f)));
        assertNotNull(half.scale);
        assertEquals(2f, half.scale.x, EPSILON);
        assertEquals(2f, half.scale.y, EPSILON);
        assertEquals(2f, half.scale.z, EPSILON);
    }

    /**
     * A scale of exactly 1 at any weight leaves the result at identity, which is what an unanimated scale channel
     * relies on.
     */
    @Test
    void anIdentityScaleIsANoOpAtAnyWeight() {
        for (float weight : new float[] { 0.25f, 0.5f, 1f }) {
            FoldedBone folded = BoneAnimationFolder.fold(
                Collections.singletonList(contribution(null, null, new Vector3f(1f, 1f, 1f), weight)));
            assertNotNull(folded.scale);
            assertEquals(1f, folded.scale.x, EPSILON, "weight " + weight);
            assertEquals(1f, folded.scale.y, EPSILON, "weight " + weight);
            assertEquals(1f, folded.scale.z, EPSILON, "weight " + weight);
        }
    }

    /** A shrink below identity at partial weight also stays above the naive result. */
    @Test
    void aPartialWeightShrinkStaysAboveZero() {
        // Scale 0.5 at weight 0.5: 1 + (0.5 - 1) * 0.5 = 0.75, not 0.25.
        FoldedBone folded = BoneAnimationFolder.fold(
            Collections.singletonList(contribution(null, null, new Vector3f(0.5f, 0.5f, 0.5f), 0.5f)));
        assertNotNull(folded.scale);
        assertEquals(0.75f, folded.scale.x, EPSILON);
    }

    // ---------------------------------------------------------------- no contribution at all

    /**
     * With no contributions, every channel is {@code null} - "nothing drove this bone". The caller must leave the bone
     * as it is, so a bone no player animates is not snapped to a zero pose. This is the assertion that keeps the fold
     * from silently flattening models.
     */
    @Test
    void noContributionsLeavesEveryChannelUnset() {
        FoldedBone folded = BoneAnimationFolder.fold(Collections.emptyList());
        assertNull(folded.rotation);
        assertNull(folded.position);
        assertNull(folded.scale);
    }

    /** A list of only zero-weight players is the same as no players at all. */
    @Test
    void onlyZeroWeightPlayersLeavesEveryChannelUnset() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(rotationOnly(9f, 9f, 9f, 0f), rotationOnly(9f, 9f, 9f, 0f)));
        assertNull(folded.rotation);
        assertNull(folded.position);
        assertNull(folded.scale);
    }

    /**
     * A per-channel null inside a contribution means "this player does not drive that channel", so the fold reports
     * only the channels some player actually drove. A player that drives rotation alone must not make the fold claim a
     * position, because the caller writes every channel the fold reports - a claimed-but-zero position would overwrite
     * whatever the bone had established.
     */
    @Test
    void aChannelNotDrivenByAPlayerIsNotInvented() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Arrays.asList(
                contribution(new Vector3f(1f, 1f, 1f), null, null, 1f),
                contribution(null, new Vector3f(2f, 2f, 2f), null, 1f)));

        assertNotNull(folded.rotation, "the first player drove rotation");
        assertNotNull(folded.position, "the second player drove position");
        assertEquals(2f, folded.position.x, EPSILON);
        assertNull(folded.scale, "no player drove scale, so the fold must not report one");
    }

    /**
     * A player that drives every channel at once populates all three, which is the ordinary case for a single-animation
     * state.
     */
    @Test
    void aPlayerDrivingEveryChannelPopulatesAllThree() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Collections.singletonList(
                contribution(new Vector3f(1f, 0f, 0f), new Vector3f(0f, 1f, 0f), new Vector3f(2f, 2f, 2f), 1f)));

        assertNotNull(folded.rotation);
        assertNotNull(folded.position);
        assertNotNull(folded.scale);
    }

    // ---------------------------------------------------------------- the eight-player case

    /**
     * The case that motivated all of this: eight players at full weight, which is what the built-in default controller
     * pack declares for its parallel axis ({@code ["parallel0" … "parallel7"]}, no conditions). Each drives a different
     * axis of the same bone, and the fold must combine all eight rather than the first.
     */
    @Test
    void eightPlayersAreAllFoldedIn() {
        java.util.List<Contribution> contributions = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            contributions.add(rotationOnly(1f, 1f, 1f, 1f));
        }
        FoldedBone folded = BoneAnimationFolder.fold(contributions);
        assertNotNull(folded.rotation);
        assertEquals(8f, folded.rotation.x, EPSILON, "all eight players must contribute, not just the first");
        assertEquals(8f, folded.rotation.y, EPSILON);
        assertEquals(8f, folded.rotation.z, EPSILON);
    }

    /**
     * Dropping any player changes the result, which proves none of the eight is being ignored - the failure this whole
     * milestone exists to remove.
     */
    @Test
    void droppingOneOfEightPlayersChangesTheResult() {
        java.util.List<Contribution> eight = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(rotationOnly(1f, 0f, 0f, 1f));
        }
        FoldedBone all = BoneAnimationFolder.fold(eight);

        java.util.List<Contribution> seven = new java.util.ArrayList<>(eight.subList(0, 7));
        FoldedBone fewer = BoneAnimationFolder.fold(seven);

        assertNotNull(all.rotation);
        assertNotNull(fewer.rotation);
        assertEquals(8f, all.rotation.x, EPSILON);
        assertEquals(7f, fewer.rotation.x, EPSILON);
    }

    // ---------------------------------------------------------------- order independence

    /**
     * Rotation and position add and scale multiplies, so the fold's result does not depend on the order the controller
     * happens to hold its players in. Upstream relies on this too: a grouped {@code animations} list carries no
     * meaning, which is why its entries may be written in any order.
     */
    @Test
    void theResultDoesNotDependOnContributionOrder() {
        Contribution a = contribution(new Vector3f(1f, 2f, 3f), new Vector3f(4f, 5f, 6f), new Vector3f(2f, 3f, 4f), 0.5f);
        Contribution b = contribution(new Vector3f(7f, 8f, 9f), new Vector3f(1f, 1f, 1f), new Vector3f(3f, 3f, 3f), 1f);

        FoldedBone forward = BoneAnimationFolder.fold(Arrays.asList(a, b));
        FoldedBone backward = BoneAnimationFolder.fold(Arrays.asList(b, a));

        assertNotNull(forward.rotation);
        assertNotNull(backward.rotation);
        assertEquals(forward.rotation.x, backward.rotation.x, EPSILON);
        assertEquals(forward.rotation.y, backward.rotation.y, EPSILON);
        assertEquals(forward.rotation.z, backward.rotation.z, EPSILON);
        assertEquals(forward.scale.x, backward.scale.x, EPSILON);
        assertEquals(forward.scale.y, backward.scale.y, EPSILON);
        assertEquals(forward.scale.z, backward.scale.z, EPSILON);
    }

    // ---------------------------------------------------------------- applying the accumulated rotation

    /**
     * With no accumulated rotation there is nothing to apply, and the caller must be told so rather than handed a
     * zeroed rotation that would snap the bone to rest.
     */
    @Test
    void applyingNoRotationYieldsNothing() {
        assertNull(BoneAnimationFolder.applyRotation(null, new Vector3f(), new Vector3f(1f, 2f, 3f)));
    }

    /**
     * The accumulated rotation is applied relative to the bone's initial rotation, and the fold's progress pole
     * decides whether the accumulated value replaces what the bone had or defers to it. This is the same
     * inverted-relative-to-a-weight semantics {@code AnimationVec3} documents: progress {@code 0} takes the
     * accumulated value outright.
     */
    @Test
    void applyingAnAccumulatedRotationAtTheReplacingPoleLandsOnTheAccumulatedValue() {
        Vector3f initial = new Vector3f(0.1f, 0f, 0f);
        Vector3f current = new Vector3f(9f, 9f, 9f);
        AnimationVec3 accumulated = new AnimationVec3(0.5f, 0f, 0f);
        accumulated.setEndingTransitionPercentProgressIfLess(0f);

        Vector3f applied = BoneAnimationFolder.applyRotation(accumulated, initial, current);
        assertNotNull(applied);
        assertEquals(0.5f, applied.x, 0.02f, "progress 0 must take the accumulated rotation outright");
        assertEquals(0f, applied.y, 0.02f);
        assertEquals(0f, applied.z, 0.02f);
    }

    /** Applying an accumulated rotation never produces a non-finite bone value. */
    @Test
    void applyingAnAccumulatedRotationStaysFinite() {
        for (float value = -1f; value <= 1f; value += 0.25f) {
            Vector3f applied = BoneAnimationFolder.applyRotation(
                new Vector3f(value, -value, value * 2f),
                new Vector3f(0.3f, -0.2f, 0.1f),
                new Vector3f());
            assertNotNull(applied);
            assertTrue(
                Float.isFinite(applied.x) && Float.isFinite(applied.y) && Float.isFinite(applied.z),
                "applied rotation must stay finite, got " + applied);
        }
    }

    /**
     * A contribution keeps a null channel as null rather than substituting a neutral value, because the fold decides
     * per-channel presence from exactly that. Substituting here is what would make a rotation-only player report a
     * zero position and overwrite the bone's.
     */
    @Test
    void aContributionKeepsItsNullChannelsAsNull() {
        Contribution empty = contribution(null, null, null, 1f);
        assertNull(empty.rotation);
        assertNull(empty.position);
        assertNull(empty.scale);

        Contribution rotationOnly = contribution(new Vector3f(1f, 2f, 3f), null, null, 1f);
        assertNotNull(rotationOnly.rotation);
        assertNull(rotationOnly.position);
        assertNull(rotationOnly.scale);
    }

    /**
     * A contribution carrying all nulls drives nothing, so the fold reports nothing - a player that applies but wants
     * to change no channel is the same as no player at all.
     */
    @Test
    void aContributionDrivingNothingFoldsToNothing() {
        FoldedBone folded = BoneAnimationFolder.fold(
            Collections.singletonList(contribution(null, null, null, 1f)));
        assertNull(folded.rotation);
        assertNull(folded.position);
        assertNull(folded.scale);
    }
}

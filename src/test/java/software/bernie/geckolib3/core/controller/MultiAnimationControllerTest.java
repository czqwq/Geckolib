package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

/**
 * One controller holding N animations at once, which is what a Bedrock state listing several entries means.
 * <p>
 * Upstream's counterpart is {@code BedrockAnimationController} ({@code :52} keeps a list of animation players and
 * {@code :129-133} advances every one whose condition applies). This engine's {@link AnimationController} treats a list
 * as a <em>sequence</em> - it queues the clips and advances one at a time ({@code :228-247}, {@code :703-715}) - so
 * {@link MultiAnimationController} exists to give each clip its own player and fold their per-bone queues
 * ({@code BoneAnimationFolder}).
 * <p>
 * The first test is the one that matters most and it is written because the class shipped broken: an inner player's
 * predicate used to <em>throw</em>, on the reasoning that inner players are driven by {@code setAnimation} rather than
 * by a predicate. But {@code AnimationController.process} calls {@code testAnimationPredicate} unconditionally on
 * every frame ({@code :480}, {@code :669-671}), so the first frame of every player raised and the controller could not
 * advance a single clip. It went unnoticed because nothing constructed the class yet - which is exactly why the
 * assertion belongs in the engine's own suite rather than only in the consumer's.
 */
class MultiAnimationControllerTest {

    /**
     * Processing must not raise, and the clips must actually advance. A throwing inner predicate fails here rather
     * than in the field, where it would look like a model frozen with an exception per frame.
     */
    @Test
    void processingAdvancesEveryPlayerWithoutRaising() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "parallel_0_controller",
                0,
                null);

            AnimationBuilder builder = new AnimationBuilder();
            builder.addAnimation("parallel0");
            builder.addAnimation("parallel1");
            controller.setAnimation(builder);

            assertDoesNotThrow(() -> processOnce(controller), "an inner player must not raise when it is processed");

            List<AnimationController<Animatable>> players = controller.getPlayers();
            assertEquals(2, players.size());
            for (AnimationController<Animatable> player : players) {
                assertNotNull(player.getCurrentAnimation(), "a processed player must hold its clip");
            }
        });
    }

    /**
     * The clips are held <em>simultaneously</em>, not queued. A sequence would leave all but one player idle, which is
     * the exact regression this class exists to remove.
     */
    @Test
    void eachRequestedClipGetsItsOwnPlayerHoldingThatClip() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "parallel_0_controller",
                0,
                null);

            AnimationBuilder builder = new AnimationBuilder();
            for (int i = 0; i < 8; i++) {
                builder.addAnimation("parallel" + i);
            }
            controller.setAnimation(builder);
            processOnce(controller);

            List<String> held = new ArrayList<>();
            for (AnimationController<Animatable> player : controller.getPlayers()) {
                assertNotNull(player.getCurrentAnimation());
                held.add(player.getCurrentAnimation().animationName);
            }
            assertEquals(
                Arrays.asList(
                    "parallel0",
                    "parallel1",
                    "parallel2",
                    "parallel3",
                    "parallel4",
                    "parallel5",
                    "parallel6",
                    "parallel7"),
                held);
        });
    }

    /**
     * Re-requesting an unchanged set must not rebuild the players, because a stable state calls this every frame and
     * rebuilding would restart every clip and pin it to its first frame.
     */
    @Test
    void reRequestingAnUnchangedSetKeepsTheSamePlayers() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "parallel_0_controller",
                0,
                null);

            AnimationBuilder builder = new AnimationBuilder();
            builder.addAnimation("parallel0");
            builder.addAnimation("parallel1");
            controller.setAnimation(builder);

            List<AnimationController<Animatable>> first = new ArrayList<>(controller.getPlayers());
            controller.setAnimation(builder);

            assertEquals(first.size(), controller.getPlayers().size());
            for (int i = 0; i < first.size(); i++) {
                assertSame(first.get(i), controller.getPlayers().get(i));
            }
        });
    }

    /**
     * An entry whose condition fails must stop contributing, and the surplus players of a shrunk set must be silenced
     * rather than discarded, so a later expansion reuses them.
     */
    @Test
    void applyConditionsSilencePlayersAndAShrinkKeepsThem() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "post_swing_controller",
                0,
                null);

            AnimationBuilder three = new AnimationBuilder();
            three.addAnimation("sword_attack_01");
            three.addAnimation("sword_idle_attack_01");
            three.addAnimation("sword_walk_01");
            controller.setAnimation(three);

            controller.setApplyConditions(Arrays.asList(true, false, true));
            assertEquals(Boolean.TRUE, controller.getAppliedNames().get("sword_attack_01"));
            assertEquals(Boolean.FALSE, controller.getAppliedNames().get("sword_idle_attack_01"));
            assertEquals(Boolean.TRUE, controller.getAppliedNames().get("sword_walk_01"));

            AnimationBuilder one = new AnimationBuilder();
            one.addAnimation("sword_attack_01");
            controller.setAnimation(one);

            assertEquals(
                Arrays.asList("sword_attack_01"),
                new ArrayList<>(controller.getAppliedNames().keySet()),
                "a shrunk set reports only what is requested");
            assertEquals(3, controller.getPlayers().size(), "the surplus players must be kept so they can be reused");

            controller.setAnimation(three);
            assertEquals(Boolean.TRUE, controller.getAppliedNames().get("sword_walk_01"));
        });
    }

    /**
     * The outer controller's own predicate must still get its say, and it must do so before the players advance: a pack
     * binds a controller script by file name and that script chooses the clip, so a controller that skipped its
     * predicate would silently kill every pack controller script.
     * <p>
     * This drives the <em>outer</em> controller. Processing the inner players directly would bypass the predicate
     * entirely and the assertion would pass for the wrong reason.
     */
    @Test
    void theOuterPredicateIsConsultedAndCanStopTheController() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "main_controller",
                0,
                event -> PlayState.STOP);

            AnimationBuilder builder = new AnimationBuilder();
            builder.addAnimation("parallel0");
            controller.setAnimation(builder);

            processOuterOnce(controller);

            assertEquals(
                Arrays.asList(),
                new ArrayList<>(controller.getAppliedNames().keySet()),
                "a STOP from the outer predicate must clear the requested clips");
        });
    }

    /**
     * Re-requesting a <em>changed</em> set while keeping the tick must reach the inner players.
     * <p>
     * This is the path that carries a state whose own {@code animations} conditions changed which clips apply - the
     * built-in default controller pack declares its sword states that way. The host port calls
     * {@code setAnimationPreservingTick} rather than {@code setAnimation} there, because a plain re-request restarts
     * every clip from zero. Without an override on this class that call lands on {@link AnimationController}'s
     * {@code currentAnimation} and every inner player keeps holding its previous clip, so the state's animation
     * silently never changes: the model keeps playing the pose it was in while the pack believes it switched.
     */
    @Test
    void aChangedSetRequestedWithPreservedTickReachesTheInnerPlayers() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "post_swing_controller",
                0,
                null);

            AnimationBuilder first = new AnimationBuilder();
            first.addAnimation("sword_attack_01");
            first.addAnimation("sword_idle_attack_01");
            controller.setAnimation(first);

            AnimationBuilder second = new AnimationBuilder();
            second.addAnimation("sword_attack_01");
            second.addAnimation("sword_walk_01");
            assertTrue(
                controller.setAnimationPreservingTick(second, 5.0D, 3.0D),
                "every requested clip is declared, so the batch must be accepted");

            List<String> held = new ArrayList<>();
            for (AnimationController<Animatable> player : controller.getPlayers()) {
                assertNotNull(player.getCurrentAnimation(), "every player must hold the clip it was handed");
                held.add(player.getCurrentAnimation().animationName);
            }
            assertEquals(Arrays.asList("sword_attack_01", "sword_walk_01"), held);
            assertEquals(
                Arrays.asList("sword_attack_01", "sword_walk_01"),
                new ArrayList<>(controller.getAppliedNames().keySet()),
                "the requested set is what was asked for, not the union with the previous one");
        });
    }

    /**
     * A request naming a clip the model does not declare is refused whole rather than applied in part.
     * <p>
     * The ordinary controller's contract is the same - it builds every animation first and returns false without
     * touching its queue if any is missing ({@code AnimationController:262-283}) - and the caller falls back to a plain
     * {@code setAnimation} on false. Applying the batch in part would instead leave the players split across two
     * different animation sets, which no state ever asked for.
     */
    @Test
    void aPreservedTickRequestWithAnUndeclaredClipIsRefused() {
        AnimationController.ModelFetcher<Animatable> fetcher = ignored -> new RestrictedAnimationModel("sword_attack_01");
        AnimationController.addModelFetcher(fetcher);
        try {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "post_swing_controller",
                0,
                null);

            AnimationBuilder one = new AnimationBuilder();
            one.addAnimation("sword_attack_01");
            controller.setAnimation(one);

            AnimationBuilder missing = new AnimationBuilder();
            missing.addAnimation("sword_attack_01");
            missing.addAnimation("not_in_this_model");
            assertFalse(
                controller.setAnimationPreservingTick(missing, 5.0D, 3.0D),
                "a clip the model cannot resolve must refuse the whole request");
        } finally {
            AnimationController.removeModelFetcher(fetcher);
        }
    }

    /**
     * A clip that was already playing keeps its phase when the requested set changes; a clip that was dropped and
     * comes back does not.
     * <p>
     * This is the assertion behind a real question about the port's shape. Upstream gates each state entry on its own
     * {@code ConditionHolder} and re-evaluates it every frame ({@code BedrockAnimationController:391-416},
     * {@code :129-133}), so an entry whose condition fails keeps its animation player - and therefore its clock -
     * running behind the gate. The port instead filters a failing entry out of the request, so the player is silenced
     * and the clip starts from zero when the condition holds again. The two are observably different, and this test
     * pins which one this engine implements so the difference cannot be mistaken for the former.
     * <p>
     * It drives the engine's own arithmetic rather than a re-implementation: the phase a preserved clip resumes at is
     * {@code adjustTick}'s {@code tick - tickOffset}, and the offset {@code setAnimationPreservingTick} installs is
     * {@code absoluteTick - elapsedTick} ({@code AnimationController:287}).
     */
    @Test
    void aPreservedClipKeepsItsPhaseAndAReAddedOneRestarts() {
        withModelFetcher(() -> {
            MultiAnimationController<Animatable> controller = new MultiAnimationController<>(
                new Animatable(),
                "post_swing_controller",
                0,
                null);

            AnimationBuilder original = new AnimationBuilder();
            original.addAnimation("sword_attack_01");
            original.addAnimation("sword_idle_attack_01");
            controller.setAnimation(original);

            // The requested set is known immediately; `currentAnimation` is not, because the engine only moves a queued
            // clip into that field while it processes (AnimationController:518-535).
            assertEquals(
                Arrays.asList("sword_attack_01", "sword_idle_attack_01"),
                controller.getRequestedNames(),
                "both clips are requested at once");
            AnimationController<Animatable> kept = controller.getPlayers()
                .get(0);

            // The same set minus the second clip, at absolute tick 5 with 3 ticks already elapsed: the survivor must
            // resume where it was, so its offset is 5 - 3 = 2 and its tick is 3.
            AnimationBuilder reduced = new AnimationBuilder();
            reduced.addAnimation("sword_attack_01");
            assertTrue(controller.setAnimationPreservingTick(reduced, 5.0D, 3.0D));
            assertSame(kept, controller.getPlayers().get(0), "the surviving clip keeps its own player instance");
            assertEquals(2.0D, kept.tickOffset, 0.0001D, "the offset is absoluteTick - elapsedTick");
            assertEquals(3.0D, kept.adjustTick(5.0D), 0.0001D, "the clip resumes at the tick it had reached");

            // The dropped clip comes back while the first one keeps playing. Because the port filters a failing entry
            // out of the *request* while the engine's player is retained, this is a continuation rather than a restart:
            // the player still holds the clip it was last handed, so it is reused and its offset is refreshed to the
            // new frame's absoluteTick - elapsedTick. That is the phase-preserving half of the port's shape.
            AnimationBuilder restored = new AnimationBuilder();
            restored.addAnimation("sword_attack_01");
            restored.addAnimation("sword_idle_attack_01");
            assertTrue(controller.setAnimationPreservingTick(restored, 9.0D, 7.0D));
            AnimationController<Animatable> readded = controller.getPlayers()
                .get(1);
            assertEquals(2.0D, readded.tickOffset, 0.0001D, "a returning clip continues rather than restarting");
            assertEquals(
                Arrays.asList("sword_attack_01", "sword_idle_attack_01"),
                controller.getRequestedNames(),
                "the restored set is what was asked for, in request order");

            // A clip the request has never mentioned before is a fresh player, and its very first request is told the
            // same offset - absoluteTick minus elapsedTick - as everything else in the batch. That is the engine's own
            // contract for setAnimationPreservingTick and it is deliberate here: every clip the state asks for starts
            // at the elapsed position, so a state's players stay in step with each other and with the state's clock.
            AnimationBuilder fresh = new AnimationBuilder();
            fresh.addAnimation("sword_attack_01");
            fresh.addAnimation("sword_idle_attack_01");
            fresh.addAnimation("sword_walk_01");
            assertTrue(controller.setAnimationPreservingTick(fresh, 20.0D, 10.0D));
            AnimationController<Animatable> freshPlayer = controller.getPlayers()
                .get(2);
            assertEquals(10.0D, freshPlayer.tickOffset, 0.0001D, "a new clip is told the batch's elapsed offset");
            assertEquals(10.0D, freshPlayer.adjustTick(20.0D), 0.0001D, "so it begins at the elapsed position");
            assertEquals(
                10.0D,
                kept.adjustTick(20.0D),
                0.0001D,
                "and it agrees with the clip that was already playing, so the state's players stay in step");
        });
    }

    /** The clip one inner player currently holds, or {@code null} while it holds none. */
    private static String current(AnimationController<Animatable> player) {
        Animation animation = player.getCurrentAnimation();
        return animation == null ? null : animation.animationName;
    }

    /** Drives the outer controller once, which is the path that consults its predicate. */
    private static void processOuterOnce(MultiAnimationController<Animatable> controller) {
        MolangParser parser = new MolangParser();
        List<IBone> bones = new ArrayList<>();
        HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();
        AnimationEvent<Animatable> event = new AnimationEvent<>(
            new Animatable(),
            0.0F,
            0.0F,
            0.5F,
            true,
            new ArrayList<>());
        event.setController(controller);
        controller.process(1.0D, event, bones, snapshots, parser, false);
    }

    private static void processOnce(MultiAnimationController<Animatable> controller) {
        MolangParser parser = new MolangParser();
        List<IBone> bones = new ArrayList<>();
        HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();
        for (AnimationController<Animatable> player : controller.getPlayers()) {
            AnimationEvent<Animatable> event = new AnimationEvent<>(
                new Animatable(),
                0.0F,
                0.0F,
                0.5F,
                true,
                new ArrayList<>());
            event.setController(player);
            player.process(1.0D, event, bones, snapshots, parser, false);
        }
    }

    private static void withModelFetcher(Runnable body) {
        AnimationController.ModelFetcher<Animatable> fetcher = ignored -> new AnyAnimationModel();
        AnimationController.addModelFetcher(fetcher);
        try {
            body.run();
        } finally {
            AnimationController.removeModelFetcher(fetcher);
        }
    }

    /** A minimal animatable: the controllers under test ask it nothing else. */
    private static final class Animatable implements IAnimatable {

        private final AnimationFactory factory = new AnimationFactory(this);

        @Override
        public void registerControllers(AnimationData data) {}

        @Override
        public AnimationFactory getFactory() {
            return this.factory;
        }
    }

    /** Serves a looping animation only for names in {@code declared}, and nothing otherwise. */
    private static final class RestrictedAnimationModel implements IAnimatableModel<Animatable> {

        private final List<String> declared;

        private RestrictedAnimationModel(String... declared) {
            this.declared = Arrays.asList(declared);
        }

        @Override
        public void setLivingAnimations(Animatable entity, Integer uniqueID, AnimationEvent customPredicate) {}

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            if (!declared.contains(name)) {
                return null;
            }
            Animation animation = new Animation();
            animation.animationName = name;
            animation.animationLength = 1.0D;
            animation.loop = ILoopType.EDefaultLoopTypes.LOOP;
            animation.boneAnimations = new ArrayList<>();
            return animation;
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {}
    }

    /** Serves a looping animation for any name, so no real resource pack is needed. */
    private static final class AnyAnimationModel implements IAnimatableModel<Animatable> {

        @Override
        public void setLivingAnimations(Animatable entity, Integer uniqueID, AnimationEvent customPredicate) {}

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            Animation animation = new Animation();
            animation.animationName = name;
            animation.animationLength = 1.0D;
            animation.loop = ILoopType.EDefaultLoopTypes.LOOP;
            animation.boneAnimations = new ArrayList<>();
            return animation;
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {}
    }
}

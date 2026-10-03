package software.bernie.geckolib3.core.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector3f;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.RawAnimation;
import software.bernie.geckolib3.core.controller.transition.IBlendTransition;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.AnimationPointQueue;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.util.MathUtil;

/**
 * A controller that plays several animations at once and folds them per bone.
 * <p>
 * Why this exists. {@link AnimationController} holds one animation at a time, and its {@code setAnimation} treats the
 * list it is given as a <em>sequence</em>: it fills {@code animationQueue} and advances through it one clip at a time
 * ({@code AnimationController:228-247}, {@code :703-715}). A Bedrock state, by contrast, lists animations that all play
 * simultaneously, each weighted by its own condition; the built-in default controller pack declares exactly that for its
 * parallel axis, {@code ["parallel0" … "parallel7"]} with no conditions. Feeding those eight to the ordinary controller
 * plays them one after another, which is the wrong behaviour this class removes.
 * <p>
 * How it works. Each animation gets its own inner {@link AnimationController}, and this class folds their per-bone
 * queues into a single set through {@link BoneAnimationFolder}. The fold's arithmetic is upstream's
 * ({@code BedrockAnimationController.BlendBoneAnimationQueue:422-673}, whose three poll methods carry the comment "do
 * not casually change this lump"): rotation and position accumulate weightedly, scale multiplies with each contribution
 * de-weighted towards identity.
 * <p>
 * Why a sibling rather than a change to {@link AnimationController}. That class has about twenty interlocking fields,
 * five of them public and written from four call sites outside it, and the fold lives in a different class
 * ({@code AnimationProcessor:82-158}) - so generalising it in place would force every external reference to be
 * re-answered at once, against roughly thirty controllers per model, with silent mis-posing as the failure mode. This
 * subclass leaves that class byte-identical for every existing caller and is opt-in per controller name.
 * <p>
 * A note on the engine's existing additive rule. {@code AnimationProcessor:114-123} adds a controller's rotation into an
 * accumulating value when the controller's name starts with {@code parallel_}, and overwrites otherwise. This class
 * folds its own players and then hands the processor <em>one</em> value per bone, so it participates as a single
 * controller and does not multiply that accumulation. A model relying on the {@code parallel_} naming convention should
 * keep using the ordinary controller.
 *
 * @param <T> the animatable type
 */
public class MultiAnimationController<T extends IAnimatable> extends AnimationController<T> {

    /** One inner player: the clip it plays, whether it applies this frame, and the controller that advances it. */
    private final class Player {
        private final AnimationController<T> controller;
        private boolean applies = true;

        private Player(AnimationController<T> controller) {
            this.controller = controller;
        }
    }

    private final List<Player> players = new ArrayList<>();
    /** The animations the inner players were last handed, so a rebuild can drop those that still hold one. */
    private final LinkedList<String> innerAnimations = new LinkedList<>();
    /** The animation names this controller is currently playing, in the order they were requested. */
    private List<String> requestedNames = new ArrayList<>();

    public MultiAnimationController(T animatable, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate) {
        super(animatable, name, transitionLengthTicks, animationPredicate);
    }

    /**
     * Accepts a builder whose animations all play at once.
     * <p>
     * Deliberately does <em>not</em> call {@code super.setAnimation}, because that would queue the clips as a sequence.
     * Instead each clip gets its own inner player, so the ordinary controller's queue-and-advance logic runs per clip
     * rather than across clips. Re-requesting the same set is a no-op, so a controller whose state is stable does not
     * restart its clips every frame - the same idempotence {@code AnimationController.setAnimation} provides for one
     * animation.
     */
    @Override
    public void setAnimation(AnimationBuilder builder) {
        List<String> names = new ArrayList<>();
        if (builder != null) {
            for (RawAnimation raw : builder.getRawAnimationList()) {
                if (raw != null && raw.animationName != null && !raw.animationName.isEmpty()) {
                    names.add(raw.animationName);
                }
            }
        }
        if (names.equals(requestedNames)) {
            return;
        }
        requestedNames = names;
        applyRequested();
    }

    /**
     * Hands each requested clip to its own inner player, growing the player list to fit.
     * <p>
     * A shrunk state keeps its players and silences the surplus, so a later re-expansion does not rebuild them from
     * scratch.
     */
    private void applyRequested() {
        while (players.size() < requestedNames.size()) {
            players.add(new Player(newInnerPlayer(players.size())));
        }
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            if (i < requestedNames.size()) {
                player.applies = true;
                player.controller.setAnimation(new AnimationBuilder().addAnimation(requestedNames.get(i)));
            } else {
                player.applies = false;
                player.controller.setAnimation(new AnimationBuilder());
            }
        }
        innerAnimations.clear();
        innerAnimations.addAll(requestedNames);
    }

    /**
     * Re-requests a clip while keeping each of them at the phase they have already reached.
     * <p>
     * This exists because {@link AnimationController#setAnimationPreservingTick} is the ordinary controller's
     * one-animation answer to "the state did not change but the clip it plays did", and a plain {@code setAnimation}
     * restarts every clip from zero. The host port calls it exactly when a state's own {@code animations} conditions
     * change which clips apply - a shape the built-in default controller pack declares for its sword states - and
     * without an override the call would land on {@link AnimationController}'s {@code currentAnimation} and leave every
     * inner player holding its previous clip, so the state's animation would silently never change.
     * <p>
     * A rebuilt inner player is kept only while its animation is unchanged <em>and</em> still requested; that is the
     * same rule the ordinary controller applies to the animation it holds, and it also answers what the caller's
     * {@code elapsedTick} is relative to - the clip the player was already running.
     * <p>
     * The tick offset is handed to each player's {@link AnimationController#adjustTick} as absolute minus elapsed, the
     * same arithmetic the ordinary controller does, so the two paths continue a clip at the same phase.
     *
     * @return whether every requested clip was accepted, which is the ordinary controller's contract as well
     */
    @Override
    public boolean setAnimationPreservingTick(AnimationBuilder builder, double absoluteTick, double elapsedTick) {
        List<String> names = namesOf(builder);
        if (names.isEmpty()) {
            return false;
        }

        // A player is reused only while the animation it was last handed is still requested. The key is the requested
        // name rather than `getCurrentAnimation()`, and that distinction is load-bearing: an inner player holds nothing
        // in `currentAnimation` until its first process pass moves the queued clip in
        // (AnimationController:518-535), so keying on it would fail to reuse exactly the players that have a phase
        // worth preserving. The first version of this method did key on it, and the engine's own test caught it.
        Set<String> wanted = new HashSet<>(names);
        Map<String, Player> reusable = new HashMap<>();
        for (int i = 0; i < players.size() && i < innerAnimations.size(); i++) {
            Player candidate = players.get(i);
            String lastRequested = innerAnimations.get(i);
            if (lastRequested != null && wanted.contains(lastRequested)) {
                reusable.putIfAbsent(lastRequested, candidate);
            }
        }

        List<Player> rebuilt = new ArrayList<>(names.size());
        for (String name : names) {
            Player player = reusable.remove(name);
            if (player == null) {
                player = new Player(newInnerPlayer(rebuilt.size()));
            } else {
                // Carry the clip's own history into the continuation, which is what makes the preserved tick mean
                // anything: setAnimationPreservingTick decides whether the request is a reload from the player's
                // current animation, or from its last request when none has been processed yet, and it is that state
                // the engine's own setAnimation guard consults too (AnimationController:248-261).
                Animation held = player.controller.getCurrentAnimation();
                player.controller.currentAnimationBuilder = new AnimationBuilder().addAnimation(
                    held != null ? held.animationName : name);
            }
            if (!player.controller.setAnimationPreservingTick(
                new AnimationBuilder().addAnimation(name),
                absoluteTick,
                elapsedTick)) {
                // The model does not declare this clip. The caller's batch is not applied, so the previous one stays in
                // force rather than leaving the players split across two different animation sets.
                return false;
            }
            player.applies = true;
            rebuilt.add(player);
        }

        players.clear();
        players.addAll(rebuilt);
        requestedNames = names;
        innerAnimations.clear();
        innerAnimations.addAll(names);
        return true;
    }

    private static List<String> namesOf(AnimationBuilder builder) {
        List<String> names = new ArrayList<>();
        if (builder == null) {
            return names;
        }
        for (RawAnimation raw : builder.getRawAnimationList()) {
            if (raw != null && raw.animationName != null && !raw.animationName.isEmpty()) {
                names.add(raw.animationName);
            }
        }
        return names;
    }

    /**
     * Builds one inner player.
     * <p>
     * The predicate answers {@link PlayState#CONTINUE} and nothing else, because an inner player's job is to advance
     * the one clip it was given - which clips play at all, and which of them apply, is decided by {@link #setAnimation}
     * and {@link #setApplyConditions} on the outer controller, mirroring upstream, where an inner
     * {@code AnimationPlayerHolder} has no predicate at all and is driven straight from the state's animation list
     * ({@code BedrockAnimationController:52}, {@code :129-133}).
     * <p>
     * It must not throw. {@code AnimationController.process} calls {@code testAnimationPredicate} on every frame
     * unconditionally ({@code AnimationController:480}), so a throwing predicate does not merely go unused - it kills
     * every inner player on its first frame, which is to say the whole class never worked. It must not ask the model
     * again either: doing so would re-enter the outer controller's predicate once per clip and let a pack script choose
     * a different clip per player.
     */
    private AnimationController<T> newInnerPlayer(int index) {
        return new AnimationController<T>(
            this.animatable,
            getName() + "#" + index,
            (float) this.transitionLengthTicks,
            (IAnimationPredicate<T>) event -> PlayState.CONTINUE);
    }

    /**
     * Marks which of the currently requested animations actually apply this frame, so a state entry whose condition is
     * false contributes nothing - upstream's {@code ConditionHolder} ({@code BedrockAnimationController:391-416}).
     * <p>
     * This is <em>not</em> the blend weight, and the two are not interchangeable. A condition is a boolean gate, so a
     * failing one means the entry does not contribute at all; the numeric contribution is the animation's own
     * {@code blend_weight}, which lives on the {@code Animation} and is read by the fold below
     * ({@code AnimationPlayer:318}, {@code :346}, {@code :365}). Keeping them separate is upstream's shape: upstream
     * never passes a weight in from outside, it evaluates {@code currentAnim.blendWeight} where the animation is
     * already in hand.
     */
    public void setApplyConditions(List<Boolean> applies) {
        for (int i = 0; i < players.size(); i++) {
            players.get(i).applies = i < applies.size() && Boolean.TRUE.equals(applies.get(i));
        }
    }

    /** The animation names this controller is playing, in request order. */
    public List<String> getRequestedNames() {
        return new ArrayList<>(requestedNames);
    }

    @Override
    public void process(double tick, AnimationEvent<T> event, List<IBone> modelRendererList,
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection, MolangParser parser,
        boolean crashWhenCantFindBone) {
        // Each player advances its own clock and fills its own queues. The predicate is not consulted: this
        // controller's animation set comes from setAnimation and which of them apply from setApplyConditions.
        // The predicate still gets its say, and it must: a pack binds a controller script by file name and that script
        // decides which clip plays (the port evaluates it in AnimationManager.predicateWithPackScript, upstream in
        // CodedAnimationController:65-72,124-134). Skipping the predicate here would have silently killed every pack
        // controller script - including the ctrl.set_animation(...) calls they use to choose their own clip - because
        // this class otherwise drives its players straight from setAnimation. The predicate is therefore consulted
        // first and, when it names a clip, the set of clips played becomes that one.
        if (this.animationPredicate != null) {
            event.setController(this);
            PlayState state = testAnimationPredicate(event);
            if (state == PlayState.STOP) {
                setAnimation(null);
            }
        }

        for (Player player : players) {
            player.controller
                .process(tick, event, modelRendererList, boneSnapshotCollection, parser, crashWhenCantFindBone);
        }
        foldIntoOwnQueues(modelRendererList);
    }

    /**
     * Folds every player's per-bone queues into this controller's own queues, which is the single set
     * {@code AnimationProcessor} reads ({@code :82}).
     */
    private void foldIntoOwnQueues(List<IBone> modelRendererList) {
        Map<String, BoneAnimationQueue> own = getBoneAnimationQueues();
        for (IBone bone : modelRendererList) {
            BoneAnimationQueue target = own.get(bone.getName());
            if (target == null) {
                continue;
            }
            clear(target);

            List<BoneAnimationFolder.Contribution> contributions = new ArrayList<>();
            for (Player player : players) {
                if (!player.applies) {
                    continue;
                }
                BoneAnimationQueue queue = player.controller.getBoneAnimationQueues().get(bone.getName());
                if (queue == null) {
                    continue;
                }
                contributions.add(
                    new BoneAnimationFolder.Contribution(
                        readTriple(queue.rotationXQueue.peek(), queue.rotationYQueue.peek(), queue.rotationZQueue.peek()),
                        readTriple(
                            queue.positionXQueue.peek(),
                            queue.positionYQueue.peek(),
                            queue.positionZQueue.peek()),
                        readTriple(queue.scaleXQueue.peek(), queue.scaleYQueue.peek(), queue.scaleZQueue.peek()),
                        // The weight the inner player put on this queue when it filled it, which is that animation's
                        // own blend_weight (AnimationController.blendWeightOf, upstream AnimationPlayer:322/:348/:368)
                        // and 1 when it declares none. Reading it from the queue rather than passing it in keeps the
                        // weight attached to the values it weights.
                        queue.getBlendWeight()));
            }

            BoneAnimationFolder.FoldedBone folded = BoneAnimationFolder.fold(contributions);
            addComponent(target.rotationXQueue, folded.rotation, 0);
            addComponent(target.rotationYQueue, folded.rotation, 1);
            addComponent(target.rotationZQueue, folded.rotation, 2);
            addComponent(target.positionXQueue, folded.position, 0);
            addComponent(target.positionYQueue, folded.position, 1);
            addComponent(target.positionZQueue, folded.position, 2);
            addComponent(target.scaleXQueue, folded.scale, 0);
            addComponent(target.scaleYQueue, folded.scale, 1);
            addComponent(target.scaleZQueue, folded.scale, 2);
        }
    }

    private static void clear(BoneAnimationQueue queue) {
        queue.rotationXQueue.clear();
        queue.rotationYQueue.clear();
        queue.rotationZQueue.clear();
        queue.positionXQueue.clear();
        queue.positionYQueue.clear();
        queue.positionZQueue.clear();
        queue.scaleXQueue.clear();
        queue.scaleYQueue.clear();
        queue.scaleZQueue.clear();
    }

    /**
     * Reads one vector from three already-lerped points, or {@code null} when any is missing.
     * <p>
     * The players' queues hold {@link AnimationPoint}s that {@code AnimationProcessor} would normally lerp itself. This
     * fold needs the numbers, so it lerps them here with the same call the processor uses, which keeps one definition of
     * what a point evaluates to.
     */
    private Vector3f readTriple(AnimationPoint x, AnimationPoint y, AnimationPoint z) {
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Vector3f(lerp(x), lerp(y), lerp(z));
    }

    private float lerp(AnimationPoint point) {
        return MathUtil.lerpValues(point, this.easingType, this.customEasingMethod);
    }

    /**
     * Adds one component of a folded vector to a queue as a constant point, so the processor's own lerp leaves it
     * unchanged. Constant points are used because the folding has already resolved every value.
     */
    private static void addComponent(AnimationPointQueue queue, Vector3f value, int component) {
        if (value == null) {
            return;
        }
        float componentValue = component == 0 ? value.x : component == 1 ? value.y : value.z;
        queue.add(new AnimationPoint(null, 0.0d, 0.0d, componentValue, componentValue));
    }

    /**
     * Hands the transition's shape to every inner player, so all of them interpolate the same way.
     * <p>
     * Upstream gives each player the state's own transition object when it starts it
     * ({@code BedrockAnimationController:320}, {@code holder.animationPlayer().setBeginningTransition(newState
     * .blendTransition().startNew())}), which is why one state's {@code blend_transition} curve applies to every clip
     * the state plays rather than only the first. Without this the outer controller would carry the curve while the
     * players that actually produce the bone values ramped linearly.
     */
    @Override
    public void setBlendTransition(double lengthTicks, IBlendTransition transition) {
        super.setBlendTransition(lengthTicks, transition);
        for (Player player : players) {
            player.controller.setBlendTransition(lengthTicks, transition);
        }
    }

    /** The state this controller reports, derived from whether any applying player is still running. */
    @Override
    public AnimationState getAnimationState() {
        for (Player player : players) {
            if (player.applies && player.controller.getAnimationState() != AnimationState.Stopped) {
                return AnimationState.Running;
            }
        }
        return AnimationState.Stopped;
    }

    /** The controllers behind this one, for a caller that needs to inspect or clear them. */
    public List<AnimationController<T>> getPlayers() {
        List<AnimationController<T>> result = new ArrayList<>();
        for (Player player : players) {
            result.add(player.controller);
        }
        return result;
    }

    /** The requested name to whether it currently applies, in request order. */
    public Map<String, Boolean> getAppliedNames() {
        Map<String, Boolean> applied = new LinkedHashMap<>();
        for (int i = 0; i < requestedNames.size(); i++) {
            applied.put(requestedNames.get(i), i < players.size() && players.get(i).applies);
        }
        return applied;
    }
}

/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */
// TODO AnimationController 尚未检查完
package software.bernie.geckolib3.core.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.controller.transition.IBlendTransition;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.event.CustomInstructionKeyframeEvent;
import software.bernie.geckolib3.core.event.ParticleKeyFrameEvent;
import software.bernie.geckolib3.core.event.SoundKeyframeEvent;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrameLocation;
import software.bernie.geckolib3.core.keyframe.ParticleEventKeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.util.Axis;

/**
 * The type Animation controller.
 *
 * @param <T> the type parameter
 */
public class AnimationController<T extends IAnimatable> {

    static List<ModelFetcher<?>> modelFetchers = new ArrayList<>();
    /**
     * The Entity.
     */
    protected T animatable;
    /**
     * The animation predicate, is tested in every process call (i.e. every frame)
     */
    protected IAnimationPredicate<T> animationPredicate;

    /**
     * The name of the animation controller
     */
    private final String name;

    protected AnimationState animationState = AnimationState.Stopped;

    /**
     * How long it takes to transition between animations
     */
    public double transitionLengthTicks;
    /**
     * The shape a transition follows over its length, or {@code null} for this engine's plain linear ramp.
     * <p>
     * A Bedrock pack may declare a transition as a {@code time -> weight} curve rather than a length, and upstream
     * builds a different implementation per form ({@code geckolib3/core/controller/transition/}, chosen in
     * {@code BlendTransition.Adapter:31-39}). The engine's length is a scalar, so the shape is carried beside it and
     * read when the transition's points are built - see {@link #setBlendTransition} and {@link #transitionPoint}.
     * <p>
     * {@code null} means "behave exactly as before", which is the case for every caller that has not declared a curve,
     * so this changes no existing behaviour.
     */
    protected IBlendTransition blendTransition;

    /**
     * Sets how long a transition lasts and the shape it follows, from one place.
     * <p>
     * The two are set together rather than separately because they describe one transition: a curve whose own length
     * disagrees with {@link #transitionLengthTicks} would interpolate over a span the controller has already left, and
     * the engine decides when a transition has ended from the length alone
     * ({@code tick >= transitionLengthTicks}). A {@code null} transition clears the shape, which is upstream's outcome
     * for a state that declares no {@code blend_transition} at all.
     *
     * @param lengthTicks how long the transition lasts, in ticks; a negative value leaves it unchanged
     * @param transition  the curve to follow, or {@code null} for this engine's linear ramp
     */
    public void setBlendTransition(double lengthTicks, IBlendTransition transition) {
        if (lengthTicks >= 0) {
            this.transitionLengthTicks = lengthTicks;
        }
        this.blendTransition = transition;
    }

    /**
     * One point of a transition: the value the bone already held, the value the incoming animation starts at, and the
     * length and shape this controller is currently transitioning with.
     * <p>
     * Built here rather than inline at each of the nine queue sites so the curve cannot reach some channels and not
     * others - a rotation that followed the pack's curve while its position ramped linearly is a mis-pose nobody can
     * attribute from the outside.
     * <p>
     * <b>The curve is applied here, by remapping the tick - not inside {@code MathUtil}.</b> Upstream keeps the two
     * apart: its keyframe lerp divides {@code currentTick / animationEndTick} linearly
     * (`LgeacyYSM-1.20.1-forge` {@code MathUtil.java:25,30}), while a transition's shape is consulted by the
     * transition point itself - {@code BeginningTransitionPoint.getLerpPoint} reads
     * {@code beginningTransition.get(transitionTicks)} and passes the fraction in as an argument
     * ({@code YesSteveModel-dev-1.20} {@code AnimationPlayer.java:319}, {@code BeginningTransitionPoint.java}).
     * The port previously consulted the curve from {@code MathUtil.lerpValues(AnimationPoint, ...)} through
     * {@link software.bernie.geckolib3.core.keyframe.AnimationPoint#percentCompleted()}, which put a transition-only
     * shape on the shared path that every animation's keyframes also travel - and a {@code blend_transition} is
     * exactly what packs declare on their walk and run states.
     * <p>
     * Remapping the tick keeps the curve on all nine channels while leaving the shared lerp linear, so the point's own
     * interpolation reproduces the shaped value: for a point spanning {@code [0, length]} the linear fraction is
     * {@code tick / length}, so a tick of {@code curve(tick) * length} yields {@code curve(tick)}.
     */
    private AnimationPoint transitionPoint(double tick, double from, Double to) {
        // The curve is applied by remapping the tick, not by leaving it on the point for MathUtil to read - see
        // MathUtil.shapedTransitionTick for why, and for the two references that fix the placement.
        return new AnimationPoint(
            null,
            MathUtil.shapedTransitionTick(tick, transitionLengthTicks, blendTransition),
            transitionLengthTicks,
            from,
            to,
            null);
    }

    /**
     * The sound listener is called every time a sound keyframe is encountered (i.e.
     * every frame)
     */
    private ISoundListener<T> soundListener;

    /**
     * The particle listener is called every time a particle keyframe is encountered
     * (i.e. every frame)
     */
    private IParticleListener<T> particleListener;

    /**
     * The custom instruction listener is called every time a custom instruction
     * keyframe is encountered (i.e. every frame)
     */
    private ICustomInstructionListener<T> customInstructionListener;
    public List emitters;
    public boolean isJustStarting = false;
    public int particleUpdatesPerSecond = 20;

    public static void addModelFetcher(ModelFetcher<?> fetcher) {
        modelFetchers.add(fetcher);
    }

    public static void removeModelFetcher(ModelFetcher<?> fetcher) {
        Objects.requireNonNull(fetcher);
        modelFetchers.remove(fetcher);
    }

    /**
     * An AnimationPredicate is run every render frame for ever AnimationController.
     * The "test" method is where you should change animations, stop animations,
     * restart, etc.
     */
    @FunctionalInterface
    public interface IAnimationPredicate<P extends IAnimatable> {

        /**
         * An AnimationPredicate is run every render frame for ever AnimationController.
         * The "test" method is where you should change animations, stop animations,
         * restart, etc.
         *
         * @return CONTINUE if the animation should continue, STOP if it should stop.
         */
        PlayState test(AnimationEvent<P> event);
    }

    /**
     * Sound Listeners are run when a sound keyframe is hit. You can either return
     * the SoundEvent and geckolib will play the sound for you, or return null and
     * handle the sounds yourself.
     */
    @FunctionalInterface
    public interface ISoundListener<A extends IAnimatable> {

        /**
         * Sound Listeners are run when a sound keyframe is hit. You can either return
         * the SoundEvent and geckolib will play the sound for you, or return null and
         * handle the sounds yourself.
         */
        void playSound(SoundKeyframeEvent<A> event);
    }

    /**
     * Particle Listeners are run when a sound keyframe is hit. You need to handle
     * the actual playing of the particle yourself.
     */
    @FunctionalInterface
    public interface IParticleListener<A extends IAnimatable> {

        /**
         * Particle Listeners are run when a sound keyframe is hit. You need to handle
         * the actual playing of the particle yourself.
         */
        void summonParticle(ParticleKeyFrameEvent<A> event);
    }

    /**
     * Custom instructions can be added in blockbench by enabling animation effects
     * in Animation - Animate Effects. You can then add custom instruction keyframes
     * and use them as timecodes/events to handle in code.
     */
    @FunctionalInterface
    public interface ICustomInstructionListener<A extends IAnimatable> {

        /**
         * Custom instructions can be added in blockbench by enabling animation effects
         * in Animation - Animate Effects. You can then add custom instruction keyframes
         * and use them as timecodes/events to handle in code.
         */
        void executeInstruction(CustomInstructionKeyframeEvent<A> event);
    }

    private final HashMap<String, BoneAnimationQueue> boneAnimationQueues = new HashMap<>();
    private final List<BoneAnimationQueue> activeBoneAnimationQueues = new ArrayList<>();
    public double tickOffset;
    protected Queue<Animation> animationQueue = new LinkedList<>();
    public Animation currentAnimation;
    public AnimationBuilder currentAnimationBuilder = new AnimationBuilder();
    public boolean shouldResetTick = false;
    private final HashMap<String, BoneSnapshot> boneSnapshots = new HashMap<>();
    private boolean justStopped = false;
    protected boolean justStartedTransition = false;
    /**
     * The tick a {@link PlayState#PAUSE} began at, or {@code NaN} when the controller is not paused; see
     * {@code process}.
     */
    private double pausedTick = Double.NaN;
    public Function<Double, Double> customEasingMethod;
    protected boolean needsAnimationReload = false;
    public double animationSpeed = 1D;
    private final Set<EventKeyFrame<?>> executedKeyFrames = new HashSet<>();

    /**
     * This method sets the current animation with an animation builder. You can run
     * this method every frame, if you pass in the same animation builder every
     * time, it won't restart. Additionally, it smoothly transitions between
     * animation states.
     */
    public void setAnimation(AnimationBuilder builder) {
        /// ADDED
        if (builder != null && !builder.getRawAnimationList()
            .isEmpty()) {
            if (builder.getRawAnimationList()
                .equals(this.currentAnimationBuilder.getRawAnimationList()) && !this.needsAnimationReload) {
                if (builder.getRawAnimationList()
                    .get(
                        builder.getRawAnimationList()
                            .size() - 1).loopType
                    == ILoopType.EDefaultLoopTypes.LOOP && currentAnimation == null) {
                    needsAnimationReload = true;
                }
            }
        }
        /// END ADDED
        IAnimatableModel<T> model = getModel(this.animatable);
        if (model != null) {
            if (builder == null || builder.getRawAnimationList()
                .size() == 0) {
                animationState = AnimationState.Stopped;
            } else if (!builder.getRawAnimationList()
                .equals(currentAnimationBuilder.getRawAnimationList()) || needsAnimationReload) {
                    AtomicBoolean encounteredError = new AtomicBoolean(false);
                    // Convert the list of animation names to the actual list, keeping track of the
                    // loop boolean along the way
                    LinkedList<Animation> animations = builder.getRawAnimationList()
                        .stream()
                        .map((rawAnimation) -> {
                            Animation animation = model.getAnimation(rawAnimation.animationName, animatable);
                            if (animation == null) {
                                System.out
                                    .printf("Could not load animation: %s. Is it missing?", rawAnimation.animationName);
                                encounteredError.set(true);
                            }
                            if (animation != null && rawAnimation.loopType != null) {
                                animation.loop = rawAnimation.loopType;
                            }
                            return animation;
                        })
                        .collect(Collectors.toCollection(LinkedList::new));

                    if (encounteredError.get()) {
                        return;
                    } else {
                        animationQueue = animations;
                    }
                    currentAnimationBuilder = builder;

                    // Reset the adjusted tick to 0 on next animation process call
                    shouldResetTick = true;
                    this.animationState = AnimationState.Transitioning;
                    justStartedTransition = true;
                    needsAnimationReload = false;
                }
        }
    }

    public boolean setAnimationPreservingTick(AnimationBuilder builder, double absoluteTick, double elapsedTick) {
        IAnimatableModel<T> model = getModel(this.animatable);
        if (model == null || builder == null || builder.getRawAnimationList()
            .isEmpty()) {
            return false;
        }
        AtomicBoolean encounteredError = new AtomicBoolean(false);
        LinkedList<Animation> animations = builder.getRawAnimationList()
            .stream()
            .map((rawAnimation) -> {
                Animation animation = model.getAnimation(rawAnimation.animationName, animatable);
                if (animation == null) {
                    System.out.printf("Could not load animation: %s. Is it missing?", rawAnimation.animationName);
                    encounteredError.set(true);
                }
                if (animation != null && rawAnimation.loopType != null) {
                    animation.loop = rawAnimation.loopType;
                }
                return animation;
            })
            .collect(Collectors.toCollection(LinkedList::new));
        if (encounteredError.get() || animations.isEmpty()) {
            return false;
        }
        this.animationQueue = animations;
        this.currentAnimationBuilder = builder;
        this.currentAnimation = this.animationQueue.poll();
        this.tickOffset = absoluteTick - Math.max(0.0D, elapsedTick);
        this.shouldResetTick = false;
        this.animationState = AnimationState.Running;
        this.justStartedTransition = false;
        this.justStopped = false;
        this.needsAnimationReload = false;
        resetEventKeyFrames();
        return this.currentAnimation != null;
    }

    /**
     * By default Geckolib uses the easing types of every keyframe. If you want to
     * override that for an entire AnimationController, change this value.
     */
    public EasingType easingType = EasingType.NONE;

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     * @param easingtype            The method of easing to use. The other
     *                              constructor defaults to no easing.
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks, EasingType easingtype,
        IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.easingType = easingtype;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     * @param customEasingMethod    If you want to use an easing method that's not
     *                              included in the EasingType enum, pass your
     *                              method into here. The parameter that's passed in
     *                              will be a number between 0 and 1. Return a
     *                              number also within 0 and 1. Take a look at
     *                              {@link EasingManager}
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks,
        Function<Double, Double> customEasingMethod, IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.customEasingMethod = customEasingMethod;
        this.easingType = EasingType.CUSTOM;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Gets the controller's name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the current animation. Can be null
     *
     * @return the current animation
     */

    public Animation getCurrentAnimation() {
        return currentAnimation;
    }

    /**
     * Returns the current state of this animation controller.
     */
    public AnimationState getAnimationState() {
        return animationState;
    }

    /**
     * Gets the current animation's bone animation queues.
     *
     * @return the bone animation queues
     */
    public HashMap<String, BoneAnimationQueue> getBoneAnimationQueues() {
        return boneAnimationQueues;
    }

    public List<BoneAnimationQueue> getActiveBoneAnimationQueues() {
        return activeBoneAnimationQueues;
    }

    /**
     * Registers a sound listener.
     */
    public void registerSoundListener(ISoundListener<T> soundListener) {
        this.soundListener = soundListener;
    }

    /**
     * Registers a particle listener.
     */
    public void registerParticleListener(IParticleListener<T> particleListener) {
        this.particleListener = particleListener;
    }

    /**
     * Registers a custom instruction listener.
     */
    public void registerCustomInstructionListener(ICustomInstructionListener<T> customInstructionListener) {
        this.customInstructionListener = customInstructionListener;
    }

    /**
     * This method is called every frame in order to populate the animation point
     * queues, and process animation state logic.
     *
     * @param tick                   The current tick + partial tick
     * @param event                  The animation test event
     * @param modelRendererList      The list of all AnimatedModelRender's
     * @param boneSnapshotCollection The bone snapshot collection
     */
    public void process(double tick, AnimationEvent<T> event, List<IBone> modelRendererList,
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection, MolangParser parser,
        boolean crashWhenCantFindBone) {
        parser.setValue("query.life_time", tick / 20);
        if (currentAnimation != null) {
            IAnimatableModel<T> model = getModel(this.animatable);
            if (model != null) {
                Animation animation = model.getAnimation(currentAnimation.animationName, this.animatable);
                if (animation != null) {
                    ILoopType loop = currentAnimation.loop;
                    currentAnimation = animation;
                    currentAnimation.loop = loop;
                }
            }
        }

        createInitialQueues(modelRendererList);

        double actualTick = tick;
        tick = adjustTick(tick);

        // Transition period has ended, reset the tick and set the animation to running
        if (animationState == AnimationState.Transitioning && tick >= transitionLengthTicks) {
            this.shouldResetTick = true;
            animationState = AnimationState.Running;
            tick = adjustTick(actualTick);
        }

        assert tick >= 0 : "GeckoLib: Tick was less than zero";

        // This tests the animation predicate
        PlayState playState = this.testAnimationPredicate(event);
        if (playState == PlayState.STOP || (currentAnimation == null && animationQueue.size() == 0)) {
            // The animation should transition to the model's initial state
            animationState = AnimationState.Stopped;
            justStopped = true;
            return;
        }
        if (justStartedTransition && (shouldResetTick || justStopped)) {
            justStopped = false;
            tick = adjustTick(actualTick);
        } else if (currentAnimation == null && this.animationQueue.size() != 0) {
            this.shouldResetTick = true;
            this.animationState = AnimationState.Transitioning;
            justStartedTransition = true;
            needsAnimationReload = false;
            tick = adjustTick(actualTick);
        } else {
            if (animationState != AnimationState.Transitioning) {
                animationState = AnimationState.Running;
            }
        }

        // A-07: while the predicate asks to hold this frame, keep evaluating at the tick the pause began. Rebuilding
        // the queues from that same tick applies exactly the values the bones already carry, so the pose neither
        // advances nor drifts back to rest - which is what "hold the hand pose while that hand swings" means. Upstream
        // holds it a different way, by discarding the queued bone animation for the frame
        // (geckolib3/core/controller/CodedAnimationController.java:87-91); this port has no such separate queue, so it
        // stops the clock instead. A controller with nothing playing has nothing to hold, and that case stopped above.
        if (playState == PlayState.PAUSE) {
            if (Double.isNaN(pausedTick)) {
                pausedTick = tick;
            }
            tick = pausedTick;
        } else {
            pausedTick = Double.NaN;
        }

        // Handle transitioning to a different animation (or just starting one)
        if (animationState == AnimationState.Transitioning) {
            // Just started transitioning, so set the current animation to the first one
            if (tick == 0 || isJustStarting) {
                justStartedTransition = false;
                // A controller can be processed more than once at the same tick: a host that draws one entity
                // through two render paths - YSMU draws the local player in the world and again in the HUD, and both
                // share this animation data - reaches this block twice with the same tick. The first pass drains
                // this queue, and polling it a second time would assign null over the animation that is
                // transitioning. setAnimation's loop guard reads that very field, answers `needsAnimationReload`,
                // and restarts the transition; since the clock is reset on every restart it can never advance past
                // transitionLengthTicks, so the controller stays in Transitioning forever and the model is frozen on
                // the transition's first frame. Only take an animation when there is one to take.
                if (!animationQueue.isEmpty()) {
                    this.currentAnimation = animationQueue.poll();
                    resetEventKeyFrames();
                    saveSnapshotsForAnimation(currentAnimation, boneSnapshotCollection);
                }
            }
            if (currentAnimation != null) {
                setAnimTime(parser, 0);
                for (BoneAnimation boneAnimation : currentAnimation.boneAnimations) {
                    BoneAnimationQueue boneAnimationQueue = boneAnimationQueues.get(boneAnimation.boneName);
                    BoneSnapshot boneSnapshot = this.boneSnapshots.get(boneAnimation.boneName);
                    Optional<IBone> first = modelRendererList.stream()
                        .filter(
                            x -> x.getName()
                                .equals(boneAnimation.boneName))
                        .findFirst();
                    if (!first.isPresent()) {
                        if (crashWhenCantFindBone) {
                            throw new RuntimeException("Could not find bone: " + boneAnimation.boneName);
                        } else {
                            continue;
                        }
                    }
                    markActiveBoneAnimationQueue(boneAnimationQueue);
                    // The animation's own contribution weight travels with the values it weights, so a bone's rotation
                    // and its weight cannot come from different players. Upstream sets it at the same point in each of
                    // its three paths (AnimationPlayer:322, :348, :368) from the animation's blend_weight, falling back
                    // to 1 when it declares none (:318).
                    boneAnimationQueue.setBlendWeight(blendWeightOf(currentAnimation));
                    BoneSnapshot initialSnapshot = first.get()
                        .getInitialSnapshot();
                    assert boneSnapshot != null : "Bone snapshot was null";

                    VectorKeyFrameList<KeyFrame<IValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
                    VectorKeyFrameList<KeyFrame<IValue>> positionKeyFrames = boneAnimation.positionKeyFrames;
                    VectorKeyFrameList<KeyFrame<IValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

                    // Adding the initial positions of the upcoming animation, so the model
                    // transitions to the initial state of the new animation
                    if (!rotationKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(rotationKeyFrames.xKeyFrames, 0, true, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(rotationKeyFrames.yKeyFrames, 0, true, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(rotationKeyFrames.zKeyFrames, 0, true, Axis.Z);
                        boneAnimationQueue.rotationXQueue.add(
                            transitionPoint(
                                tick,
                                boneSnapshot.rotationValueX - initialSnapshot.rotationValueX,
                                xPoint.animationStartValue));
                        boneAnimationQueue.rotationYQueue.add(
                            transitionPoint(
                                tick,
                                boneSnapshot.rotationValueY - initialSnapshot.rotationValueY,
                                yPoint.animationStartValue));
                        boneAnimationQueue.rotationZQueue.add(
                            transitionPoint(
                                tick,
                                boneSnapshot.rotationValueZ - initialSnapshot.rotationValueZ,
                                zPoint.animationStartValue));
                    }

                    if (!positionKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(positionKeyFrames.xKeyFrames, 0, false, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(positionKeyFrames.yKeyFrames, 0, false, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(positionKeyFrames.zKeyFrames, 0, false, Axis.Z);
                        boneAnimationQueue.positionXQueue.add(
                            transitionPoint(tick, boneSnapshot.positionOffsetX, xPoint.animationStartValue));
                        boneAnimationQueue.positionYQueue.add(
                            transitionPoint(tick, boneSnapshot.positionOffsetY, yPoint.animationStartValue));
                        boneAnimationQueue.positionZQueue.add(
                            transitionPoint(tick, boneSnapshot.positionOffsetZ, zPoint.animationStartValue));
                    }

                    if (!scaleKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(scaleKeyFrames.xKeyFrames, 0, false, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(scaleKeyFrames.yKeyFrames, 0, false, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(scaleKeyFrames.zKeyFrames, 0, false, Axis.Z);
                        boneAnimationQueue.scaleXQueue.add(
                            transitionPoint(tick, boneSnapshot.scaleValueX, xPoint.animationStartValue));
                        boneAnimationQueue.scaleYQueue.add(
                            transitionPoint(tick, boneSnapshot.scaleValueY, yPoint.animationStartValue));
                        boneAnimationQueue.scaleZQueue.add(
                            transitionPoint(tick, boneSnapshot.scaleValueZ, zPoint.animationStartValue));
                    }
                }
            }
        } else if (getAnimationState() == AnimationState.Running) {
            // Actually run the animation
            processCurrentAnimation(tick, actualTick, parser, crashWhenCantFindBone);
        }
    }

    private void setAnimTime(MolangParser parser, double tick) {
        parser.setValue("query.anim_time", tick / 20);
    }

    private IAnimatableModel<T> getModel(T animatable) {
        for (ModelFetcher<?> modelFetcher : modelFetchers) {
            IAnimatableModel<T> model = (IAnimatableModel<T>) modelFetcher.apply(animatable);
            if (model != null) {
                return model;
            }
        }
        System.out.printf(
            "Could not find suitable model for animatable of type %s. Did you register a Model Fetcher?%n",
            animatable.getClass());
        return null;
    }

    protected PlayState testAnimationPredicate(AnimationEvent<T> event) {
        return this.animationPredicate.test(event);
    }

    // At the beginning of a new transition, save a snapshot of the model's
    // rotation, position, and scale values as the initial value to lerp from
    private void saveSnapshotsForAnimation(Animation animation,
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection) {
        for (Pair<IBone, BoneSnapshot> snapshot : boneSnapshotCollection.values()) {
            if (animation != null && animation.boneAnimations != null) {
                if (animation.boneAnimations.stream()
                    .anyMatch(
                        x -> x.boneName.equals(
                            snapshot.getLeft()
                                .getName()))) {
                    this.boneSnapshots.put(
                        snapshot.getLeft()
                            .getName(),
                        new BoneSnapshot(snapshot.getRight()));
                }
            }
        }
    }

    private void processCurrentAnimation(double tick, double actualTick, MolangParser parser,
        boolean crashWhenCantFindBone) {
        assert currentAnimation != null;
        // Animation has ended
        if (tick >= currentAnimation.animationLength) {
            if (currentAnimation.loop == EDefaultLoopTypes.HOLD_ON_LAST_FRAME) {
                tick = Math.max(0.0D, currentAnimation.animationLength);
            } else if (!currentAnimation.loop.isRepeatingAfterEnd()) {
                processKeyFrameEvents(currentAnimation.animationLength);
                resetEventKeyFrames();
                // Pull the next animation from the queue
                Animation peek = animationQueue.peek();
                if (peek == null) {
                    // No more animations left, stop the animation controller
                    this.animationState = AnimationState.Stopped;
                    return;
                } else {
                    // Otherwise, set the state to transitioning and start transitioning to the next
                    // animation next frame
                    this.animationState = AnimationState.Transitioning;
                    shouldResetTick = true;
                    currentAnimation = this.animationQueue.peek();
                }
            } else {
                processKeyFrameEvents(currentAnimation.animationLength);
                resetEventKeyFrames();
                tick = wrapLoopTick(actualTick, tick, currentAnimation.animationLength);
            }
        }
        setAnimTime(parser, tick);
        processKeyFrameEvents(tick);

        // Loop through every boneanimation in the current animation and process the
        // values
        List<BoneAnimation> boneAnimations = currentAnimation.boneAnimations;
        for (BoneAnimation boneAnimation : boneAnimations) {
            BoneAnimationQueue boneAnimationQueue = boneAnimationQueues.get(boneAnimation.boneName);
            if (boneAnimationQueue == null) {
                if (crashWhenCantFindBone) {
                    throw new RuntimeException("Could not find bone: " + boneAnimation.boneName);
                } else {
                    continue;
                }
            }
            markActiveBoneAnimationQueue(boneAnimationQueue);
            // See the note on the transition path above: the weight is the animation's own blend_weight, defaulting to
            // 1 (AnimationPlayer:346 and its fallback at :318).
            boneAnimationQueue.setBlendWeight(blendWeightOf(currentAnimation));

            VectorKeyFrameList<KeyFrame<IValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
            VectorKeyFrameList<KeyFrame<IValue>> positionKeyFrames = boneAnimation.positionKeyFrames;
            VectorKeyFrameList<KeyFrame<IValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

            if (!rotationKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.rotationXQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.xKeyFrames, tick, true, Axis.X));
                boneAnimationQueue.rotationYQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.yKeyFrames, tick, true, Axis.Y));
                boneAnimationQueue.rotationZQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.zKeyFrames, tick, true, Axis.Z));
            }

            if (!positionKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.positionXQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.xKeyFrames, tick, false, Axis.X));
                boneAnimationQueue.positionYQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.yKeyFrames, tick, false, Axis.Y));
                boneAnimationQueue.positionZQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.zKeyFrames, tick, false, Axis.Z));
            }

            if (!scaleKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.scaleXQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.xKeyFrames, tick, false, Axis.X));
                boneAnimationQueue.scaleYQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.yKeyFrames, tick, false, Axis.Y));
                boneAnimationQueue.scaleZQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.zKeyFrames, tick, false, Axis.Z));
            }
        }
        if (this.transitionLengthTicks == 0 && shouldResetTick && this.animationState == AnimationState.Transitioning) {
            this.currentAnimation = animationQueue.poll();
        }
    }

    private double wrapLoopTick(double actualTick, double tick, double animationLength) {
        if (animationLength <= 0.0D) {
            this.tickOffset = actualTick;
            this.shouldResetTick = false;
            return 0.0D;
        }
        double wrappedTick = tick % animationLength;
        if (Double.isNaN(wrappedTick) || Double.isInfinite(wrappedTick)) {
            wrappedTick = 0.0D;
        }
        this.tickOffset = this.animationSpeed == 0.0D ? actualTick : actualTick - wrappedTick / this.animationSpeed;
        this.shouldResetTick = false;
        return wrappedTick;
    }

    private void processKeyFrameEvents(double tick) {
        if (soundListener != null) {
            for (EventKeyFrame<String> soundKeyFrame : currentAnimation.soundKeyFrames) {
                if (!this.executedKeyFrames.contains(soundKeyFrame) && tick >= soundKeyFrame.getStartTick()) {
                    SoundKeyframeEvent<T> event = new SoundKeyframeEvent<>(
                        this.animatable,
                        tick,
                        soundKeyFrame.getEventData(),
                        this);
                    soundListener.playSound(event);

                    this.executedKeyFrames.add(soundKeyFrame);
                }
            }
        }

        if (particleListener != null) {
            for (ParticleEventKeyFrame particleEventKeyFrame : currentAnimation.particleKeyFrames) {
                if (!this.executedKeyFrames.contains(particleEventKeyFrame)
                    && tick >= particleEventKeyFrame.getStartTick()) {
                    ParticleKeyFrameEvent<T> event = new ParticleKeyFrameEvent<>(
                        this.animatable,
                        tick,
                        particleEventKeyFrame.effect,
                        particleEventKeyFrame.locator,
                        particleEventKeyFrame.script,
                        this);
                    particleListener.summonParticle(event);

                    this.executedKeyFrames.add(particleEventKeyFrame);
                }
            }
        }

        if (customInstructionListener != null) {
            for (EventKeyFrame<String> customInstructionKeyFrame : currentAnimation.customInstructionKeyframes) {
                if (!this.executedKeyFrames.contains(customInstructionKeyFrame)
                    && tick >= customInstructionKeyFrame.getStartTick()) {
                    CustomInstructionKeyframeEvent<T> event = new CustomInstructionKeyframeEvent<>(
                        this.animatable,
                        tick,
                        customInstructionKeyFrame.getEventData(),
                        this);
                    customInstructionListener.executeInstruction(event);

                    this.executedKeyFrames.add(customInstructionKeyFrame);
                }
            }
        }
    }

    // Helper method to populate all the initial animation point queues
    private void createInitialQueues(List<IBone> modelRendererList) {
        boneAnimationQueues.clear();
        activeBoneAnimationQueues.clear();
        for (IBone modelRenderer : modelRendererList) {
            boneAnimationQueues.put(modelRenderer.getName(), new BoneAnimationQueue(modelRenderer));
        }
    }

    /**
     * The contribution weight of an animation, which is its own {@code blend_weight} expression or 1 when it declares
     * none.
     * <p>
     * Upstream's rule, verbatim shape: {@code currentAnim.blendWeight != null ? currentAnim.blendWeight.evalAsFloat(
     * evaluator) : 1} ({@code AnimationPlayer:318}, {@code :346}, {@code :365}). The expression is evaluated per frame
     * rather than at load time because the packs use it as a live function of the animation clock - all thirteen
     * declarations across the packs this engine loads are expressions, the most common being
     * {@code 0.75*math.sin(query.anim_time*20)+1.5}, which is a breathing weight that only means anything once
     * {@code query.anim_time} advances.
     * <p>
     * {@code MolangExpression} extends the engine's {@code IValue}, whose {@code get()} is the older runtime's
     * evaluation entry point - the same runtime that parsed the animation, so the expression's variables resolve
     * against the parser this controller is already processing with.
     */
    private static float blendWeightOf(Animation animation) {
        if (animation == null || animation.blendWeight == null) {
            return 1f;
        }
        return (float) animation.blendWeight.get();
    }

    private void markActiveBoneAnimationQueue(BoneAnimationQueue boneAnimationQueue) {
        if (boneAnimationQueue != null && !activeBoneAnimationQueues.contains(boneAnimationQueue)) {
            activeBoneAnimationQueues.add(boneAnimationQueue);
        }
    }

    // Used to reset the "tick" everytime a new animation starts, a transition
    // starts, or something else of importance happens
    public double adjustTick(double tick) {
        if (shouldResetTick) {
            if (getAnimationState() == AnimationState.Transitioning) {
                this.tickOffset = tick;
            } else if (getAnimationState() == AnimationState.Running) {
                this.tickOffset = tick;
            }
            shouldResetTick = false;
            return 0;
        } else {
            // assert tick - this.tickOffset >= 0;
            return animationSpeed * Math.max(tick - tickOffset, 0.0D);
        }
    }

    // Helper method to transform a KeyFrameLocation to an AnimationPoint
    private AnimationPoint getAnimationPointAtTick(List<KeyFrame<IValue>> frames, double tick, boolean isRotation,
        Axis axis) {
        KeyFrameLocation<KeyFrame<IValue>> location = getCurrentKeyFrameLocation(frames, tick);
        KeyFrame<IValue> currentFrame = location.currentFrame;
        double startValue = currentFrame.getStartValue()
            .get();
        double endValue = currentFrame.getEndValue()
            .get();

        if (isRotation) {
            if (!(currentFrame.getStartValue() instanceof ConstantValue)) {
                startValue = Math.toRadians(startValue);
                if (axis == Axis.X || axis == Axis.Y) {
                    startValue *= -1;
                }
            }
            if (!(currentFrame.getEndValue() instanceof ConstantValue)) {
                endValue = Math.toRadians(endValue);
                if (axis == Axis.X || axis == Axis.Y) {
                    endValue *= -1;
                }
            }
        }

        return new AnimationPoint(currentFrame, location.currentTick, currentFrame.getLength(), startValue, endValue);
    }

    /**
     * Returns the current keyframe object, plus how long the previous keyframes
     * have taken (aka elapsed animation time)
     **/
    private KeyFrameLocation<KeyFrame<IValue>> getCurrentKeyFrameLocation(List<KeyFrame<IValue>> frames,
        double ageInTicks) {
        double totalTimeTracker = 0;
        for (KeyFrame<IValue> frame : frames) {
            totalTimeTracker += frame.getLength();
            if (totalTimeTracker > ageInTicks) {
                double tick = (ageInTicks - (totalTimeTracker - frame.getLength()));
                return new KeyFrameLocation<>(frame, tick);
            }
        }
        return new KeyFrameLocation<>(frames.get(frames.size() - 1), ageInTicks);
    }

    private void resetEventKeyFrames() {
        this.executedKeyFrames.clear();
    }

    public void markNeedsReload() {
        this.needsAnimationReload = true;
    }

    public void clearAnimationCache() {
        this.currentAnimationBuilder = new AnimationBuilder();
    }

    public double getAnimationSpeed() {
        return animationSpeed;
    }

    public void setAnimationSpeed(double animationSpeed) {
        this.animationSpeed = animationSpeed;
    }

    @FunctionalInterface
    public interface ModelFetcher<T> extends Function<IAnimatable, IAnimatableModel<T>> {
    }
}

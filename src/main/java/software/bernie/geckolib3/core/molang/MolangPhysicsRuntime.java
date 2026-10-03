package software.bernie.geckolib3.core.molang;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;

/**
 * Per-frame MoLang physics/variable scope.
 * <p>
 * The host mod opens a scope with {@link #begin} before a model's animation is evaluated and closes it with
 * {@link #end}. While a scope is open the {@code ysm.first_order}/{@code ysm.second_order} functions, the
 * {@code ysm.bone_*} query functions and {@code v.*} variable reads resolve against it.
 */
public final class MolangPhysicsRuntime {

    private static final ThreadLocal<FrameContext> CURRENT = new ThreadLocal<>();
    private static final Map<ScopeKey, ScopeState> STATES = new ConcurrentHashMap<>();

    private MolangPhysicsRuntime() {}

    public static void begin(IMolangPhysicsScope animatable, double renderTicks, AnimationProcessor<?> processor) {
        if (animatable == null || processor == null) {
            CURRENT.remove();
            return;
        }
        EntityLivingBase entity = animatable.getMolangEntity();
        ScopeKey key = ScopeKey.from(entity, animatable.getMolangModelId(), animatable.getMolangAnimationId());
        ScopeState state = STATES.computeIfAbsent(key, ignored -> new ScopeState());
        state.physics.update(renderTicks);
        applyRemoteVariables(entity, state);
        applyScopeVariables(animatable, state);
        // Before this frame's expressions run, so the bone_color / bone_transparency / bone_glow calls that follow
        // start from white, opaque and not emissive rather than from whatever the previous frame left behind.
        resetBoneRenderState(processor);
        CURRENT.set(new FrameContext(state, processor));
    }

    /**
     * Seeds the scope with the entity's remote animation variables before the animation is evaluated, so a
     * model can read {@code v.roaming.<name>} even when the animation file never assigns it.
     */
    private static void applyRemoteVariables(EntityLivingBase entity, ScopeState state) {
        Map<String, Double> remote = RemoteAnimationVariables.get(entity);
        if (remote == null || remote.isEmpty()) {
            return;
        }
        state.variables.putAll(remote);
    }

    /**
     * Seeds the scope with the variables the animatable supplies for itself, after the entity's remote variables so
     * a host can override its own values. See {@link IMolangPhysicsScope#getMolangVariables()}.
     */
    private static void applyScopeVariables(IMolangPhysicsScope animatable, ScopeState state) {
        Map<String, Double> own = animatable.getMolangVariables();
        if (own == null || own.isEmpty()) {
            return;
        }
        // Canonicalised on the way in, because the scope is read by the parser under the canonical name: a host that
        // supplies "v.roaming.C" would otherwise be unreachable from an expression that says "v.roaming.C", since the
        // parser lower-cases the expression before looking anything up.
        for (Map.Entry<String, Double> entry : own.entrySet()) {
            state.variables.put(MolangParser.canonicalVariableName(entry.getKey()), entry.getValue());
        }
    }

    public static void end() {
        CURRENT.remove();
    }

    public static void clear() {
        STATES.clear();
        CURRENT.remove();
    }

    public static double firstOrder(int nameId, double input, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = CURRENT.get();
        if (context == null) {
            return input;
        }
        return context.state.physics.firstOrder(nameId, input, response);
    }

    public static double secondOrder(int nameId, double input, double frequency, double coefficient, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = CURRENT.get();
        if (context == null) {
            return input;
        }
        return context.state.physics.secondOrder(nameId, input, frequency, coefficient, response);
    }

    public static double getVariable(String name, double fallback) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return fallback;
        }
        Double value = context.state.variables.get(name);
        return value == null ? fallback : value;
    }

    public static boolean setVariable(String name, double value) {
        FrameContext context = CURRENT.get();
        if (context == null) {
            return false;
        }
        context.state.variables.put(name, value);
        return true;
    }

    public static double boneRotation(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return -Math.toDegrees(bone.getRotationX());
        }
        if (axis == 'y') {
            return -Math.toDegrees(bone.getRotationY());
        }
        return Math.toDegrees(bone.getRotationZ());
    }

    public static double bonePosition(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return bone.getPositionX();
        }
        if (axis == 'y') {
            return bone.getPositionY();
        }
        return bone.getPositionZ();
    }

    public static double boneScale(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            // Upstream returns null for a bone it cannot find (BoneParamFunction.java:22-26), and its runtime turns
            // that into 0 wherever the value is used as a number (ValueConversions.asDouble:76-79). 0 is therefore the
            // faithful answer; identity is not, because a bone that does not exist would read as a scale of 1 and a
            // pack testing `ysm.bone_scale('X') > 0` would take the true branch. The old code also returned 1 only for
            // a known axis and 0 otherwise, which made the answer depend on how the caller spelled the axis.
            return 0.0D;
        }
        if (axis == 'x') {
            return bone.getScaleX();
        }
        if (axis == 'y') {
            return bone.getScaleY();
        }
        return bone.getScaleZ();
    }

    /**
     * {@code bone_color(bone, red, green, blue)} - upstream rounds and clamps each channel to 0..255 before storing
     * it ({@code client/animation/molang/functions/BoneRenderFunction.java:52-66}). A bone this model does not have
     * is ignored, which is upstream's behaviour too.
     */
    public static void boneColor(int nameId, double red, double green, double blue) {
        GeoBone bone = renderBone(nameId);
        if (bone == null) {
            return;
        }
        bone.setRenderColor(channel(red), channel(green), channel(blue));
    }

    /** {@code bone_transparency(bone, alpha)} - 0..255, clamped the way upstream clamps it. */
    public static void boneTransparency(int nameId, double alpha) {
        GeoBone bone = renderBone(nameId);
        if (bone != null) {
            bone.setRenderTransparency(channel(alpha));
        }
    }

    /** {@code bone_glow(bone, level)} - -1 for "not emissive" or 0..15, upstream's clamp range. */
    public static void boneGlow(int nameId, double level) {
        GeoBone bone = renderBone(nameId);
        if (bone != null) {
            bone.setRenderGlow((int) Math.max(-1.0D, Math.min(15.0D, Math.round(level))));
        }
    }

    /**
     * Clears the per-bone render state before a frame's expressions run, so a bone that stops calling
     * {@code bone_glow} (or whose model changed) goes back to lit, opaque and white. Upstream gets this for free by
     * rebuilding its attribute array every frame; the port's bones are cached per model, so it has to be explicit.
     */
    public static void resetBoneRenderState(AnimationProcessor<?> processor) {
        List<IBone> bones = processor.getBones();
        for (int i = 0; i < bones.size(); i++) {
            IBone bone = bones.get(i);
            if (bone instanceof GeoBone geoBone) {
                geoBone.resetRenderState();
            }
        }
    }

    private static int channel(double value) {
        return (int) Math.max(0.0D, Math.min(255.0D, Math.round(value)));
    }

    private static GeoBone renderBone(int nameId) {
        IBone bone = bone(nameId);
        return bone instanceof GeoBone geoBone ? geoBone : null;
    }

    private static IBone bone(int nameId) {
        FrameContext context = CURRENT.get();
        if (context == null || nameId == MolangStringPool.EMPTY_ID) {
            return null;
        }
        String boneName = MolangStringPool.get(nameId);
        return boneName == null ? null : context.processor.getBone(boneName);
    }

    private static final class FrameContext {
        private final ScopeState state;
        private final AnimationProcessor<?> processor;

        private FrameContext(ScopeState state, AnimationProcessor<?> processor) {
            this.state = state;
            this.processor = processor;
        }
    }

    private static final class ScopeState {
        private final MolangPhysicsState physics = new MolangPhysicsState();
        private final Map<String, Double> variables = new ConcurrentHashMap<>();
    }

    /**
     * What one scope belongs to: the owning entity, plus the model and animation being evaluated.
     * <p>
     * Every component is compared <em>by value</em>. A detached preview has no entity and is handed freshly built id
     * objects, so any identity-based component would give it a different key - and therefore a different, empty scope -
     * from one frame to the next, discarding the variables the pack's own scripts assigned. YSMU's GUI tiles do exactly
     * that: {@code RenderUtil.renderModel} calls {@code setMainModel(ModelIdUtil.getMainId(modelId))} on every frame
     * and {@code getMainId} returns a {@code new ResourceLocation}. A world entity never saw the problem because its
     * key is the player's UUID.
     */
    private static final class ScopeKey {
        private final UUID entityId;
        private final ResourceLocation modelId;
        private final ResourceLocation animationId;

        private ScopeKey(UUID entityId, ResourceLocation modelId, ResourceLocation animationId) {
            this.entityId = entityId;
            this.modelId = modelId;
            this.animationId = animationId;
        }

        private static ScopeKey from(EntityLivingBase entity, ResourceLocation modelId,
            ResourceLocation animationId) {
            return new ScopeKey(entity == null ? null : entity.getUniqueID(), modelId, animationId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ScopeKey)) {
                return false;
            }
            ScopeKey other = (ScopeKey) obj;
            if (entityId == null ? other.entityId != null : !entityId.equals(other.entityId)) {
                return false;
            }
            if (modelId == null ? other.modelId != null : !modelId.equals(other.modelId)) {
                return false;
            }
            return animationId == null ? other.animationId == null : animationId.equals(other.animationId);
        }

        @Override
        public int hashCode() {
            int result = entityId == null ? 0 : entityId.hashCode();
            result = 31 * result + (modelId == null ? 0 : modelId.hashCode());
            result = 31 * result + (animationId == null ? 0 : animationId.hashCode());
            return result;
        }
    }
}

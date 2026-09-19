package software.bernie.geckolib3.core.molang;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

/**
 * Contract a host mod implements so {@link MolangPhysicsRuntime} can identify the animatable instance currently
 * being evaluated.
 * <p>
 * The physics runtime keys its per-scope state (first/second order filters and roaming variables) on the owning
 * entity plus the active model and animation ids, so the host has to supply all three. Any of the three may be
 * {@code null}; the runtime falls back to an identity based key when the entity is absent.
 */
public interface IMolangPhysicsScope {

    /** The living entity the animation belongs to, or {@code null} for a detached preview. */
    EntityLivingBase getMolangEntity();

    /** Resource location of the model currently animating, used as part of the scope key. */
    ResourceLocation getMolangModelId();

    /** Resource location of the animation currently playing, used as part of the scope key. */
    ResourceLocation getMolangAnimationId();
}

package software.bernie.geckolib3.geo;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import software.bernie.example.config.ConfigHandler;
import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.MatrixStack;

public interface IGeoRenderer<T> {

    public static MatrixStack MATRIX_STACK = new MatrixStack();

    /**
     * Render failures already reported, so one broken model cannot flood the log.
     */
    Set<String> REPORTED_RENDER_FAILURES = ConcurrentHashMap.newKeySet();

    int MAX_REPORTED_RENDER_FAILURES = 24;

    /**
     * Reports geometry that could not be drawn.
     * <p>
     * A cube that threw used to be skipped in complete silence unless {@code debugStacktraces} was on, which makes
     * "the model is not on screen" indistinguishable from "the model drew nothing at all". Announcing the cause once
     * per distinct failure is what turns it back into a diagnosable event; the cap keeps a broken pack from filling
     * the log. The full stack trace still needs the config flag.
     *
     * @param where what was being drawn, for example {@code "cube #3 of bone LeftLeg"}
     */
    default void reportRenderFailure(String where, Throwable failure) {
        String key = where + " | " + failure.getClass()
            .getName() + " | " + failure.getMessage();
        if (REPORTED_RENDER_FAILURES.size() >= MAX_REPORTED_RENDER_FAILURES
            || !REPORTED_RENDER_FAILURES.add(key)) {
            return;
        }
        GeckoLib.LOG.warn(
            "GeckoLib: could not draw {} ({}); that geometry is skipped. Set general.debugStacktraces=true in the"
                + " GeckoLib config for the full stack trace, and report which model pack shows this.",
            where,
            failure.toString());
        if (ConfigHandler.debugPrintStacktraces) {
            failure.printStackTrace();
        }
    }

    default void render(GeoModel model, T animatable, YsmRenderType type, float partialTicks, float red, float green,
        float blue, float alpha) {
        // The GL state comes from the type the caller chose, exactly as upstream's draw applies the RenderType it was
        // handed (com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:18-26). Nothing is decided in here.
        if (type.isCull()) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if (type.isAlphaTest()) {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, type.getAlphaRef());
        } else {
            GlStateManager.disableAlpha();
        }
        GlStateManager.enableRescaleNormal();
        if (type.isBlend()) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        renderEarly(model, animatable, partialTicks, red, green, blue, alpha);

        renderLate(model, animatable, partialTicks, red, green, blue, alpha);
        Tessellator tess = Tessellator.instance;
        // BufferBuilder builder = Tessellator.instance.getBuffer();

        // builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        tess.startDrawing(GL11.GL_QUADS);// , DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        // Render all top level bones
        for (GeoBone group : model.topLevelBones) {
            renderRecursively(tess, animatable, group, red, green, blue, alpha);
        }

        Tessellator.instance.draw();

        renderAfter(model, animatable, partialTicks, red, green, blue, alpha);
        // GlStateManager.disableRescaleNormal();
        // Restores what this method has always restored. The alpha test is left disabled because the engine never
        // turned it on before the render type existed, so a cutout draw nets out to no change for anything after it.
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.enableCull();
    }

    default boolean isBoneRenderOverriden(T animatable, GeoBone bone) {
        return false;
    }

    default void drawOverridenBone(T animatable, GeoBone bone) {

    }

    default void renderRecursively(Tessellator builder, T animatable, GeoBone bone, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.push();

        // The body runs in a try/finally so the shared matrix stack is popped on every path. It used to be popped
        // only on the normal path, so one escaping exception leaked a frame for the rest of the session and every
        // later model could then fail its own pop (silently, through the callers' catch blocks).
        try {
            MATRIX_STACK.translate(bone);
            MATRIX_STACK.moveToPivot(bone);
            MATRIX_STACK.rotate(bone);
            MATRIX_STACK.scale(bone);
            MATRIX_STACK.moveBackFromPivot(bone);

            if (isBoneRenderOverriden(animatable, bone)) {
                drawOverridenBone(animatable, bone);
                return;
            }

            if (!bone.isHidden()) {
                // Per-bone render state, upstream's bone_color / bone_transparency / bone_glow. A bone that never sets
                // them carries white, opaque and "not emissive", so every multiplication below is by one and the
                // lighting branch is not taken: a model that uses none of these draws exactly as it did before.
                float boneRed = red * bone.getRenderRed();
                float boneGreen = green * bone.getRenderGreen();
                float boneBlue = blue * bone.getRenderBlue();
                float boneAlpha = alpha * bone.getRenderAlpha();
                boolean emissive = bone.getRenderGlow() >= 0;
                if (emissive) {
                    // The fixed-function pipeline has no per-bone lightmap, so an emissive bone is simply drawn
                    // unlit. Upstream's level selects a light value there; what a pack asks for by calling
                    // bone_glow at all is that the bone glows, and the level is kept on the bone for a future
                    // brightness mapping rather than being invented here.
                    GlStateManager.disableLighting();
                }
                try {
                    int cubeIndex = -1;
                    for (GeoCube cube : bone.childCubes) {
                        cubeIndex++;
                        MATRIX_STACK.push();
                        GlStateManager.pushMatrix();
                        try {
                            renderCube(builder, cube, boneRed, boneGreen, boneBlue, boneAlpha);
                        } catch (Exception e) {
                            reportRenderFailure("cube #" + cubeIndex + " of bone " + bone.getName(), e);
                        } finally {
                            GlStateManager.popMatrix();
                            MATRIX_STACK.pop();
                        }
                    }
                } finally {
                    if (emissive) {
                        GlStateManager.enableLighting();
                    }
                }
            }
            if (!bone.childBonesAreHiddenToo()) {
                for (GeoBone childBone : bone.childBones) {
                    renderRecursively(builder, animatable, childBone, red, green, blue, alpha);
                }
            }
        } catch (Exception e) {
            reportRenderFailure("bone " + bone.getName(), e);
        } finally {
            MATRIX_STACK.pop();
        }
    }

    default void renderCube(Tessellator builder, GeoCube cube, float red, float green, float blue, float alpha) {
        MATRIX_STACK.moveToPivot(cube);
        MATRIX_STACK.rotate(cube);
        MATRIX_STACK.moveBackFromPivot(cube);

        boolean flat = !cube.mesh && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0);
        if (flat) {
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
        }

        for (GeoQuad quad : cube.quads) {
            if (quad == null) continue;
            Vector3f normal = quad.normalVector == null
                ? new Vector3f(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ())
                : new Vector3f(quad.normalVector);

            MATRIX_STACK.getNormalMatrix()
                .transform(normal);

            /*
             * Fix shading dark shading for flat cubes + compatibility wish Optifine shaders
             */
            if (!cube.mesh && (cube.size.y == 0 || cube.size.z == 0) && normal.x < 0) {
                normal.x *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.z == 0) && normal.y < 0) {
                normal.y *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.y == 0) && normal.z < 0) {
                normal.z *= -1;
            }

            for (GeoVertex vertex : quad.vertices) {
                Vector4f vector4f = new Vector4f(vertex.position.x, vertex.position.y, vertex.position.z, 1.0F);

                MATRIX_STACK.getModelMatrix()
                    .transform(vector4f);
                builder.setColorRGBA_F(red, green, blue, alpha);
                builder.setNormal(normal.x, normal.y, normal.z);
                builder.addVertexWithUV(vector4f.x, vector4f.y, vector4f.z, vertex.textureU, vertex.textureV);
            }
        }

        if (flat) {
            GlStateManager.disablePolygonOffset();
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
        }
    }

    /*
     * (-0.4095761, 0.5882118, 0.70710677, 1.0)
     * (0.409576, 1.1617882, 0.70710677, 1.0)
     * (0.0039961934, 1.7410161, -5.9604645E-8, 1.0)
     * (-0.81515586, 1.1674397, -5.9604645E-8, 1.0)
     * (-0.003996223, 0.008983791, -5.9604645E-8, 1.0)
     * (0.81515586, 0.5825603, -5.9604645E-8, 1.0)
     * (0.40957603, 1.1617882, -0.7071068, 1.0)
     * (-0.40957603, 0.5882118, -0.7071068, 1.0)
     */
    @SuppressWarnings("rawtypes")
    GeoModelProvider getGeoModelProvider();

    ResourceLocation getTextureLocation(T instance);

    @Nullable
    default GeoModel getGeoModel() {
        return null;
    }

    default void renderEarly(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        float width = getWidthScale(animatable);
        float height = getHeightScale(animatable);
        MATRIX_STACK.push();
        MATRIX_STACK.scale(width, height, width);
    }

    default void renderLate(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {}

    default void renderAfter(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.pop();
    }

    default Color getRenderColor(T animatable, float partialTicks) {
        return Color.ofRGBA(255, 255, 255, 255);
    }

    default Integer getUniqueID(T animatable) {
        return animatable.hashCode();
    }

    default GeoBone[] getPathFromRoot(GeoBone bone) {
        ArrayList<GeoBone> bones = new ArrayList<>();
        while (bone != null) {
            bones.add(0, bone);
            bone = bone.parent;
        }
        return bones.toArray(new GeoBone[0]);
    }

    default float getWidthScale(T animatable) {
        return 1F;
    }

    default float getHeightScale(T entity) {
        return 1F;
    }

    /**
     * Whether this frame's layers (held item, back attachments, armor) are drawn before the model instead of after it.
     * <p>
     * A pack can declare this per model - a held item or a wing the model geometry is drawn over has to go first,
     * otherwise the model covers it. The setting belongs to the host that owns the model data, so the renderer asks
     * here rather than the engine keeping a table of its own.
     *
     * @return {@code false} to keep the default order, which draws the model first
     */
    default boolean shouldRenderLayersFirst(T animatable) {
        return false;
    }

    /**
     * The state this model is drawn with, decided here and handed to {@link #render} as a parameter - the port of
     * upstream's {@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39}, same name, same parameters, same
     * branches. {@link YsmRenderType} is only the carrier, because 1.7.10 has no {@code RenderType} to return.
     * <p>
     * Upstream's body, kept verbatim in shape: a visible model is drawn translucent - which blends and culls, and is
     * what a flat decal needs, because the pack zeroes the uvs of the face it does not want and those faces are built
     * anyway - or as cutout with no culling. An invisible model is drawn only when it glows, as an outline; 1.7.10 has
     * no outline pass, so that branch draws nothing, which is this engine's behaviour today.
     *
     * @param translucent upstream reads this from {@code data.modelState.hasTranslucentVertices()}; the host answers
     *                    here through {@link #hasTranslucentVertices}
     */
    @Nullable
    default YsmRenderType getRenderType(ResourceLocation texture, boolean visible, boolean glowing,
        boolean translucent) {
        if (visible) {
            return translucent ? YsmRenderType.translucent(texture) : YsmRenderType.cutoutNoCull(texture);
        }
        return null;
    }

    /**
     * Whether the model has a vertex that is drawn translucently, i.e. whether {@link #getRenderType} takes the
     * translucent branch for it.
     * <p>
     * Upstream asks its baked model state: {@code GeoModelState#hasTranslucentVertices} returns
     * {@code nativeState.getTranslucentVertexCount() != 0}
     * ({@code com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState.java:73-74}), a count its bake produces with the
     * pack's texture pixels as an input. That count is native and cannot be read here, so the host answers instead,
     * with the same two inputs the bake has: the per-bone transparency the engine already carries (see
     * {@code GeoBone#getRenderTransparency}, the port of upstream's {@code AnimatedGeoBone} slot 13) and the texels
     * the model's faces actually sample.
     *
     * @return {@code false} to take the cutout branch, which is what every model did before this existed
     */
    default boolean hasTranslucentVertices(T animatable) {
        return false;
    }
}

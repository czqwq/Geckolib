//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package software.bernie.geckolib3.core.builder;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.ParticleEventKeyFrame;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;

public class Animation implements Serializable {

    private static final long serialVersionUID = 42L;
    public String animationName;
    public Double animationLength;
    public ILoopType loop;
    /**
     * How much this animation contributes where several play at once, as a MoLang expression, or {@code null} to
     * contribute fully.
     * <p>
     * Upstream's field of the same name ({@code geckolib3/core/builder/Animation.java:26}, parsed at {@code :72} from
     * the animation's own {@code blend_weight}) and read where the animation is already in hand, defaulting to 1 when
     * it declares none ({@code AnimationPlayer:318}, {@code :346}, {@code :365}:
     * {@code currentAnim.blendWeight != null ? currentAnim.blendWeight.evalAsFloat(evaluator) : 1}).
     * <p>
     * It is <em>not</em> the same thing as a controller state entry's condition. A condition is a boolean gate - a
     * failing one means the entry does not contribute at all - while this is how far a contributing entry moves the
     * bone, and in the packs this engine loads all thirteen declarations are expressions rather than constants
     * (for example {@code 0.75*math.sin(query.anim_time*20)+1.5}), so it needs evaluating rather than casting.
     * <p>
     * The declared type is {@link MolangExpression}, which is this engine's older {@code core.molang} runtime - the
     * one the animation pipeline actually uses, since {@code JsonAnimationUtils.deserializeJsonToAnimation} is handed a
     * {@code MolangParser} and the port installs animations through it. The engine also carries a newer
     * {@code molang.runtime} VM with its own {@code IValue} and an {@code evalAsFloat} shape, which is what upstream's
     * {@code AnimationPlayer} is written against, but nothing on the animation-loading path uses it: choosing it here
     * would mean this field could not be populated by the parser that builds the rest of the animation. A value of
     * {@code null} means the animation declares no {@code blend_weight} and contributes fully, which is upstream's
     * fallback of 1.
     */
    public MolangExpression blendWeight;
    public List<BoneAnimation> boneAnimations;
    public List<EventKeyFrame<String>> soundKeyFrames;
    public List<ParticleEventKeyFrame> particleKeyFrames;
    public List<EventKeyFrame<String>> customInstructionKeyframes;

    public Animation() {
        this.loop = EDefaultLoopTypes.LOOP;
        this.soundKeyFrames = new ArrayList();
        this.particleKeyFrames = new ArrayList();
        this.customInstructionKeyframes = new ArrayList();
    }

    public static Animation copy(Animation animation) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(animation);
            objectOutputStream.flush();
            String serialized = Base64.getEncoder()
                .encodeToString(byteArrayOutputStream.toByteArray());

            byte[] data = Base64.getDecoder()
                .decode(serialized);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
            ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
            return (Animation) objectInputStream.readObject();
        } catch (Exception e) {
            return animation;
        }
    }
}

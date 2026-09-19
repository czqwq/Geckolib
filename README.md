# GeckoLib 3 for Minecraft 1.7.10

A standalone Forge 1.7.10 mod that ships the GeckoLib animation engine plus the modern MoLang runtime, so that
TouhouLittleMaid and YesSteveModel-Unofficial can depend on one shared copy instead of each vendoring their own.

* mod id: `geckolib`
* mod group / root package: `software.bernie.geckolib3`
* entry point: `software.bernie.geckolib3.GeckoLib`
* Minecraft 1.7.10, Forge 10.13.4.1614, JVM 8 bytecode (Jabel supplies modern *syntax* only)

## Why this repository exists

FML 1.7.10 loads every mod into one class loader, so two mods shipping the same `software.bernie.geckolib3` classes
would silently shadow each other. Hosting the engine here and publishing it (JitPack) removes that hazard and gives
both mods one implementation to keep working.

## Layout

```
src/main/java/
  software/bernie/geckolib3/          the engine, extracted from the 1.7.10 port that ysmu used
    animation/ core/ file/ geo/ model/ resource/ util/
    compat/                           small helpers the engine needs (Axis, Utils)
    core/molang/                      the LEGACY MathBuilder-based MoLang used by the engine above
    molang/                           the MODERN MoLang runtime ported from YesSteveModel 1.20.1
      lexer/ parser/ runtime/         the expression engine (tokens, AST, evaluator, bindings)
      binding/ builtin/ value/ storage/ util/ variable/ function/ context/
  com/eliotlash/mclib/                vendored mclib maths/interpolation helpers
  net/geckominecraft/                 GL state manager and 1.7.10 BlockPos/Vec3i shims
```

Both MoLang implementations coexist on purpose: the ported animation core still evaluates through
`core.molang.MolangParser`, while `molang.MolangParser` is the new engine a host mod can migrate to.

## Consuming it

```gradle
dependencies {
    compileOnly("com.github.czqwq:Geckolib:<tag>")
    devOnlyNonPublishable("com.github.czqwq:Geckolib:<tag>")
}
```

The jar shades and relocates Jackson, so the Bedrock geometry POJOs work without the host mod adding a dependency.

## The port: YesSteveModel 1.20.1 -> 1.7.10

Only the MoLang subsystem was ported; the animation/rendering architecture of the 1.20.1 fork was deliberately left
behind. Package names were relocated into the shared namespace:

| 1.20.1 | here |
| --- | --- |
| `com.elfmcys.ysm.molang.**` | `software.bernie.geckolib3.molang.{lexer,parser,runtime}` |
| `com.elfmcys.ysm.geckolib3.core.molang.**` | `software.bernie.geckolib3.molang.**` |

### Host contract

The 1.20.1 MoLang layer reached into the owning mod's `AnimatableEntity`, capabilities, render context and sound
system. Those are the host mod's business, so the port replaces each with a small interface the host implements:

| interface | replaces |
| --- | --- |
| `molang.context.IMolangAnimatable` | `model.AnimatableEntity` (seek time, state tracker, user functions) |
| `molang.context.IMolangStateTracker` | `model.EntityStateTracker` (render-tick delta, position delta) |
| `molang.context.IMolangPlayerState` | `capability.PlayerAnimatableCapability` (remote-player health, level, flight, yaw speed) |
| `molang.context.IMolangAnimationEvent` | `core.event.predicate.AnimationEvent` |
| `molang.context.IMolangSoundManager` | `client.sound.instance.SoundInstanceManager` (installed via `MolangSoundManagers.setFactory`) |
| `molang.context.IMolangDeferHandler` | `client.entity.CustomEntity#getMolangDeferHandler` |
| `molang.context.ForeignStorageResolver` | the per-entity `*AnimatableCapability` lookups (installed via `MolangForeignStorage.setResolver`) |
| `molang.context.DebugSource` | `client.animation.debug.CustomDebugSource` / `DebugAnimationScreen` (installed via `MolangDebug.setGlobalSource`) |

`IContext<TEntity>` keeps its 1.20.1 shape; only the types it hands back changed.

### Type mapping applied to the ported code

| 1.20.1 | 1.7.10 |
| --- | --- |
| `world.entity.Entity` | `entity.Entity` |
| `world.entity.LivingEntity` | `entity.EntityLivingBase` |
| `world.entity.player.Player` | `entity.player.EntityPlayer` |
| `world.entity.Mob` | `entity.EntityLiving` |
| `world.entity.TamableAnimal` | `entity.passive.EntityTameable` |
| `world.entity.projectile.Projectile` | `entity.Entity` (1.7.10 has no common projectile base) |
| `world.entity.projectile.AbstractArrow` / `Arrow` | `entity.projectile.EntityArrow` |
| `world.entity.projectile.FishingHook` | `entity.projectile.EntityFishHook` |
| `world.entity.projectile.ThrowableItemProjectile` | `entity.projectile.EntityThrowable` |
| `world.item.Item` / `ItemStack` / `UseAnim` | `item.Item` / `ItemStack` / `EnumAction` |
| `world.level.block.Block` / `BlockState` / `BlockBehaviour` | `block.Block` (metadata is a separate int on 1.7.10) |
| `world.level.biome.Biome` | `world.biome.BiomeGenBase` |
| `resources.ResourceLocation` | `util.ResourceLocation` |
| `core.BlockPos` | `net.geckominecraft.util.math.BlockPos` |
| `client.player.LocalPlayer` / `AbstractClientPlayer` | `client.entity.EntityPlayerSP` / `AbstractClientPlayer` |
| `client.multiplayer.ClientLevel` | `client.multiplayer.WorldClient` |
| `network.chat.Component` | `util.IChatComponent` |
| `util.Mth` | `util.MathHelper` (plus `molang.util.MolangUtils.lerp`) |
| `util.RandomSource` | `java.util.Random` |
| `world.phys.Vec3` | `util.Vec3` |
| `world.entity.EquipmentSlot` | `molang.util.MolangEquipmentSlot` |
| `tags.TagKey` | OreDictionary names (see below) |
| `forge.registries.ForgeRegistries` | `GameRegistry` / `Block.blockRegistry` / `Item.itemRegistry` |

### Behaviour that has no 1.7.10 equivalent

Each of these is a deliberate, commented degradation rather than a guess:

* **Tags.** `*_has_all_tags` / `*_has_any_tag` match OreDictionary names for items and blocks, and the registered
  biome name for biomes.
* **Poses and model parts.** `is_sneaking` uses `Entity#isSneaking`; `is_swimming` uses `isInWater`;
  `has_cape` checks only `getLocationCape()`. There is no `Pose` or `PlayerModelPart`.
* **Spectator mode** does not exist, so `is_spectator` is always `false`.
* **Off hand** does not exist, so the `offhand` slot resolves to nothing.
* **Item-in-use state** lives on `EntityPlayer` only; other living entities report "not using an item".
* **The cape wobble** reads `EntityPlayer.field_71091_bM`..`field_71085_bR` (the previous/current chasing
  positions behind 1.20.1's `xCloakO/xCloak` names).

## Building

```
.\gradlew.bat build        # compile + tests + jar
.\gradlew.bat runClient
```

While iterating on Java only, `tools/jcheck.ps1` compiles the whole source tree against the project's real
classpath in a few seconds and writes `tmp/_analysis/javac.log`:

```
pwsh -NoProfile -File tools/jcheck.ps1
```

(It exists because the Gradle round trip is several minutes; keep it if it is useful, ignore it otherwise. The
`tools/` and `tmp/` directories are git-ignored.)

## License

MIT, matching upstream GeckoLib. Vendored engine code keeps its original headers; the MoLang engine descends from
the MIT-licensed `molang` parser used by the 1.20.1 fork.

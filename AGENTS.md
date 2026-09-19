# AGENTS.md

## Project Snapshot

This repository is the standalone **GeckoLib 3** mod for Minecraft Forge 1.7.10. It exists so that
TouhouLittleMaid and YesSteveModel-Unofficial can depend on one shared copy of the animation engine instead of each
vendoring their own (FML 1.7.10 has a single class loader, so duplicate `software.bernie.geckolib3` classes silently
shadow each other).

* mod id `geckolib`, root package `software.bernie.geckolib3`, entry point `software.bernie.geckolib3.GeckoLib`
* GTNH Gradle convention plugin, Minecraft 1.7.10, Forge 10.13.4.1614, MCP stable 12
* Jabel supplies modern Java **syntax** only; the artifact is JVM 8, so never introduce Java 9+ library APIs
* `README.md` documents the layout, the host contract and the 1.20.1 -> 1.7.10 mapping table

## Working Agreement (read this first)

These rules come from the repository owner and override any habit or default workflow:

1. **Parallelise with subagents.** When several features/ports are independent, spawn subagents to do them at the same
   time. The owner explicitly prefers this, single-threaded work is the fallback, not the default.
2. **Do not serialise on file ownership.** Separate workstreams may touch nearby files; minor edit collisions are
   acceptable and redoing a small file is cheaper than serialising the whole job. Partition by package or feature, not
   by paranoid file locks.
3. **Build once, at the end, from the main agent.** Do **not** run a Gradle build after every edit. Gather every
   workstream's changes, then let the main agent run the final `build`. Intermediate Gradle runs are a waste of the
   owner's budget (a full round trip is minutes).
4. **Do not run tests or the in-game harness unless explicitly asked.** No `gradlew test`, no `runClient` harness
   passes "to be safe". If verification is needed before the final build, use the fast compile check below.
5. **Keep public names stable.** This is a compatibility port: class names, method names and packages are what host
   mods compile against. Relocate packages only when the task says so.
6. **Prefer evidenced ports over guesses.** When a 1.20.1 API has no 1.7.10 equivalent, degrade deliberately and say
   why in a comment (see "Behaviour that has no 1.7.10 equivalent" in `README.md`). Do not invent abstractions the
   host mod did not agree to.

## Fast Iteration Without Gradle

For Java-only work, compile the whole tree against the project's real classpath in seconds:

```
pwsh -NoProfile -File tools/jcheck.ps1          # writes tmp/_analysis/javac.log, prints ERRORS=<n>
```

This is a **compile check, not a test run**; it is the intended replacement for repeated Gradle builds while a port
is in flight. `tools/printcp.gradle` refreshes `tmp/_analysis/cp.txt` (the Minecraft 1.7.10 + dependency classpath)
if the classpath ever goes stale:

```
.\gradlew.bat -I tools/printcp.gradle dumpCp --offline --no-configuration-cache
```

`tools/` and `tmp/` are git-ignored.

## Final Build

```
.\gradlew.bat build --offline
```

Expected result: `BUILD SUCCESSFUL` and a shaded jar in `build/libs`. A build that fails on formatting is a real
failure here: `disableSpotless` and `disableCheckstyle` are set because the vendored engine is not GTNH-formatted.

## Reference Sources

Read these instead of guessing at APIs:

* `build/rfg/minecraft-src/java` — decompiled Minecraft + Forge 1.7.10 (stable-12 names, Forge patches applied).
  Check a method's real 1.7.10 name and signature here before using it; 1.20.1 names differ constantly
  (`Mth` vs `MathHelper`, `getX()` vs `posX`, `isUsingItem` on `EntityPlayer` rather than `EntityLivingBase`,
  `getHorizontalFacing` does not exist at all).
* `build/rfg/minecraft-src/resources` — matching resources.
* `tmp/YesSteveModel-Unofficial/src/main/java` — the 1.7.10 mod the engine was extracted from. Its vendored
  `software/bernie/geckolib3`, `com/eliotlash/mclib` and `net/geckominecraft` trees are the provenance of this repo.
* `tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/molang` and `.../geckolib3/core/molang` — the 1.20.1
  sources of the MoLang runtime that was ported.
* `tmp/geckolib-1.20.1` — upstream GeckoLib 4.8.4, useful when comparing where a behaviour originally came from.

Do not decompile or unpack the jars in `build/rfg`; the sources above are the same content. `build/` is generated
output — never commit it and never put workspace files in it.

## Layout

```
src/main/java/
  software/bernie/geckolib3/            the engine (extracted from the 1.7.10 port ysmu used)
    animation/ core/ file/ geo/ model/ resource/ util/
    compat/                             Axis/Utils helpers the engine needs
    core/molang/                        LEGACY MathBuilder-based MoLang, still used by the engine above
    molang/                             MODERN MoLang runtime ported from YesSteveModel 1.20.1
      lexer/ parser/ runtime/           expression engine: tokens, AST, evaluator, bindings
      binding/ builtin/ value/ storage/ util/ variable/ function/ context/
  com/eliotlash/mclib/                  vendored mclib maths/interpolation helpers
  net/geckominecraft/                   GL state manager and 1.7.10 BlockPos/Vec3i shims
src/main/resources/
  META-INF/geckolib_at.cfg              access transformer for the engine's render paths
  assets/geckolib/lang/                 en_US.lang, zh_CN.lang
```

Two MoLang implementations coexist on purpose. `core.molang.MolangParser` is the legacy parser the animation core
still evaluates through; `molang.MolangParser` is the ported 1.20.1 engine. Do not delete either without migrating
the caller.

## Host Contract

The ported MoLang layer must not reach into a host mod's concrete classes. It talks to these interfaces, which a host
implements or installs:

| interface | installed by | replaces (1.20.1) |
| --- | --- | --- |
| `molang.context.IMolangAnimatable` | host implements | `model.AnimatableEntity` |
| `molang.context.IMolangStateTracker` | host implements | `model.EntityStateTracker` |
| `molang.context.IMolangPlayerState` | optional, on the state tracker | `capability.PlayerAnimatableCapability` |
| `molang.context.IMolangAnimationEvent` | host implements | `core.event.predicate.AnimationEvent` |
| `molang.context.IMolangSoundManager` | `MolangSoundManagers.setFactory` | `client.sound.instance.SoundInstanceManager` |
| `molang.context.IMolangDeferHandler` | host implements | `client.entity.CustomEntity#getMolangDeferHandler` |
| `molang.context.ForeignStorageResolver` | `MolangForeignStorage.setResolver` | per-entity capability lookups |
| `molang.context.DebugSource` | `MolangDebug.setGlobalSource` | `CustomDebugSource` / `DebugAnimationScreen` |
| `molang.IMolangPhysicsScope` | host implements | `CustomPlayerEntity` (physics scope keys) |

When a port needs a value only the host can know, add a hook here rather than a reference to a host class.

## Model Format Compatibility

`geo/raw/pojo/Converter` and `geo/raw/pojo/FormatVersion` are where third-party model packs meet the engine, and
both were originally stricter than the data they parse. Do not tighten them again:

* `Converter.instantiateMapper()` must keep `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`. Jackson
  defaults it to `true` and Bedrock exporters add unmodelled keys (tool version stamps, custom markers), so without
  it one unknown field aborts the whole document and the model silently disappears.
* `FormatVersion` accepts `1.8.0`, `1.10.0`, `1.12.0` and `1.14.0`, and `forValue` maps each string to **its own
  constant** - it does not fold the older strings onto `VERSION_1_12_0`. Throwing `IOException` for the older
  strings is what made 150 models in the 1.12.2 corpus fail inside `Converter.fromJsonString`.

`isSupportedLayout()` means "this port deserializes it and hands it to the geometry builder". The builder
(`RawGeometryTree` / `GeoBuilder`) never reads the version - nothing outside `RawGeoModel` touches
`FormatVersion` - so it returns `true` for every constant. It is **not** a "1.12-family" test. A caller that wants
to restrict which layouts it builds has to say so itself.

The stable gate for a caller that must build against both the old vendored jar and this library is the declared
version string, `formatVersion.toValue()`: it exists in both with identical output for the values the old enum
accepted. YSMU's `ClientModelManager.registerGeo` currently gates on `getFormatVersion() == VERSION_1_12_0`, which
silently skips every other version - `1.14.0` included, today - so those models parse and then never appear.

## Coding Conventions

Follow `.editorconfig`: UTF-8, LF, 4-space Java, 2-space Markdown/JSON/YAML, final newline. On Windows PowerShell
read UTF-8 files with `-Encoding UTF8` or the Chinese text in `.lang` files and comments becomes mojibake.

Modern syntax (`var`, pattern `instanceof`, switch expressions) is fine via Jabel. Runtime APIs must stay on JVM 8:
no `List.of`, `Map.of`, `Files.readString/writeString`, `HexFormat`, `Optional.isEmpty`, `VarHandle`.

Vendored engine code (`com/eliotlash`, `net/geckominecraft`, the extracted `geckolib3` tree) is third-party
compatibility code: keep edits narrow and preserve its formatting.

## License

MIT, matching upstream GeckoLib.
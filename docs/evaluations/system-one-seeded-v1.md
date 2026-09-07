# Independent iron-pickaxe worlds

[The frozen protocol](system-one-seeded-v1.json) fixes three development seeds and ten held-out seeds before terrain inspection. It also fixes budgets and the initial 8-of-10 release threshold. The current 27-scenario fixture regression is a separate development suite.

Each seed archive contains only `level.dat`. Preparation uses Minecraft 1.21.8's own NBT implementation, copies configuration from the hashed original scenario, changes the generation seed, disables the bonus chest, and sets `initialized=false`. It omits player data, generated chunks, entities, POI, advancements, saved structures, and Baritone caches. Minecraft initializes the natural spawn and generates terrain when the evaluator first loads its disposable copy. The local Yarn-mapped `MinecraftServer.createWorlds` bytecode calls `setupSpawn` when `ServerWorldProperties.isInitialized()` is false, then marks the world initialized.

Difficulty remains Peaceful, matching the reference archive. Game rules and generation settings remain those of the reference. These iron-pickaxe runs cannot establish ordinary survival or game-completion competence.

The three development archives are prepared and their configuration has been verified through Minecraft NBT round trips. Development seed 01 loaded live in run `20260908-023751-924146-50371`: the initial inventory was empty, and the final saved NBT confirms the frozen seed, initialized world, and natural spawn (-64,79,-544). That diagnostic run was interrupted after collection and performance gaps were identified; it did not pass the gameplay goal. Seeds 02–03 still need live setup validation. Held-out terrain has not been generated or inspected.

```bash
source .envrc
scripts/prepare-system-one-worlds --verify
scripts/run-evaluation-scenarios --system-one \
  --scenario iron-pickaxe-development-01 \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --stop-client-after-scenario
```

`scripts/prepare-system-one-worlds` creates development archives if their directories do not already exist. It refuses to replace existing inputs. `--verify` checks their protocol/scenario/archive hashes and their seed, initialization, and no-player/no-chunks contract. Each `generation.json` records preparation and Minecraft-jar hashes. Run preparation only after the repository's normal build has populated Loom's classpath and mapped Minecraft jar.

After development gates pass, freeze the tested runtime revision, then use `--split held-out` to prepare the held-out archives. Do not inspect their terrain or tune methods against them before the release attempt. Report every outcome, including harness and recording failures. Apply the declared travel limit to recorded travel as well as the evaluator's time and runtime-command budgets. Missing perception auditing is not zero violations and cannot satisfy release acceptance. A passing inventory check alone does not close this gate.

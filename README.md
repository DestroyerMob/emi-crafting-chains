# Multiblock Patterns

Multiblock Patterns is an Applied Energistics 2 add-on for Forge 1.20.1. Select
one compatible multiblock through EMI, remove optional structure entries, and
encode that material list using AE2's ordinary Pattern Encoding Terminal.

The AE2 request path uses the network's ordinary crafting and processing
patterns by default. It does not consume the structure materials or turn them
into a placeholder bundle: completed blocks remain available in ME storage for
the player to build with.

## Features

- Adds **Structure Crafting Tree** to GTCEu multiblock-info recipes and other
  counted EMI recipe categories whose id contains `multiblock`.
- Encodes a reusable **Multiblock Pattern** from an ordinary AE2 blank pattern
  through AE2's existing **Encode** action; filled normal pattern grids keep
  their standard behaviour.
- Shift-clicking that pattern from the player inventory in an AE2 terminal
  subtracts existing ME stock and queues only the missing blocks through the
  network's normal crafting patterns.
- Tracks exactly one recipe tree at a time, matching EMI's standard tree model.
- Right-clicks a tree node to prune that position and every child branch.
- Omits pruned top-level multiblock entries entirely, allowing optional input,
  output, energy, and maintenance hatches to be removed for a shell request.
- Keeps the existing local **Craft Chain** path for player inventory, AE2, and
  Tom's Simple Storage, with configurable animation speed and strength limits.

## AE2 workflow

1. Open an AE2 Pattern Encoding Terminal and insert a blank pattern.
2. Use EMI from that terminal to open a multiblock-info recipe.
3. Press **Structure Crafting Tree**.
4. Right-click optional structure entries to prune them.
5. Return to the Pattern Encoding Terminal and press its normal **Encode**
   control with the normal crafting/processing grid empty.
6. Move the new Multiblock Pattern into your inventory. In any AE2 terminal,
   Shift-click it to request the saved material set. A regular click still
   moves or stores it normally.

The server checks the same AE2 network behind the open terminal. For
each material, existing stored items count first. Missing quantities are planned
and submitted using AE2's normal pattern providers and crafting CPUs. Requests
run sequentially so one CPU is enough and shared ingredients are recalculated
against the latest network state.

If an output has no AE2 pattern, required ingredients are missing, the network
is offline, or no suitable CPU exists, the request stops and reports the reason.

## Local Craft Chain

The original **Craft Chain** control remains available. It recursively executes
ordinary shaped and shapeless crafting-table recipes and can source materials
from the player inventory, an open AE2 terminal, or Tom's Simple Storage.

For ordinary trees, pruning makes the selected ingredient supply-only: an
existing copy may be consumed, but the local planner will not autocraft it or
its descendants. For multiblocks, pruning a top-level entry removes that entry
from the requested shell.

## Configuration

The common Forge config is generated at
`config/multiblock_patterns-common.toml`:

- `enabled`: enable or disable local Craft Chain execution.
- `animateSteps`: commit local crafting immediately when disabled.
- `stepTicks`: local crafting animation/commit delay per compacted step
  (default 10; 20 ticks is one second).
- `maxBatchesPerTarget`: maximum local batches per root (default 64).
- `maxCraftingOperations`: maximum crafting-table operations in one local job
  (default 512).
- `maxMaterialItems`: maximum total counted items in a local or AE2 multiblock
  material request (default 32,768).

## Compatibility and safety

- Minecraft Forge `1.20.1-47.4.20`
- Applied Energistics 2 `15.4.10`
- EMI `1.1.24+1.20.1`
- Optional local sourcing: Tom's Simple Storage `1.7.1`

Multiblock Patterns are recognized by AE2's encoded-pattern slots and Clear
action, but are intentionally ignored by Pattern Providers. Treating the bill
of materials as a normal processing recipe would consume the structure blocks;
the terminal request path instead leaves every completed output in ME storage.
Packet sizes and total material counts are bounded on both client and server.

## Building

Use JDK 17 and point `local_mods_dir` at a directory containing:

```text
emi-1.1.24+1.20.1+forge.jar
appliedenergistics2-forge-15.4.10.jar
toms_storage-1.20-1.7.1.jar
```

Then run:

```bash
./gradlew build -Plocal_mods_dir=/path/to/minecraft/mods
```

The reobfuscated `multiblock-patterns-*.jar` is written to `build/libs/`.

## Licence

Multiblock Patterns is available under the [MIT License](LICENSE).

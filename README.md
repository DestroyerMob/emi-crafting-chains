# EMI Crafting Chains

EMI Crafting Chains is a Forge 1.20.1 add-on that turns EMI's recipe tree into
an actionable crafting plan. Select recipes and a batch count in EMI, then run
the complete dependency chain from one integrated **Craft Chain** control.

The current release focuses on crafting-table chains. The project name and
architecture intentionally leave room for future multiblock and machine-chain
providers.

## Features

- Uses the recipes and batch count selected in EMI's crafting tree.
- Recursively plans ordinary shaped and shapeless crafting-table recipes.
- Reads materials from the player inventory and an open AE2 or Tom's Simple
  Storage terminal.
- Shows shortages in EMI as red `available/required` resource counts.
- Displays the maximum number of complete batches currently craftable.
- Animates dependency steps in the open 3x3 crafting grid for half a second.
- Collapses repeated identical crafts into one stacked animation step.
- Revalidates and commits the entire plan atomically after the animation.

## Usage

1. Open a crafting-table recipe in EMI.
2. Open EMI's crafting tree and choose the recipes for its dependencies.
3. Set the desired batch count.
4. Press **Craft Chain**.

The button displays `Craft Chain (max N)`. It and insufficient resource amounts
turn red when the selected quantity cannot be completed. Existing intermediate
items are consumed before new copies are planned.

## Storage integrations

- The player's 36-slot inventory is always included.
- An open AE2 storage or crafting terminal includes its network inventory.
- An open Tom's Simple Storage terminal includes its connected inventory.

The requested result is inserted into the player inventory. Container items and
surplus batch outputs return to the open storage network first, then the player,
then the ground if necessary.

## Safety and current scope

Only Minecraft crafting recipes are executed in the current provider. Furnace,
Create, GregTech, and other machine-processing nodes are rejected instead of
being silently substituted. Plans are bounded by recursion, recipe, operation,
and batch limits, and materials are not consumed until the final transaction is
revalidated.

Future work will add provider interfaces for multiblocks and machine chains
without weakening the existing server-authoritative validation.

## Building

Requirements:

- JDK 17
- EMI `1.1.24+1.20.1` Forge
- Applied Energistics 2 Forge `15.4.10`
- Tom's Simple Storage `1.7.1`

The three integration jars are compile-only and are never bundled. Point
`local_mods_dir` at a directory containing these exact jar filenames:

```text
emi-1.1.24+1.20.1+forge.jar
appliedenergistics2-forge-15.4.10.jar
toms_storage-1.20-1.7.1.jar
```

Then build with:

```powershell
.\gradlew.bat build -Plocal_mods_dir="C:\path\to\minecraft\mods"
```

On Linux or macOS:

```bash
./gradlew build -Plocal_mods_dir=/path/to/minecraft/mods
```

The reobfuscated jar is written to `build/libs/`.

## Licence

EMI Crafting Chains is available under the [MIT License](LICENSE).

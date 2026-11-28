# Uncrafting Table

Rename a Crafting Table item to exactly `Uncrafting Table` in an anvil, then place it. Right-click
the placed table to open its uncrafting interface. Breaking it removes its registered-table
status.

Put a crafted item in the bottom input slot. If a matching shaped or shapeless crafting recipe
is registered and the input matches the recipe result, its ingredients appear in the 3x3 preview.
Click a preview slot to consume the recipe's output amount and return the ingredients to your
inventory; overflow drops safely nearby. The hybrid Elytra chestplate is uncrafted into its
original chestplate and an Elytra. Table locations persist across server restarts.

Build and install with `npm run build:uncrafting-plugin`. The plugin is separate and is not
rebuilt by `npm start`; remove `runtime/backend/plugins/UncraftingTable.jar` to disable it.

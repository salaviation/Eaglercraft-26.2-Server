# Uncrafting Table

Rename a Crafting Table item to exactly `Uncrafting Table` in an anvil, then place it. Right-click
the placed table to open the native Crafting Table interface. The 3x3 grid is read-only and shows
the recipe ingredients; put the crafted item in the result slot to request uncrafting. Breaking
the table removes its registered-table status.

Click ingredient slots to take the ingredients. The crafted item is consumed on the first
ingredient pickup; uncollected ingredients and any unused input are returned when the interface
closes, with inventory overflow dropped safely nearby. The hybrid Elytra chestplate is uncrafted
into its original chestplate and an Elytra. Table locations persist across server restarts.

Build and install with `npm run build:uncrafting-plugin`. The plugin is separate and is not
rebuilt by `npm start`; remove `runtime/backend/plugins/UncraftingTable.jar` to disable it.

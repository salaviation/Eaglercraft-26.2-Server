# Elytra Chestplate

This optional Paper plugin adds a shapeless recipe: combine one Elytra and one chestplate
anywhere in a 2x2 or 3x3 crafting grid. The output keeps the chestplate's material and item
metadata, including leather dye, enchantments, and armor trim, and adds an `Elytra` lore line.
Smithing results keep the hybrid marker and lore.

While wearing the hybrid chestplate, players automatically start gliding after falling at
least one block. It retains the chestplate's armor value and loses one durability every 10
gliding ticks (twice the normal Elytra wear rate). Vanilla Mending and Unbreaking continue to
work.

The plugin is built and installed as `runtime/backend/plugins/ElytraChestplate.jar` by
`npm run build:plugin`. Removing that JAR disables the plugin for the current server install.

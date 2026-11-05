# VoidMain AntiCheat 1.0.2

A small integration plugin. It does **not** repackage or modify the GrimAC or Vulcan binaries; it subscribes to their public events and coordinates the configured punishment sequence.

## Required files in plugins/

- GrimAC (the server's tested build)
- Vulcan (downloaded from its official SpigotMC distribution)
- PacketEvents (required by Vulcan)
- VoidMain-AntiCheat-1.0.2.jar (this bridge)

The bridge detects GrimAC, Vulcan and PacketEvents as optional integrations; it remains enabled if at least one anti-cheat engine is present, and `/voidac status` shows which API hooks connected.

## Behavior

- OP and players with `voidmain.anticheat.bypass` are exempt.
- GrimAC combat-related flags are counted per check in a configurable window. Only the configured repeated-flag threshold triggers the bridge sequence.
- Vulcan's `VulcanPunishEvent` is intercepted for combat checks; the bridge cancels Vulcan's default kick/command and runs the VoidMain sequence.
- The sequence locks movement and interactions, displays a purple/lavender/white particle animation, drops a snapshot of the player's inventory, kills the player and adds a Bukkit name ban.
- Non-combat Vulcan punishments remain under Vulcan's own `config.yml`.

## Commands

- `/voidac status`
- `/voidac check <player>` — freeze an online player for manual review, including OPs; automatic punishment remains bypassed for exempt players.
- `/voidac release <player>` — release the player from manual review.
- `/voidac violations <player>` — view the saved Vulcan punishment-event count.
- `/voidac reset <player>` — reset the saved count.
- `/voidac reload`

Permission: `voidmain.anticheat.admin` (default OP).

## Important

1. Back up the world and test with a non-OP test account before enabling on the live server.
2. This bridge controls punishment only. It does not add new packet-detection checks to GrimAC or Vulcan.
3. The anti-cheat engines remain separate plugin JARs because they have their own bootstraps and packet libraries. VoidMain-AntiCheat is their shared controller, not a binary merge.
4. OP exemption is enforced by cancelling their flag/punish events and granting Grim's `grim.exempt` permission while they are online.
5. Bans are stored in the Bukkit server's local name ban list. A proxy-wide network ban requires a separate ban-management plugin.
6. Inventory is copied at the start of the animation and dropped at its end; keep-inventory is handled by clearing the player's inventory before the death event.

# VoidMain-Bridge 1.3.0

Paper 1.21.11 / Java 21 integration plugin for the VoidMain website.

## Features
- `/voidmain link` creates a 6-digit, 10-minute website link code (registered executor + tab suggestions for `link`, `balance`, `help`; `/vm` alias).
- Website account links are one-to-one with a Minecraft UUID.
- `/voidmain balance` reads the real PlayerPoints balance.
- Daily reward requests are queued by the website by Minecraft nickname; the named player receives the configured VoidCoin amount through PlayerPoints when online.
- Website reward claims do not require `/voidmain link`; the player enters their nickname in the website account.
- Server heartbeat publishes online/max players and version.
- Linked players' playtime, kills, deaths and PlayerPoints balance are synchronized while online.
- SQLite receipt journal prevents a successfully processed claim from being paid again after a restart.

## Required setup
1. Install PlayerPoints 3.3.5 or compatible version on the Paper server.
2. Set a long random secret as Render environment variable `VOIDMAIN_BRIDGE_SECRET`.
3. Put the exact same secret in `plugins/VoidMain-Bridge/config.yml` at `website.shared-secret`.
4. Set Render environment variable `VOIDMAIN_DAILY_AMOUNT=2` (optional; default is 2).
5. Set `VOIDMAIN_TIMEZONE=Europe/Moscow` on Render if a different calendar timezone is required.
6. Deploy the website and restart the Minecraft server after installing the JAR.

## Important reliability note
PlayerPoints does not expose a transaction shared with this plugin's local SQLite database. The plugin writes a durable `processing` receipt before calling PlayerPoints and marks it `paid` after success. This favors preventing duplicate payments. If the server crashes in the tiny interval between writing `processing` and PlayerPoints completing the give operation, that claim may need manual administrator review; it will not be automatically paid twice. If website confirmation fails after a successful payment, the local receipt allows the plugin to retry confirmation without issuing the points again.

## Build
`mvn clean package`

The JAR is produced in `target/` and uploaded by GitHub Actions.

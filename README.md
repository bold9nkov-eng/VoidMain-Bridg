# VoidMain-Bridge

Paper 1.21.11 / Java 21 bridge plugin for the VoidMain website and PlayerPoints.

## Current commands
- `/voidmain link` — creates a one-time six-digit linking code in-game.
- `/voidmain balance` — displays the player's PlayerPoints balance (used as VoidCoin).
- `/voidmain help` — command help.

## Requirements
- Paper 1.21.11
- Java 21
- PlayerPoints 3.3.5 or compatible

## Build
```bash
mvn clean package
```
The JAR is created in `target/voidmain-bridge-1.0.0.jar`.

## Important
The initial project scaffold currently generates link codes locally. Website-side code redemption, secure reward queue processing, and statistics synchronization still need to be connected before website linking or daily rewards can work end-to-end. Do not publish the shared secret or expose it to players.

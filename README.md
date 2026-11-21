# ItemTracker

A client-side **LiteLoader** mod for **Minecraft 1.12.2** that tracks an item wherever it goes and draws a line from your crosshair to it, plus a box around the item and around the container that holds it. The lines and boxes show through walls.

![license](https://img.shields.io/badge/license-MIT-green)

## Usage

| Command | Effect |
|---|---|
| `/track` | Track the item in your main hand |
| `/track <id> [meta]` | Track by item id, e.g. `/track diamond` or `/track wool 14` |
| `/track off` | Stop tracking |

| Colour | What it is |
|---|---|
| Yellow | Dropped item |
| Orange | Item in an item frame |
| Cyan | Held or carried by a player, mob or minecart |
| Green | Block container (chest, hopper, dropper, furnace, shulker box, ...) |

Items with durability (tools, armour) match by item type only; everything else matches item, metadata and NBT. An item in your own inventory draws nothing, because it is already with you.

## Three modes, picked automatically

1. **Carpet server** (best): the server tells the client where every match is, with nothing to open, anywhere in the loaded world. Needs the server module in [`carpet-module/`](carpet-module) and the rule `modItemTracker` on; you must be op.
2. **Singleplayer / LAN host**: reads the integrated server directly. Same coverage as mode 1, no setup.
3. **Any other server** (client only): sees dropped items, item frames and what mobs and players hold or wear in loaded chunks, plus containers *you opened* (remembered by position, stale until you open them again).

If you track an item on a server that does not answer, the mod says so in chat after a few seconds and uses mode 3.

## Install

1. Install [LiteLoader 1.12.2](https://www.liteloader.com/).
2. Drop `mod-itemtracker-*.litemod` from the [releases](../../releases) into `mods/` (or `mods/1.12.2/`).

## Server module for CarpetMod 1.12

[`carpet-module/ItemTrackerServer.java`](carpet-module/ItemTrackerServer.java) is a plain Carpet plugin-channel handler (channel `itemtracker:main`). To add it to a Carpet fork:

1. Copy it to `carpet/itemtracker/ItemTrackerServer.java`.
2. In `CarpetSettings.java` add the rule:
   ```java
   @Rule(desc = "Server side of the ItemTracker client mod", category = {FEATURE})
   public static boolean modItemTracker = false;
   ```
3. In `CarpetServer.java`:
   ```java
   private static carpet.itemtracker.ItemTrackerServer itemTracker;
   // in init(), after pluginChannels is created:
   itemTracker = new carpet.itemtracker.ItemTrackerServer(server);
   pluginChannels.register(itemTracker);
   // in tick():
   itemTracker.tick();
   ```
4. In game: `/carpet modItemTracker true` (ops only can then use `/track`).

The module sends at most 200 matches per tick to each tracking op and scans only loaded chunks.

## Build

JDK 8 and the bundled Gradle wrapper (ForgeGradle 2.3 LiteLoader fork):

```
./gradlew build      # -> build/libs/mod-itemtracker-1.0.0-mc1.12.2.litemod
```

On Linux, if `./gradlew` fails with `sh\r`, run `sh gradlew build` or fix the line endings.

## Protocol (channel `itemtracker:main`)

- Client to server: one `ItemStack` (`PacketBuffer.writeItemStack`); an empty stack means stop.
- Server to client, every tick: `int dimension`, `int count`, then `count` times `double x, y, z, byte kind, int entityId` (kind 0 dropped, 1 frame, 2 entity, 3 container; entityId is -1 for blocks). `count = -1` means the server refused (rule off or not op).

## License

MIT, see [LICENSE](LICENSE).

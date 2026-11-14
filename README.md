# tether

Let a local agent drive your Minecraft client.

## Overview

Tether is a client-side Fabric mod for Minecraft 1.21.1 that opens a small JSON API on `127.0.0.1`, so a script or an AI agent on the same machine can see what the player sees and act for them: read a build block by block, rebuild it to a list of exact block states, click through inventories and storage terminals, chat, and path across the world with Baritone. It is meant for survival play on a normal server, so it does everything through the player's own hands and inventory. It ships as a single mod jar plus a shell CLI, and needs nothing on the server.

## Features

* **Player state**: position, rotation, health, hunger, held item, the block or entity under the crosshair.
* **World queries**: read any loaded block, scan nearby blocks or entities by id, dump a box of blocks as text layers with full states.
* **Building**: give a list of positions and block states; tether skips what already matches, mines what's wrong with the best tool, puts the right item in hand and places it with the right orientation (stairs, slabs, logs, trapdoors, doors, lanterns, torches, modded blocks), opens or shuts doors and trapdoors, and re-reads everything at the end.
* **Reach**: walks into reach with Baritone, collects the drops, and can jump-place a pillar to get at roofs.
* **Actions**: look at a point, hold movement keys for a number of ticks, use or attack.
* **Screens**: list widgets and menu slots with their screen coordinates, click slots, click at coordinates (with shift), type into text boxes, press keys.
* **Tom's Simple Storage**: list everything an open terminal holds, pull whole stacks by item id, push inventory back.
* **Baritone**: run any Baritone command (`goto`, `mine`, `follow`, ...) and wait until it finishes. Block breaking and placing during pathing are switched off by default.
* **Pause key**: Delete (rebindable under Controls > Tether) freezes the agent on the spot without losing its place; it resumes when told to.
* **On-screen status**: a status line above the hotbar says what the agent is doing, optionally spoken aloud, and a 2 px red border frames the game while the agent is acting, so viewers of a stream can follow along.
* **Chat**: send chat messages and commands, read recent messages.
* **Local only**: listens on loopback with a random port and a per-launch token stored in a user-only file.
* **No bundled dependencies**: uses the JDK's HTTP server and Minecraft's bundled Gson; Baritone and Tom's Simple Storage are optional.

## Installation

### Requirements

* Java 21
* Minecraft 1.21.1 with Fabric Loader 0.16+ and Fabric API
* Optional: [Baritone](https://github.com/cabaletta/baritone/releases) 1.11.3 (`baritone-api-fabric-1.11.3.jar`), Tom's Simple Storage 2.x
* For the CLI: `sh`, `curl`, `jq`
* For spoken status lines: the `flite` speech library (Minecraft's narrator uses it on Linux)

### Build

```sh
git clone https://github.com/GrunclePug/tether.git
cd tether
./gradlew build
cp build/libs/tether-*.jar <instance>/minecraft/mods/
```

Put `cli/tether` somewhere on your `PATH`.

## Usage

Start the game and join a world. The mod writes its port and token to the session file, which the CLI reads.

```sh
tether help                                   # list routes
tether status text='Pulling planks from storage'   # status line above the hotbar
tether state                                  # where am I, what am I looking at
tether look x=100.5 y=65 z=-20.5
tether move keys='["forward","sprint"]' ticks=40
tether select slot=2
tether region x1=0 y1=64 z1=0 x2=12 y2=72 z2=10   # read a house
tether place x=101 y=64 z=-20 block='minecraft:oak_stairs[facing=north,half=top]'
tether build "$(cat plan.json)"                 # see Building
tether break x=101 y=64 z=-20
tether use x=930 y=68 z=637                   # open the block there
tether storage match=oak                      # Tom's terminal contents
tether storage/pull item=minecraft:oak_planks stacks=2
tether storage/push item=minecraft:cobblestone
tether screen/close
tether baritone command='goto 200 70 -50'
tether baritone/wait timeout=120
tether chat message='/home'
```

Each route takes a JSON object body and returns a JSON object; errors come back as `{"error": "..."}` with HTTP 400.

### Configuration

| Variable | Flag | Default | Description |
| --- | --- | --- | --- |
| `TETHER_SESSION` | | `$XDG_STATE_HOME/tether/session` (falls back to `~/.local/state/tether/session`) | Where the mod writes, and the CLI reads, the port and token. |

## Routes

| Route | Arguments | Does |
| --- | --- | --- |
| `help` | | Lists routes and which integrations are loaded. |
| `status` | `text`, or `clear` | Sets or clears the status line above the hotbar. |
| `status/tts` | `enabled` | Reads or sets whether status lines are spoken. |
| `resume` | | Lifts a pause; the running build or pillar carries on from where it stopped. |
| `state` | | Player position, rotation, health, food, held item, crosshair target, open screen. |
| `inventory` | | Non-empty inventory slots (0-8 hotbar, 9-35 main). |
| `select` | `slot` 0-8 | Selects a hotbar slot. |
| `look` | `x y z`, or `yaw pitch` | Turns the camera. |
| `move` | `keys` (forward, back, left, right, jump, sneak, sprint), `ticks` 1-400 | Holds keys, returns when released. |
| `stop` | | Releases keys, aborts breaking, cancels Baritone. |
| `block` | `x y z` | Block id and state at a position. |
| `blocks` | `radius` 1-32, `match`, `limit` | Nearby non-air blocks, nearest first. |
| `region` | `x1 y1 z1 x2 y2 z2`, `states` | A box (up to 65536 blocks) as text layers plus a palette of block states. |
| `entities` | `radius`, `match` | Nearby entities with ids. |
| `use` | `x y z` and optional `face`, or nothing | Right-clicks a block, or uses the held item. |
| `place` | `x y z`, optional `block`, `walk` | Places one block (default: the held one); a one-step `build`. |
| `place/options` | `x y z`, `block` | Every state that block could be placed as there, from where the player stands. |
| `break` | `x y z`, `walk`, `pickup` | Mines one block; a one-step `build` to air. |
| `build` | `blocks`, `origin`, `walk`, `pickup`, `perTick` 1-4, `wait`, `timeout` | Applies a list of `{x, y, z, block}`; see Building. |
| `build/status` | | Progress of the running build, its recovery count and the player's position. |
| `pillar` | `height` 1-16, `block` (default dirt) | Jump-places a column under the player; returns the positions. |
| `view` | `hideGui`, `camera` (first, back, front) | Hides the HUD or changes perspective, for screenshots. |
| `attack` | `id` | Attacks an entity. |
| `screen` | | Open screen, its widgets, menu slots and their coordinates. |
| `screen/click` | `slot`, `button`, `type` (pickup, quick_move, swap, throw, ...) | Clicks a menu slot; works on the player inventory with no screen open. |
| `screen/mouse` | `x y`, `button`, `shift` | Clicks at screen coordinates. |
| `screen/type` | `text`, optional `widget` | Sets a text box's value, or types into the focused widget. |
| `screen/key` | `key`, `shift` | Presses a key in the open screen. |
| `screen/close` | | Closes the open screen. |
| `storage` | `match` | Items in the open Tom's terminal. |
| `storage/pull` | `item` or `index`, `stacks` | Pulls whole stacks into the inventory. |
| `storage/push` | `item`, `hotbar` | Shift-clicks inventory slots into the open container (all but the hotbar by default). |
| `chat` | `message` | Sends chat, or a command if it starts with `/`. |
| `chat/log` | `since` | Messages after sequence number `since`. |
| `baritone` | `command` | Runs a Baritone command (without the `#`). |
| `baritone/status` | | Pathing state and goal. |
| `baritone/wait` | `timeout` seconds | Waits until Baritone is idle; returns `stuck: true` (and cancels) if the player stops moving for 3 seconds. |

While an agent has called the API in the last two minutes, the game doesn't open the pause menu when its window loses focus.

## Pausing

Press **Delete** (Controls > Tether > Pause Claude) to stop the agent at once, in the world or with a screen open. Held keys are released, Baritone's path is cancelled and a half-mined block is let go, but the running build or pillar keeps its place in the list. While paused, every route that acts on the game fails with "paused by the player", read-only routes still work, and the status line says so. The agent resumes with the `resume` route once you've talked it through; `/tether resume` in game does the same, and `/tether pause` pauses. On resume, a let-go block is mined again from the start and an interrupted walk is walked again.

## On-screen status

* **Status line**: `status text=...` shows "Claude: ..." centred above the hotbar, wrapped to two lines. It stays while the agent keeps acting and fades three minutes after the last update.
* **Spoken status**: `/tether tts` in game toggles reading each new status line aloud (`/tether tts on`, `/tether tts off`); the choice is saved in `config/tether.properties`. It uses Minecraft's own speech engine directly, so the menu narrator can stay off and the voice comes out of the game's audio. It needs `flite` installed.
* **Red border**: a 2 px red frame around the game, also over open screens, while the agent is acting: for 8 seconds after any route that changes something, and for as long as a build, pillar, held keys or Baritone path is running. Read-only routes don't raise it.

## Building

`build` takes a list of target blocks, in the same `minecraft:block[prop=value]` syntax as `/setblock` and as `region` prints them, plus `"air"` to clear a spot:

```json
{
  "origin": {"x": 100, "y": 64, "z": -20},
  "walk": true,
  "pickup": true,
  "blocks": [
    {"x": 0, "y": 0, "z": 0, "block": "minecraft:oak_log[axis=y]"},
    {"x": 1, "y": 3, "z": -1, "block": "minecraft:spruce_stairs[facing=south,half=bottom]"},
    {"x": 2, "y": 2, "z": -1, "block": "minecraft:spruce_trapdoor[facing=north,half=top,open=true]"},
    {"x": 3, "y": 1, "z": 0, "block": "air"}
  ]
}
```

Steps run in order, one per tick by default:

* A position that already matches is left alone. Only the properties you name are compared, and properties placement can't choose (connections, stair shape, waterlogging, power, `open`) are ignored, so a state copied from `region` matches what placing it produces.
* A wrong block is mined with the fastest hotbar tool, then the right item is moved into the hotbar and placed.
* The placement planner asks the item itself (the same code the server runs) what each clickable face, hit height and facing would produce, and uses the option that matches with the least head movement. Placement always sneaks, so clicking a chest or terminal places against it.
* `open=true` or `open=false` is applied by clicking the door, trapdoor or gate after placing it.
* A block with nothing to place against is retried once at the end, after its neighbours exist. If it still can't be placed, the problem lists the states that are reachable from there; `place/options` shows the same thing ahead of time.
* With `walk`, Baritone paths within reach of out-of-reach blocks; with `pickup`, it walks over the drops afterwards. Both need Baritone.
* Walking is watched: if Baritone says it's moving but the player hasn't moved for 3 seconds, the job cancels the path, adds any modded partial block around the player's feet to Baritone's `blocksToAvoid`, backs up with a jump and tries again. After two recoveries, or three walks in a row that end out of reach, it stops with outcome `stuck: ...` and says so on the status line. A stuck pickup trip just skips that item.
* Ten ticks after the last step every position is read again; anything the server rejected is reported as a mismatch.

The result counts what was placed, broken or already right, and lists only the problems. For roofs above reach, `pillar` up first and clear the returned positions top-down with `build` and `"air"` when done.

## Development

* Build: `./gradlew build`
* Run a dev server: `./gradlew runServer` (in `run/server`)
* Run a dev client: `./gradlew runClient` (in `run/client`)
* Clean: `./gradlew clean`

## Contributing

Fork the repository and open a pull request.

## License

MIT. See [LICENSE](LICENSE).

## Author

Chad Humphries | [Website](https://grunclepug.com/) | [GitHub](https://github.com/GrunclePug) | [More projects](https://github.com/GrunclePug?tab=repositories)

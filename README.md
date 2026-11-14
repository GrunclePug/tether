# tether

Let a local agent drive your Minecraft client.

## Overview

Tether is a client-side Fabric mod for Minecraft 1.21.1 that opens a small JSON API on `127.0.0.1`, so a script or an AI agent on the same machine can see what the player sees and act for them: walk, look, place and break blocks, click through inventories and storage terminals, chat, and path across the world with Baritone. It ships as a single mod jar plus a shell CLI, and needs nothing on the server.

## Features

* **Player state**: position, rotation, health, hunger, held item, the block or entity under the crosshair.
* **World queries**: read any loaded block, scan nearby blocks or entities by id.
* **Actions**: look at a point, hold movement keys for a number of ticks, place the held block at a position, break a block, use or attack.
* **Screens**: list widgets and menu slots with their screen coordinates, click slots, click at coordinates (with shift), type into text boxes, press keys.
* **Tom's Simple Storage**: list everything an open terminal holds and pull whole stacks by item id.
* **Baritone**: run any Baritone command (`goto`, `mine`, `follow`, ...) and wait until it finishes. Block breaking and placing during pathing are switched off by default.
* **Chat**: send chat messages and commands, read recent messages.
* **Local only**: listens on loopback with a random port and a per-launch token stored in a user-only file.
* **No bundled dependencies**: uses the JDK's HTTP server and Minecraft's bundled Gson; Baritone and Tom's Simple Storage are optional.

## Installation

### Requirements

* Java 21
* Minecraft 1.21.1 with Fabric Loader 0.16+ and Fabric API
* Optional: [Baritone](https://github.com/cabaletta/baritone/releases) 1.11.3 (`baritone-api-fabric-1.11.3.jar`), Tom's Simple Storage 2.x
* For the CLI: `sh`, `curl`, `jq`

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
tether state                                  # where am I, what am I looking at
tether look x=100.5 y=65 z=-20.5
tether move keys='["forward","sprint"]' ticks=40
tether select slot=2
tether place x=101 y=64 z=-20
tether break x=101 y=64 z=-20
tether use x=930 y=68 z=637                   # open the block there
tether storage match=oak                      # Tom's terminal contents
tether storage/pull item=minecraft:oak_planks stacks=2
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
| `state` | | Player position, rotation, health, food, held item, crosshair target, open screen. |
| `inventory` | | Non-empty inventory slots (0-8 hotbar, 9-35 main). |
| `select` | `slot` 0-8 | Selects a hotbar slot. |
| `look` | `x y z`, or `yaw pitch` | Turns the camera. |
| `move` | `keys` (forward, back, left, right, jump, sneak, sprint), `ticks` 1-400 | Holds keys, returns when released. |
| `stop` | | Releases keys, aborts breaking, cancels Baritone. |
| `block` | `x y z` | Block id and state at a position. |
| `blocks` | `radius` 1-32, `match`, `limit` | Nearby non-air blocks, nearest first. |
| `entities` | `radius`, `match` | Nearby entities with ids. |
| `use` | `x y z` and optional `face`, or nothing | Right-clicks a block, or uses the held item. |
| `place` | `x y z` | Places the held block there against a solid neighbour. |
| `break` | `x y z` | Mines the block, returns when it's gone (30 s limit). |
| `attack` | `id` | Attacks an entity. |
| `screen` | | Open screen, its widgets, menu slots and their coordinates. |
| `screen/click` | `slot`, `button`, `type` (pickup, quick_move, swap, throw, ...) | Clicks a menu slot; works on the player inventory with no screen open. |
| `screen/mouse` | `x y`, `button`, `shift` | Clicks at screen coordinates. |
| `screen/type` | `text`, optional `widget` | Sets a text box's value, or types into the focused widget. |
| `screen/key` | `key`, `shift` | Presses a key in the open screen. |
| `screen/close` | | Closes the open screen. |
| `storage` | `match` | Items in the open Tom's terminal. |
| `storage/pull` | `item` or `index`, `stacks` | Pulls whole stacks into the inventory. |
| `chat` | `message` | Sends chat, or a command if it starts with `/`. |
| `chat/log` | `since` | Messages after sequence number `since`. |
| `baritone` | `command` | Runs a Baritone command (without the `#`). |
| `baritone/status` | | Pathing state and goal. |
| `baritone/wait` | `timeout` seconds | Waits until Baritone is idle. |

While an agent has called the API in the last two minutes, the game doesn't open the pause menu when its window loses focus.

## Development

* Build: `./gradlew build`
* Run a dev client: `./gradlew runClient`
* Clean: `./gradlew clean`

## Contributing

Fork the repository and open a pull request.

## License

MIT. See [LICENSE](LICENSE).

## Author

Chad Humphries | [Website](https://grunclepug.com/) | [GitHub](https://github.com/GrunclePug) | [More projects](https://github.com/GrunclePug?tab=repositories)

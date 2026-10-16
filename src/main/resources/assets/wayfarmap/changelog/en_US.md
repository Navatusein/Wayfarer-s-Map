<!--
  The mod's changelog: shown in the "What's new" window (after the mod is updated, and from a button in "About").
  One file per language: en_US.md, ru_RU.md, uk_UA.md, zh_CN.md. A language without a file shows en_US.md.

  How to write it (newest version on top):

  # 1.2.0 | 2026-10-08        a version; the date after "|" (optional)
  ## Map                      a heading inside a version
  - [new] text                a point with a tag: [new], [fix], [change], [remove]
  - text                      a point without a tag
    - text                    a point inside the one above (indented by 2 spaces)
  > text                      a note in a frame
  text                        a plain paragraph

  In any text: **bold**, `key`, *italic*, and Minecraft's § colors.
  A line right under a point carries it on. Comments like this one are left out.
-->

# 0.0.17

## World map
- [fix] Follow: the map keeps the player in the middle as they walk; dragging it pauses following until `Space` or the button
- [new] 2D map and the map without plants: the biome under the cursor next to the coordinates at the bottom
- [change] Biome view: the biome's name shows by the cursor instead of at the bottom
- [change] Biome view: no day and night, the biome colors always as by day
- [new] Grid: Default (chunks and regions) or Regions only; the grid button goes off → default → regions only
- [new] The player icon can be hidden completely (settings → Player icon)
- [fix] 3D map: teleport to the floor under the cursor — at a wall onto the floor of that storey, on a roof into the building onto the floor below it (and a separate "Teleport onto the roof")
- [new] Teleport into another dimension from the map and the waypoint menu: through the mod on the server (operators) or `/cofh tpx`
- [new] Map data: a button that refreshes the map (read again from disk, the loaded chunks mapped anew) and one that clears all map data of this world

## Minimap
- [fix] Stutter at 1:2: no coarse copies of the regions, and the map moves in whole pixels
- [new] The world map's search highlights the same biomes, veins, fluids, power failures and nodes on the minimap

# 0.0.16

## Waypoints
- [new] Team waypoints: share a waypoint with your ServerUtilities team (on servers with the mod) from its editor, the list or its menu
- [new] A waypoint group can share its new waypoints by itself, and all it has with one button
- [new] A teammate's waypoint is tagged with their name; hide it, or save it as your own to edit it: the copy says whose it is a copy of and leads back to the original
- [new] Teammates' waypoints can come hidden: for all groups in the settings, or for one group in the list

## Windows
- [change] "About" window: Navatusein added as a developer

# 0.0.15

## Minimap
- [new] Own settings apart from the world map: the map shown (2D, without plants, topography, biomes), lighting, mod layers, chunk grid, players and mobs
- [new] Keys for each of them (unbound by default); a key gives that part of the minimap its own settings
- [change] Controls: the mod's keys are split into general, world map and minimap

# 0.0.14

## Windows
- [new] Welcome window: what the mod can do, the first steps, who made it and the choice of the mod's language
- [new] "What's new" window, shown after the mod is updated and from a button in the "About" window
- [change] Redesigned "About" window: the author's and testers' avatars, links to GitHub, Boosty and Telegram
- [new] A **Mod language** section in the settings: all of the mod's texts in the chosen language, whatever Minecraft's is

## Map
- [change] Grass, leaves and vines on the 2D map colored by their own biome
- [change] Map without plants: grass blocks in their biome's own color
- [change] The Nether on the 3D map is dim at any time of day, lit by its lava and lamps
- [change] The End is lit as by day
- [new] Copy and paste waypoints on the world map
- [new] Waypoint list: groups pane, waypoint cards, compass arrows and hover actions

## 3D map
- [change] Block pictures are kept from game to game: the map builds faster
- [fix] Blocks with metadata above 15 no longer break the hidden-picture check

## Area loading
- [new] Its own 3D switch, a Stop button, a progress bar and hints
- [fix] `/wf chunkload` sends ForgeMultipart's parts, so microblocks aren't empty

## Other
- [remove] The GregTech ore vein (3x3 chunk) grid mode

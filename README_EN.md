# IpacChunkLoader
A simple chunk loader plugin for Minecraft.

https://modrinth.com/plugin/ipacchunkloader

### Usage

Activate a chunk loader by naming an armor stand `ChunkLoad N`.
- `N` ranges from `0` to `10`. `0` loads the single chunk where the armor stand is located; `1` loads a 3x3 area (9 chunks).

Each naming activates the loader for 4 hours. Renaming can accumulate the duration up to a maximum of 24 hours.

The chunk loader works by setting chunks to be constantly loaded. The loaded area follows the armor stand's movement and updates every 20 ticks.

Note: It does not trigger mob spawning as a player would.

### Naming Rules
- `^\s*(?<id>.{0,24}?)\s*(?:ChunkLoad|ChunkLoader)\s*(?<radius>\d+)\s*$`
- `ChunkLoad 10`
- `FurnaceArray ChunkLoad 4`

### Commands

- `/icl` - Help and statistics
- `/icl reload` - Reload the plugin
- `/icl list` - View the list of active loaders

### Permissions

```yaml
permissions:

  IpacChunkLoader.reload:
    description: 'Reload the plugin'
    default: op
  IpacChunkLoader.list:
    description: 'View the list of active loaders'
    default: op
```

### Configuration File
```yaml

# Regex to identify armor stand names
name-regex: '^\s*(?<id>.{0,24}?)\s*(?:ChunkLoad|ChunkLoader)\s*(?<radius>\d+)\s*$'

# Default value used when the ID matcher finds no content
default-id: 'Unnamed'

# Armor stand name format after activation: %id% %radius% %time%
display-format: '%id% [ChunkLoader] %radius% - %time%'

# Armor stand name format after expiration: %id% %radius% %time%
expiry-format: '%id% [ChunkLoader] %radius% - %time%'

# Initial/accumulated duration in hours
add-hours: 4

# Maximum duration in hours
max-hours: 24

# Maximum loading radius
max-radius: 10

# Maximum number of concurrent active loaders
max-count: 10

# Execute commands when remaining time falls below specified hours
warn-hours: 2
# Placeholders: %world% %x% %y% %z% %name% %id% %radius% %time%
warn-commands:
  - 'say Chunk loader about to expire [%world%/%x%/%y%/%z%] [%id%] %radius% - %time%'

msg:
  activate: '§bIpacEL §f> §aChunk loader activated'
  quant-limit: '§bIpacEL §f> §bChunk loader limit reached'
  list-header: '§bIpacEL §f> §aActive loaders list:'
  list-line: '  §7- [%world%/%x%/%y%/%z%] [%id%] %radius% - %time%' # %world% %x% %y% %z% %name% %id% %radius% %time%
  list-hover: 'Click to teleport'

```

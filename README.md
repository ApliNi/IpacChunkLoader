# IpacChunkLoader
Minecraft 上的简单区块加载器插件

https://modrinth.com/plugin/ipacchunkloader

### 使用方法

通过给盔甲架命名 `区块加载 N` 来激活区块加载器.
- `N` 取值范围为 `0 ~ 10`, 为 0 则加载盔甲在所在的一个区块, 为 1 则会加载 9 个区块.

命名一次将激活 4 小时, 可以重复命名累加到最大 24 小时.

区块加载器通过设置常加载区块实现功能, 加载范围会跟随盔甲架移动, 每 20 Tick 更新一次.

无法代替玩家刷怪.

### 命名规则
- `^\s*(?<id>.{0,24}?)\s*(?:区块加载|区块加载器)\s*(?<radius>\d+)\s*$`
- `区块加载 10`
- `熔炉组 区块加载 4`

### 命令

- `/icl` - 帮助和统计信息
- `/icl reload` - 重载插件
- `/icl list` - 查看活跃加载器列表

### 权限

```yaml
permissions:

  IpacChunkLoader.reload:
    description: '重载插件'
    default: op
  IpacChunkLoader.list:
    description: '查看活跃加载器列表'
    default: op
```

### 配置文件
```yaml

# 识别盔甲架名称
name-regex: '^\s*(?<id>.{0,24}?)\s*(?:区块加载|区块加载器)\s*(?<radius>\d+)\s*$'

# 当 id 匹配器没有匹配到内容时使用的默认值
default-id: '未命名'

# 激活后盔甲架名称 %id% %radius% %time%
display-format: '%id% [区块加载器] %radius% - %time%'

# 过期后盔甲架名称 %id% %radius% %time%
expiry-format: '%id% [区块加载器] %radius% - %time%'

# 初始/累加时长
add-hours: 4

# 最大时长
max-hours: 24

# 最大加载半径
max-radius: 10

# 最大同时存在数量
max-count: 10

# 剩余时间低于指定小时数时执行指令
warn-hours: 2
# %world% %x% %y% %z% %name% %id% %radius% %time%
warn-commands:
  - 'say 区块加载器即将过期 [%world%/%x%/%y%/%z%] [%id%] %radius% - %time%'

msg:
  activate: '§bIpacEL §f> §a区块加载器已激活'
  quant-limit: '§bIpacEL §f> §b区块加载器达到数量限制'
  list-header: '§bIpacEL §f> §a活跃加载器列表:'
  list-line: '  §7- [%world%/%x%/%y%/%z%] [%id%] %radius% - %time%' # %world% %x% %y% %z% %name% %id% %radius% %time%
  list-hover: '点击传送'

```
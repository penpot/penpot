---
title: 1.1 Recommended settings
desc: Learn recommended self-hosting settings, Docker & Kubernetes installs, configuration, and troubleshooting tips in Penpot's technical guide.
---

# Recommended storage

Disk requirements depend on your usage, with the primary factors being database storage and user-uploaded files.

As a rule of thumb, start with a **minimum** database size of **50GB** to **100GB** with elastic sizing capability — this configuration should adequately support up to 10 editors. For environments with **more than 10 users**, we recommend adding approximately **5GB** of capacity per additional editor.

Keep in mind that database size doesn't grow strictly proportionally with user count, as it depends heavily on how Penpot is used and the complexity of files created. Most organizations begin with this baseline and elastic sizing approach, then monitor usage patterns monthly until resource requirements stabilize.


# About Valkey / Redis requirements

"Valkey is mainly used for coordinating websocket notifications and, since Penpot 2.11, as a cache. Therefore, disk storage will not be necessary as it will use the instance's RAM.

To prevent the cache from hogging all the system's RAM usage, it is recommended to use two configuration parameters which, both in the docker-compose.yaml provided by Penpot and in the official Helm Chart, come with default parameters that should be sufficient for most deployments:

```bash
## Recommended values for most Penpot instances.
## You can modify this value to follow your policies.

# Set maximum memory Valkey/Redis will use.
# Accepted units: b, k, kb, m, mb, g, gb
maxmemory 128mb

# Choose an eviction policy (see Valkey docs:
# https://valkey.io/topics/memory-optimization/ or for Redis
# https://redis.io/docs/latest/develop/reference/eviction/
# Common choices:
#   noeviction, allkeys-lru, volatile-lru, allkeys-random, volatile-random,
#   volatile-ttl, volatile-lfu, allkeys-lfu
#
# For Penpot, volatile-lfu is recommended
maxmemory-policy volatile-lfu
```

The `maxmemory` configuration directive specifies the maximum amount of memory to use for the cache data. If you are using a dedicated instance to host Valkey/Redis, we do not recommend using more than 60% of the available RAM.

With `maxmemory-policy` configuration directive, you can select the eviction policy you want to use when the limit set by `maxmemory` is reached. Penpot works fine with `volatile-lfu`, which evicts the least frequently used keys that have been marked as expired.

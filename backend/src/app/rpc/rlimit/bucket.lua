local tokensKey = KEYS[1]

local interval = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local capacity = tonumber(ARGV[3])
local timestamp = tonumber(ARGV[4])
local requested = tonumber(ARGV[5] or 1)

local fillTime = capacity / (rate / interval);
local ttl = math.floor(fillTime * 2)

local lastTokens = tonumber(redis.call("hget", tokensKey, "tokens"))
if lastTokens == nil then
  lastTokens = capacity
end

local lastRefreshed = tonumber(redis.call("hget", tokensKey, "timestamp"))
if lastRefreshed == nil then
  lastRefreshed = 0
end

local delta = math.max(0, (timestamp - lastRefreshed) / interval)
local filled = math.min(capacity, lastTokens + math.floor(delta * rate));
local allowed = filled >= requested
local newTokens = filled
if allowed then
  newTokens = filled - requested
  redis.call("hset", tokensKey, "tokens", newTokens, "timestamp", timestamp)
end

redis.call("expire", tokensKey, ttl)

return { allowed, newTokens }

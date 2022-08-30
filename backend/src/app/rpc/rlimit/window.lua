local windowKey = KEYS[1]

local nreq = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

local total = tonumber(redis.call("incr", windowKey))
redis.call("expire", windowKey, ttl)

local allowed = total <= nreq
local remaining = nreq - total

if remaining < 0 then
   remaining = 0
end

return {allowed, remaining}



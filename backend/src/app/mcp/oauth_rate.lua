-- Copyright (c) KALEIDOS SUBSIDIARY SL
-- MPL-2.0
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return {count}

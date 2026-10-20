-- This Source Code Form is subject to the terms of the Mozilla Public
-- License, v. 2.0. If a copy of the MPL was not distributed with this
-- file, You can obtain one at http://mozilla.org/MPL/2.0/.
--
-- Copyright (c) KALEIDOS SUBSIDIARY SL

local key = KEYS[1]
local threshold = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local window_sec = math.ceil(window_ms / 1000)

local val = redis.call('GET', key)
local count
local window_start

if val then
  local colon = string.find(val, ':')
  if colon then
    count = tonumber(string.sub(val, 1, colon - 1))
    window_start = tonumber(string.sub(val, colon + 1))
  else
    count = 0
    window_start = now_ms
  end
else
  count = 0
  window_start = now_ms
end

if (now_ms - window_start) > window_ms then
  count = 0
  window_start = now_ms
end

count = count + 1
redis.call('SET', key, count .. ':' .. window_start, 'EX', window_sec)

local locked = 0
local ttl = 0
if count >= threshold then
  locked = 1
  ttl = math.ceil((window_ms - (now_ms - window_start)) / 1000)
end

return {count, locked, ttl}

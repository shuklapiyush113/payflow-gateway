-- Token bucket, evaluated atomically inside Redis so every replica shares one bucket per key.
-- KEYS[1]: bucket key
-- ARGV[1]: capacity (maximum burst)
-- ARGV[2]: refill rate in tokens per second
-- Returns {allowed (1/0), tokens left (floored), milliseconds until the next token if rejected}.
--
-- Time comes from the Redis server, not the caller, so replicas with skewed clocks still agree.

local capacity = tonumber(ARGV[1])
local refill_per_ms = tonumber(ARGV[2]) / 1000

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last = tonumber(state[2])
if tokens == nil or last == nil then
  tokens = capacity
  last = now
end

tokens = math.min(capacity, tokens + math.max(0, now - last) * refill_per_ms)

local allowed = 0
local retry_after_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after_ms = math.ceil((1 - tokens) / refill_per_ms)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- An idle bucket refills completely in capacity / rate, after which it can simply be forgotten.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms) + 1000)

return {allowed, math.floor(tokens), retry_after_ms}

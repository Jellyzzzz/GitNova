# Redis Rate Limit Design

## Design goal

Protect GitNova HTTP entry points with shared rate limits across application instances. Redis is
ephemeral coordination state; it is not the durable source of user billing or model-token quota.

## Key schema

Each request may consume up to three independent buckets:

```text
gitnova:rate:user:{userId}
gitnova:rate:repo:{repoId}
gitnova:rate:api:{HTTP_METHOD}_{normalizedRoutePattern}
```

API keys use the Spring MVC route pattern, not the raw request URI, so repository IDs and other
path values do not create unbounded key cardinality.

## Algorithm

Each Redis Hash stores `available_permits` and `last_refill_millis`. One request costs the
configured number of permits. Permits are abstract request permissions, not LLM input/output
tokens. The response exposes the most constrained accepted bucket, or the dimension that rejected
the request.

## Lua atomicity

`redis/token_bucket.lua` uses Redis `TIME`, refills the selected bucket, checks capacity, consumes
the request cost, updates state, and refreshes TTL in one atomic script. Each dimension is an
independent atomic bucket. A request rejected by a later dimension does not roll back permits
already consumed from earlier dimensions; those permits account for attempted traffic.

## TTL

Every bucket has an idle TTL. Configuration validation requires the TTL to be at least the time
needed to refill an empty bucket, preventing expiry from resetting a bucket earlier than normal
refill would allow.

## Failure policy

Rate limiting is disabled by default for local development. When enabled, `fail-open=true` keeps
ordinary APIs available during Redis outages and records a warning. `fail-open=false` returns HTTP
503. A rejected bucket returns HTTP 429 with `X-RateLimit-Limit`, `X-RateLimit-Remaining`,
`X-RateLimit-Dimension`, and `Retry-After`.

## Quota semantics

The token bucket implements burst control and short-window request allowance. Durable daily or
monthly Agent/LLM quota requires a MySQL usage ledger and atomic reservation; it is intentionally
outside this change.

## Main tests

- Stable User, Repository, and normalized API keys.
- Allowed and rejected responses, remaining permits, retry delay, and failure policy.
- Real Redis Lua execution, idle TTL, Redis-time refill, and concurrent consumption shared by two
  limiter instances.

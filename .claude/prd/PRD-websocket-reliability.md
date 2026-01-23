# PRD: WebSocket Connection Reliability for Penpot MCP Plugin

## Problem Statement

The Penpot MCP plugin frequently disconnects from the MCP server, causing users to see "Something bad happened" errors. When disconnected, users must manually reconnect by clicking the "Connect" button in the plugin UI. This creates a poor user experience and interrupts workflows.

## Root Cause

1. **No automatic reconnection** - When WebSocket closes, the plugin does not attempt to reconnect
2. **No keepalive mechanism** - Browser iframes aggressively close idle WebSocket connections (tab throttling, proxy timeouts)
3. **Silent failures** - Pending tasks hang for 30 seconds before timing out with no feedback

## Goals

1. **Automatic reconnection** - Plugin should automatically reconnect when connection drops
2. **Connection stability** - Prevent idle disconnections through keepalive mechanism
3. **User feedback** - Clear status indication during reconnection attempts
4. **Graceful degradation** - Handle reconnection failures gracefully

## Non-Goals

- Multi-user mode improvements (out of scope)
- Server-side connection pooling
- Offline queueing of tasks

## Requirements

### Functional Requirements

#### FR1: Automatic Reconnection
- When WebSocket connection closes unexpectedly, automatically attempt to reconnect
- Use exponential backoff: 1s, 2s, 4s, 8s, 16s (max)
- Maximum reconnection attempts: 10
- Reset attempt counter on successful connection

#### FR2: Keepalive Ping
- Send ping message every 20 seconds when connection is open
- Server responds with pong (or client treats any response as keepalive)
- If no pong received within 5 seconds, consider connection dead and trigger reconnect

#### FR3: Connection Status UI
- Show current connection state: "Connected", "Connecting...", "Reconnecting (attempt X/10)", "Disconnected"
- Color coding: green (connected), yellow (connecting/reconnecting), red (disconnected)
- Show "Connection lost. Retrying..." during reconnection attempts

#### FR4: Manual Override
- "Connect" button should work during any state
- Clicking "Connect" during reconnection should reset the backoff timer
- After max retries exhausted, show "Reconnect" button

### Technical Requirements

#### TR1: Client-Side Changes (main.ts)
- Add `reconnectAttempt` counter and `maxReconnectAttempts` constant
- Add `reconnectDelay` with exponential backoff calculation
- Add `pingInterval` timer (20s interval)
- Modify `ws.onclose` to trigger reconnection logic
- Add `scheduleReconnect()` function

#### TR2: Server-Side Changes (PluginBridge.ts) - Optional
- Handle ping messages and respond with pong
- Clean up pending tasks when client disconnects (reject with connection error)

#### TR3: Protocol
- Ping message format: `{ type: "ping", timestamp: <number> }`
- Pong message format: `{ type: "pong", timestamp: <number> }`

## Success Metrics

- Connection uptime > 99% during active use
- Automatic recovery from network blips within 30 seconds
- Zero manual reconnection needed for idle timeouts

## Implementation Priority

1. **P0**: Automatic reconnection with backoff (solves immediate problem)
2. **P1**: Keepalive ping mechanism (prevents idle disconnects)
3. **P2**: Server-side pending task cleanup (better error messages)

## Risks

- **Browser restrictions**: Some browsers may limit WebSocket reconnection in iframes
- **Mitigation**: Test across Chrome, Firefox, Safari

## Timeline

Estimated: 1-2 hours implementation + testing

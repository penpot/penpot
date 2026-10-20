/**
 * WebSocket close code the MCP server uses for policy violations.
 *
 * The server rejects a second plugin connection for the same user token, and
 * connections missing a token in multi-user mode, with this code.
 */
export const WS_CLOSE_POLICY_VIOLATION = 1008;

/**
 * Returns whether a WebSocket close should trigger an automatic reconnect.
 *
 * Policy violations are terminal: the server refuses the connection again
 * immediately, so retrying only repeats the rejection. Other closes (for
 * example a dropped or restarted server) are retried with backoff.
 */
export function shouldReconnectAfterClose(code: number): boolean {
    return code !== WS_CLOSE_POLICY_VIOLATION;
}

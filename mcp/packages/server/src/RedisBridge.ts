import Redis from "ioredis";
import { PluginTaskRequest, PluginTaskResponse } from "@penpot/mcp-common";
import { createLogger } from "./logger";

/**
 * Handler invoked for a task request arriving on a subscribed request channel.
 */
export type TaskRequestHandler = (request: PluginTaskRequest) => void;

/**
 * Handler invoked for a task response arriving on a subscribed response channel.
 */
export type TaskResponseHandler = (response: PluginTaskResponse<any>) => void;

/**
 * Provides a Redis-backed transport for routing plugin task requests and responses
 * between MCP server instances.
 *
 * The bridge is a pure, stateless transport: it moves already-serialized
 * `PluginTaskRequest` and `PluginTaskResponse` objects between instances and does not
 * interpret their contents, correlate requests with responses, or impose timeouts.
 * Correlation and timeout handling remain the responsibility of the caller (see
 * `PluginBridge`, which routes Redis-delivered responses through the same
 * pending-task machinery used for direct WebSocket dispatch).
 *
 * It enables a tool call handled on one instance to be executed against a plugin
 * whose WebSocket connection lives on another instance: the request is published on a
 * channel keyed by user token (to which the instance holding the plugin connection is
 * subscribed), and the response is published on a channel keyed by task ID (to which
 * the issuing instance subscribes).
 *
 * Two Redis connections are used, as ioredis requires a dedicated connection while
 * subscribed: one for commands and publishing, and one for subscriptions.
 */
export class RedisBridge {
    private readonly logger = createLogger("RedisBridge");
    private readonly publisher: Redis;
    private readonly subscriber: Redis;
    private readonly tenant: string;

    /**
     * Message handlers keyed by channel name.
     *
     * ioredis exposes a single, global message event for all subscribed channels, so
     * incoming messages are dispatched to the correct handler by channel name. Both
     * request-channel and response-channel handlers are stored here.
     */
    private readonly handlers = new Map<string, (rawMessage: string) => void>();

    /**
     * Creates a Redis bridge connected to the given Redis instance.
     *
     * @param redisUri - The Redis connection URI (e.g. `redis://host:6379`)
     * @param tenant - The tenant identifier, used to qualify Redis channel names so that
     *   multiple environments sharing a Redis instance do not interfere.
     */
    constructor(redisUri: string, tenant: string) {
        this.tenant = tenant;
        this.publisher = new Redis(redisUri);
        this.subscriber = new Redis(redisUri);

        this.subscriber.on("message", (channel: string, rawMessage: string) => {
            const handler = this.handlers.get(channel);
            if (handler) {
                handler(rawMessage);
            } else {
                this.logger.warn(`Received message on channel with no registered handler: ${channel}`);
            }
        });
    }

    /** Builds the Redis Pub/Sub channel name for a task request addressed to a user token. */
    private requestChannel(userToken: string): string {
        return `penpot.mcp.${this.tenant}.task.req.${userToken}`;
    }

    /** Builds the Redis Pub/Sub channel name for a task response keyed by task ID. */
    private responseChannel(taskId: string): string {
        return `penpot.mcp.${this.tenant}.task.res.${taskId}`;
    }

    /**
     * Subscribes to the response channel for the given task ID and publishes the task
     * request to the given user token's request channel.
     *
     * The response subscription is established *before* the request is published, to
     * avoid a race in which the response would be published before the subscription is
     * in place. The response handler is invoked at most once and the subscription is
     * removed automatically upon delivery (response channels are single-use).
     *
     * @param userToken - The user token identifying the target plugin's request channel
     * @param request - The serialized plugin task request, passed through verbatim
     * @param onResponse - Handler invoked with the response when it arrives
     */
    async sendTaskRequest(
        userToken: string,
        request: PluginTaskRequest,
        onResponse: TaskResponseHandler
    ): Promise<void> {
        const responseChannel = this.responseChannel(request.id);
        const requestChannel = this.requestChannel(userToken);

        this.handlers.set(responseChannel, (rawMessage) => {
            // a response channel is single-use: remove the handler and unsubscribe on delivery
            this.handlers.delete(responseChannel);
            void this.subscriber.unsubscribe(responseChannel);
            try {
                onResponse(JSON.parse(rawMessage) as PluginTaskResponse<any>);
            } catch (error) {
                this.logger.error(error, "Failed to parse task response message");
            }
        });

        await this.subscriber.subscribe(responseChannel);
        // publish only once the response subscription is confirmed
        await this.publisher.publish(requestChannel, JSON.stringify(request));
    }

    /**
     * Unsubscribes from the response channel for the given task ID.
     *
     * Used to release a response subscription when no response will be processed (e.g.
     * the awaiting task has timed out), since in that case the self-unsubscribe on
     * delivery never occurs.
     *
     * @param taskId - The task ID whose response channel to unsubscribe from
     */
    async unsubscribeFromResponse(taskId: string): Promise<void> {
        const responseChannel = this.responseChannel(taskId);
        this.handlers.delete(responseChannel);
        await this.subscriber.unsubscribe(responseChannel);
    }

    /**
     * Publishes a task response on the response channel for the given task ID.
     *
     * Used by the instance executing a forwarded task to return its outcome to the
     * issuing instance.
     *
     * @param taskId - The ID of the originally requested task
     * @param response - The serialized plugin task response, passed through verbatim
     */
    publishTaskResponse(taskId: string, response: PluginTaskResponse<any>): void {
        const responseChannel = this.responseChannel(taskId);
        void this.publisher.publish(responseChannel, JSON.stringify(response));
    }

    /**
     * Subscribes to task requests for the given user token.
     *
     * The handler is invoked for each request arriving on the token's request channel.
     *
     * @param userToken - The user token whose request channel to subscribe to
     * @param handler - The handler to invoke for incoming requests
     */
    async subscribeToTasks(userToken: string, handler: TaskRequestHandler): Promise<void> {
        const requestChannel = this.requestChannel(userToken);
        this.handlers.set(requestChannel, (rawMessage) => {
            try {
                handler(JSON.parse(rawMessage) as PluginTaskRequest);
            } catch (error) {
                this.logger.error(error, "Failed to parse task request message");
            }
        });
        await this.subscriber.subscribe(requestChannel);
    }

    /**
     * Unsubscribes from task requests for the given user token.
     *
     * @param userToken - The user token whose request channel to unsubscribe from
     */
    async unsubscribeFromTasks(userToken: string): Promise<void> {
        const requestChannel = this.requestChannel(userToken);
        this.handlers.delete(requestChannel);
        await this.subscriber.unsubscribe(requestChannel);
    }

    /**
     * Closes both Redis connections. Call on server shutdown.
     */
    async close(): Promise<void> {
        await this.subscriber.quit();
        await this.publisher.quit();
    }
}

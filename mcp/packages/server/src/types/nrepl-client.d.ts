declare module "nrepl-client" {
    import type { Socket } from "net";

    interface NreplConnection extends Socket {
        /**
         * Evaluates the given Clojure expression on the nREPL server.
         *
         * @param code - the Clojure expression to evaluate
         * @param callback - called with an error or array of response messages
         */
        eval(code: string, callback: (err: Error | null, result: NreplMessage[]) => void): void;

        /**
         * Sends a raw nREPL message to the server.
         */
        send(message: Record<string, unknown>, callback: (err: Error | null, result: NreplMessage[]) => void): void;

        /**
         * Clones the current session.
         */
        clone(callback: (err: Error | null, result: NreplMessage[]) => void): void;

        /**
         * Closes the current session.
         */
        close(callback: (err: Error | null, result: NreplMessage[]) => void): void;
    }

    interface NreplMessage {
        id?: string;
        session?: string;
        ns?: string;
        value?: string;
        out?: string;
        err?: string;
        ex?: string;
        status?: string[];
    }

    interface ConnectOptions {
        port: number;
        host?: string;
    }

    /**
     * Creates a connection to an nREPL server.
     */
    function connect(options: ConnectOptions): NreplConnection;

    export default { connect };
}

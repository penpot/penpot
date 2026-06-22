import { z } from "zod";
import { Tool } from "../Tool";
import { TextResponse, ToolResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { NreplClient } from "../NreplClient";
import { createLogger } from "../logger";
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as https from "https";
import * as http from "http";

/**
 * Arguments for ImportPenpotFileTool.
 */
export class ImportPenpotFileArgs {
    static schema = {
        url: z.url().describe("URL of the .penpot file to import."),
    };

    /** URL of the .penpot file to import */
    url!: string;
}

/**
 * Tool for importing a .penpot file into the running Penpot instance.
 *
 * Downloads the file from the given URL to a temporary location in the frontend's
 * static directory, then triggers the import via the Penpot frontend's web worker
 * using the ClojureScript REPL. The temporary file is cleaned up after the import
 * completes (or fails).
 *
 * Only available in devenv mode, as it requires the ClojureScript nREPL connection.
 */
export class ImportPenpotFileTool extends Tool<ImportPenpotFileArgs> {
    private static readonly POLL_INTERVAL_MS = 1_000;
    private static readonly IMPORT_TIMEOUT_MS = 120_000;

    // assumes cwd is the server package root (same assumption as ConfigurationLoader)
    private static readonly PUBLIC_DIR = path.resolve("../../../frontend/resources/public");

    private static readonly NAVIGATION_HINT =
        "To open an imported file in the workspace, use cljs_repl with:\n" +
        "(do (require '[app.main.data.common :as dcm])\n" +
        "    (app.main.store/emit! (dcm/go-to-workspace\n" +
        '      :team-id (parse-uuid "<team-id>")\n' +
        '      :file-id (parse-uuid "<file-id>")\n' +
        '      :page-id (parse-uuid "<page-id>"))))';

    private readonly log = createLogger("ImportPenpotFileTool");
    private readonly nreplClient: NreplClient;

    /**
     * Creates a new ImportPenpotFileTool instance.
     *
     * @param mcpServer - the MCP server instance
     * @param nreplClient - the nREPL client for communicating with shadow-cljs
     */
    constructor(mcpServer: PenpotMcpServer, nreplClient: NreplClient) {
        super(mcpServer, ImportPenpotFileArgs.schema);
        this.nreplClient = nreplClient;
    }

    public getToolName(): string {
        return "import_penpot_file";
    }

    public getToolDescription(): string {
        return (
            "Imports a .penpot file into the running Penpot instance from a given URL. " +
            "The file is imported into the user's Drafts project. " +
            "Returns the name(s) of the imported file(s)."
        );
    }

    protected async executeCore(args: ImportPenpotFileArgs): Promise<ToolResponse> {
        // generate a random filename for the temporary file
        const randomName = `_import_${crypto.randomUUID()}.penpot`;
        const tempFilePath = path.join(ImportPenpotFileTool.PUBLIC_DIR, randomName);
        const servePath = `/${randomName}`;

        try {
            // download the file
            this.log.info("Downloading .penpot file from %s", args.url);
            await this.downloadFile(args.url, tempFilePath);
            const fileSize = fs.statSync(tempFilePath).size;
            this.log.info("Downloaded %d bytes to %s", fileSize, tempFilePath);

            // set up the import via CLJS REPL
            const atomName = `import-result-${crypto.randomUUID().slice(0, 8)}`;
            const setupCode = this.buildImportCode(atomName, servePath);

            this.log.info("Initiating import via CLJS REPL");
            const setupResult = await this.nreplClient.evalCljs(setupCode);
            this.log.debug("CLJS setup result: %s", JSON.stringify(setupResult));

            // check for immediate errors in the setup
            if (setupResult.err) {
                throw new Error(`CLJS evaluation error: ${setupResult.err}`);
            }

            // poll for the import result
            const result = await this.pollForResult(atomName);
            return new TextResponse(result);
        } finally {
            // clean up the temporary file
            this.cleanupTempFile(tempFilePath);
        }
    }

    /**
     * Builds the ClojureScript code that fetches the file from the static directory,
     * creates a blob URL, and triggers the import via the web worker.
     *
     * @param atomName - unique name for the result atom
     * @param servePath - the URL path to fetch the file from (same-origin)
     * @returns the ClojureScript code string
     */
    private buildImportCode(atomName: string, servePath: string): string {
        // escape for embedding in a CLJS string
        const escapedPath = servePath.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
        const escapedAtom = atomName.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

        return `
            (do
              (require '[app.main.store :as st])
              (require '[app.main.worker :as mw])
              (require '[app.common.uuid :as uuid])
              (require '[beicon.v2.core :as rx])

              (def ${escapedAtom} (atom {:status :pending}))

              (let [project-id (->> @st/state :projects vals (filter :is-default) first :id)
                    file-ids-before (set (keys (:files @st/state)))]
                (-> (js/fetch "${escapedPath}")
                    (.then (fn [resp]
                             (when-not (.-ok resp)
                               (reset! ${escapedAtom} {:status :error :error (str "Fetch failed: " (.-status resp))})
                               (throw (js/Error. (str "Fetch failed: " (.-status resp)))))
                             (.blob resp)))
                    (.then (fn [blob]
                             (let [uri (js/URL.createObjectURL blob)
                                   file-id (uuid/next)
                                   entries [{:file-id file-id
                                             :name "import"
                                             :type :binfile-v3
                                             :uri uri}]]
                               (->> (mw/ask-many!
                                     {:cmd :import-files
                                      :project-id project-id
                                      :files entries
                                      :features (get @st/state :features)})
                                    (rx/subs!
                                     (fn [msg]
                                       (when (= :finish (:status msg))
                                         (reset! ${escapedAtom}
                                           {:status :success
                                            :file-ids-before file-ids-before})))
                                     (fn [err]
                                       (reset! ${escapedAtom} {:status :error :error (str err)}))
                                     (fn []
                                       (when (= :pending (:status @${escapedAtom}))
                                         (reset! ${escapedAtom} {:status :error :error "Stream completed without success message"}))))))))
                    (.catch (fn [err]
                              (when (= :pending (:status @${escapedAtom}))
                                (reset! ${escapedAtom} {:status :error :error (str err)}))))))

              :initiated)
            `;
    }

    /**
     * Builds the ClojureScript code that resolves the imported file details.
     *
     * Refreshes the dashboard, diffs the file list against the pre-import snapshot,
     * and for each new file fetches the first page-id via the backend API.
     *
     * @param atomName - the atom holding the import result (including :file-ids-before)
     * @param resultAtomName - the atom to store the final file details in
     * @returns the ClojureScript code string
     */
    private buildResolveCode(atomName: string, resultAtomName: string): string {
        return `
            (do
              (require '[app.main.store :as st])
              (require '[app.main.repo :as rp])
              (require '[app.main.data.dashboard :as dd])
              (require '[beicon.v2.core :as rx])

              (def ${resultAtomName} (atom {:status :pending}))

              (let [file-ids-before (:file-ids-before @${atomName})
                    team-id (:current-team-id @st/state)]
                ;; refresh dashboard files
                (st/emit! (dd/fetch-recent-files))
                ;; wait a moment for the state to update, then resolve
                (js/setTimeout
                  (fn []
                    (let [all-files (vals (:files @st/state))
                          new-files (remove #(contains? file-ids-before (:id %)) all-files)
                          file-count (count new-files)]
                      (if (zero? file-count)
                        (reset! ${resultAtomName} {:status :success :files []})
                        ;; fetch page-ids for each new file
                        (let [remaining (atom file-count)
                              results (atom [])]
                          (doseq [f new-files]
                            (->> (rp/cmd! :get-file {:id (:id f) :features (get @st/state :features)})
                                 (rx/subs!
                                   (fn [file-data]
                                     (swap! results conj
                                       {:file-id (str (:id f))
                                        :name (:name f)
                                        :team-id (str team-id)
                                        :page-id (str (first (get-in file-data [:data :pages])))})
                                     (when (zero? (swap! remaining dec))
                                       (reset! ${resultAtomName} {:status :success :files @results})))
                                   (fn [err]
                                     (swap! results conj
                                       {:file-id (str (:id f))
                                        :name (:name f)
                                        :team-id (str team-id)
                                        :error (str err)})
                                     (when (zero? (swap! remaining dec))
                                       (reset! ${resultAtomName} {:status :success :files @results}))))))))))
                  500))

              :initiated)
            `;
    }

    /**
     * Polls the CLJS atom for the import result until it succeeds, fails, or times out.
     * On success, resolves the imported file details (server-side IDs, names, page-ids).
     *
     * @param atomName - the name of the atom to poll
     * @returns a JSON string with the imported file details
     */
    private async pollForResult(atomName: string): Promise<string> {
        const startTime = Date.now();

        // phase 1: wait for the import to complete
        while (Date.now() - startTime < ImportPenpotFileTool.IMPORT_TIMEOUT_MS) {
            await this.sleep(ImportPenpotFileTool.POLL_INTERVAL_MS);

            const pollResult = await this.nreplClient.evalCljs(`(pr-str @${atomName})`);
            const resultStr = pollResult.values.join("");
            this.log.debug(`Poll result: ${resultStr}`);

            if (resultStr.includes(":success")) {
                this.log.info("Import succeeded, resolving file details...");
                return await this.resolveImportedFiles(atomName);
            } else if (resultStr.includes(":error")) {
                this.log.error(`Import failed: ${resultStr}`);
                throw new Error(`Import failed: ${resultStr}`);
            }
        }

        throw new Error(`Import timed out after ${ImportPenpotFileTool.IMPORT_TIMEOUT_MS / 1000} seconds`);
    }

    /**
     * After a successful import, resolves the actual server-side file details
     * by diffing the dashboard file list and fetching page IDs.
     *
     * @param atomName - the atom holding the import result with :file-ids-before
     * @returns a JSON string with the imported file details
     */
    private async resolveImportedFiles(atomName: string): Promise<string> {
        const resultAtomName = `import-details-${crypto.randomUUID().slice(0, 8)}`;
        const resolveCode = this.buildResolveCode(atomName, resultAtomName);

        await this.nreplClient.evalCljs(resolveCode);

        // poll the result atom
        const startTime = Date.now();
        const resolveTimeoutMs = 15_000;

        while (Date.now() - startTime < resolveTimeoutMs) {
            await this.sleep(ImportPenpotFileTool.POLL_INTERVAL_MS);

            const pollResult = await this.nreplClient.evalCljs(`(pr-str @${resultAtomName})`);
            const resultStr = pollResult.values.join("");

            if (resultStr.includes(":success")) {
                this.log.info("File details resolved");
                return resultStr + "\n\n" + ImportPenpotFileTool.NAVIGATION_HINT;
            }
        }

        this.log.warn("Timed out resolving file details, returning basic success");
        return "Import succeeded but could not resolve file details.";
    }

    /**
     * Downloads a file from a URL to a local path.
     *
     * @param url - the URL to download from
     * @param destPath - the local file path to write to
     */
    private downloadFile(url: string, destPath: string): Promise<void> {
        return new Promise((resolve, reject) => {
            const client = url.startsWith("https") ? https : http;
            const file = fs.createWriteStream(destPath);

            const request = client.get(url, (response) => {
                // handle redirects
                if (
                    response.statusCode &&
                    response.statusCode >= 300 &&
                    response.statusCode < 400 &&
                    response.headers.location
                ) {
                    file.close();
                    fs.unlinkSync(destPath);
                    this.downloadFile(response.headers.location, destPath).then(resolve, reject);
                    return;
                }

                if (response.statusCode && response.statusCode !== 200) {
                    file.close();
                    fs.unlinkSync(destPath);
                    reject(new Error(`Download failed with status ${response.statusCode}`));
                    return;
                }

                response.pipe(file);
                file.on("finish", () => {
                    file.close();
                    resolve();
                });
            });

            request.on("error", (err) => {
                file.close();
                if (fs.existsSync(destPath)) {
                    fs.unlinkSync(destPath);
                }
                reject(new Error(`Download error: ${err.message}`));
            });

            file.on("error", (err) => {
                file.close();
                if (fs.existsSync(destPath)) {
                    fs.unlinkSync(destPath);
                }
                reject(new Error(`File write error: ${err.message}`));
            });
        });
    }

    /**
     * Removes the temporary file, logging but not throwing on failure.
     */
    private cleanupTempFile(filePath: string): void {
        try {
            if (fs.existsSync(filePath)) {
                fs.unlinkSync(filePath);
                this.log.info("Cleaned up temporary file: %s", filePath);
            }
        } catch (err) {
            this.log.warn("Failed to clean up temporary file %s: %s", filePath, err);
        }
    }

    private sleep(ms: number): Promise<void> {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }
}

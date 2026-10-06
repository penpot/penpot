;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.pilot
  "The diagnostic pilot executes one unscored case in headless Chromium.
  An unscored attempt checks integration without a performance conclusion.
  Stdout contains one raw RunRecord schema3 in Transit. The record
  stores the saved plan, provenance, preparation, attempt and remaining slots.

  THIS IS A SMOKE TEST AND THROWAWAY CODE. DO NOT USE THIS DESIGN AS GUIDANCE.

  TODO(mem:render-wasm/performance/cljs-rewrite/10-process-build-and-server):
  the inline static server and artifact layout here are pilot scaffolding.
  Ticket 10 owns staging and server ownership; this only serves the release
  browser bundle, the prepared renderer artifacts and `pilot.html` on an
  ephemeral 127.0.0.1 port.

  TODO(mem:render-wasm/performance/cljs-rewrite/11-runner-and-failure-accounting):
  CLI surface, orchestration style, attempt loop and exit policy belong to
  ticket 11. Raw `.then` chains below are diagnostic scaffolding, not the
  runner's interop pattern.

  Exit codes: 0 when the case completes; 1 on case failures, harness errors and
  timeouts. Failures retain raw records. A timeout closes the browser, including
  a synchronously blocked page.

  Usage, from `frontend/` with Playwright's Chromium installed:

    Follow README.md's prepared renderer release commands first.
    clojure -M:dev:renderer-bench release bench-render-wasm-browser
    pnpm run build:renderer-benchmarks:pilot
    node target/performance/render-wasm/pilot.cjs --seed 42 --screenshot /tmp/pilot-rects.png

  The pilot serves the release browser bundle, so compile
  `bench-render-wasm-browser` first (after the prepared renderer build
  refreshes `shared.js`). Flags: `--seed N` (default 42),
  `--timeout-ms N` (default 30000), `--screenshot PATH` (optional untimed
  capture for visual inspection)."
  (:require
   ["child_process" :as child-process]
   ["fs" :as fs]
   ["http" :as http]
   ["os" :as os]
   ["path" :as path]
   ["playwright" :as playwright]
   ["playwright/package.json" :as playwright-package]
   [app.common.transit :as t]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.measurement :as measurement]
   [benches.render-wasm.random :as random]
   [benches.render-wasm.report.format :as format]
   [benches.render-wasm.report.summarize :as summary]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(def ^:private default-seed 42)
(def ^:private default-timeout-ms 30000)

(defn- frontend-root
  "Resolves the frontend checkout root from the compiled pilot location
  (`target/performance/render-wasm/pilot.cjs`, next to `runner.cjs`). Ticket 10
  owns command cwd resolution; the pilot pins its own roots instead."
  []
  (path/resolve js/__dirname ".." ".." ".."))

(def ^:private mime
  {".html" "text/html"
   ".js" "text/javascript"
   ".wasm" "application/wasm"
   ".json" "application/json"})

(defn- safe-join
  "Joins `sub` under `root`, returning nil when it escapes (path traversal)."
  [root sub]
  (let [joined (path/join root sub)]
    (when-not (str/includes? (path/relative root joined) "..")
      joined)))

(defn- serve-file!
  "Serves an existing artifact with its MIME type, or returns a missing status."
  [^js res file]
  (if (and (some? file) (fs/existsSync file))
    (let [ext (path/extname file)]
      (.writeHead res 200 #js {"Content-Type" (get mime ext "application/octet-stream")})
      (.end res (fs/readFileSync file)))
    (do (.writeHead res 404 #js {"Content-Type" "text/plain"})
        (.end res "not found"))))

(defn- start-server!
  "Serves the pilot page, the release browser bundle and the prepared
  renderer artifacts. Returns a promise of `{server port}`."
  [browser-dir js-dir html-file]
  (js/Promise.
   (fn [resolve _reject]
     (let [^js server (http/createServer
                       (fn [^js req ^js res]
                         (let [url (.-url ^js req)]
                           (cond
                             (= url "/") (serve-file! res html-file)
                             (= url "/pilot.html") (serve-file! res html-file)
                             (str/starts-with? url "/bench/")
                             (serve-file! res (safe-join browser-dir (subs url (count "/bench/"))))
                             (str/starts-with? url "/js/")
                             (serve-file! res (safe-join js-dir (subs url (count "/js/"))))
                             :else (do (.writeHead res 404 #js {"Content-Type" "text/plain"})
                                       (.end res "not found"))))))]
       (.listen server 0 "127.0.0.1"
                (fn [] (resolve #js {"server" server
                                     "port" (.-port (.address server))})))))))

(defn- usage
  "Returns the diagnostic pilot's command syntax."
  []
  "pilot [--case SCENE/NAME] [--seed N] [--timeout-ms N] [--screenshot PATH]")

(defn- parse-args
  "Resolves one case, seed, deadline and optional screenshot path."
  [argv]
  (let [opts (loop [args argv
                    opts {:seed default-seed :timeout-ms default-timeout-ms
                          :case-id :rects/load}]
               (if (empty? args)
                 opts
                 (let [[flag value & rest-args] args]
                   (cond
                     (= flag "--seed") (recur rest-args (assoc opts :seed (js/parseInt value 10)))
                     (= flag "--case") (recur rest-args (assoc opts :case-id (when value (keyword value))))
                     (= flag "--timeout-ms") (recur rest-args (assoc opts :timeout-ms (js/parseInt value 10)))
                     (= flag "--screenshot") (recur rest-args (assoc opts :screenshot value))
                     :else (recur rest-args (assoc opts ::invalid flag))))))
        valid-timeout? (fn [n] (and (integer? n) (pos? n)))]
    (if (and (nil? (::invalid opts))
             (random/seed? (:seed opts)) (valid-timeout? (:timeout-ms opts))
             (some #{(:case-id opts)} (cases/case-ids)))
      opts
      (do
        (.error js/console (str "pilot: invalid args " (pr-str opts)))
        (.error js/console (usage))
        (js/process.exit 1)))))

(defn- trace
  "Diagnostic line on stderr (transit)"
  [message]
  (.error js/console message))

(defn- print-result!
  "Prints the raw schema3 record (transit)"
  [record]
  (println (result/encode record)))

(defn- git-provenance
  "Reads the revision and dirty flag without saving status text or diffs."
  [root]
  (try
    (let [opts #js {:cwd root :encoding "utf8"}]
      {:sha (str/trim (child-process/execFileSync "git" #js ["rev-parse" "HEAD"] opts))
       :dirty (not (str/blank? (child-process/execFileSync "git" #js ["status" "--porcelain"] opts)))})
    (catch :default _ {:sha nil :dirty nil})))

(defn- pilot-metadata
  "Captures available environment and declared build facts for an unscored pilot.
  Prepared artifacts lack ticket10's stamp, so their functional identity stays
  unverified. Environment capture uses a fixed allowlist."
  [root opts]
  (let [env (into {} (keep (fn [k]
                             (when-some [v (unchecked-get (.-env js/process) k)] [k v])))
                  ["BUILD_MODE" "NODE_ENV" "PENPOT_WASM_PREPARED" "PENPOT_WASM_FUNCTION_NAMES"
                   "CARGO_BUILD_TARGET" "CARGO_NET_OFFLINE" "COREPACK_ENABLE_NETWORK"])
        cpus (array-seq (os/cpus))]
    {:git (git-provenance root)
     :build {:functional {:prepared-artifact-settings :unverified :cljs-profile-marks false}
             :provenance {:artifact-source :prepared-public-assets :declared-env env}}
     :environment {:node (.-version js/process) :playwright (.-version playwright-package)
                   :browser nil :platform (os/platform) :release (os/release) :arch (os/arch)
                   :cpu {:models (vec (distinct (map #(.-model %) cpus))) :logical-count (count cpus)}
                   :launch {:headless true :args ["--enable-gpu"]}}
     :scored? false
     :diagnostics (select-keys opts [:seed :screenshot])}))

(defn- complete-record
  "Adds available preparation and attempt evidence, then records termination.
  Warm setup errors leave the slot unattempted. Fresh setup belongs to the
  attempted cold load and consumes its slot even when setup fails."
  [run preparation evidence attempted?]
  (let [id (get-in run [:plan :cases 0 :id])
        run (cond-> run preparation (result/record-preparation preparation))
        run (if attempted?
              (result/record-attempt run (measurement/attempt id (:preparation-id preparation) false evidence))
              run)
        ok? (= "ok" (:status evidence))]
    (result/finish-run run (.toISOString (js/Date.))
                       (cond-> {:reason (if ok? :completed (if attempted? :failed :preparation-failed))}
                         (not ok?) (assoc :failure (select-keys evidence [:status :phase :message :cause])
                                          :partial (:partial evidence))))))

(defn- evaluate-bridge
  "Calls a fixed bridge method with one Transit string and returns its Promise.
  JSON quoting embeds the string in JavaScript; records use Transit only."
  [^js page method text]
  (.evaluate page (str "window.__benchBridge." method "(" (.stringify js/JSON text) ")")))

(defn -main
  "Runs one unscored attempt and verifies Node/browser summaries of its raw record."
  [& argv]
  (trace "pilot: parsing args")
  (let [opts     (parse-args (or argv []))
        root     (frontend-root)
        bundle   (path/join root "target" "performance" "render-wasm" "browser")
        js-dir   (path/join root "resources" "public" "js")
        html     (path/join root "performance" "bench" "render_wasm" "browser" "pilot.html")
        seed     (:seed opts)
        selected (some #(when (= (:id %) (:case-id opts)) %)
                       (cases/collect-cases {:master-seed seed}))
        deadline (:timeout-ms opts)
        started-at (.toISOString (js/Date.))
        initial-run (result/create-run {:run-id (str "pilot-" started-at) :started-at started-at
                                        :plan {:cases [selected] :warmups 0 :repetitions 1
                                               :timeout-policy {:attempt-ms deadline :pilot-total-ms deadline}}
                                        :metadata (pilot-metadata root opts)})
        preparation-ref (atom nil)
        attempted-ref (atom false)
        run-ref (atom initial-run)]
    (-> (start-server! bundle js-dir html)
        (.then
         (fn [info]
           (trace "pilot: server up")
           (let [server (unchecked-get info "server")
                 port   (unchecked-get info "port")
                 url    (str "http://127.0.0.1:" port "/pilot.html")
                 close! (fn [] (js/Promise.resolve (.close server)))
                 wait-for-bench-ready
                 (fn [^js page] (fn [] (.waitForFunction page "window.__benchReady === true")))
                 evaluate-load
                 (fn [^js page] (fn [_]
                                  (trace "pilot: bridge ready, evaluating")
                                  (when (= :fresh (:context selected))
                                    (reset! attempted-ref true))
                                  (let [request (t/encode-str {:case selected :timeout-ms deadline
                                                               :module-url "/js/render-wasm.js"
                                                               :wasm-url "/js/render-wasm.wasm"})]
                                    (-> (evaluate-bridge page "prepareScene" request)
                                        (.then (fn [text]
                                                 (let [prepared (t/decode-str text)]
                                                   (if (= "ok" (:status prepared))
                                                     (do
                                                       (reset! preparation-ref (:preparation prepared))
                                                       (reset! attempted-ref true)
                                                       (evaluate-bridge page "runPrepared"
                                                                        (t/encode-str {:preparation-id (get-in prepared [:preparation :preparation-id])
                                                                                       :timeout-ms deadline})))
                                                     text))))))))
                 attach-screenshot-path
                 (fn [^js page version] (fn [text]
                                          (let [out (complete-record
                                                     (assoc-in @run-ref [:metadata :environment :browser] version)
                                                     @preparation-ref (t/decode-str text) @attempted-ref)
                                                report (summary/summarize out)
                                                check-report (fn [_]
                                                               (-> (evaluate-bridge page "summarize" (result/encode out))
                                                                   (.then (fn [browser-summary]
                                                                            (when-not (= report (t/decode-str browser-summary))
                                                                              (throw (ex-info "Node/browser summaries differ" {})))
                                                                            (trace "pilot: Node/browser summaries agree")
                                                                            (trace (format/format-run report))
                                                                            out))))]
                                            (reset! run-ref out)
                                            (-> (if-let [shot (:screenshot opts)]
                                                  (.screenshot page #js {"path" shot})
                                                  (js/Promise.resolve nil))
                                                (.then check-report)))))
                 viewport  #js {"viewport"
                                #js {"width" 1920 "height" 1080}
                                "deviceScaleFactor" 2}
                 ;; Holder so the outer timeout path can close a browser
                 ;; the work chain never returned (blocked page, or a
                 ;; launch/context/page failure after launch).
                 browser-ref (atom nil)
                 chromium-launch (-> (.-chromium playwright)
                                     (.launch #js {"args" #js ["--enable-gpu"]}))
                 work
                 (-> chromium-launch
                     (.then
                      (fn [^js pw-browser]
                        (reset! browser-ref pw-browser)
                        (let [version (.version pw-browser)]
                          (swap! run-ref assoc-in [:metadata :environment :browser] version)
                          (-> (.newContext pw-browser viewport)
                              (.then
                               (fn [^js context]
                                 (-> (.newPage context)
                                     (.then
                                      (fn [^js page]
                                        (trace "pilot: page open")
                                        (-> (.goto page url)
                                            (.then (wait-for-bench-ready page))
                                            (.then (evaluate-load page))
                                            (.then (attach-screenshot-path page version))
                                            (.then (fn [out]
                                                     (-> (.evaluate page "window.__benchBridge.dispose()")
                                                         (.then (fn [_] (.close page)))
                                                         (.then (fn [_] (.close context)))
                                                         (.then (fn [_] (.close pw-browser)))
                                                         (.then (fn [_] out)))))
                                            (.catch (fn [cause]
                                                      (.close pw-browser)
                                                      (throw cause))))))))))))))
                 guard  (js/Promise.
                         (fn [_resolve reject]
                           (js/setTimeout
                            (fn [] (reject (ex-info "pilot timeout" {:timeout-ms deadline})))
                            deadline)))]
             (-> (js/Promise.race #js [work guard])
                 (.then (fn [out]
                          (-> (close!)
                              (.then (fn [_]
                                       (print-result! out)
                                       (js/process.exit
                                        (if (= :completed (get-in out [:termination :reason])) 0 1)))))))
                 (.catch (fn [cause]
                           ;; The work chain may have left a browser open
                           ;; (guard timeout, blocked page, post-launch
                           ;; failure): close it before the server so no
                           ;; Chromium is orphaned. Double close is safe.
                           (-> (if-let [b @browser-ref]
                                 (-> (.close b)
                                     (.catch (fn [_] nil)))
                                 (js/Promise.resolve nil))
                               (.then (fn [_] (close!)))
                               (.then (fn [_]
                                        (if (= :running (get-in @run-ref [:termination :reason]))
                                          (print-result! (complete-record @run-ref @preparation-ref
                                                                          {:status "failed" :phase "pilot-harness"
                                                                           :message (or (ex-message cause) (str cause))
                                                                           :cause (failures/describe-cause cause)}
                                                                          @attempted-ref))
                                          (print-result! (assoc @run-ref :termination
                                                                {:reason :diagnostic-failed :cause (failures/describe-cause cause)})))
                                        (js/process.exit 1))))))))))
        (.catch (fn [cause]
                  (print-result! (complete-record initial-run nil
                                                  {:status "failed" :phase "pilot-startup"
                                                   :message (or (ex-message cause) (str cause))
                                                   :cause (failures/describe-cause cause)} false))
                  (js/process.exit 1))))))

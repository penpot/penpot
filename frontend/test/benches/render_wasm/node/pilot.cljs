;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.pilot
  "Interim diagnostic: loads `:rects/load` in headless Chromium and prints
  a Transit record with the bridge result on stdout.

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

  Exit codes: 0 when a result transit string is produced (including bridge
  failure maps, which are valid pilot output); 1 on harness errors and
  timeouts. A timeout closes the browser, so it stops even a synchronously blocked
  page.

  Usage, from `frontend/` with Playwright's Chromium installed:

    PENPOT_WASM_PREPARED=1 ../render-wasm/build frontend
    clojure -M:dev:renderer-bench release bench-render-wasm-browser
    pnpm run build:renderer-benchmarks:pilot
    node target/renderer-benchmarks/pilot.cjs --seed 42 --screenshot /tmp/pilot-rects.png

  The pilot serves the release browser bundle, so compile
  `bench-render-wasm-browser` first (after the prepared renderer build
  refreshes `shared.js`). Flags: `--seed N` (default 42),
  `--timeout-ms N` (default 30000), `--screenshot PATH` (optional untimed
  capture for visual inspection)."
  (:require
   ["fs" :as fs]
   ["http" :as http]
   ["path" :as path]
   ["playwright" :as playwright]
   [app.common.transit :as t]
   [benches.render-wasm.cases :as cases]
   [clojure.string :as str]))

(def ^:private default-seed 42)
(def ^:private default-timeout-ms 30000)

(defn- frontend-root
  "Resolves the frontend checkout root from the compiled pilot location
  (`target/renderer-benchmarks/pilot.cjs`, next to `runner.cjs`). Ticket 10
  owns command cwd resolution; the pilot pins its own roots instead."
  []
  (path/resolve js/__dirname ".." ".."))

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
  []
  "pilot [--case SCENE/NAME] [--seed N] [--timeout-ms N] [--screenshot PATH]")

(defn- parse-args
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
        valid-seed?    (fn [n] (and (integer? n) (<= 0 n 4294967295)))
        valid-timeout? (fn [n] (and (integer? n) (pos? n)))]
    (if (and (nil? (::invalid opts))
             (valid-seed? (:seed opts)) (valid-timeout? (:timeout-ms opts))
             (some #{(:case-id opts)} (cases/case-ids)))
      opts
      (do
        (.error js/console (str "pilot: invalid args " (pr-str opts)))
        (.error js/console (usage))
        (js/process.exit 1)))))

(defn- trace
  "Diagnostic line on stderr; stdout carries only the result transit string."
  [message]
  (.error js/console message))

(defn- print-result!
  [result]
  (println (t/encode-str result)))

(defn -main
  [& argv]
  (trace "pilot: parsing args")
  (let [opts     (parse-args (or argv []))
        root     (frontend-root)
        bundle   (path/join root "target" "renderer-benchmarks" "browser")
        js-dir   (path/join root "resources" "public" "js")
        html     (path/join root "test" "benches" "render_wasm" "browser" "pilot.html")
        seed     (:seed opts)
        selected (some #(when (= (:id %) (:case-id opts)) %)
                       (cases/collect-cases {:master-seed seed}))
        deadline (:timeout-ms opts)]
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
                                  ;; NOTE: a string page-function evaluates
                                  ;; as an expression, so inline the args
                                  ;; into an IIFE; a passed arg would
                                  ;; never arrive.
                                  (let [request   (t/encode-str
                                                   {:case selected
                                                    :module-url "/js/render-wasm.js"
                                                    :wasm-url "/js/render-wasm.wasm"})
                                        call      (str "(async () => window.__benchBridge.loadScene("
                                                       (.stringify js/JSON request)
                                                       "))()")]
                                    (.evaluate page call))))
                 attach-screenshot-path
                 (fn [^js page ^js version] (fn [result]
                                              (let [out {:seed seed
                                                         :case (:id selected)
                                                         :scored false
                                                         :attempts 1
                                                         :result (t/decode-str result)
                                                         :browser {:chromium version}}]
                                                (if-let [shot (:screenshot opts)]
                                                  (-> (.screenshot
                                                       page
                                                       #js {"path" shot})
                                                      (.then (fn [_]
                                                               (assoc out :screenshot shot))))
                                                  out))))
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
                                        (if (= "ok" (get-in out [:result :status])) 0 1)))))))
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
                                        (println "pilot harness failure:"
                                                 (or (ex-message cause) (str cause)))
                                        (js/process.exit 1))))))))))
        (.catch (fn [cause]
                  (println "pilot startup failure:" (or (ex-message cause) (str cause)))
                  (js/process.exit 1))))))

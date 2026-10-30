;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.build
  "Prepares retained release artifacts under a single preparation deadline.

  The caller owns exclusive preparation for this checkout. The renderer's
  lock protects Cargo and copying; CLJS compilation follows after it releases.
  Preparation uses installed dependencies. Cargo may fetch missing crates and
  the custom Skia binaries on a cold build. Every attempt retains its manifest
  and compiler log, including failed attempts."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["url" :as url]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors :include-macros true :refer [attempt failure?]]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.options :as options]
   [benches.render-wasm.node.process :as process]
   [clojure.string :as str]))

(defn ^:async git-provenance
  "Captures revision and dirty state through the same bounded child boundary."
  [root bounds]
  (try
    (let [options (merge bounds {:command "git" :cwd root})
          sha     (await (process/run (assoc options :args ["rev-parse" "HEAD"])))
          status  (await (process/run (assoc options :args ["status" "--porcelain"])))]
      {:sha (str/trim (:stdout sha)) :dirty (not (str/blank? (:stdout status)))})
    (catch :default cause
      (if (contains? #{:process-timeout :process-cancelled} (:type (ex-data cause)))
        (throw cause)
        (do
          (log/warn (str "Git provenance unavailable: " (errors/message-of cause)))
          {:sha nil :dirty nil})))))

(defn- check-profile-marks
  [compiled]
  (when-not (false? (:cljs-profile-marks compiled))
    (throw (ex-info "compiled benchmark enables profiling marks or omits its setting"
                    {:type ::profile-marks :compiled compiled})))
  compiled)

(defn- ^:async validate-browser
  "Imports the release bridge in Node without preparing a renderer.
  The bridge's identity method returns the compiled identity over Transit."
  [build root bounds]
  (let [module-url (.-href (url/pathToFileURL (path/join (:browser-dir build) "browser.js")))
        script     "const {default: bridge} = await import(process.env.RENDER_BENCH_MODULE_URL); process.stdout.write(bridge.buildIdentity());"
        result     (await (process/run (merge bounds
                                              {:command (.-execPath js/process) :cwd root
                                               :args ["--input-type=module" "-e" script]
                                               :env {"RENDER_BENCH_MODULE_URL" module-url}})))]
    (check-profile-marks (identity/check (:id build) (transit/decode-str (:stdout result))))))

(defn ^:async prepare
  "Returns a ready build reference after compilation and identity validation.

  Options: frontend :root, :selection or :features, :build-timeout-ms
  (300000), AbortSignal :signal, and optional :on-created callback. The callback
  runs once after directory, log and initial manifest creation. CLI callers
  print the ID there. Storage errors have no recorded ID. Later errors carry
  :build-id and :phase. An error also carries :manifest-error and is fatal when
  the failed manifest cannot be saved. A retry always creates a new attempt."
  [{:keys [root features selection build-timeout-ms signal on-created]
    :or {build-timeout-ms options/default-build-timeout-ms}}]
  (when-not (and (js/Number.isSafeInteger build-timeout-ms) (pos? build-timeout-ms))
    (throw (ex-info "build timeout must be a positive integer" {:type ::invalid-timeout})))
  (let [root      (or root (artifacts/frontend-root))
        selection (options/feature-selection (or selection features))
        deadline  (process/deadline build-timeout-ms)
        bounds    {:deadline deadline :signal signal}
        initial   (artifacts/create-build root selection {:git {:sha nil :dirty nil}
                                                          :feature-selection selection})
        build-ref (atom initial)
        phase     (atom :storage)]
    (binding [log/*log-path* (:log-path initial)]
      (try
        (when on-created (on-created initial))
        (reset! phase :provenance)
        (let [git (await (git-provenance root bounds))]
          (swap! build-ref #(artifacts/write-manifest % (assoc-in (:manifest %) [:provenance :git] git))))
        (reset! phase :renderer)
        (log/info "Renderer compiling")
        (let [build @build-ref
              facts-path (path/join (:renderer-dir build) "cargo-build.json")
              args (cond-> ["frontend"]
                     (not (:default-features? selection)) (conj "--no-default-features")
                     (seq (:features selection)) (into ["--features" (str/join "," (:features selection))]))]
          (await (process/run (merge bounds
                                     {:command (path/resolve root ".." "render-wasm" "build")
                                      :args args :cwd (path/resolve root ".." "render-wasm")
                                      :capture-output? false :on-line (fn [line _] (log/debug line))
                                      :env {"PENPOT_WASM_PREPARED" "1" "NODE_ENV" "production"
                                            "BUILD_MODE" "release"
                                            "VERSION_TAG" (:id build)
                                            "FRONTEND_DEST" (:renderer-dir build)
                                            "FRONTEND_SHARED_DEST" (path/join root "src" "app" "render_wasm" "api" "shared.js")
                                            "RENDER_WASM_SHARED_ARTIFACT" (path/join (:renderer-dir build) "shared.js")
                                            "RENDER_WASM_BUILD_INFO" facts-path}})))
          (let [facts    (js/JSON.parse (fs/readFileSync facts-path "utf8"))
                profile  (js->clj (.-profile facts) :keywordize-keys true)
                resolved (:features (options/feature-selection {:features (js->clj (.-features facts))
                                                                :default-features? (:default-features? selection)}))]
            (when-not (and (= "3" (:opt_level profile))
                           (false? (:debug_assertions profile)))
              (throw (ex-info "Cargo did not produce an optimized renderer"
                              {:type ::renderer-profile :profile profile})))
            (swap! build-ref #(artifacts/write-manifest
                               % (cond-> (-> (:manifest %)
                                             (assoc-in [:functional :features] resolved)
                                             (assoc-in [:functional :default-features?] (boolean (some #{"default"} resolved)))
                                             (assoc-in [:provenance :cargo-profile] profile))
                                   (js/Object.hasOwn facts "environment")
                                   (assoc :environment (js->clj (.-environment facts))))))))
        (reset! phase :cljs)
        (log/info "Modules compiling")
        (let [build @build-ref]
          (await (process/run (merge bounds
                                     {:command "clojure" :cwd root
                                      :args ["-M:dev:renderer-bench" "release"
                                             "bench-render-wasm-catalog" "bench-render-wasm-browser"]
                                      :capture-output? false :on-line (fn [line _] (log/debug line))
                                      :env {"RENDER_BENCH_BUILD_ID" (:id build)
                                            "RENDER_BENCH_CASES_PATH" (:cases-path build)
                                            "RENDER_BENCH_BROWSER_DIR" (:browser-dir build)}})))
          (fs/copyFileSync (path/join root "performance" "benches" "render_wasm" "browser" "index.html")
                           (:index-path build))
          (reset! phase :identity)
          (log/info "Validating")
          (check-profile-marks (catalog/build-identity build))
          (catalog/collect build {:master-seed 42})
          (await (validate-browser build root bounds))
          (artifacts/check-files build)
          (when (zero? (process/remaining deadline))
            (throw (ex-info "preparation deadline expired" {:type :process-timeout})))
          (log/info "  ready")
          (let [ready (artifacts/write-manifest build (assoc (:manifest build) :status :ready))]
            (reset! build-ref ready)
            (artifacts/load-build root (:id ready))))
        (catch :default cause
          (let [build          @build-ref
                failure        {:phase @phase :cause (failures/describe-cause cause)}
                saved          (when (= :building (get-in build [:manifest :status]))
                                 (attempt :catch-fatal
                                          (artifacts/write-manifest
                                           build (assoc (:manifest build) :status :failed :failure failure))))
                manifest-error (when (failure? saved) (errors/message-of saved))
                data           (cond-> {:type ::preparation-failed :build-id (:id build)
                                        :phase @phase :log-path (or (:log-path (ex-data cause)) (:log-path build))
                                        :reason (str/join " -> " (keep :message (:cause failure)))}
                                 manifest-error (assoc :manifest-error manifest-error))]
            (throw (if manifest-error
                     (errors/fatal-error "renderer build preparation failed" data cause)
                     (ex-info "renderer build preparation failed" data cause)))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner.build
  "These functions build the renderer and package it with the code that runs
  benchmarks in Node and the browser.

  Each build gets its own directory under
  frontend/target/performance/render-wasm/builds/. It contains the renderer
  compiled to WebAssembly, a list of benchmarks that Node can load, and the
  page and JavaScript that run them in a browser.

  Run `prepare` from Node in a checkout with the project's build tools and
  dependencies installed. Stop other builds and any processes that rebuild the
  renderer on file changes until it finishes."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["url" :as url]
   [app.common.transit :as transit]
   [benches.render-wasm.errors :as errors :refer [attempt failure?]]
   [benches.render-wasm.features.selection :as selection]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.catalog.loader :as catalog]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.process :as process]
   [benches.render-wasm.node.settings :as settings]
   [benches.render-wasm.node.storage.artifacts :as artifacts]
   [clojure.string :as str]))

(defn ^:async git-provenance
  "Read the Git commit ID and whether the checkout containing `root` has local
  changes, including staged changes and untracked files.

  `bounds` supplies :deadline, an absolute time in milliseconds since the Unix
  epoch, and an optional :signal AbortSignal to cancel the work.

  Return a promise of a map containing :sha, the commit ID, and :dirty, which
  is true when the checkout has local changes. If Git information is
  unavailable, log a warning and return nil for both values. Fatal process
  failures, timeouts and cancellation reject the promise."
  [root bounds]
  (try
    (let [options (merge bounds {:command "git" :cwd root})
          sha     (await (process/run (assoc options :args ["rev-parse" "HEAD"])))
          status  (await (process/run (assoc options :args ["status" "--porcelain"])))]
      {:sha (str/trim (:stdout sha)) :dirty (not (str/blank? (:stdout status)))})
    (catch :default cause
      (if (or (errors/fatal? cause)
              (contains? #{:process-timeout :process-cancelled} (:type (ex-data cause))))
        (throw cause)
        (do
          (log/warn (str "Git provenance unavailable: " (errors/message-of cause)))
          {:sha nil :dirty nil})))))

(defn- check-profile-marks
  "Require :cljs-profile-marks to be false in the map describing the compiled
  code. This flag indicates whether extra calls record timings for profiling;
  those calls can affect benchmark measurements. Return the map or throw."
  [compiled]
  (when-not (false? (:cljs-profile-marks compiled))
    (throw (ex-info "compiled benchmark enables profiling marks or omits its setting"
                    {:type ::profile-marks :compiled compiled})))
  compiled)

(defn- ^:async validate-browser
  "Checks that the JavaScript compiled for the browser belongs to the requested
  build and has profiling disabled.

    build: supplies the expected :id and :browser-dir, the directory containing
        browser.js.
    root: the frontend directory.
    bounds: supplies :deadline, an absolute time in milliseconds since the Unix
        epoch, and an optional :signal AbortSignal for cancellation.

  Returns a promise of the code's build information, including :build-id and
  :cljs-profile-marks. Rejects the promise if loading or checking the code fails."
  [build root bounds]
  (let [module-url (.-href (url/pathToFileURL (path/join (:browser-dir build) "browser.js")))
        script     "const {default: bridge} = await import(process.env.RENDER_BENCH_MODULE_URL); process.stdout.write(bridge.buildIdentity());"
        result     (await (process/run (merge bounds
                                              {:command (.-execPath js/process) :cwd root
                                               :args ["--input-type=module" "-e" script]
                                               :env {"RENDER_BENCH_MODULE_URL" module-url}})))]
    (check-profile-marks (identity/check (:id build) (transit/decode-str (:stdout result))))))

(defn ^:async prepare
  "Build the renderer and the programs that run benchmarks, and save the files
  for later benchmark runs.

  Pass an options map with :root set to the frontend directory. If omitted,
  the function locates that directory from the Node program's location.

  Cargo features choose which optional parts of the renderer to compile. Use
  :selection {:default-features? true :features []} to enable Cargo's defaults.
  Put additional feature names from render-wasm/Cargo.toml in the vector, and
  set :default-features? to false to disable the declared defaults. The
  :features option accepts the same map when :selection is absent. Omit both
  options to use Cargo's defaults.

  :build-timeout-ms sets the time allowed for building, in milliseconds
  (300000 by default). An optional `:signal AbortSignal` cancels the work.
  An optional :on-created function receives a map with the build's ID and
  paths after its directory, log and initial metadata file exist. This callback
  runs before compilation. Await the promise from `prepare` before using the
  compiled files.

  The promise resolves to a map with :id, the build's unique identifier;
  :dir, its output directory; :log-path, its compiler log; and :manifest,
  metadata describing the build and its settings. :cases-path points to the
  JavaScript module that lists the benchmarks for Node. :browser-dir and
  :renderer-dir contain the compiled programs, and :index-path points to the
  HTML page.

  A failure rejects the promise and keeps any files already written for
  inspection. Error data includes :build-id and :log-path when available."
  [{:keys [root features selection build-timeout-ms signal on-created]
    :or {build-timeout-ms settings/default-build-timeout-ms}}]
  (when-not (and (js/Number.isSafeInteger build-timeout-ms) (pos? build-timeout-ms))
    (throw (ex-info "build timeout must be a positive integer" {:type ::invalid-timeout})))
  (let [root      (or root (artifacts/frontend-root))
        selection (selection/feature-selection (or selection features))
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
                resolved (:features (selection/feature-selection {:features (js->clj (.-features facts))
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
                context        (errors/project-context (ex-data cause))
                failure        (errors/evidence @phase cause)
                saved          (when (= :building (get-in build [:manifest :status]))
                                 (attempt :catch-fatal
                                          (artifacts/write-manifest
                                           build (assoc (:manifest build) :status :failed :failure failure))))
                manifest-error (when (failure? saved) (errors/message-of saved))
                data           (cond-> (merge context
                                              {:type ::preparation-failed :build-id (:id build)
                                               :phase @phase :log-path (or (:log-path (ex-data cause)) (:log-path build))
                                               :reason (str/join " -> " (keep :message (:cause failure)))})
                                 manifest-error (assoc :manifest-error manifest-error))]
            (throw (if manifest-error
                     (errors/fatal-error
                      "renderer build preparation failed" data
                      (errors/combine [{:error cause}
                                       {:phase "storage" :operation :write-manifest
                                        :path (:manifest-path build) :error saved}]))
                     (ex-info "renderer build preparation failed" data cause)))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.build
  "Prepares retained release artifacts under a single preparation deadline.

  The caller owns exclusive preparation for this checkout. The renderer's
  lock protects Cargo and copying; CLJS compilation follows after it releases.
  Preparation uses installed dependencies and offline Cargo. Every attempt
  retains its manifest and compiler log, including failed attempts."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["url" :as url]
   [app.common.transit :as transit]
   [benches.render-wasm.failures :as failures]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.catalog :as catalog]
   [benches.render-wasm.node.process :as process]
   [clojure.string :as str]))

(def diagnostic-features #{"stats" "profile" "profile-macros" "profile-raf"})

(defn feature-selection
  "Validates measurement feature selection before allocating an attempt.
  Cargo validates functional feature names during the retained build."
  [{:keys [default-features? features]
    :or {default-features? true features []}}]
  (when-not (and (boolean? default-features?) (vector? features)
                 (every? #(and (string? %) (re-matches #"[A-Za-z0-9_-]+" %)) features))
    (throw (ex-info "invalid Cargo feature selection" {:type ::invalid-features})))
  (when-let [selected (seq (filter diagnostic-features features))]
    (throw (ex-info "measurement builds cannot enable diagnostic Cargo features"
                    {:type ::diagnostic-features :features (vec selected)})))
  {:default-features? default-features? :features (vec (sort (distinct features)))})

(defn parse-feature-list
  [text]
  (:features (feature-selection {:features (mapv str/trim (str/split text #"," -1))})))

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
          (.error js/console (str "Git provenance unavailable: " (or (ex-message cause) (str cause))))
          {:sha nil :dirty nil})))))

(defn- check-profile-marks
  [compiled]
  (when-not (false? (:cljs-profile-marks compiled))
    (throw (ex-info "compiled benchmark enables profiling marks or omits its setting"
                    {:type ::profile-marks :compiled compiled})))
  compiled)

(defn- output
  [build text _stream]
  (fs/appendFileSync (:log-path build) text "utf8")
  (.write (.-stderr js/process) text))

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

  Options: frontend :root, :features, :build-timeout-ms (300000), AbortSignal
  :signal, and optional :on-created callback. The callback runs once after
  directory, log and initial manifest creation. CLI callers print the ID there.
  Storage errors have no recorded ID. Later errors carry :build-id and :phase.
  A retry always creates a new attempt."
  [{:keys [root features build-timeout-ms signal on-created]
    :or {build-timeout-ms process/default-build-timeout-ms}}]
  (when-not (and (js/Number.isSafeInteger build-timeout-ms) (pos? build-timeout-ms))
    (throw (ex-info "build timeout must be a positive integer" {:type ::invalid-timeout})))
  (let [root      (or root (artifacts/frontend-root))
        selection (feature-selection features)
        deadline  (process/deadline build-timeout-ms)
        bounds    {:deadline deadline :signal signal}
        initial   (artifacts/create-build root selection {:git {:sha nil :dirty nil}
                                                          :feature-selection selection})
        build-ref (atom initial)
        phase     (atom :storage)]
    (try
      (when on-created (on-created initial))
      (reset! phase :provenance)
      (let [git (await (git-provenance root bounds))]
        (swap! build-ref #(artifacts/write-manifest % (assoc-in (:manifest %) [:provenance :git] git))))
      (reset! phase :renderer)
      (let [build @build-ref
            facts-path (path/join (:renderer-dir build) "cargo-build.json")
            args (cond-> ["frontend" "--offline"]
                   (not (:default-features? selection)) (conj "--no-default-features")
                   (seq (:features selection)) (into ["--features" (str/join "," (:features selection))]))]
        (await (process/run (merge bounds
                                   {:command (path/resolve root ".." "render-wasm" "build")
                                    :args args :cwd (path/resolve root ".." "render-wasm")
                                    :capture-output? false :on-output #(output build %1 %2)
                                    :env {"PENPOT_WASM_PREPARED" "1" "NODE_ENV" "production"
                                          "BUILD_MODE" "release" "CARGO_NET_OFFLINE" "true"
                                          "VERSION_TAG" (:id build)
                                          "FRONTEND_DEST" (:renderer-dir build)
                                          "FRONTEND_SHARED_DEST" (path/join root "src" "app" "render_wasm" "api" "shared.js")
                                          "RENDER_WASM_SHARED_ARTIFACT" (path/join (:renderer-dir build) "shared.js")
                                          "RENDER_WASM_BUILD_INFO" facts-path}})))
        (let [facts    (js->clj (js/JSON.parse (fs/readFileSync facts-path "utf8")) :keywordize-keys true)
              resolved (:features (feature-selection {:features (:features facts)
                                                      :default-features? (:default-features? selection)}))]
          (when-not (and (= "3" (get-in facts [:profile :opt_level]))
                         (false? (get-in facts [:profile :debug_assertions])))
            (throw (ex-info "Cargo did not produce an optimized renderer"
                            {:type ::renderer-profile :profile (:profile facts)})))
          (swap! build-ref #(artifacts/write-manifest
                             % (-> (:manifest %)
                                   (assoc-in [:functional :features] resolved)
                                   (assoc-in [:functional :default-features?] (boolean (some #{"default"} resolved)))
                                   (assoc-in [:provenance :cargo-profile] (:profile facts)))))))
      (reset! phase :cljs)
      (let [build @build-ref]
        (await (process/run (merge bounds
                                   {:command "clojure" :cwd root
                                    :args ["-M:dev:renderer-bench" "release"
                                           "bench-render-wasm-catalog" "bench-render-wasm-browser"]
                                    :capture-output? false :on-output #(output build %1 %2)
                                    :env {"RENDER_BENCH_BUILD_ID" (:id build)
                                          "RENDER_BENCH_CASES_PATH" (:cases-path build)
                                          "RENDER_BENCH_BROWSER_DIR" (:browser-dir build)}})))
        (fs/copyFileSync (path/join root "performance" "benches" "render_wasm" "browser" "index.html")
                         (:index-path build))
        (reset! phase :identity)
        (check-profile-marks (catalog/build-identity build))
        (catalog/collect build {:master-seed 42})
        (await (validate-browser build root bounds))
        (artifacts/check-files build)
        (when (zero? (process/remaining deadline))
          (throw (ex-info "preparation deadline expired" {:type :process-timeout})))
        (let [ready (artifacts/write-manifest build (assoc (:manifest build) :status :ready))]
          (reset! build-ref ready)
          (artifacts/load-build root (:id ready))))
      (catch :default cause
        (let [build   @build-ref
              failure {:phase @phase :cause (failures/describe-cause cause)}]
          (output build (str "\nPreparation failed: " (pr-str failure) "\n") :stderr)
          (artifacts/write-manifest build (assoc (:manifest build) :status :failed :failure failure))
          (throw (ex-info "renderer build preparation failed"
                          {:type ::preparation-failed :build-id (:id build)
                           :phase @phase :log-path (:log-path build)} cause)))))))

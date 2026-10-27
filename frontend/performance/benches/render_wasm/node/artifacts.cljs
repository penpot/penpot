;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.artifacts
  "Managed renderer builds under frontend/target/performance/render-wasm.

  create-build writes the initial manifest and log before returning. A build
  helper can then publish its :on-created output, compile into the returned
  paths, and write a :ready or :failed manifest. Failed builds retain their
  files and logs until an idle caller explicitly removes them."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.node.files :as files]
   [clojure.string :as str]))

(def schema:build
  "Stored renderer build identity, lifecycle and provenance."
  [:map
   [:type [:= :renderer-build]]
   [:id [:string {:min 1}]]
   [:status [:enum :building :ready :failed]]
   [:created-at :string]
   [:updated-at :string]
   [:functional :map]
   [:provenance :map]])

(def ^:private executable-dir
  ;; CJS boots use __dirname. Node's ESM test boots have an absolute argv[1]
  ;; instead; capture it before a caller changes cwd. Neither uses a cwd root.
  (if (exists? js/__dirname)
    js/__dirname
    (let [script (aget js/process.argv 1)]
      (when (and (string? script) (path/isAbsolute script))
        (path/dirname script)))))

(defn frontend-root
  "Finds the frontend checkout by walking up from the Node executable.

  Supports target/performance/render-wasm/runner.cjs and the ESM
  target/{performance-tests,tests}/test.js boots, independent of caller cwd."
  []
  (when-not executable-dir
    (throw (ex-info "cannot locate the Node executable directory"
                    {:type ::missing-executable-dir})))
  (loop [dir (path/resolve executable-dir)]
    (let [deps   (path/join dir "deps.edn")
          scenes (path/join dir "performance" "benches" "render_wasm")
          parent (path/dirname dir)]
      (cond
        (and (fs/existsSync deps) (.isFile ^js (fs/statSync deps))
             (fs/existsSync scenes) (.isDirectory ^js (fs/statSync scenes)))
        dir

        (= parent dir)
        (throw (ex-info "cannot locate the frontend checkout"
                        {:type ::missing-frontend-root
                         :executable-dir executable-dir}))

        :else
        (recur parent)))))

(defn- check-id
  [id]
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,127}" id))
    (throw (ex-info "invalid renderer build id" {:type ::invalid-id :id id})))
  id)

(defn- contained?
  [root file]
  (let [relative (path/relative root file)]
    (and (not (path/isAbsolute relative))
         (not= relative "..")
         (not (str/starts-with? relative (str ".." path/sep))))))

(defn- checked-path
  [root file]
  (let [resolved (fs/realpathSync file)]
    (when-not (contained? root resolved)
      (throw (ex-info "renderer artifact path escapes its managed directory"
                      {:type ::unsafe-path :path file})))
    resolved))

(defn managed-root
  "Returns the managed path under an existing frontend checkout.

  Checks its nearest existing ancestor before creating or removing paths,
  so a managed-root or ancestor symlink cannot escape the frontend root."
  [root]
  (let [frontend (fs/realpathSync root)
        managed  (path/resolve root "target" "performance" "render-wasm")]
    (loop [existing managed]
      (if (fs/existsSync existing)
        (checked-path frontend existing)
        (recur (path/dirname existing))))
    managed))

(defn inside-builds?
  "Checks the whole builds tree, resolving symlink ancestors of both paths."
  [frontend-root output-path]
  (contained? (files/get-real-path (path/join (managed-root frontend-root) "builds"))
              (files/get-real-path output-path)))

(defn- build-reference
  [root id manifest]
  (let [dir (path/join (managed-root root) "builds" id)]
    {:id id
     :dir dir
     :manifest-path (path/join dir "build.transit")
     :log-path (path/join dir "build.log")
     :cases-path (path/join dir "cases.cjs")
     :browser-dir (path/join dir "browser")
     :renderer-dir (path/join dir "renderer")
     :index-path (path/join dir "index.html")
     :manifest manifest}))

(defn create-build
  "Allocates a UUID build and saves its :building build.transit and empty log.

  root is the frontend checkout. selection is {:default-features? boolean
  :features vector}; provenance is {:git {:sha string-or-nil
  :dirty boolean-or-nil}}. This function does not compile or remove builds."
  [root selection provenance]
  (let [id       (str (random-uuid))
        now      (.toISOString (js/Date.))
        manifest {:type :renderer-build
                  :id id
                  :status :building
                  :created-at now
                  :updated-at now
                  :functional {:mode :release
                               :default-features? (:default-features? selection)
                               :features (:features selection)
                               :cljs-profile-marks false}
                  :provenance provenance}
        build    (build-reference root id manifest)]
    (fs/mkdirSync (path/dirname (:dir build)) #js {:recursive true})
    (checked-path (fs/realpathSync (managed-root root)) (path/dirname (:dir build)))
    (fs/mkdirSync (:dir build))
    (fs/mkdirSync (:browser-dir build))
    (fs/mkdirSync (:renderer-dir build))
    (fs/writeFileSync (:log-path build) "" "utf8")
    (files/atomic-write (:manifest-path build) (codec/encode-str manifest))
    build))

(defn write-manifest
  "Atomically replaces a manifest and returns the updated build reference.

  Only :building builds can change. An unchanged terminal manifest is a
  no-op; :ready and :failed builds cannot otherwise change.
  Read the saved status so an old build reference cannot reopen a build."
  [build manifest]
  (let [current (transit/decode-str (fs/readFileSync (:manifest-path build) "utf8"))]
    (when-not (and (= (:id build) (:id current) (:id manifest))
                   (sm/validate schema:build current)
                   (sm/validate schema:build manifest))
      (throw (ex-info "invalid schema:build"
                      {:type ::invalid-manifest :id (:id build)})))
    (if (contains? #{:ready :failed} (:status current))
      (if (= current manifest)
        (assoc build :manifest current)
        (throw (ex-info "renderer build is already terminal"
                        {:type ::terminal-build
                         :id (:id build)
                         :status (:status current)})))
      (let [manifest (assoc manifest :updated-at (.toISOString (js/Date.)))]
        (files/atomic-write (:manifest-path build) (codec/encode-str manifest))
        (assoc build :manifest manifest)))))

(defn- require-file
  [root file]
  (let [resolved (checked-path root file)]
    (when-not (.isFile ^js (fs/statSync resolved))
      (throw (ex-info "renderer artifact is not a file"
                      {:type ::missing-artifact :path file})))
    resolved))

(defn check-files
  "Checks required files and selected directory containment; returns build.

  build is a reference from create-build or load-build. This check neither
  reads nor changes manifest status. The build helper must also check catalog
  and browser identities before writing its terminal :ready manifest."
  [build]
  ;; Build directories use <frontend>/target/performance/render-wasm/builds/<id>.
  (let [root    (path/resolve (:dir build) ".." ".." ".." ".." "..")
        managed (fs/realpathSync (managed-root root))
        dir     (checked-path managed (:dir build))]
    (require-file dir (:cases-path build))
    (require-file dir (path/join (:browser-dir build) "browser.js"))
    (require-file dir (path/join (:renderer-dir build) "render-wasm.js"))
    (require-file dir (path/join (:renderer-dir build) "render-wasm.wasm"))
    (require-file dir (path/join (:renderer-dir build) "shared.js"))
    (require-file dir (:index-path build))
    build))

(defn load-build
  "Loads a ready schema:build and checks every required artifact.

  IDs cannot contain path segments. The build, manifest and artifact paths
  must remain inside the managed root and the selected build after resolving
  symlinks. Building, failed, incomplete and unsupported builds are rejected."
  [root id]
  (check-id id)
  (let [managed  (fs/realpathSync (managed-root root))
        build    (build-reference root id nil)
        dir      (checked-path managed (:dir build))
        file     (require-file dir (:manifest-path build))
        manifest (transit/decode-str (fs/readFileSync file "utf8"))]
    (when-not (and (sm/validate schema:build manifest)
                   (= id (:id manifest)))
      (throw (ex-info "invalid schema:build"
                      {:type ::unsupported-manifest :id id})))
    (when-not (= :ready (:status manifest))
      (throw (ex-info "renderer build is not ready"
                      {:type ::not-ready :id id :status (:status manifest)})))
    (check-files (assoc build :manifest manifest))))

(def ^:private build-outputs
  ["build.transit" "build.log" "cases.cjs" "index.html"
   "browser/browser.js" "renderer/render-wasm.js" "renderer/render-wasm.wasm"
   "renderer/render-wasm.wasm.map" "renderer/shared.js" "renderer/cargo-build.json"
   "renderer/worker/render.js"])

(defn- directory?
  [file]
  (and (fs/existsSync file) (.isDirectory (fs/statSync file))))

(defn- unlink-if-present
  [file]
  (when-not (directory? file)
    (try
      (fs/unlinkSync file)
      (catch :default error
        (when-not (contains? #{"ENOENT" "ENOTDIR"} (.-code error))
          (throw error))))))

(defn- clean-build
  [dir]
  (when (directory? dir)
    (doseq [file build-outputs]
      (unlink-if-present (path/join dir file)))))

(defn- purge-builds
  [root]
  (when (directory? root)
    (doseq [id (array-seq (fs/readdirSync root))]
      (clean-build (path/join root id)))))

(defn- purge-runs
  [root]
  (loop [pending [root]
         visited #{}]
    (when-let [dir (peek pending)]
      (let [pending (pop pending)
            real    (when (directory? dir) (fs/realpathSync dir))]
        (if (or (nil? real) (contains? visited real))
          (recur pending visited)
          (do
            (unlink-if-present (path/join dir "run.log"))
            (let [names (array-seq (fs/readdirSync dir))]
              (doseq [file names
                      :when (str/ends-with? (str/lower-case file) ".png")]
                (unlink-if-present (path/join dir file)))
              (unlink-if-present (path/join dir "run.transit"))
              (recur (into pending (map #(path/join dir %) names))
                     (conj visited real)))))))))

(defn purge
  "Unlinks known outputs from :builds, :runs or both (:all).

  The caller must be idle: no build, server or run may still own these paths.
  Follows directory symlinks; keeps directories and unrelated files."
  [root category]
  (let [managed (managed-root root)]
    (case category
      :builds (purge-builds (path/join managed "builds"))
      :runs (purge-runs (path/join managed "runs"))
      :all (do
             (purge-builds (path/join managed "builds"))
             (purge-runs (path/join managed "runs")))
      (throw (ex-info "invalid renderer artifact category"
                      {:type ::invalid-category :category category})))))

(defn remove-builds
  "Unlinks known outputs of selected build IDs. The caller must be idle."
  [root ids]
  (let [ids     (mapv check-id ids)
        managed (managed-root root)
        builds  (path/join managed "builds")]
    (doseq [id ids]
      (clean-build (path/join builds id)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns performance-tests.render-wasm.artifacts-test
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   ["worker_threads" :as workers]
   [app.common.transit :as transit]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.identity :as identity]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.catalog :as catalog]
   [cljs.test :as t :include-macros true]))

(def ^:private temporary-root (atom nil))
(def ^:private selection {:default-features? true :features []})
(def ^:private provenance {:git {:sha "test-revision" :dirty false}})

(t/use-fixtures :each
  {:before #(reset! temporary-root
                    (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-artifacts-")))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(defn- create-build
  []
  (artifacts/create-build @temporary-root selection provenance))

(defn- write-file
  [file content]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file content "utf8"))

(defn- complete-files
  [build]
  (write-file (:cases-path build) "module.exports = {};")
  (write-file (path/join (:browser-dir build) "browser.js") "export default {};")
  (write-file (path/join (:renderer-dir build) "render-wasm.js") "export default {};")
  (fs/writeFileSync (path/join (:renderer-dir build) "render-wasm.wasm")
                    (js/Buffer.from #js [0 97 115 109 1 0 0 0]))
  (write-file (path/join (:renderer-dir build) "shared.js") "export default {};")
  (write-file (:index-path build) "<!doctype html><title>selected build</title>")
  build)

(defn- ready-build
  []
  (let [build (complete-files (create-build))]
    (artifacts/write-manifest build (assoc (:manifest build) :status :ready))))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch :default error error)))

(t/deftest frontend-root-is-independent-of-caller-cwd
  (let [expected (artifacts/frontend-root)
        previous (.cwd js/process)]
    (try
      (.chdir js/process @temporary-root)
      (t/is (= expected (artifacts/frontend-root)))
      (t/is (.isFile (fs/statSync (path/join expected "deps.edn"))))
      (t/is (.isDirectory (fs/statSync
                           (path/join expected "performance" "benches" "render_wasm"))))
      (finally
        (.chdir js/process previous)))))

(t/deftest create-build-retains-its-initial-release-evidence
  (let [selected {:default-features? false :features ["feature-one" "feature-two"]}
        build    (artifacts/create-build @temporary-root selected provenance)
        manifest (transit/decode-str (fs/readFileSync (:manifest-path build) "utf8"))]
    (t/is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                      (:id build)))
    (t/is (= (path/join (artifacts/managed-root @temporary-root) "builds" (:id build))
             (:dir build)))
    (t/is (= (path/join (:dir build) "build.transit") (:manifest-path build)))
    (t/is (= (path/join (:dir build) "index.html") (:index-path build)))
    (t/is (= manifest (:manifest build)))
    (t/is (= {:type :renderer-build :schema-version 1 :status :building}
             (select-keys manifest [:type :schema-version :status])))
    (t/is (= {:mode :release :default-features? false
              :features ["feature-one" "feature-two"] :cljs-profile-marks false}
             (:functional manifest)))
    (t/is (= provenance (:provenance manifest)))
    (t/is (= (:created-at manifest) (.toISOString (js/Date. (:created-at manifest)))))
    (t/is (= (:created-at manifest) (:updated-at manifest)))
    (t/is (= "" (fs/readFileSync (:log-path build) "utf8")))
    (t/is (= (path/join (:dir build) "cases.cjs") (:cases-path build)))
    (t/is (.isDirectory (fs/statSync (:browser-dir build))))
    (t/is (.isDirectory (fs/statSync (:renderer-dir build))))))

(t/deftest ready-build-loads-all-selected-paths
  (let [build  (ready-build)
        loaded (artifacts/load-build @temporary-root (:id build))]
    (t/is (= build loaded))
    (t/is (= (path/join (:dir build) "index.html") (:index-path loaded)))))

(t/deftest complete-building-files-can-be-checked-before-publishing-ready
  (let [build (complete-files (create-build))]
    (t/is (= build (artifacts/check-files build)))
    (t/is (= :building (:status (transit/decode-str
                                 (fs/readFileSync (:manifest-path build) "utf8")))))
    (t/is (= ::artifacts/not-ready
             (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build)))))))))

(t/deftest incomplete-building-files-are-rejected-before-publishing-ready
  (doseq [relative ["cases.cjs" "browser/browser.js" "renderer/render-wasm.js"
                    "renderer/render-wasm.wasm" "renderer/shared.js" "index.html"]]
    (let [build (complete-files (create-build))]
      (fs/unlinkSync (path/join (:dir build) relative))
      (t/is (some? (failure #(artifacts/check-files build))) relative)
      (t/is (= :building (:status (transit/decode-str
                                   (fs/readFileSync (:manifest-path build) "utf8"))))))))

(t/deftest building-and-failed-builds-are-not-loadable-and-retain-evidence
  (let [building (create-build)
        failed   (create-build)
        log      "renderer compilation failed\n"]
    (fs/appendFileSync (:log-path failed) log "utf8")
    (artifacts/write-manifest failed (assoc (:manifest failed) :status :failed
                                            :failure {:stage :renderer :message "compile failed"}))
    (doseq [build [building failed]]
      (t/is (= ::artifacts/not-ready
               (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build))))))))
    (t/is (= :failed (:status (transit/decode-str
                               (fs/readFileSync (:manifest-path failed) "utf8")))))
    (t/is (= log (fs/readFileSync (:log-path failed) "utf8")))
    (t/is (fs/existsSync (:dir building)))))

(t/deftest stale-references-cannot-reopen-terminal-builds
  (doseq [status [:ready :failed]]
    (let [build    (create-build)
          terminal (artifacts/write-manifest build (assoc (:manifest build) :status status))
          saved    (fs/readFileSync (:manifest-path build) "utf8")]
      (t/is (= terminal (artifacts/write-manifest terminal (:manifest terminal))))
      (doseq [next-status [:building :ready :failed]]
        (let [attempt (assoc (:manifest terminal) :status next-status :extra "changed")]
          (t/is (= ::artifacts/terminal-build
                   (:type (ex-data (failure #(artifacts/write-manifest build attempt))))))))
      (t/is (= saved (fs/readFileSync (:manifest-path build) "utf8"))))))

(t/deftest unencodable-update-keeps-the-last-manifest-and-log
  (let [build (create-build)
        saved (fs/readFileSync (:manifest-path build) "utf8")]
    (fs/appendFileSync (:log-path build) "saved diagnostic\n" "utf8")
    (t/is (some? (failure #(artifacts/write-manifest
                            build (assoc (:manifest build) :callback (fn [] nil))))))
    (t/is (= saved (fs/readFileSync (:manifest-path build) "utf8")))
    (t/is (= "saved diagnostic\n" (fs/readFileSync (:log-path build) "utf8")))
    (t/is (= #{"browser" "renderer" "build.log" "build.transit"}
             (set (array-seq (fs/readdirSync (:dir build))))))))

(def ^:private manifest-reader
  (str "const {parentPort, workerData} = require('worker_threads');"
       "const fs = require('fs'); const stop = new Int32Array(workerData.stop);"
       "let reads = 0; const errors = [];"
       "function read() { try { JSON.parse(fs.readFileSync(workerData.path, 'utf8'));"
       "reads++; } catch (error) { if (errors.length < 5) errors.push(error.message); } }"
       "read(); parentPort.postMessage('ready');"
       "while (Atomics.load(stop, 0) === 0) read();"
       "parentPort.postMessage({reads, errors});"))

(defn- worker-message
  [^js worker]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(received [value]
               (.removeListener worker "error" failed)
               (resolve value))
             (failed [error]
               (.removeListener worker "message" received)
               (reject error))]
       (.once worker "message" received)
       (.once worker "error" failed)))))

(t/deftest ^:async manifest-replacements-never-expose-a-partial-file
  (let [build      (create-build)
        stop       (js/SharedArrayBuffer. 4)
        ^js worker (workers/Worker. manifest-reader
                                    #js {:eval true
                                         :workerData #js {:path (:manifest-path build) :stop stop}})]
    (try
      (await (worker-message worker))
      (let [result (worker-message worker)]
        (dotimes [n 24]
          (artifacts/write-manifest
           build (assoc (:manifest build) :progress n
                        :diagnostic (apply str (repeat (+ 8192 (* n 1024)) "evidence ")))))
        (js/Atomics.store (js/Int32Array. stop) 0 1)
        (let [^js observed (await result)]
          (t/is (> (.-reads observed) 1) "a separate thread read during replacement")
          (t/is (empty? (array-seq (.-errors observed)))
                "every observed manifest remained a complete JSON document")))
      (t/is (= 23 (:progress (transit/decode-str
                              (fs/readFileSync (:manifest-path build) "utf8")))))
      (finally
        (await (.terminate worker))))))

(t/deftest unsupported-manifests-and-incomplete-builds-are-rejected
  (doseq [change [{:schema-version 2} {:type :run-record} {:id "another-build"}]]
    (let [build (ready-build)]
      (fs/writeFileSync (:manifest-path build) (codec/encode-str (merge (:manifest build) change))
                        "utf8")
      (t/is (= ::artifacts/unsupported-manifest
               (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build)))))))))
  (doseq [relative ["cases.cjs" "browser/browser.js" "renderer/render-wasm.js"
                    "renderer/render-wasm.wasm" "renderer/shared.js" "index.html"]]
    (let [build (ready-build)]
      (fs/unlinkSync (path/join (:dir build) relative))
      (t/is (some? (failure #(artifacts/load-build @temporary-root (:id build)))) relative))))

(t/deftest ids-cannot-select-or-delete-outside-paths
  (let [build   (ready-build)
        outside (path/join @temporary-root "outside.txt")]
    (write-file outside "keep this")
    (doseq [id [nil "" "." ".." "../outside.txt" "safe/../../outside.txt"
                "..\\outside.txt" "/tmp/outside" "id%2fother"]]
      (t/is (= ::artifacts/invalid-id
               (:type (ex-data (failure #(artifacts/load-build @temporary-root id))))))
      (t/is (= ::artifacts/invalid-id
               (:type (ex-data (failure #(artifacts/remove-builds
                                          @temporary-root [(:id build) id])))))))
    (t/is (= build (artifacts/load-build @temporary-root (:id build))))
    (t/is (= "keep this" (fs/readFileSync outside "utf8")))))

(t/deftest safe-non-uuid-ids-can-load-existing-builds
  (let [build (ready-build)
        id    "retained-build_1"
        dir   (path/join (path/dirname (:dir build)) id)]
    (fs/renameSync (:dir build) dir)
    (fs/writeFileSync (path/join dir "build.transit")
                      (codec/encode-str (assoc (:manifest build) :id id)) "utf8")
    (t/is (= id (:id (artifacts/load-build @temporary-root id))))
    (t/is (= dir (:dir (artifacts/load-build @temporary-root id))))))

(t/deftest build-and-artifact-symlinks-cannot-escape-the-managed-root
  (let [build   (ready-build)
        outside (path/join @temporary-root "outside.txt")]
    (write-file outside "private")
    (fs/unlinkSync (:cases-path build))
    (fs/symlinkSync outside (:cases-path build))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/check-files build))))))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build))))))))
  (let [build   (ready-build)
        outside (path/join @temporary-root "outside-build")]
    (fs/renameSync (:dir build) outside)
    (fs/symlinkSync outside (:dir build) "dir")
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/check-files build))))))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build)))))))))

(t/deftest category-purge-deletes-only-the-requested-managed-subroots
  (doseq [category [:builds :runs :all]]
    (let [root     (fs/mkdtempSync (path/join @temporary-root (str (name category) "-")))
          building (artifacts/create-build root selection provenance)
          failed   (artifacts/create-build root selection provenance)
          managed  (artifacts/managed-root root)
          run-file (path/join managed "runs" "saved-run" "record.transit")
          boot     (path/join managed "runner.cjs")
          outside  (path/join root "unmanaged.txt")]
      (artifacts/write-manifest failed (assoc (:manifest failed) :status :failed))
      (write-file run-file "retained run")
      (write-file boot "boot executable")
      (write-file outside "unmanaged")
      (artifacts/purge root category)
      (t/is (= (= category :runs) (fs/existsSync (:dir building))))
      (t/is (= (= category :runs) (fs/existsSync (:dir failed))))
      (t/is (= (= category :builds) (fs/existsSync run-file)))
      (t/is (= "boot executable" (fs/readFileSync boot "utf8")))
      (t/is (= "unmanaged" (fs/readFileSync outside "utf8")))
      (artifacts/purge root category))))

(t/deftest purge-removes-selected-subroot-symlinks-without-deleting-their-targets
  (let [managed (artifacts/managed-root @temporary-root)]
    (fs/mkdirSync managed #js {:recursive true})
    (doseq [category [:builds :runs]]
      (let [outside (path/join @temporary-root (str "outside-" (name category)))
            saved   (path/join outside "keep.txt")
            link    (path/join managed (name category))]
        (write-file saved "keep symlink target")
        (fs/symlinkSync outside link "dir")
        (artifacts/purge @temporary-root category)
        (t/is (not (fs/existsSync link)))
        (t/is (= "keep symlink target" (fs/readFileSync saved "utf8")))))))

(t/deftest managed-root-symlink-cannot-create-or-delete-outside-the-frontend
  (let [root      (path/join @temporary-root "frontend")
        managed   (path/join root "target" "performance" "render-wasm")
        outside   (path/join @temporary-root "outside-managed")
        build-log (path/join outside "builds" "retained" "build.log")
        run-file  (path/join outside "runs" "retained" "record.transit")]
    (write-file build-log "outside build")
    (write-file run-file "outside run")
    (fs/mkdirSync (path/dirname managed) #js {:recursive true})
    (fs/symlinkSync outside managed "dir")
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/managed-root root))))))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/create-build root selection provenance))))))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/purge root :all))))))
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/remove-builds root ["retained"]))))))
    (t/is (= "outside build" (fs/readFileSync build-log "utf8")))
    (t/is (= "outside run" (fs/readFileSync run-file "utf8")))
    (t/is (= #{"retained"} (set (array-seq (fs/readdirSync (path/join outside "builds"))))))))

(t/deftest escaping-target-ancestor-is-rejected-before-the-managed-path-exists
  (let [root    (path/join @temporary-root "frontend-ancestor")
        outside (path/join @temporary-root "outside-target")]
    (fs/mkdirSync root)
    (fs/mkdirSync outside)
    (fs/symlinkSync outside (path/join root "target") "dir")
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/create-build root selection provenance))))))
    (t/is (empty? (array-seq (fs/readdirSync outside))))))

(t/deftest create-build-rejects-an-escaping-builds-parent-before-writing-files
  (let [managed (artifacts/managed-root @temporary-root)
        outside (path/join @temporary-root "outside-builds")
        saved   (path/join outside "retained" "build.log")]
    (write-file saved "keep external build")
    (fs/mkdirSync managed #js {:recursive true})
    (fs/symlinkSync outside (path/join managed "builds") "dir")
    (t/is (= ::artifacts/unsafe-path
             (:type (ex-data (failure #(artifacts/create-build
                                        @temporary-root selection provenance))))))
    (t/is (= "keep external build" (fs/readFileSync saved "utf8")))
    (t/is (= #{"retained"} (set (array-seq (fs/readdirSync outside)))))))

(t/deftest remove-builds-keeps-other-builds-runs-and-symlink-targets
  (let [removed (create-build)
        kept    (ready-build)
        managed (artifacts/managed-root @temporary-root)
        run     (path/join managed "runs" "saved" "record.transit")
        outside (path/join @temporary-root "outside" "keep.txt")
        link    (path/join managed "builds" "outside-link")]
    (write-file run "keep run")
    (write-file outside "keep target")
    (fs/symlinkSync (path/dirname outside) link "dir")
    (artifacts/remove-builds @temporary-root [(:id removed) "outside-link" "absent-build"])
    (t/is (not (fs/existsSync (:dir removed))))
    (t/is (not (fs/existsSync link)))
    (t/is (= kept (artifacts/load-build @temporary-root (:id kept))))
    (t/is (= "keep run" (fs/readFileSync run "utf8")))
    (t/is (= "keep target" (fs/readFileSync outside "utf8")))))

(defn- descriptor
  [id]
  {:id id
   :scene :artifact-only
   :scene-version 1
   :scene-description "A descriptor supplied only by this build"
   :scene-seed 42
   :params {}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh
   :completion :render-full
   :batch-size 1
   :preparation {:version 1}})

(defn- write-catalog
  [build loaded-id response]
  (write-file
   (:cases-path build)
   (str "const identity = " (js/JSON.stringify (codec/encode-str {:build-id loaded-id})) ";"
        "const response = " (js/JSON.stringify (codec/encode-str response)) ";"
        "module.exports = {buildIdentity() {return identity;},"
        "collectCases(encodedSelection) {return response;}};")))

(t/deftest catalogs-use-the-selected-commonjs-module-with-isolated-scope
  (let [first-build  (ready-build)
        second-build (ready-build)
        first-case   (descriptor :artifact-only/first)
        second-case  (descriptor :artifact-only/second)
        selection    {:master-seed 42}]
    (write-catalog first-build (:id first-build)
                   {:build-id (:id first-build) :cases [first-case]})
    (write-catalog second-build (:id second-build)
                   {:build-id (:id second-build) :cases [second-case]})
    (t/is (= [first-case] (catalog/collect first-build selection)))
    (t/is (= [second-case] (catalog/collect second-build selection)))
    (t/is (= [first-case] (catalog/collect first-build selection)))))

(t/deftest catalog-rejects-both-loaded-and-response-identity-mismatches
  (doseq [boundary [:loaded :response]]
    (let [build (ready-build)]
      (write-catalog build (if (= boundary :loaded) "wrong-build" (:id build))
                     {:build-id (if (= boundary :response) "wrong-build" (:id build))
                      :cases [(descriptor :artifact-only/load)]})
      (t/is (= ::identity/mismatch
               (:type (ex-data (failure #(catalog/collect build {:master-seed 42})))))))))

(t/deftest catalog-rejects-malformed-descriptors-and-empty-responses
  (doseq [cases [[(assoc (descriptor :artifact-only/load) :scene-seed -1)] [] nil]]
    (let [build (ready-build)]
      (write-catalog build (:id build) {:build-id (:id build) :cases cases})
      (t/is (some? (failure #(catalog/collect build {:master-seed 42})))))))

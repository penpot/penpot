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
    (t/is (= #{:type :id :status :created-at :updated-at :functional :provenance}
             (set (keys manifest))))
    (t/is (= {:type :renderer-build :status :building}
             (select-keys manifest [:type :status])))
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

(t/deftest malformed-builds-cannot-replace-saved-manifests
  (doseq [side [:current :replacement]]
    (let [build     (create-build)
          valid     (:manifest build)
          malformed (dissoc valid :functional)
          proposed  (if (= :replacement side) malformed valid)]
      (when (= :current side)
        (fs/writeFileSync (:manifest-path build) (codec/encode-str malformed) "utf8"))
      (let [saved (fs/readFileSync (:manifest-path build) "utf8")
            error (failure #(artifacts/write-manifest build proposed))]
        (t/is (= ::artifacts/invalid-manifest (:type (ex-data error))) (name side))
        (t/is (= saved (fs/readFileSync (:manifest-path build) "utf8")) (name side))))))

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

(t/deftest malformed-manifests-and-incomplete-builds-are-rejected
  (doseq [change [{:functional []} {:type :run-record} {:id "another-build"}
                  {:status :unknown}]]
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

(t/deftest saved-builds-validate-current-shapes
  (let [build    (ready-build)
        manifest (select-keys (:manifest build)
                              [:type :id :status :created-at :updated-at :functional :provenance])]
    (fs/writeFileSync (:manifest-path build) (codec/encode-str manifest) "utf8")
    (t/is (= manifest (:manifest (artifacts/load-build @temporary-root (:id build)))))
    (fs/writeFileSync (:manifest-path build)
                      (codec/encode-str (dissoc manifest :functional)) "utf8")
    (t/is (= ::artifacts/unsupported-manifest
             (:type (ex-data (failure #(artifacts/load-build @temporary-root (:id build)))))))))

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

(t/deftest category-purge-unlinks-only-known-outputs-and-keeps-directories
  (doseq [category [:builds :runs :all]]
    (let [root     (fs/mkdtempSync (path/join @temporary-root (str (name category) "-")))
          building (complete-files (artifacts/create-build root selection provenance))
          failed   (artifacts/create-build root selection provenance)
          managed  (artifacts/managed-root root)
          run-dir  (path/join managed "runs" "group" "saved-run")
          outputs  ["build.transit" "build.log" "cases.cjs" "index.html"
                    "browser/browser.js" "renderer/render-wasm.js"
                    "renderer/render-wasm.wasm" "renderer/render-wasm.wasm.map"
                    "renderer/shared.js" "renderer/cargo-build.json"
                    "renderer/worker/render.js"]
          boot     (path/join managed "runner.cjs")
          outside  (path/join root "explicit-output" "run.transit")]
      (artifacts/write-manifest failed (assoc (:manifest failed) :status :failed))
      (doseq [file outputs]
        (write-file (path/join (:dir building) file) "build output"))
      (doseq [file ["run.log" "run.transit" "shot.png" "SHOT.PNG"]]
        (write-file (path/join run-dir file) "run output"))
      (doseq [dir ["run.log" "run.transit" "directory.PNG"]]
        (write-file (path/join managed "runs" dir "keep.txt") "keep directory"))
      (write-file (path/join managed "runs" "no-marker" "only.PNG") "screenshot")
      (write-file (path/join run-dir "record.transit") "unrelated run file")
      (write-file (path/join (:dir building) "custom" "build.log") "unrelated build file")
      (write-file boot "boot executable")
      (write-file outside "unmanaged")
      (artifacts/purge root category)
      (doseq [dir [(:dir building) (:dir failed) (:browser-dir building)
                   (:renderer-dir building) run-dir]]
        (t/is (.isDirectory (fs/statSync dir))))
      (doseq [file outputs]
        (t/is (= (= category :runs) (fs/existsSync (path/join (:dir building) file)))))
      (t/is (= (= category :runs) (fs/existsSync (:manifest-path failed))))
      (doseq [file ["run.log" "run.transit" "shot.png" "SHOT.PNG"]]
        (t/is (= (= category :builds) (fs/existsSync (path/join run-dir file)))))
      (t/is (= (= category :builds)
               (fs/existsSync (path/join managed "runs" "no-marker" "only.PNG"))))
      (doseq [dir ["run.log" "run.transit" "directory.PNG"]]
        (t/is (fs/existsSync (path/join managed "runs" dir "keep.txt"))))
      (t/is (fs/existsSync (path/join run-dir "record.transit")))
      (t/is (fs/existsSync (path/join (:dir building) "custom" "build.log")))
      (t/is (= "boot executable" (fs/readFileSync boot "utf8")))
      (t/is (= "unmanaged" (fs/readFileSync outside "utf8")))
      (artifacts/purge root category))))

(t/deftest purge-follows-category-and-nested-directory-symlinks-and-stops-cycles
  (let [managed (artifacts/managed-root @temporary-root)]
    (fs/mkdirSync managed #js {:recursive true})
    (doseq [category [:builds :runs]]
      (let [outside (path/join @temporary-root (str "outside-" (name category)))
            detached (path/join @temporary-root (str "detached-" (name category)))
            output  (if (= category :builds) "build.transit" "run.transit")
            saved   (path/join outside "keep.txt")
            link    (path/join managed (name category))]
        (write-file saved "keep symlink target")
        (write-file (path/join outside "saved" output) "output, not a parsed record")
        (write-file (path/join detached output) "linked output")
        (write-file (path/join detached "keep.txt") "unrelated")
        (fs/symlinkSync outside (path/join outside "saved" "cycle") "dir")
        (fs/symlinkSync detached (path/join outside "linked") "dir")
        (fs/symlinkSync outside link "dir")
        (artifacts/purge @temporary-root category)
        (t/is (.isSymbolicLink (fs/lstatSync link)))
        (t/is (.isSymbolicLink (fs/lstatSync (path/join outside "saved" "cycle"))))
        (t/is (.isSymbolicLink (fs/lstatSync (path/join outside "linked"))))
        (t/is (not (fs/existsSync (path/join outside "saved" output))))
        (t/is (not (fs/existsSync (path/join detached output))))
        (t/is (fs/existsSync (path/join detached "keep.txt")))
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

(t/deftest remove-builds-cleans-selected-ids-and-keeps-directories-and-unrelated-files
  (let [removed (ready-build)
        kept    (ready-build)
        managed (artifacts/managed-root @temporary-root)
        run     (path/join managed "runs" "saved" "run.transit")
        outside (path/join @temporary-root "outside" "keep.txt")
        browser (path/join @temporary-root "linked-browser")
        link    (path/join managed "builds" "outside-link")]
    (write-file run "keep run")
    (write-file outside "keep target")
    (write-file (path/join (:dir removed) "screenshot.PNG") "keep build screenshot")
    (write-file (path/join (path/dirname outside) "build.transit") "linked build output")
    (write-file (path/join browser "browser.js") "linked browser output")
    (write-file (path/join browser "keep.js") "unrelated")
    (fs/symlinkSync browser (path/join (path/dirname outside) "browser") "dir")
    (fs/symlinkSync (path/dirname outside) link "dir")
    (artifacts/remove-builds @temporary-root [(:id removed) "outside-link" "absent-build"])
    (t/is (.isDirectory (fs/statSync (:dir removed))))
    (t/is (not (fs/existsSync (:manifest-path removed))))
    (t/is (not (fs/existsSync (:cases-path removed))))
    (t/is (fs/existsSync (path/join (:dir removed) "screenshot.PNG")))
    (t/is (.isSymbolicLink (fs/lstatSync link)))
    (t/is (not (fs/existsSync (path/join link "build.transit"))))
    (t/is (not (fs/existsSync (path/join browser "browser.js"))))
    (t/is (fs/existsSync (path/join browser "keep.js")))
    (t/is (= kept (artifacts/load-build @temporary-root (:id kept))))
    (t/is (= "keep run" (fs/readFileSync run "utf8")))
    (t/is (= "keep target" (fs/readFileSync outside "utf8")))))

(t/deftest inside-builds-resolves-symlink-ancestors-with-missing-output-components
  (let [managed (artifacts/managed-root @temporary-root)
        builds  (path/join managed "builds")
        outside (path/join @temporary-root "external-builds")
        alias   (path/join @temporary-root "build-alias")]
    (t/is (artifacts/inside-builds? @temporary-root (path/join builds "future" "run")))
    (fs/mkdirSync managed #js {:recursive true})
    (fs/mkdirSync outside)
    (fs/symlinkSync outside builds "dir")
    (fs/symlinkSync builds alias "dir")
    (doseq [output [builds outside (path/join builds "new" "run")
                    (path/join outside "new" "run") (path/join alias "new" "run")]]
      (t/is (artifacts/inside-builds? @temporary-root output)))
    (t/is (not (artifacts/inside-builds? @temporary-root (str outside "-neighbor/run"))))
    (t/is (not (artifacts/inside-builds? @temporary-root (path/join managed "runs" "new"))))))

(defn- descriptor
  [id]
  {:id id
   :scene :artifact-only
   :scene-description "A descriptor supplied only by this build"
   :scene-seed 42
   :params {}
   :view {:scale 1 :x 0 :y 0}
   :context :fresh
   :completion :render-full
   :batch-size 1
   :preparation {}})

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

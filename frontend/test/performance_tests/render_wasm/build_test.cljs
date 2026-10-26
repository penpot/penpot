;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.build-test
  "Real preparation failures in temporary frontend/render-wasm checkouts.

  A scripted compiler keeps these tests independent of Cargo and Skia while
  exercising the process, filesystem and failure-retention boundary. Release
  integration checks cover compiler success and Cargo feature forwarding."
  (:require
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.transit :as transit]
   [benches.render-wasm.node.build :as build]
   [benches.render-wasm.node.process :as process]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private temporary-root (atom nil))

(defn- frontend-root
  []
  (path/join @temporary-root "frontend"))

(defn- renderer-root
  []
  (path/join @temporary-root "render-wasm"))

(t/use-fixtures :each
  {:before (fn []
             (reset! temporary-root
                     (fs/mkdtempSync (path/join (os/tmpdir) "penpot-render-build-test-")))
             (fs/mkdirSync (frontend-root))
             (fs/mkdirSync (renderer-root)))
   :after #(fs/rmSync @temporary-root #js {:recursive true :force true})})

(def ^:private renderer-wrapper
  (str "#!/usr/bin/env node\n"
       "const fs = require('node:fs');\n"
       "const path = require('node:path');\n"
       "const {spawnSync} = require('node:child_process');\n"
       "const dir = path.dirname(process.env.FRONTEND_DEST);\n"
       "const observation = {\n"
       "  cwd: process.cwd(),\n"
       "  manifest: fs.readFileSync(path.join(dir, 'build.transit'), 'utf8'),\n"
       "  log: fs.readFileSync(path.join(dir, 'build.log'), 'utf8'),\n"
       "  created: JSON.parse(fs.readFileSync(path.join(dir, 'created.json'), 'utf8'))\n"
       "};\n"
       "fs.writeFileSync(path.join(dir, 'renderer-observation.json'), JSON.stringify(observation));\n"
       "const result = spawnSync('flock', [\n"
       "  '--exclusive', path.join(__dirname, 'build.lock'),\n"
       "  process.execPath, path.join(__dirname, 'compiler.cjs')\n"
       "], {stdio: 'inherit'});\n"
       "if (result.error) throw result.error;\n"
       "process.exitCode = result.status === null ? 1 : result.status;\n"))

(def ^:private failing-compiler
  (str "const fs = require('node:fs');\n"
       "const path = require('node:path');\n"
       "fs.writeFileSync(path.join(process.env.FRONTEND_DEST, 'partial.js'), 'partial renderer output\\n');\n"
       "process.stdout.write('compiler stdout before failure\\n');\n"
       "process.stderr.write('compiler stderr before failure\\n');\n"
       "process.exitCode = 23;\n"))

(def ^:private sleeping-compiler
  (str "const fs = require('node:fs');\n"
       "const path = require('node:path');\n"
       "fs.writeFileSync(path.join(process.env.FRONTEND_DEST, 'lock-acquired.txt'), 'held');\n"
       "process.stdout.write('compiler lock acquired\\n');\n"
       "process.stderr.write('waiting in compiler\\n');\n"
       "setInterval(() => {}, 1000);\n"))

(defn- install-renderer
  [compiler]
  (fs/writeFileSync (path/join (renderer-root) "build") renderer-wrapper #js {:mode 493})
  (fs/writeFileSync (path/join (renderer-root) "compiler.cjs") compiler "utf8"))

(defn- read-manifest
  [build-ref]
  (transit/decode-str (fs/readFileSync (:manifest-path build-ref) "utf8")))

(defn- record-created
  [build-ref]
  ;; These reads must succeed before the executable can read created.json.
  (let [observation {:id (:id build-ref)
                     :manifest (fs/readFileSync (:manifest-path build-ref) "utf8")
                     :log (fs/readFileSync (:log-path build-ref) "utf8")
                     :renderer-started? (fs/existsSync (path/join (:dir build-ref) "renderer-observation.json"))}]
    (fs/writeFileSync (path/join (:dir build-ref) "created.json")
                      (js/JSON.stringify (clj->js observation)) "utf8")))

(defn- retained-build
  [cause]
  (let [id  (:build-id (ex-data cause))
        dir (path/join (frontend-root) "target" "performance" "render-wasm" "builds" id)]
    {:id id :dir dir
     :manifest-path (path/join dir "build.transit")
     :log-path (path/join dir "build.log")
     :renderer-dir (path/join dir "renderer")}))

(defn- ^:async rejected
  [promise]
  (try
    (let [result (await promise)]
      (t/is false (str "Expected preparation to fail, got " (pr-str result)))
      nil)
    (catch :default cause cause)))

(defn- complete
  [promise done]
  (.then promise
         (fn [_] (done))
         (fn [cause]
           (t/is false (str "Unexpected test error: " cause))
           (done))))

(t/deftest real-git-outside-a-repository-has-unknown-provenance
  (t/async done
    (complete
     ((^:async fn []
        (let [git (await (build/git-provenance (frontend-root)
                                               {:deadline (process/deadline 10000)}))]
          (t/is (= {:sha nil :dirty nil} git)))))
     done)))

(t/deftest nonzero-renderer-retains-created-evidence-output-and-failed-manifest
  (t/async done
    (complete
     ((^:async fn []
        (install-renderer failing-compiler)
        (let [cause       (await (rejected (build/prepare {:root (frontend-root)
                                                           :build-timeout-ms 10000
                                                           :on-created record-created})))
              data        (ex-data cause)
              child-data  (ex-data (ex-cause cause))
              retained    (retained-build cause)
              manifest    (read-manifest retained)
              log         (fs/readFileSync (:log-path retained) "utf8")
              observation (js->clj (js/JSON.parse
                                    (fs/readFileSync (path/join (:dir retained) "renderer-observation.json") "utf8"))
                                   :keywordize-keys true)
              created     (:created observation)
              initial     (transit/decode-str (:manifest created))
              at-renderer (transit/decode-str (:manifest observation))]
          (t/is (= ::build/preparation-failed (:type data)))
          (t/is (= :renderer (:phase data)))
          (t/is (= (:log-path retained) (:log-path data)))
          (t/is (= :process-exit (:type child-data)))
          (t/is (= 23 (:exit-code child-data)))
          (t/is (= (path/join (renderer-root) "build") (:command child-data)))
          (t/is (= (renderer-root) (:cwd observation)))
          (t/is (= (:id retained) (:id created) (:id manifest)
                   (:id initial) (:id at-renderer)))
          (t/is (= :building (:status initial) (:status at-renderer)))
          (t/is (false? (:renderer-started? created)))
          (t/is (= "" (:log created) (:log observation)))
          (t/is (= :failed (:status manifest)))
          (t/is (= :renderer (get-in manifest [:failure :phase])))
          (t/is (= :process-exit (get-in manifest [:failure :cause 0 :type])))
          (t/is (= {:sha nil :dirty nil} (get-in manifest [:provenance :git])))
          (t/is (str/includes? log "compiler stdout before failure\n"))
          (t/is (str/includes? log "compiler stderr before failure\n"))
          (t/is (str/includes? log "Preparation failed:"))
          (t/is (= "partial renderer output\n"
                   (fs/readFileSync (path/join (:renderer-dir retained) "partial.js") "utf8"))))))
     done)))

(t/deftest timed-out-lock-holder-retains-its-attempt-and-retry-gets-a-new-id
  (t/async done
    (complete
     ((^:async fn []
        (install-renderer sleeping-compiler)
        (let [cause    (await (rejected (build/prepare {:root (frontend-root)
                                                        :build-timeout-ms 3000
                                                        :on-created record-created})))
              retained (retained-build cause)
              manifest (read-manifest retained)
              before   (fs/readFileSync (:manifest-path retained) "utf8")
              log      (fs/readFileSync (:log-path retained) "utf8")]
          (t/is (= ::build/preparation-failed (:type (ex-data cause))))
          (t/is (= :renderer (:phase (ex-data cause))))
          (t/is (= (:log-path retained) (:log-path (ex-data cause))))
          (t/is (= :process-timeout (:type (ex-data (ex-cause cause)))))
          (t/is (= (:id retained) (:id manifest)))
          (t/is (= :failed (:status manifest)))
          (t/is (= :renderer (get-in manifest [:failure :phase])))
          (t/is (= :process-timeout (get-in manifest [:failure :cause 0 :type])))
          (t/is (= "held" (fs/readFileSync (path/join (:renderer-dir retained) "lock-acquired.txt") "utf8")))
          (t/is (str/includes? log "compiler lock acquired\n"))
          (t/is (str/includes? log "waiting in compiler\n"))
          (t/is (str/includes? log "Preparation failed:"))
          ;; The retry must acquire the same flock before its compiler can exit.
          (install-renderer failing-compiler)
          (let [retry-cause (await (rejected (build/prepare {:root (frontend-root)
                                                             :build-timeout-ms 10000
                                                             :on-created record-created})))
                retry       (retained-build retry-cause)
                retry-log   (fs/readFileSync (:log-path retry) "utf8")]
            (t/is (not= (:id retained) (:id retry)))
            (t/is (= :renderer (:phase (ex-data retry-cause))))
            (t/is (= 23 (:exit-code (ex-data (ex-cause retry-cause)))))
            (t/is (= :failed (:status (read-manifest retry))))
            (t/is (str/includes? retry-log "compiler stderr before failure\n"))
            (t/is (= before (fs/readFileSync (:manifest-path retained) "utf8")))
            (t/is (= log (fs/readFileSync (:log-path retained) "utf8")))
            (t/is (= #{(:id retained) (:id retry)}
                     (set (array-seq (fs/readdirSync (path/dirname (:dir retained)))))))))))
     done)))

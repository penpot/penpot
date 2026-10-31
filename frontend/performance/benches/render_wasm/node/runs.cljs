;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runs
  "Manages saved run destinations, schema:run persistence and diagnostic logs.

  Runs are saved under <frontend>/target/performance/render-wasm/runs.

  --output-dir selects another destination. Purge cleans files reachable from
  the default runs tree, including explicit destinations and directory links."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [benches.render-wasm.node.artifacts :as artifacts]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(defn create-home
  "Creates one run home and its empty log. The record is written separately
  after the plan exists. An explicit directory must not exist; a managed home
  uses the frontend root regardless of cwd."
  [{:keys [root run-id output-dir remove]}]
  (files/check-id run-id "invalid run id")
  (let [dir (files/check-output-dir
             (or output-dir (path/join (artifacts/managed-root root) "runs" run-id)))]
    (when (and remove (artifacts/inside-builds? root dir))
      (files/invalid-home "--remove cannot use an output directory inside builds" {:path dir}))
    (assoc (files/create-home dir "run" "cannot create run home") :run-id run-id)))

(defn get-record
  "Reads and validates a saved run record from a path relative to cwd."
  [file]
  (when-not (and (string? file) (fs/existsSync file))
    (throw (ex-info "run record is missing" {:type ::missing-run :path file})))
  (let [record (result/decode (fs/readFileSync file "utf8"))]
    (when-not (= :run-record (:type record))
      (throw (ex-info "expected a saved run record" {:type ::invalid-run :path file})))
    record))

(defn write-record!
  "Validates schema:run and atomically replaces the saved file."
  [home run]
  (files/atomic-write (:record-path home) (result/encode run)))

(defn screenshot-file
  "Returns the relative PNG filename for a case, replacing path separators."
  [case-id]
  (str (str/replace (result/case-name case-id) #"[/\\]" "-") ".png"))

(defn screenshot-path
  "Returns the relative filename and absolute path under the run home."
  [home case-id]
  (let [file (screenshot-file case-id)]
    {:file file :path (path/join (:dir home) file)}))

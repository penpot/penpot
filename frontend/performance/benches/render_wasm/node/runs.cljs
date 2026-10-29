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

(defn- invalid-home
  [message data]
  (throw (ex-info message (assoc data :type ::invalid-home))))

(defn- check-id
  [id]
  (when-not (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,127}" id))
    (invalid-home "invalid run id" {:id id}))
  id)

(defn check-output-dir
  "Resolves an explicit user directory from the caller's cwd and checks that it
  does not exist. Nil selects the managed default. Does not create anything."
  [dir]
  (when (some? dir)
    (when (str/blank? dir)
      (invalid-home "output directory requires a nonempty path" {}))
    (let [resolved (path/resolve dir)]
      (when (fs/lstatSync resolved #js {:throwIfNoEntry false})
        (invalid-home "output directory already exists" {:path resolved}))
      resolved)))

(defn create-home
  "Creates one run home and its empty log. The record is written separately
  after the plan exists. An explicit directory must not exist; a managed home
  uses the frontend root regardless of cwd."
  [{:keys [root run-id output-dir remove]}]
  (check-id run-id)
  (let [dir        (check-output-dir
                    (or output-dir (path/join (artifacts/managed-root root) "runs" run-id)))
        record-path (path/join dir "run.transit")
        log-path    (path/join dir "run.log")]
    (when (and remove (artifacts/inside-builds? root dir))
      (invalid-home "--remove cannot use an output directory inside builds" {:path dir}))
    (try
      (fs/mkdirSync dir #js {:recursive true})
      (catch :default cause
        (invalid-home "cannot create run home"
                      {:path dir :cause (or (ex-message cause) (str cause))})))
    (try
      (fs/writeFileSync log-path "" #js {:encoding "utf8" :flag "wx"})
      (catch :default cause
        (invalid-home "cannot create run log"
                      {:path log-path :cause (or (ex-message cause) (str cause))})))
    {:dir dir
     :record-path record-path
     :log-path log-path
     :run-id run-id}))

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

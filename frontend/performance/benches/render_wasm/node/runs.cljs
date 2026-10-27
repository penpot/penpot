;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runs
  "Manages saved run destinations, RunRecord persistence and diagnostic logs.

  Runs are saved under <frontend>/target/performance/render-wasm/runs.

  This can be overriden in the runner with `--output-dir PATH`. If done, this
  path is not included among those purged when cleaning up."
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
      (when (fs/existsSync resolved)
        (invalid-home "output directory already exists" {:path resolved}))
      resolved)))

(defn create-home
  "Creates one run home and its empty log. The record is written separately
  after the plan exists. An explicit directory must not exist; a managed home
  uses the frontend root regardless of cwd."
  [{:keys [root run-id output-dir]}]
  (check-id run-id)
  (let [explicit   (check-output-dir output-dir)
        managed    (when-not explicit (artifacts/managed-root root))
        runs-dir   (when managed (path/join managed "runs"))
        dir        (or explicit (path/join runs-dir run-id))
        record-path (path/join dir "run.transit")
        log-path    (path/join dir "run.log")]
    (when managed
      (fs/mkdirSync runs-dir #js {:recursive true}))
    (try
      (fs/mkdirSync dir)
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
  "Validates and atomically replaces the saved RunRecord."
  [home run]
  (files/atomic-write (:record-path home) (result/encode run)))

(defn append-log!
  "Appends runner diagnostics to the run log. Callers keep lines terminated."
  [home text]
  (fs/appendFileSync (:log-path home) (str text) "utf8"))

(defn screenshot-file
  "Returns the relative PNG filename for a case, replacing path separators."
  [case-id]
  (str (str/replace (result/case-name case-id) #"[/\\]" "-") ".png"))

(defn screenshot-path
  "Returns the relative filename and absolute path under the run home."
  [home case-id]
  (let [file (screenshot-file case-id)]
    {:file file :path (path/join (:dir home) file)}))

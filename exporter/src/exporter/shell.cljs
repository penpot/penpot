;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.shell
  "Shell and filesystem utilities of the new tree.

  Same operations as the legacy `app.util.shell`, adapted: nothing
  runs at namespace load (no log level, no global tmpdir created the
  first time anything requires this) and no promesa chains (`^:async`
  + `await`). Paths are explicit everywhere: `tempfile` takes the
  directory to create under, so the temp area is injected by whoever
  owns it — the `:exporter/tmpdir` service at boot — instead of read
  from a global. Legacy operations nobody calls did not survive the
  move."
  (:require
   ["node:child_process" :as proc]
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(def ^:const default-deletion-delay
  (* 60 60 1)) ;; 1h

(defn ensure-dir
  "Creates `dir` (and parents) when missing, returning it. The one
  place a directory comes into existence in the new tree; a blank path
  is a wiring error, not an empty directory."
  [dir]
  (when (str/blank? dir)
    (throw (ex/error :type :assertion
                     :code :tmpdir-path-missing
                     :hint "shell needs a directory to ensure")))
  (when-not (fs/existsSync dir)
    (fs/mkdirSync dir #js {:recursive true}))
  dir)

(defn schedule-deletion
  ([path] (schedule-deletion path default-deletion-delay))
  ([path delay]
   (let [remove-path
         (fn []
           (try
             (when (fs/existsSync path)
               (fs/rmSync path #js {:recursive true})
               (l/trc :hint "tempfile permanently deleted" :path path))
             (catch :default cause
               (l/err :hint "error on deleting temporal file"
                      :path path
                      :cause cause))))
         scheduled-at
         (-> (ct/now) (ct/plus #js {:seconds delay}))]

     (l/trc :hint "schedule tempfile deletion"
            :path path
            :scheduled-at (ct/format-inst scheduled-at))

     (js/setTimeout remove-path (* delay 1000))
     path)))

(defn tempfile
  "A fresh path under `dir` that nothing else owns yet, scheduled for
  deletion like every other temp file."
  [dir & {:keys [prefix suffix]
          :or {prefix "penpot."
               suffix ".tmp"}}]
  (loop [i 0]
    (if (< i 1000)
      (let [path (path/join dir (str/concat prefix (uuid/next) "-" i suffix))]
        (if (fs/existsSync path)
          (recur (inc i))
          (schedule-deletion path)))
      (throw (ex/error :type :internal
                       :code :unable-to-locate-temporal-file
                       :hint "unable to find a tempfile candidate")))))

(defn ^:async move
  [origin-path dest-path]
  (await (.rename fs/promises origin-path dest-path)))

(defn ^:async write-file
  [fpath content]
  (await (.writeFile fs/promises fpath content)))

(defn ^:async read-file
  [fpath]
  (await (.readFile fs/promises fpath)))

(defn ^:async run-cmd
  [cmd & args]
  (await
   (js/Promise.
    (fn [resolve reject]
      (l/trace :fn :run-cmd :cmd cmd :args args)
      (proc/execFile cmd (clj->js args) #js {:encoding "buffer"}
                     (fn [error stdout _stderr]
                       (if error
                         (reject error)
                         (resolve stdout))))))))

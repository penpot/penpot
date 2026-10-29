;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.tmpdir
  "The temp-file area as a system component (`:exporter/tmpdir`).

  Its config value names the directory (`{:path ...}`, read from the
  environment at the wiring layer) plus the age that makes a leftover
  an orphan (`:job-ttl`, seconds, same unit as the environment knob);
  booting ensures the directory exists and cleans whatever a previous
  process left behind, before anything that writes to it boots. The
  running instance is the path itself, which renderers and runners
  read straight from their config. Fail fast on a blank path instead
  of failing the first render that writes. Named for what it owns,
  not for the legacy namespace it shadows.

  The area also owns what jobs park in it: every path a job tracks is
  removed on its release, so temp files die with the settle instead of
  an hour later (or never, on a crash)."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [cuerdas.core :as str]
   [exporter.shell :as shell]
   [exporter.utils.system :as system]))

(def ^:private managed-prefix "penpot.")

(defonce ^:private tracked (atom {}))

(defn track
  "Registers `path` as owned by `job-id`, so it is removed when the job
  settles. Returns the path, so it wraps the call that creates it."
  [job-id path]
  (when (and job-id path)
    (swap! tracked update (str job-id) (fnil conj #{}) path))
  path)

(defn- ^:async remove-path
  [path]
  (try
    (await (fsp/rm path #js {:recursive true :force true}))
    (catch :default cause
      (l/warn :hint "unable to remove job temp file" :path path :cause cause))))

(defn ^:async release
  "Removes every file the job owns. Called once the job reached a
  terminal state and its result has already been uploaded, so nothing
  else reads them. Unknown jobs resolve at once."
  [job-id]
  (let [paths (get @tracked (str job-id))]
    (swap! tracked dissoc (str job-id))
    (when (seq paths)
      (await (js/Promise.all (mapv remove-path paths)))
      (l/dbg :hint "released job temp files" :job-id (str job-id) :count (count paths)))
    nil))

(defn ^:async clean-orphans
  "Removes managed temp files older than `job-ttl-s`. They can only be
  leftovers of a previous process: every live one belongs to a job of
  this process. Resolves the count it removed."
  [tmpdir job-ttl-s]
  (let [max-age (* 1000 job-ttl-s)
        now     (js/Date.now)]
    (try
      (let [entries (await (fsp/readdir tmpdir))
            removed (volatile! 0)]
        (doseq [entry (filter #(str/starts-with? % managed-prefix) entries)]
          (let [fpath (path/join tmpdir entry)]
            (try
              (let [^js stat (await (fsp/stat fpath))]
                (when (> (- now (inst-ms (.-mtime stat))) max-age)
                  (await (remove-path fpath))
                  (vswap! removed inc)))
              (catch :default _ nil))))
        (when (pos? @removed)
          (l/info :hint "removed orphaned export temp files" :count @removed))
        @removed)
      (catch :default cause
        (l/warn :hint "temp file cleanup failed" :cause cause)
        0))))

(defn- ^:async boot
  [path job-ttl]
  (let [dir (shell/ensure-dir path)]
    (await (clean-orphans dir job-ttl))
    dir))

(defmethod system/init-key :exporter/tmpdir
  [_ {:keys [path job-ttl]}]
  (when (nil? job-ttl)
    (throw (ex/error :type :assertion
                     :code :job-ttl-missing
                     :hint "tmpdir needs :job-ttl (seconds) in its config")))
  (boot path job-ttl))

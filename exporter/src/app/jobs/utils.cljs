;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs.utils
  "Temp file ownership for export jobs.

  Temp files used to be cleaned only by the per-file timer in `app.util.shell`,
  an hour after creation and lost entirely on restart. Here each job owns the
  paths it creates, so they are dropped as soon as it settles and whatever a
  crash left behind is cleaned at boot."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private managed-prefix "penpot.")

(defonce ^:private tracked (atom {}))

(defn track!
  "Registers `path` as owned by `job-id`, so it is removed when the job settles."
  [job-id path]
  (when (and job-id path)
    (swap! tracked update (str job-id) (fnil conj #{}) path))
  path)

(defn- remove-path!
  [path]
  (->> (p/do (fsp/rm path #js {:recursive true :force true}))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to remove job temp file" :path path :cause cause)
                 (p/resolved nil)))))

(defn release!
  "Removes every file the job owns. Called once the job reached a terminal
  state and its result has already been uploaded, so nothing else reads them."
  [job-id]
  (let [k     (str job-id)
        paths (get @tracked k)]
    (swap! tracked dissoc k)
    (if (seq paths)
      (->> (map remove-path! paths)
           (p/all)
           (p/fmap (fn [_]
                     (l/dbg :hint "released job temp files" :job-id k :count (count paths))
                     nil)))
      (p/resolved nil))))

(defn- clean!
  "Removes managed temp files older than the job TTL. They can only be leftovers
  of a previous process: every live one belongs to a job of this process."
  []
  (let [max-age (* 1000 (cf/get :exporter-job-ttl 3600))
        now     (js/Date.now)]
    (->> (p/do (fsp/readdir sh/tmpdir))
         (p/mcat (fn [entries]
                   (->> (filter #(str/starts-with? % managed-prefix) entries)
                        (map (fn [entry]
                               (let [fpath (path/join sh/tmpdir entry)]
                                 (->> (p/do (fsp/stat fpath))
                                      (p/mcat (fn [^js stat]
                                                (if (> (- now (inst-ms (.-mtime stat))) max-age)
                                                  (->> (remove-path! fpath)
                                                       (p/fmap (constantly 1)))
                                                  (p/resolved 0))))
                                      (p/merr (fn [_] (p/resolved 0)))))))
                        (p/all))))
         (p/fmap (fn [results]
                   (let [removed (reduce + 0 results)]
                     (when (pos? removed)
                       (l/info :hint "removed orphaned export temp files" :count removed))
                     removed)))
         (p/merr (fn [cause]
                   (l/warn :hint "temp file cleanup failed" :cause cause)
                   (p/resolved 0))))))

(defn init
  []
  (clean!))

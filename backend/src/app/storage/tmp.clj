;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.tmp
  "Temporal files service all created files will be tried to clean after
  1 hour after creation. This is a best effort, if this process fails,
  the operating system cleaning task should be responsible of
  permanently delete these files (look at systemd-tempfiles)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.storage :as-alias sto]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(declare remove-temp-file)
(defonce queue (a/chan 128))

(defmethod ig/pre-init-spec ::cleaner [_]
  (s/keys :req [::sto/min-age ::wrk/scheduled-executor]))

(defmethod ig/prep-key ::cleaner
  [_ cfg]
  (merge {::sto/min-age (dt/duration "30m")}
         (d/without-nils cfg)))

(defmethod ig/init-key ::cleaner
  [_ {:keys [::sto/min-age ::wrk/scheduled-executor] :as cfg}]
  (px/thread
    {:name "penpot/storage-tmp-cleaner"}
    (try
      (l/info :hint "started tmp file cleaner")
      (loop []
        (when-let [path (a/<!! queue)]
          (l/trace :hint "schedule tempfile deletion" :path path
                   :expires-at (dt/plus (dt/now) min-age))
          (px/schedule! scheduled-executor
                        (inst-ms min-age)
                        (partial remove-temp-file path))
          (recur)))
      (catch InterruptedException _
        (l/debug :hint "interrupted"))
      (finally
        (l/info :hint "terminated tmp file cleaner")))))

(defmethod ig/halt-key! ::cleaner
  [_ thread]
  (px/interrupt! thread))

(defn- remove-temp-file
  "Permanently delete tempfile"
  [path]
  (l/trace :hint "permanently delete tempfile" :path path)
  (when (fs/exists? path)
    (fs/delete path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tempfile
  "Returns a tmpfile candidate (without creating it)"
  [& {:keys [suffix prefix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (let [candidate (fs/tempfile :suffix suffix :prefix prefix)]
    (a/offer! queue candidate)
    candidate))

(defn create-tempfile
  [& {:keys [suffix prefix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (let [path (fs/create-tempfile :suffix suffix :prefix prefix)]
    (a/offer! queue path)
    path))

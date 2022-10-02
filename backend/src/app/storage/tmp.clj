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
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(declare remove-temp-file)
(defonce queue (a/chan 128))

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::cleaner [_]
  (s/keys :req-un [::min-age ::wrk/scheduler ::wrk/executor]))

(defmethod ig/prep-key ::cleaner
  [_ cfg]
  (merge {:min-age (dt/duration {:minutes 30})}
         (d/without-nils cfg)))

(defmethod ig/init-key ::cleaner
  [_ {:keys [scheduler executor min-age] :as cfg}]
  (l/info :hint "starting tempfile cleaner service")
  (let [cch (a/chan)]
    (a/go-loop []
      (let [[path port] (a/alts! [queue cch])]
        (when (not= port cch)
          (l/trace :hint "schedule tempfile deletion" :path path
                   :expires-at (dt/plus (dt/now) min-age))
          (px/schedule! scheduler
                        (inst-ms min-age)
                        (partial remove-temp-file executor path))
          (recur))))
    cch))

(defmethod ig/halt-key! ::cleaner
  [_ close-ch]
  (l/info :hint "stopping tempfile cleaner service")
  (some-> close-ch a/close!))

(defn- remove-temp-file
  "Permanently delete tempfile"
  [executor path]
  (px/with-dispatch executor
    (l/trace :hint "permanently delete tempfile" :path path)
    (when (fs/exists? path)
      (fs/delete path))))

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

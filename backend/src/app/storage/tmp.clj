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
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.worker :as wrk]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.nio.file.Files))

(def default-tmp-dir "/tmp/penpot")

(declare ^:private remove-temp-file)
(declare ^:private io-loop)

(defonce queue (sp/chan :buf 128))

(defmethod ig/assert-key ::cleaner
  [_ {:keys [::wrk/executor]}]
  (assert (sm/valid? ::wrk/executor executor)))

(defmethod ig/expand-key ::cleaner
  [k v]
  {k (assoc v ::min-age (ct/duration "60m"))})

(defmethod ig/init-key ::cleaner
  [_ cfg]
  (fs/create-dir default-tmp-dir)
  (px/fn->thread (partial io-loop cfg)
                 {:name "penpot/storage/tmp-cleaner"}))

(defmethod ig/halt-key! ::cleaner
  [_ thread]
  (px/interrupt! thread))

(defn- io-loop
  [{:keys [::min-age] :as cfg}]
  (l/inf :hint "started tmp cleaner" :default-min-age (ct/format-duration min-age))
  (try
    (loop []
      (when-let [[path min-age'] (sp/take! queue)]
        (let [min-age (or min-age' min-age)]
          (l/dbg :hint "schedule tempfile deletion" :path path
                 :expires-at (ct/plus (ct/now) min-age))
          (px/schedule! (inst-ms min-age) (partial remove-temp-file cfg path))
          (recur))))
    (catch InterruptedException _
      (l/trace :hint "cleaner interrupted"))
    (finally
      (l/info :hint "cleaner terminated"))))

(defn- remove-temp-file
  "Permanently delete tempfile"
  [{:keys [::wrk/executor]} path]
  (when (fs/exists? path)
    (px/run! executor
             (fn []
               (l/dbg :hint "permanently delete tempfile" :path path)
               (fs/delete path)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tempfile*
  [& {:keys [suffix prefix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (let [attrs (fs/make-permissions "rw-r--r--")
        path  (fs/join default-tmp-dir (str prefix (uuid/next) suffix))]
    (Files/createFile path attrs)))

(defn tempfile
  [& {:keys [min-age] :as opts}]
  (let [path (tempfile* opts)]
    (sp/offer! queue [path (some-> min-age ct/duration)])
    path))

(defn tempfile-from
  "Create a new tempfile from from consuming the stream"
  [input & {:as options}]
  (let [path (tempfile options)]
    (with-open [^InputStream input (io/input-stream input)]
      (with-open [^OutputStream output (io/output-stream path)]
        (io/copy input output)))
    path))

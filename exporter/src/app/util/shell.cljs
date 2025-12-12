;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.shell
  "Shell & FS utilities."
  (:require
   ["node:child_process" :as proc]
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :trace)

(def ^:const default-deletion-delay
  (* 60 60 1)) ;; 1h

(def tmpdir
  (let [path (cf/get :tempdir)]
    (l/inf :hint "tmptdir setup" :path path)
    (when-not (fs/existsSync path)
      (fs/mkdirSync path #js {:recursive true}))
    path))

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
  [& {:keys [prefix suffix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (loop [i 0]
    (if (< i 1000)
      (let [path (path/join tmpdir (str/concat prefix (uuid/next) "-" i  suffix))]
        (if (fs/existsSync path)
          (recur (inc i))
          (schedule-deletion path)))
      (ex/raise :type :internal
                :code :unable-to-locate-temporal-file
                :hint "unable to find a tempfile candidate"))))

(defn move!
  [origin-path dest-path]
  (.rename fs/promises origin-path dest-path))

(defn stat
  [path]
  (->> (.stat fs/promises path)
       (p/fmap (fn [data]
                 {:path path
                  :created-at (inst-ms (.-ctime ^js data))
                  :size (.-size data)}))
       (p/merr (fn [_cause]
                 (p/resolved nil)))))

(defn rmdir!
  [path]
  (.rm fs/promises path #js {:recursive true}))

(defn write-file!
  [fpath content]
  (.writeFile fs/promises fpath content))

(defn read-file
  [fpath]
  (.readFile fs/promises fpath))

(defn run-cmd!
  [cmd]
  (p/create
   (fn [resolve reject]
     (l/trace :fn :run-cmd :cmd cmd)
     (proc/exec cmd #js {:encoding "buffer"}
                (fn [error stdout _stderr]
                  ;; (l/trace :fn :run-cmd :stdout stdout)
                  (if error
                    (reject error)
                    (resolve stdout)))))))


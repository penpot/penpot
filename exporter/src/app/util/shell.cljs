;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.shell
  "Shell & FS utilities."
  (:require
   ["child_process" :as proc]
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :trace)

(def tempfile-minage (* 1000 60 60 1)) ;; 1h

(def tmpdir
  (let [path (path/join (os/tmpdir) "penpot")]
    (when-not (fs/existsSync path)
      (fs/mkdirSync path #js {:recursive true}))
    path))


(defn- schedule-deletion!
  [path]
  (letfn [(remote-tempfile []
            (when (fs/existsSync path)
              (l/trace :hint "permanently remove tempfile" :path path)
              (fs/rmSync path #js {:recursive true})))]
    (l/trace :hint "schedule tempfile deletion"
             :path path
             :scheduled-at (.. (js/Date. (+ (js/Date.now) tempfile-minage)) toString))
    (js/setTimeout remote-tempfile tempfile-minage)))

(defn tempfile
  [& {:keys [prefix suffix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (loop [i 0]
    (if (< i 1000)
      (let [path (path/join tmpdir (str/concat prefix (uuid/next) "-" i  suffix))]
        (if (fs/existsSync path)
          (recur (inc i))
          (do
            (schedule-deletion! path)
            path)))
      (ex/raise :type :internal
                :code :unable-to-locate-temporal-file
                :hint "unable to find a tempfile candidate"))))

(defn move!
  [origin-path dest-path]
  (.rename fs/promises origin-path dest-path))

(defn stat
  [path]
  (-> (.stat fs/promises path)
      (p/then (fn [data]
                {:created-at (inst-ms (.-ctime ^js data))
                 :size (.-size data)}))
      (p/catch (constantly nil))))

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


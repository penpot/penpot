;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.shell
  "Shell & FS utilities."
  (:require
   ["child_process" :as proc]
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [app.common.logging :as l]
   [promesa.core :as p]))

(l/set-level! :trace)

(defn create-tmpdir!
  [prefix]
  (-> (.mkdtemp fs/promises prefix)
      (p/then (fn [result]
                (path/join (os/tmpdir) result)))))


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
                (fn [error stdout stderr]
                  ;; (l/trace :fn :run-cmd :stdout stdout)
                  (if error
                    (reject error)
                    (resolve stdout)))))))


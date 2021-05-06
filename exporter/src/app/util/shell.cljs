;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.shell
  "Shell & FS utilities."
  (:require
   ["child_process" :as chp]
   ["fs" :as fs]
   ["os" :as os]
   ["path" :as path]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]))

(log/set-level "app.util.shell" :trace)

(defn create-tmpdir!
  [prefix]
  (p/create
   (fn [resolve reject]
     (fs/mkdtemp (path/join (os/tmpdir) prefix)
                 (fn [err dir]
                   (if err
                     (reject err)
                     (resolve dir)))))))

(defn write-file!
  [fpath content]
  (p/create
   (fn [resolve reject]
     (fs/writeFile fpath content (fn [err]
                                   (if err
                                     (reject err)
                                     (resolve nil)))))))
(defn read-file
  [fpath]
  (p/create
   (fn [resolve reject]
     (fs/readFile fpath (fn [err content]
                          (if err
                            (reject err)
                            (resolve content)))))))

(defn run-cmd!
  [cmd]
  (p/create
   (fn [resolve reject]
     (log/trace :fn :run-cmd :cmd cmd)
     (chp/exec cmd #js {:encoding "buffer"}
               (fn [error stdout stderr]
                 ;; (log/trace :fn :run-cmd :stdout stdout)
                 (if error
                   (reject error)
                   (resolve stdout)))))))

(defn rmdir!
  [path]
  (p/create
   (fn [resolve reject]
     (fs/rmdir path #js {:recursive true}
               (fn [err]
                 (if err
                   (reject err)
                   (resolve nil)))))))



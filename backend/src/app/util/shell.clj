;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.shell
  "A penpot specific, modern api for executing external (shell)
  subprocesses"
  (:require
   [app.worker :as-alias wrk]
   [datoteka.io :as io]
   [promesa.exec :as px])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.util.List
   org.apache.commons.io.IOUtils))

(set! *warn-on-reflection* true)

(defn- read-as-bytes
  [in]
  (with-open [^InputStream input (io/input-stream in)]
    (io/read input)))

(defn- read-as-string
  ([in] (read-as-string in "UTF-8"))
  ([in enc]
   (IOUtils/toString ^InputStream in ^String enc)))

(defn- read-with-enc
  [stream enc]
  (if (= enc :bytes)
    (read-as-bytes stream)
    (read-as-string stream enc)))

(defn- set-env
  [penv k v]
  (.put ^java.util.Map penv
        ^String k
        ^String v))

(defn exec!
  [system & {:keys [cmd in out-enc in-enc env]
             :or {out-enc "UTF-8"
                  in-enc "UTF-8"}}]
  (assert (vector? cmd) "a command parameter should be a vector")
  (assert (every? string? cmd) "the command should be a vector of strings")

  (let [executor (::wrk/executor system)
        builder  (ProcessBuilder. ^List cmd)
        env-map  (.environment ^ProcessBuilder builder)
        _        (reduce-kv set-env env-map env)
        process  (.start builder)]

    (if in
      (px/run! executor
               (fn []
                 (with-open [^OutputStream stdin (.getOutputStream ^Process process)]
                   (io/write stdin in :encoding in-enc))))
      (io/close (.getOutputStream ^Process process)))

    (with-open [stdout (.getInputStream ^Process process)
                stderr (.getErrorStream ^Process process)]
      (let [out (px/submit! executor (fn [] (read-with-enc stdout out-enc)))
            err (px/submit! executor (fn [] (read-as-string stderr)))
            ext (.waitFor ^Process process)]
        {:exit ext
         :out @out
         :err @err}))))

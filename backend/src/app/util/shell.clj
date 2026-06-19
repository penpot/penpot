;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.shell
  "A penpot specific, modern api for executing external (shell)
  subprocesses"
  (:require
   [app.common.exceptions :as ex]
   [app.worker :as-alias wrk]
   [datoteka.io :as io]
   [promesa.exec :as px])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.util.concurrent.TimeUnit
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
        ^String v)
  penv)

(defn exec!
  [system & {:keys [cmd in out-enc in-enc env timeout]
             :or {out-enc "UTF-8"
                  in-enc "UTF-8"}}]
  (assert (vector? cmd) "a command parameter should be a vector")
  (assert (every? string? cmd) "the command should be a vector of strings")

  (let [executor (::wrk/executor system)
        _        (assert (some? executor) "executor is required, check ::wrk/executor")
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
      (let [out (px/submit! executor (fn [] (try (read-with-enc stdout out-enc)
                                                 (catch java.io.IOException _ ""))))
            err (px/submit! executor (fn [] (try (read-as-string stderr)
                                                 (catch java.io.IOException _ ""))))
            ext (if timeout
                  (let [completed (.waitFor ^Process process (long timeout) TimeUnit/SECONDS)]
                    (if completed
                      (.exitValue ^Process process)
                      (do
                        (.destroyForcibly ^Process process)
                        (ex/raise :type :internal
                                  :code :process-timeout
                                  :hint (str "process timed out after " timeout " seconds")
                                  :cmd cmd
                                  :timeout timeout))))
                  (.waitFor ^Process process))]
        {:exit ext
         :out @out
         :err @err}))))

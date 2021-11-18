;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.rlimit
  "Resource usage limits (in other words: semaphores)."
  (:require
   [app.common.logging :as l]
   [app.util.services :as sv])
  (:import
   java.util.concurrent.Semaphore))

(defn acquire!
  [sem]
  (.acquire ^Semaphore sem))

(defn release!
  [sem]
  (.release ^Semaphore sem))

(defn wrap-rlimit
  [_cfg f mdata]
  (if-let [permits (::permits mdata)]
    (let [sem (Semaphore. permits)]
      (l/debug :hint "wrapping rlimit" :handler (::sv/name mdata) :permits permits)
      (fn [cfg params]
        (try
          (acquire! sem)
          (f cfg params)
          (finally
            (release! sem)))))
    f))



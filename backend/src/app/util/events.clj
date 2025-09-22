;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.events
  "A generic asynchronous events notifications subsystem; used mainly
  for mark event points in functions and be able to attach listeners
  to them. Mainly used in http.sse for progress reporting."
  (:refer-clojure :exclude [tap run!])
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(def ^:dynamic *channel* nil)

(defn tap
  ([type data]
   (when-let [channel *channel*]
     (sp/put! channel [type data])
     nil))
  ([channel type data]
   (when channel
     (sp/put! channel [type data])
     nil)))

(defn spawn-listener
  [channel on-event on-close]
  (assert (sp/chan? channel) "expected active events channel")

  (px/thread
    {:virtual true}
    (try
      (loop []
        (when-let [event (sp/take! channel)]
          (let [result (ex/try! (on-event event))]
            (if (ex/exception? result)
              (do
                (l/wrn :hint "unexpected exception" :cause result)
                (sp/close! channel))
              (recur)))))
      (finally
        (on-close)))))

(defn run-with!
  "A high-level facility for to run a function in context of event
  emiter."
  [f on-event]

  (binding [*channel* (sp/chan :buf 32)]
    (let [listener (spawn-listener *channel* on-event (constantly nil))]
      (try
        (f)
        (finally
          (sp/close! *channel*)
          (px/await! listener))))))


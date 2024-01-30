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
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(def ^:dynamic *channel* nil)

(defn channel
  []
  (sp/chan :buf 32))

(defn tap
  [type data]
  (when-let [channel *channel*]
    (sp/put! channel [type data])
    nil))

(defn start-listener
  [on-event on-close]

  (dm/assert!
   "expected active events channel"
   (sp/chan? *channel*))

  (px/thread
    {:virtual true}
    (try
      (loop []
        (when-let [event (sp/take! *channel*)]
          (let [result (ex/try! (on-event event))]
            (if (ex/exception? result)
              (do
                (l/wrn :hint "unexpected exception" :cause result)
                (sp/close! *channel*))
              (recur)))))
      (finally
        (on-close)))))

(defn run-with!
  "A high-level facility for to run a function in context of event
  emiter."
  [f on-event]

  (binding [*channel* (sp/chan :buf 32)]
    (let [listener (start-listener on-event (constantly nil))]
      (try
        (f)
        (finally
          (sp/close! *channel*)
          (px/await! listener))))))


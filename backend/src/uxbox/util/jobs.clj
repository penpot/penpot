;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.jobs
  "Scheduled jobs facilities."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [vertx.timers :as vt]
   [vertx.util :as vu]))

(defn schedule!
  [vsm f {:keys [::interval] :as options}]
  (s/assert var? f)
  (let [system (vu/resolve-system vsm)
        state  (atom nil)
        taskfn (fn wrapped-task []
                 (-> (p/do! (@f options))
                     (p/catch (fn [err]
                                (log/error err "Error on executing the task")
                                nil))
                     (p/then (fn [_]
                               (let [tid (vt/schedule-once! vsm interval wrapped-task)]
                                 (reset! state tid)
                                 nil)))))
        tid  (vt/schedule-once! vsm interval taskfn)]
    (reset! state tid)
    (reify
      java.lang.AutoCloseable
      (close [this]
        (locking this
          (when-let [timer-id (deref state)]
            (.cancelTimer system timer-id)
            (reset! state nil)))))))

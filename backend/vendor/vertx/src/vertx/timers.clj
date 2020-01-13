;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.timers
  "The timers and async scheduled tasks."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [vertx.util :as vu])
  (:import
   io.vertx.core.Vertx
   io.vertx.core.Handler))

(defn schedule-once!
  [vsm ms f]
  (let [^Vertx system (vu/resolve-system vsm)
        ^Handler handler (vu/fn->handler (fn [v] (f)))
        timer-id (.setTimer system ms handler)]
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.cancelTimer system timer-id)))))

(defn sechdule-periodic!
  [vsm ms f]
  (let [^Vertx system (vu/resolve-system vsm)
        ^Handler handler (vu/fn->handler (fn [v] (f)))
        timer-id (.setPeriodic system ms handler)]
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.cancelTimer system timer-id)))))

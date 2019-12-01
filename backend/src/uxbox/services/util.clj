;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.util
  (:require
   [clojure.tools.logging :as log]
   [vertx.core :as vc]
   [uxbox.core :refer [system]]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.dispatcher :as uds]
   [uxbox.util.exceptions :as ex]))

;; (def logging-interceptor
;;   {:enter (fn [data]
;;             (let [type (get-in data [:request ::type])]
;;               (assoc data ::start-time (System/nanoTime))))
;;    :leave (fn [data]
;;             (let [elapsed (- (System/nanoTime) (::start-time data))
;;                   elapsed (str (quot elapsed 1000000) "ms")
;;                   type (get-in data [:request ::type])]
;;               (log/info "service" type "processed in" elapsed)
;;               data))})

(defn raise-not-found-if-nil
  [v]
  (if (nil? v)
    (ex/raise :type :not-found
              :hint "Object doest not exists.")
    v))

(def constantly-nil (constantly nil))

(defn handle-on-context
  [p]
  (->> (vc/get-or-create-context system)
       (vc/handle-on-context p)))

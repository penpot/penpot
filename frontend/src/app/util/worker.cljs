;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.worker
  "A lightweight layer on top of webworkers api."
  (:require
   [beicon.core :as rx]
   [app.common.uuid :as uuid]
   [app.util.transit :as t]))

(declare handle-response)
(defrecord Worker [instance stream])

(defn ask!
  [w message]
  (let [sender-id (uuid/next)
        data (t/encode {:payload message :sender-id sender-id})
        instance (:instance w)]
    (.postMessage instance data)
    (->> (:stream w)
         (rx/filter #(= (:reply-to %) sender-id))
         (rx/map handle-response)
         (rx/first))))

(defn init
  "Return a initialized webworker instance."
  [path on-error]
  (let [ins (js/Worker. path)
        bus (rx/subject)
        wrk (Worker. ins bus)]
    (.addEventListener ins "message"
                       (fn [event]
                         (let [data (.-data event)
                               data (t/decode data)]
                           (if (:error data)
                             (on-error (:error data))
                             (rx/push! bus data)))))
    (.addEventListener ins "error"
                       (fn [error]
                         (on-error wrk (.-data error))))

    wrk))

(defn- handle-response
  [{:keys [payload error] :as response}]
  (if-let [{:keys [data message]} error]
    (throw (ex-info message data))
    payload))


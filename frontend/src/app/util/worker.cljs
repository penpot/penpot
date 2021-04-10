;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.worker
  "A lightweight layer on top of webworkers api."
  (:require
   [beicon.core :as rx]
   [app.common.uuid :as uuid]
   [app.util.transit :as t]))

(declare handle-response)
(defrecord Worker [instance stream])

(defn- send-message! [worker {sender-id :sender-id :as message}]
  (let [data (t/encode message)
        instance (:instance worker)]
    (.postMessage instance data)
    (->> (:stream worker)
         (rx/filter #(= (:reply-to %) sender-id))
         (rx/take 1)
         (rx/map handle-response))))

(defn ask!
  [worker message]
  (send-message!
   worker
   {:sender-id (uuid/next)
    :payload message}))

(defn ask-buffered!
  [worker message]
  (send-message!
   worker
   {:sender-id (uuid/next)
    :payload message
    :buffer? true}))

(defn init
  "Return a initialized webworker instance."
  [path on-error]
  (let [instance (js/Worker. path)
        bus     (rx/subject)
        worker  (Worker. instance bus)

        handle-message
        (fn [event]
          (let [data (.-data event)
                data (t/decode data)]
            (if (:error data)
              (on-error (:error data))
              (rx/push! bus data))))

        handle-error
        (fn [error]
          (on-error worker (.-data error)))]
    
    (.addEventListener instance "message" handle-message)
    (.addEventListener instance "error" handle-error)

    worker))

(defn- handle-response
  [{:keys [payload error dropped] :as response}]
  (when-not dropped
    (if-let [{:keys [data message]} error]
      (throw (ex-info message data))
      payload)))


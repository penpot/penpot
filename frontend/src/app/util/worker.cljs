;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.worker
  "A lightweight layer on top of webworkers api."
  (:require
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [beicon.core :as rx]))

(declare handle-response)
(defrecord Worker [instance stream])

(defn- send-message!
  ([worker message]
   (send-message! worker message nil))

  ([worker {sender-id :sender-id :as message} {:keys [many?] :or {many? false}}]
   (let [take-messages
         (fn [ob]
           (if many?
             (rx/take-while #(not (:completed %)) ob)
             (rx/take 1 ob)))

         data (t/encode-str message)
         instance (:instance worker)]

     (if (some? instance)
       (do (.postMessage instance data)
           (->> (:stream worker)
                (rx/filter #(= (:reply-to %) sender-id))
                (take-messages)
                (rx/filter (complement :dropped))
                (rx/map handle-response)))
       (rx/empty)))))

(defn ask!
  [worker message]
  (send-message!
   worker
   {:sender-id (uuid/next)
    :payload message}))

(defn ask-many!
  [worker message]
  (send-message!
   worker
   {:sender-id (uuid/next)
    :payload message}
   {:many? true}))

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
        worker  (Worker. instance (rx/to-observable bus))

        handle-message
        (fn [event]
          (let [data (.-data event)
                data (t/decode-str data)]
            (if (:error data)
              (on-error (:error data))
              (rx/push! bus data))))

        handle-error
        (fn [error]
          (on-error worker (.-data error)))]

    (.addEventListener instance "message" handle-message)
    (.addEventListener instance "error" handle-error)

    (ask! worker
          {:cmd :configure
           :params
           {"penpotPublicURI" (obj/get global "penpotPublicURI")}})

    worker))

(defn- handle-response
  [{:keys [payload error]}]
  (if-let [{:keys [data message]} error]
    (throw (ex-info message data))
    payload))


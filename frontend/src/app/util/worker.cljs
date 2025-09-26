;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.worker
  "A lightweight layer on top of webworkers api."
  (:require
   [app.common.uuid :as uuid]
   [app.util.object :as obj]
   [app.worker.messages :as wm]
   [beicon.v2.core :as rx]))

(declare handle-response)
(defrecord Worker [instance stream])

(defn- send-message!
  ([worker message]
   (send-message! worker message nil))

  ([worker {sender-id :sender-id :as message} {:keys [many? ignore-response?] :or {many? false ignore-response? false}}]
   (let [take-messages
         (fn [ob]
           (if many?
             (rx/take-while #(not (:completed %)) ob)
             (rx/take 1 ob)))

         transfer (:transfer message)
         data (cond-> (wm/encode (dissoc message :transfer))
                (some? transfer)
                (obj/set! "transfer" transfer))
         instance (:instance worker)]

     (if (some? instance)
       (do (.postMessage instance data transfer)
           (if (not ignore-response?)
             (->> (:stream worker)
                  (rx/filter #(= (:reply-to %) sender-id))
                  (take-messages)
                  (rx/filter (complement :dropped))
                  (rx/map handle-response))
             (rx/empty)))
       (rx/empty)))))

(defn ask!
  ([worker message]
   (ask! worker message nil))
  ([worker message transfer]
   (send-message!
    worker
    {:sender-id (uuid/next)
     :payload message
     :transfer transfer})))

(defn emit!
  ([worker message]
   (emit! worker message nil))
  ([worker message transfer]
   (send-message!
    worker
    {:sender-id (uuid/next)
     :payload message
     :transfer transfer}
    {:ignore-response? true})))

(defn ask-many!
  ([worker message]
   (ask-many! worker message nil))
  ([worker message transfer]
   (send-message!
    worker
    {:sender-id (uuid/next)
     :payload message
     :transfer transfer}
    {:many? true})))

(defn ask-buffered!
  ([worker message]
   (ask-buffered! worker message nil))
  ([worker message transfer]
   (send-message!
    worker
    {:sender-id (uuid/next)
     :payload message
     :buffer? true
     :transfer transfer})))

(defn init
  "Return a initialized webworker instance."
  [path on-error]
  (let [instance (js/Worker. path)
        bus     (rx/subject)
        worker  (Worker. instance (rx/to-observable bus))

        handle-message
        (fn [event]
          (let [message (wm/decode (.-data event))]
            (if (:error message)
              (on-error (:error message))
              (rx/push! bus message))))

        handle-error
        (fn [error]
          (on-error worker (.-data error)))]

    (.addEventListener instance "message" handle-message)
    (.addEventListener instance "error" handle-error)

    worker))

(defn- handle-response
  [{:keys [payload error]}]
  (if-let [{:keys [data message]} error]
    (throw (ex-info message data))
    payload))


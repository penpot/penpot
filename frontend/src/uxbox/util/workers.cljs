;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.workers
  "A lightweight layer on top of Web Workers API."
  (:require [beicon.core :as rx]
            [uxbox.common.uuid :as uuid]
            [uxbox.util.transit :as t]))

;; --- Implementation

(defprotocol IWorker
  (-ask [_ msg] "Send and receive message as rx stream.")
  (-send [_ msg] "Send message and forget."))

(deftype WebWorker [stream wrk]
  IWorker
  (-ask [this message]
    (let [sender (uuid/next)
          data (assoc message :sender sender)
          data (t/encode data)]
      (.postMessage wrk data)
      (->> stream
           (rx/filter #(= (:reply-to %) sender))
           (rx/take 1))))

  (-send [this message]
    (let [sender (uuid/next)
          data (assoc message :sender sender)
          data (t/encode data)]
      (.postMessage wrk data)
      (->> stream
           (rx/filter #(= (:reply-to %) sender))))))

;; --- Public Api

(defn init
  "Return a initialized webworker instance."
  [path]
  (let [wrk (js/Worker. path)
        bus (rx/subject)]
    (.addEventListener wrk "message"
                       (fn [event]
                         (let [data (.-data event)
                               data (t/decode data)]
                           (rx/push! bus data))))
    (.addEventListener wrk "error"
                       (fn [event]
                         (rx/error! bus event)))

    (WebWorker. (rx/map identity bus) wrk)))

(defn ask!
  [wrk message]
  (-ask wrk message))

(defn send!
  [wrk message]
  (-send wrk message))


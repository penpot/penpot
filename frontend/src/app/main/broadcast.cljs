;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.broadcast
  "BroadcastChannel API."
  (:require
   [app.common.transit :as t]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defrecord BroadcastMessage [id type data]
  cljs.core/IDeref
  (-deref [_] data))

(def ^:const default-topic "penpot")

;; The main broadcast channel instance, used for emit data
(defonce default-channel
  (js/BroadcastChannel. default-topic))

(defonce stream
  (->> (rx/create (fn [subs]
                    (let [chan (js/BroadcastChannel. default-topic)]
                      (unchecked-set chan "onmessage" #(rx/push! subs (unchecked-get % "data")))
                      (fn [] (.close ^js chan)))))
       (rx/map t/decode-str)
       (rx/map map->BroadcastMessage)
       (rx/share)))

(defn emit!
  ([type data]
   (.postMessage ^js default-channel (t/encode-str {:id nil :type type :data data}))
   nil)
  ([id type data]
   (.postMessage ^js default-channel (t/encode-str {:id id :type type :data data}))
   nil))

(defn type?
  ([type]
   (fn [obj] (= (:type obj) type)))
  ([obj type]
   (= (:type obj) type)))

(defn event
  [type data]
  (ptk/reify ::event
    ptk/EffectEvent
    (effect [_ _ _]
      (emit! type data))))

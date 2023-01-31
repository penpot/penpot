;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.broadcast
  "BroadcastChannel API."
  (:require
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defrecord BroadcastMessage [id type data]
  cljs.core/IDeref
  (-deref [_] data))

(def ^:const default-topic "penpot")

;; The main broadcast channel instance, used for emit data
;; If used as a library may be we can't access js/BroadcastChannel.
;; and even if it exists we can receive an exception like:
;; Failed to construct 'BroadcastChannel': Can't create BroadcastChannel in an opaque origin
(defonce default-channel
  (when (exists? js/BroadcastChannel)
    (ex/ignoring (js/BroadcastChannel. default-topic))))

(defonce stream
  (if (exists? js/BroadcastChannel)
    (->> (rx/create (fn [subs]
                      (let [chan (js/BroadcastChannel. default-topic)]
                        (unchecked-set chan "onmessage" #(rx/push! subs (unchecked-get % "data")))
                        (fn [] (.close ^js chan)))))
         (rx/map t/decode-str)
         (rx/map map->BroadcastMessage)
         (rx/share))
    (rx/subject)))

(defn emit!
  ([type data]
   (when default-channel
     (.postMessage ^js default-channel (t/encode-str {:id nil :type type :data data}))
     nil))
  ([id type data]
   (when default-channel
     (.postMessage ^js default-channel (t/encode-str {:id id :type type :data data}))
     nil)))

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

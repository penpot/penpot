;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.store
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.common.uuid :as uuid]
            [uxbox.util.storage :refer [storage]]))

;; TODO: move outside uxbox.main

(enable-console-print!)

(def ^:dynamic *on-error* identity)

(defonce state (atom {}))
(defonce loader (atom false))
(defonce store (ptk/store {:on-error #(*on-error* %)}))
(defonce stream (ptk/input-stream store))

(defn- repr-event
  [event]
  (cond
    (satisfies? ptk/Event event)
    (str "typ: " (pr-str (ptk/type event)))

    (and (fn? event)
         (pos? (count (.-name event))))
    (str "fn: " (demunge (.-name event)))

    :else
    (str "unk: " (pr-str event))))

(defonce ^:dynamic *debug* (atom false))

(when *assert*
  (defonce debug-subscription
    (as-> stream $
      (rx/filter ptk/event? $)
      (rx/filter (fn [s] (deref *debug*)) $)
      (rx/subscribe $ (fn [event]
                        (println "[stream]: " (repr-event event)))))))
(def auth-ref
  (-> (l/key :auth)
      (l/derive state)))

(defn emit!
  ([] nil)
  ([event]
   (ptk/emit! store event)
   nil)
  ([event & events]
   (apply ptk/emit! store (cons event events))
   nil))

(def initial-state
  {:session-id (uuid/next)
   :profile (:profile storage)})

(defn init
  "Initialize the state materialization."
  ([] (init {}))
  ([props]
   (emit! #(merge % initial-state props))
   (rx/to-atom store state)))

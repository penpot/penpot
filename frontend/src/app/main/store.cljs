;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.store
  (:require
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]
   [app.common.uuid :as uuid]
   [app.util.storage :refer [storage]]
   [app.util.debug :refer [debug? logjs]]))

(enable-console-print!)

(def ^:dynamic *on-error* identity)

(defonce state  (l/atom {}))
(defonce loader (l/atom false))
(defonce store  (ptk/store {:resolve ptk/resolve}))
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

(when *assert*
  (defonce debug-subscription
    (as-> stream $
      #_(rx/filter ptk/event? $)
      (rx/filter (fn [s] (debug? :events)) $)
      (rx/subscribe $ (fn [event]
                        (println "[stream]: " (repr-event event)))))))
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

(defn ^:export dump-state []
  (logjs "state" @state))

(defn ^:export dump-objects []
  (let [page-id (get @state :current-page-id)]
    (logjs "state" (get-in @state [:workspace-data :pages-index page-id :objects]))))

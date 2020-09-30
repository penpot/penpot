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
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.pages-helpers :as cph]
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

(defn emitf
  [& events]
  #(apply ptk/emit! store events))

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

(defn ^:export dump-tree []
  (let [page-id    (get @state :current-page-id)
        objects    (get-in @state [:workspace-data :pages-index page-id :objects])
        components (get-in @state [:workspace-data :components])
        libraries  (get-in @state [:workspace-libraries])
        root (d/seek #(nil? (:parent-id %)) (vals objects))]

    (letfn [(show-shape [shape-id level objects]
              (let [shape (get objects shape-id)]
                (println (str/pad (str (str/repeat "  " level)
                                       (:name shape))
                                  {:length 20
                                   :type :right})
                         (show-component shape objects))
                (when (:shapes shape)
                  (dorun (for [shape-id (:shapes shape)]
                           (show-shape shape-id (inc level) objects))))))

            (show-component [shape objects]
              (let [root-id           (cph/get-root-component (:id shape) objects)
                    root-shape        (when root-id (get objects root-id))
                    component-id      (when root-shape (:component-id root-shape))
                    component-file-id (when root-shape (:component-file root-shape))
                    component-file    (when component-file-id (get libraries component-file-id))
                    shape-ref         (:shape-ref shape)
                    component         (when component-id
                                        (if component-file
                                          (get-in component-file [:data :components component-id])
                                          (get components component-id)))
                    component-shape (when (and component shape-ref)
                                      (get-in component [:objects shape-ref]))]
                (if component-shape
                  (str/format " %s--> %s%s"
                       (if (:component-id shape) "#" "-")
                       (when component-file (str/format "<%s> " (:name component-file)))
                       (:name component-shape))
                  "")))]

      (println "[Workspace]")
      (show-shape (:id root) 0 objects)

      (dorun (for [component (vals components)]
               (do
                 (println)
                 (println (str/format "[%s]" (:name component)))
                 (show-shape (:id component) 0 (:objects component))))))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.modal
  (:refer-clojure :exclude [update])
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.store :as st]
   [cljs.core :as c]
   [potok.v2.core :as ptk]))

(defonce components (atom {}))

;; TODO: rename `:type` to `:name`

(defn- summarize-export-shapes
  "Builds a compact body for the `:export-shapes` modal event. We know the
  shape of these props here, so instead of dumping every export (each one
  carrying its full shape, which can be megabytes) we emit counters: total
  exports, distinct shapes and one count per export type."
  [props]
  (let [exports (:exports props)]
    (merge
     {:page        (:name props)
      :num-shapes  (count (distinct (map :object-id exports)))
      :num-exports (count exports)}
     (frequencies (map :type exports)))))

(defn show
  ([props]
   (show (uuid/next) (:type props) props))
  ([type props]
   (show (uuid/next) type props))
  ([id type props]
   (ptk/reify ::show-modal
     ev/Event
     (-data [_]
       (let [origin (:origin props)
             data   (if (= type :export-shapes)
                      (assoc (summarize-export-shapes props) :name type)
                      (-> props
                          (dissoc :type)
                          (assoc :name type)))]
         ;; The origin is event metadata, not a prop: sending it as
         ;; ::ev/origin makes `make-proto-event` put it on the event context
         ;; (`:event-origin`); as a plain `:origin` it only stayed as a prop.
         (cond-> (dissoc data :origin)
           (some? origin) (assoc ::ev/origin origin))))

     ptk/UpdateEvent
     (update [_ state]
       (assoc state ::modal {:id id
                             :type type
                             :props props
                             :allow-click-outside false})))))

(defn update-props
  ([_type props]
   (ptk/reify ::update-modal-props
     ptk/UpdateEvent
     (update [_ state]
       (cond-> state
         (::modal state)
         (update-in [::modal :props] merge props))))))

(defn hide
  []
  (ptk/reify ::hide-modal
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::modal))))

(defn update
  [options]
  (ptk/reify ::update-modal
    ptk/UpdateEvent
    (update [_ state]
      (cond-> state
        (::modal state)
        (c/update ::modal merge options)))))

(defn show!
  ([props] (st/emit! (show props)))
  ([type props] (st/emit! (show type props))))

(defn update-props!
  [type props]
  (st/emit! (update-props type props)))

(defn allow-click-outside!
  []
  (st/emit! (update {:allow-click-outside true})))

(defn disallow-click-outside!
  []
  (st/emit! (update {:allow-click-outside false})))

(defn hide!
  []
  (st/emit! (hide)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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

(defn show
  ([props]
   (show (uuid/next) (:type props) props))
  ([type props]
   (show (uuid/next) type props))
  ([id type props]
   (ptk/reify ::show-modal
     ev/Event
     (-data [_]
       (-> props
           (dissoc :type)
           (assoc :name type)))

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

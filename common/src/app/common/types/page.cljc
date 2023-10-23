;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page
  (:require
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.types.color :as-alias ctc]
   [app.common.types.grid :as ctg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/def! ::flow
  [:map {:title "PageFlow"}
   [:id ::sm/uuid]
   [:name :string]
   [:starting-frame ::sm/uuid]])

(def flow?
  (sm/pred-fn ::flow))

(sm/def! ::guide
  [:map {:title "PageGuide"}
   [:id ::sm/uuid]
   [:axis [::sm/one-of #{:x :y}]]
   [:position ::sm/safe-number]
   [:frame-id {:optional true} [:maybe ::sm/uuid]]])

(def guide?
  (sm/pred-fn ::guide))

(sm/def! ::page
  [:map {:title "FilePage"}
   [:id ::sm/uuid]
   [:name :string]
   [:objects
    [:map-of {:gen/max 5} ::sm/uuid ::cts/shape]]
   [:options
    [:map {:title "PageOptions"}
     [:background {:optional true} ::ctc/rgb-color]
     [:saved-grids {:optional true} ::ctg/saved-grids]
     [:flows {:optional true}
      [:vector {:gen/max 2} ::flow]]
     [:guides {:optional true}
      [:map-of {:gen/max 2} ::sm/uuid ::guide]]]]])

(def page?
  (sm/pred-fn ::page))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialization

(def root uuid/zero)

(def empty-page-data
  {:options {}
   :objects {root
             (cts/setup-shape {:id root
                               :type :frame
                               :parent-id root
                               :frame-id root
                               :name "Root Frame"})}})

(defn make-empty-page
  [id name]
  (let [wrap-objects-fn cfeat/*wrap-with-objects-map-fn*
        wrap-pointer-fn cfeat/*wrap-with-pointer-map-fn*]
    (-> empty-page-data
        (assoc :id id)
        (assoc :name name)
        (update :objects wrap-objects-fn)
        (wrap-pointer-fn))))

;; --- Helpers for flow

(defn rename-flow
  [flow name]
  (assoc flow :name name))

(defn add-flow
  [flows flow]
  (conj (or flows []) flow))

(defn remove-flow
  [flows flow-id]
  (d/removev #(= (:id %) flow-id) flows))

(defn update-flow
  [flows flow-id update-fn]
  (let [index (d/index-of-pred flows #(= (:id %) flow-id))]
    (update flows index update-fn)))

(defn get-frame-flow
  [flows frame-id]
  (d/seek #(= (:starting-frame %) frame-id) flows))

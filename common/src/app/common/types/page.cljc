;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page
  (:require
   [app.common.data :as d]
   [app.common.files.features :as ffeat]
   [app.common.spec :as us]
   [app.common.types.page.flow :as ctpf]
   [app.common.types.page.grid :as ctpg]
   [app.common.types.page.guide :as ctpu]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.spec.alpha :as s]))

;; --- Background color

(s/def ::background ::us/rgb-color-str)

;; --- Page options

(s/def ::options
  (s/keys :opt-un [::background
                   ::ctpg/saved-grids
                   ::ctpf/flows
                   ::ctpu/guides]))

;; --- Page

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::objects (s/map-of uuid? ::cts/shape))

(s/def ::page
  (s/keys :req-un [::id ::name ::objects ::options]))

;; --- Initialization

(def root uuid/zero)

(def empty-page-data
  {:options {}
   :objects {root
             {:id root
              :type :frame
              :name "Root Frame"}}})

(defn make-empty-page
  [id name]
  (let [wrap-objects-fn ffeat/*wrap-with-objects-map-fn*
        wrap-pointer-fn ffeat/*wrap-with-pointer-map-fn*]
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




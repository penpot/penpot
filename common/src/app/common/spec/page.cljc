;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.page
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.spec.shape :as shape]
   [clojure.spec.alpha :as s]))

;; --- Grid options

(s/def :internal.grid.color/color string?)
(s/def :internal.grid.color/opacity ::us/safe-number)

(s/def :internal.grid/size (s/nilable ::us/safe-integer))
(s/def :internal.grid/item-length (s/nilable ::us/safe-number))

(s/def :internal.grid/color (s/keys :req-un [:internal.grid.color/color
                                             :internal.grid.color/opacity]))
(s/def :internal.grid/type #{:stretch :left :center :right})
(s/def :internal.grid/gutter (s/nilable ::us/safe-integer))
(s/def :internal.grid/margin (s/nilable ::us/safe-integer))

(s/def :internal.grid/square
  (s/keys :req-un [:internal.grid/size
                   :internal.grid/color]))

(s/def :internal.grid/column
  (s/keys :req-un [:internal.grid/color]
          :opt-un [:internal.grid/size
                   :internal.grid/type
                   :internal.grid/item-length
                   :internal.grid/margin
                   :internal.grid/gutter]))

(s/def :internal.grid/row :internal.grid/column)

(s/def ::saved-grids
  (s/keys :opt-un [:internal.grid/square
                   :internal.grid/row
                   :internal.grid/column]))

;; --- Background options

(s/def ::background string?)

;; --- Flow options

(s/def :internal.flow/id uuid?)
(s/def :internal.flow/name string?)
(s/def :internal.flow/starting-frame uuid?)

(s/def ::flow
  (s/keys :req-un [:internal.flow/id
                   :internal.flow/name
                   :internal.flow/starting-frame]))

(s/def ::flows
  (s/coll-of ::flow :kind vector?))

;; --- Guides

(s/def :internal.guides/id uuid?)
(s/def :internal.guides/axis #{:x :y})
(s/def :internal.guides/position ::us/safe-number)
(s/def :internal.guides/frame-id (s/nilable uuid?))

(s/def ::guide
  (s/keys :req-un [:internal.guides/id
                   :internal.guides/axis
                   :internal.guides/position]
          :opt-un [:internal.guides/frame-id]))

(s/def ::guides
  (s/map-of uuid? ::guide))

;; --- Page Options

(s/def ::options
  (s/keys :opt-un [::background
                   ::saved-grids
                   ::flows
                   ::guides]))

;; --- Page

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::objects (s/map-of uuid? ::shape/shape))

(s/def ::page
  (s/keys :req-un [::id ::name ::objects ::options]))

(s/def ::type #{:page :component})
(s/def ::path (s/nilable string?))
(s/def ::container
  (s/keys :req-un [::id ::name ::objects]
          :opt-un [::type ::path]))

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




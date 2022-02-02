;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.page-options
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; --- Grid options

(s/def :artboard-grid.color/color ::us/string)
(s/def :artboard-grid.color/opacity ::us/safe-number)

(s/def :artboard-grid/size (s/nilable ::us/safe-integer))
(s/def :artboard-grid/item-length (s/nilable ::us/safe-number))

(s/def :artboard-grid/color (s/keys :req-un [:artboard-grid.color/color
                                             :artboard-grid.color/opacity]))
(s/def :artboard-grid/type #{:stretch :left :center :right})
(s/def :artboard-grid/gutter (s/nilable ::us/safe-integer))
(s/def :artboard-grid/margin (s/nilable ::us/safe-integer))

(s/def :artboard-grid/square
  (s/keys :req-un [:artboard-grid/size
                   :artboard-grid/color]))

(s/def :artboard-grid/column
  (s/keys :req-un [:artboard-grid/color]
          :opt-un [:artboard-grid/size
                   :artboard-grid/type
                   :artboard-grid/item-length
                   :artboard-grid/margin
                   :artboard-grid/gutter]))

(s/def :artboard-grid/row :artboard-grid/column)

(s/def ::saved-grids
  (s/keys :opt-un [:artboard-grid/square
                   :artboard-grid/row
                   :artboard-grid/column]))

;; --- Background options

(s/def ::background string?)

;; --- Flow options

(s/def :interactions-flow/id ::us/uuid)
(s/def :interactions-flow/name ::us/string)
(s/def :interactions-flow/starting-frame ::us/uuid)

(s/def ::flow
  (s/keys :req-un [:interactions-flow/id
                   :interactions-flow/name
                   :interactions-flow/starting-frame]))

(s/def ::flows
  (s/coll-of ::flow :kind vector?))

;; --- Options

(s/def ::options
  (s/keys :opt-un [::background
                   ::saved-grids
                   ::flows]))

;; --- Helpers for flow

(defn rename-flow
  [flow name]
  (assoc flow :name name))

;; --- Helpers for flows

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


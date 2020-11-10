;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.proportions
  (:require
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.data :as d]))

;; --- Proportions

(declare assign-proportions-path)
(declare assign-proportions-rect)

(defn assign-proportions
  [{:keys [type] :as shape}]
  (case type
    :path (assign-proportions-path shape)
    (assign-proportions-rect shape)))

(defn- assign-proportions-rect
  [{:keys [width height] :as shape}]
  (assoc shape :proportion (/ width height)))


;; --- Setup Proportions

(declare setup-proportions-const)
(declare setup-proportions-image)

(defn setup-proportions
  [shape]
  (case (:type shape)
    :icon (setup-proportions-image shape)
    :image (setup-proportions-image shape)
    :text shape
    (setup-proportions-const shape)))

(defn setup-proportions-image
  [{:keys [metadata] :as shape}]
  (let [{:keys [width height]} metadata]
    (assoc shape
           :proportion (/ width height)
           :proportion-lock false)))

(defn setup-proportions-const
  [shape]
  (assoc shape
         :proportion 1
         :proportion-lock false))

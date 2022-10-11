;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.proportions)

;; --- Proportions

(defn assign-proportions
  [shape]
  (let [{:keys [width height]} (:selrect shape)]
    (assoc shape :proportion (/ width height))))

;; --- Setup Proportions

(defn setup-proportions-image
  [{:keys [metadata] :as shape}]
  (let [{:keys [width height]} metadata]
    (assoc shape
           :proportion (/ width height)
           :proportion-lock true)))

(defn setup-proportions-svg
  [{:keys [width height] :as shape}]
  (assoc shape
         :proportion (/ width height)
         :proportion-lock true))

(defn setup-proportions-const
  [shape]
  (assoc shape
         :proportion 1
         :proportion-lock false))

(defn setup-proportions
  [shape]
  (case (:type shape)
    :svg-raw (setup-proportions-svg shape)
    :image (setup-proportions-image shape)
    :text shape
    (setup-proportions-const shape)))

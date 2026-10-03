;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.stroke
  (:require
   [app.common.data :as d]
   [app.common.types.color :as clr]
   [app.common.types.token :as ctt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def stroke-caps-line #{:round :square})
(def stroke-caps-marker #{:line-arrow :triangle-arrow :square-marker :circle-marker :diamond-marker})

(def default-stroke
  {:stroke-alignment :inner
   :stroke-style :solid
   :stroke-color clr/black
   :stroke-opacity 1
   :stroke-width 1})

(defn materialize-stroke-side-widths
  "Given a stroke (or nil) and the set of edited side keys, returns the stroke
  with the four per-side width keys concretized: edited sides take `value`,
  the rest keep their current per-side width, falling back to `:stroke-width`
  (0 when `stroke` is nil). `:stroke-width` mirrors the top side so legacy
  consumers that only read it see the top value."
  [stroke edited-keys value]
  (let [base-width (or (:stroke-width stroke) 0)
        current    (or stroke default-stroke)
        side-attrs (reduce
                    (fn [acc side-key]
                      (assoc acc side-key
                             (if (contains? edited-keys side-key)
                               value
                               (d/nilv (get current side-key) base-width))))
                    {}
                    ctt/per-side-stroke-width-keys)]
    (merge current side-attrs {:stroke-width (get side-attrs :stroke-width-top)})))

(defn set-width-to-all-sides
  "Set `value` as the width of every side of `stroke` (uniform width), keeping
  `:stroke-width` mirroring the top side. Mirrors
  `app.common.types.shape.radius/set-radius-to-all-corners`."
  [stroke value]
  (materialize-stroke-side-widths stroke ctt/per-side-stroke-width-keys value))

(defn set-width-to-single-side
  "Set `value` as the width of the `attr` side of `stroke`, leaving the other
  sides untouched. `:stroke-width` (the top-side alias) is updated too when
  `attr` is the top side. Mirrors
  `app.common.types.shape.radius/set-radius-to-single-corner`."
  [stroke attr value]
  (let [stroke (or stroke default-stroke)]
    (cond-> (assoc stroke attr value)
      (= attr :stroke-width-top) (assoc :stroke-width value))))

(defn side-width
  "Effective width of the `attr` side of `stroke`, falling back to the uniform
  `:stroke-width` alias when the side has no value of its own."
  [stroke attr]
  (d/nilv (get stroke attr) (:stroke-width stroke)))

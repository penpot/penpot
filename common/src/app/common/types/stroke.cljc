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
  "Sets edited widths and fills missing sides from `:stroke-width` (default 0).
  Keeps `:stroke-width` equal to the top width."
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
  "Sets every side and `:stroke-width` to `value`."
  [stroke value]
  (materialize-stroke-side-widths stroke ctt/per-side-stroke-width-keys value))

(defn set-width-to-single-side
  "Sets one side and preserves the other widths.
  Keeps `:stroke-width` equal to the top width."
  [stroke attr value]
  (materialize-stroke-side-widths (or stroke default-stroke) #{attr} value))

(defn side-width
  "Returns the side width, falling back to `:stroke-width` when unset."
  [stroke attr]
  (d/nilv (get stroke attr) (:stroke-width stroke)))

(defn width-type
  "Returns simple when all effective side widths match, multiple otherwise."
  [stroke]
  (if (= (side-width stroke :stroke-width-top)
         (side-width stroke :stroke-width-right)
         (side-width stroke :stroke-width-bottom)
         (side-width stroke :stroke-width-left))
    :simple
    :multiple))

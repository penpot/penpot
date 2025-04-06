;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.shadow
  (:require
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.types.color :as ctc]))

(def styles #{:drop-shadow :inner-shadow})

(def schema:shadow
  [:map {:title "Shadow"}
   [:id [:maybe ::sm/uuid]]
   [:style
    [:and {:gen/gen (sg/elements styles)}
     :keyword
     [::sm/one-of styles]]]
   [:offset-x ::sm/safe-number]
   [:offset-y ::sm/safe-number]
   [:blur ::sm/safe-number]
   [:spread ::sm/safe-number]
   [:hidden :boolean]
   [:color ::ctc/color]])

(sm/register! ::shadow schema:shadow)

(def check-shadow
  (sm/check-fn schema:shadow))

(defn update-shadow-opacity [shape value]
  (let [updated-shadows (mapv
                         (fn [shadow]
                           (let [shadow-color (:color shadow)
                                 updated-color (assoc shadow-color :opacity value)]
                             (assoc shadow :color updated-color)))
                         (:shadow shape))]
    ;; Update all shadows in the shape
    (assoc shape :shadow updated-shadows)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.util.dom :as dom]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 510
        ;; Checks for overflow outside the viewport height
        overflow-fix (max 0 (+ y (- 50) h (- vh)))

        x-pos 325]
    (cond
      (or (nil? x) (nil? y)) {:left "auto" :right "16rem" :top "4rem"}
      (= position :left) {:left (str (- x x-pos) "px")
                          :top (str (- y 50 overflow-fix) "px")}
      :else {:left (str (+ x 80) "px")
             :top (str (- y 70 overflow-fix) "px")})))

(def viewport
  (l/derived :vport refs/workspace-local))

(defn fields->map [fields]
  (->> (map (fn [{:keys [key] :as field}]
              [key (:value field)]) fields)
       (into {})))

(mf/defc tokens-properties-form
  {::mf/wrap-props false}
  [{:keys [token-type x y position fields token]}]
  (let [vport (mf/deref viewport)
        style (calculate-position vport position x y)

        name (mf/use-var (or (:name token) ""))
        on-update-name #(reset! name (dom/get-target-val %))

        token-value (mf/use-var (or (:value token) ""))

        description (mf/use-var (or (:description token) ""))
        on-update-description #(reset! description (dom/get-target-val %))

        initial-fields (mapv (fn [field]
                               (assoc field :value (or (:value token) "")))
                             fields)
        state (mf/use-state initial-fields)

        on-update-state-field (fn [idx e]
                                (->> (dom/get-target-val e)
                                     (assoc-in @state [idx :value])
                                     (reset! state)))

        on-submit (fn [e]
                    (dom/prevent-default e)
                    (let [token-value (-> (fields->map @state)
                                          (first)
                                          (val))
                          token (cond-> {:name @name
                                         :type (or (:type token) token-type)
                                         :value token-value}
                                  @description (assoc :description @description)
                                  (:id token) (assoc :id (:id token)))]
                      (st/emit! (dt/add-token token))
                      (modal/hide!)))]

    [:form
     {:class (stl/css :shadow)
      :style (clj->js style)
      :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:& tokens.common/labeled-input {:label "Name"
                                       :input-props {:defaultValue @name
                                                     :autoFocus true
                                                     :onChange on-update-name}}]
      (for [[idx {:keys [type label]}] (d/enumerate @state)]
        [:* {:key (str "form-field-" idx)}
         (case type
           :box-shadow [:p "TODO BOX SHADOW"]
           [:& tokens.common/labeled-input {:label label
                                            :input-props {:defaultValue @token-value
                                                          :onChange #(on-update-state-field idx %)}}])])
      [:& tokens.common/labeled-input {:label "Description"
                                       :input-props {:defaultValue @description
                                                     :onChange #(on-update-description %)}}]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"}
        "Save"]]]]))

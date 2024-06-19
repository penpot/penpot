;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modal
  (:require-macros [app.main.style :as stl])
  (:require
   ["lodash.debounce" :as debounce]
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.util.dom :as dom]
   [okulary.core :as l]
   [promesa.core :as p]
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

(defn fields-to-token
  "Converts field to token value that will be stored and processed.
  Handles a simple token token type for now."
  [token-type fields]
  (case token-type
    (first fields)))

;; https://dev.to/haseeb1009/the-useevent-hook-1c8l
(defn use-event-callback
  [f]
  (let [ref (mf/use-ref)]
    (mf/use-layout-effect
     (fn []
       (reset! ref f)
       js/undefined))
    (mf/use-callback (fn [& args] (some-> @ref (apply args))) [])))

(defn use-promise-debounce [fn+ on-success on-err]
  (let [debounce-promise (mf/use-ref nil)
        callback-fn (fn []
                      (let [id (random-uuid)]
                        (mf/set-ref-val! debounce-promise id)
                        (-> (fn+)
                            (p/then (fn [result]
                                      (js/console.log "@debounce-promise id" @debounce-promise id)
                                      (when (= @debounce-promise id)
                                        (js/console.log "update" result)
                                        (on-success result))))
                            (p/catch on-err))))
        debounced-fn (debounce callback-fn)]
    debounced-fn))

(mf/defc tokens-properties-form
  {::mf/wrap-props false}
  [{:keys [token-type x y position fields token] :as args}]
  (let [tokens (sd/use-resolved-workspace-tokens {:debug? true})
        used-token-names (mf/use-memo
                          (mf/deps tokens)
                          (fn []
                            (-> (into #{} (map (fn [[_ {:keys [name]}]] name) tokens))
                                 ;; Allow setting token to already used name
                                (disj (:name token)))))
        vport (mf/deref viewport)
        style (calculate-position vport position x y)

        resolved-value (mf/use-state (get-in tokens [(:id token) :value]))

        name (mf/use-var (or (:name token) ""))
        on-update-name #(reset! name (dom/get-target-val %))

        token-value (mf/use-var (or (:value token) ""))

        description (mf/use-var (or (:description token) ""))
        on-update-description #(reset! description (dom/get-target-val %))

        initial-fields (mapv (fn [field]
                               (assoc field :value (or (:value token) "")))
                             fields)
        state (mf/use-state initial-fields)

        debounced-update (use-promise-debounce sd/resolve-tokens+
                                               (fn [tokens]
                                                 (let [value (get-in tokens [(:id token) :value])]
                                                   (reset! resolved-value value)))
                                               #(reset! resolved-value nil))

        on-update-state-field (fn [idx e]
                                (let [value (dom/get-target-val e)]
                                  (debounced-update)
                                  (swap! state assoc-in [idx :value] value)))

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
                                       :input-props {:default-value @name
                                                     :auto-focus true
                                                     :on-change on-update-name}}]
      (for [[idx {:keys [label type]}] (d/enumerate @state)]
        [:* {:key (str "form-field-" idx)}
         (case type
           :box-shadow [:p "TODO BOX SHADOW"]
           [:& tokens.common/labeled-input {:label "Value"
                                            :input-props {:default-value @token-value
                                                          :on-change #(on-update-state-field idx %)}}])])
      (when (and @resolved-value
                 (not= @resolved-value (:value (first @state))))
        [:div {:class (stl/css :resolved-value)}
         [:p @resolved-value]])
      [:& tokens.common/labeled-input {:label "Description"
                                       :input-props {:default-value @description
                                                     :on-change #(on-update-description %)}}]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"}
        "Save"]]]]))

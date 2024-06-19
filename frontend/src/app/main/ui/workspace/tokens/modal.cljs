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
   [clojure.set :as set]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
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

(defn use-viewport-position-style [x y position]
  (let [vport (-> (l/derived :vport refs/workspace-local)
                  (mf/deref))]
    (-> (calculate-position vport position x y)
        (clj->js))))

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

(defn token-name-schema
  "Generate a dynamic schema validation to check if a token name already exists.
  `existing-token-names` should be a set of strings."
  [existing-token-names]
  (let [non-existing-token-schema
        (m/-simple-schema
         {:type :token/name-exists
          :pred #(not (get existing-token-names %))
          :type-properties {:error/fn #(str (:value %) " is an already existing token name")
                            :existing-token-names existing-token-names}})]
    (m/schema
     [:and
      [:string {:min 1 :max 255}]
      non-existing-token-schema])))

(comment
  (-> (m/explain (token-name-schema #{"foo"}) nil)
      (me/humanize))
  nil)

(mf/defc tokens-properties-form
  {::mf/wrap-props false}
  [{:keys [x y position token] :as _args}]
  (let [wrapper-style (use-viewport-position-style x y position)

        ;; Tokens
        tokens (sd/use-resolved-workspace-tokens)
        existing-token-names (mf/use-memo
                              (mf/deps tokens)
                              (fn []
                                (-> (into #{} (map (fn [[_ {:keys [name]}]] name) tokens))
                                     ;; Allow setting token to already used name
                                    (disj (:name token)))))

        ;; State
        state* (mf/use-state (merge {:name ""
                                     :value ""
                                     :description ""}
                                    token))
        state @state*

        ;; Name
        finalize-name str/trim
        name-schema (mf/use-memo
                     (mf/deps existing-token-names)
                     (fn []
                       (token-name-schema existing-token-names)))
        on-update-name (fn [e]
                         (let [value (dom/get-target-val e)
                               errors (->> (finalize-name value)
                                           (m/explain name-schema))]
                           (swap! state* merge {:name value
                                                :errors/name errors})))
        disabled? (or
                   (empty? (finalize-name (:name state)))
                   (:errors/name state))]

        ;; on-update-name (fn [e]
        ;;                  (let [{:keys [errors] :as state} (mf/deref state*)
        ;;                        value (-> (dom/get-target-val e)
        ;;                                  (str/trim))]
        ;;                    (cond-> @state*
        ;;                      ;; Remove existing name errors
        ;;                      :always (update :errors set/difference #{:empty})
        ;;                      (str/empty?) (conj))
        ;;                    (swap! state* assoc :name (dom/get-target-val e))))
        ;; on-update-description #(swap! state* assoc :description (dom/get-target-val %))
        ;; on-update-field (fn [idx e]
        ;;                   (let [value (dom/get-target-val e)]
        ;;                     (swap! state* assoc-in [idx :value] value)))


        ;; on-submit (fn [e]
        ;;             (dom/prevent-default e)
        ;;             (let [token-value (-> (fields->map state)
        ;;                                   (first)
        ;;                                   (val))
        ;;                   token (cond-> {:name (:name state)
        ;;                                  :type (or (:type token) token-type)
        ;;                                  :value token-value
        ;;                                  :description (:description state)}
        ;;                           (:id token) (assoc :id (:id token)))]
        ;;               (st/emit! (dt/add-token token))
        ;;               (modal/hide!)))]
    [:form
     {:class (stl/css :shadow)
      :style wrapper-style
      #_#_:on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:div
       [:& tokens.common/labeled-input {:label "Name"
                                        :error? (:errors/name state)
                                        :input-props {:default-value (:name state)
                                                      :auto-focus true
                                                      :on-change on-update-name}}]
       (when-let [errors (:errors/name state)]
         [:p {:class (stl/css :error)} (me/humanize errors)])]
      #_(for [[idx {:keys [label type value]}] (d/enumerate (:fields state))]
          [:* {:key (str "form-field-" idx)}
           (case type
             :box-shadow [:p "TODO BOX SHADOW"]
             [:& tokens.common/labeled-input {:label "Value"
                                              :input-props {:default-value value
                                                            :on-change #(on-update-field idx %)}}])])
      ;; (when (and @resolved-value
      ;;            (not= @resolved-value (:value (first @state*))))
      ;;   [:div {:class (stl/css :resolved-value)}
      ;;    [:p @resolved-value]])
      #_[:& tokens.common/labeled-input {:label "Description"
                                         :input-props {:default-value (:description state)
                                                       :on-change #(on-update-description %)}}]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"
                 :disabled disabled?}
        "Save"]]]]))

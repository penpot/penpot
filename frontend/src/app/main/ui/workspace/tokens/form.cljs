;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
   [rumext.v2 :as mf]))

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

(mf/defc form
  {::mf/wrap-props false}
  [{:keys [token] :as _args}]
  (let [tokens (sd/use-resolved-workspace-tokens)
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
     {#_#_:on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:div
       [:& tokens.common/labeled-input {:label "Name"
                                        :error? (:errors/name state)
                                        :input-props {:default-value (:name state)
                                                      :auto-focus true
                                                      :on-change on-update-name}}]
       (when-let [errors (:errors/name state)]
         [:p {:class (stl/css :error)} (me/humanize errors)])]
      [:& tokens.common/labeled-input {:label "Value"
                                       :input-props {:default-value (:value state)
                                                     #_#_:on-change #(on-update-field idx %)}}]
      ;; (when (and @resolved-value
      ;;            (not= @resolved-value (:value (first @state*))))
      ;;   [:div {:class (stl/css :resolved-value)}
      ;;    [:p @resolved-value]])
      [:& tokens.common/labeled-input {:label "Description"
                                       :input-props {:default-value (:description state)
                                                     #_#_:on-change #(on-update-description %)}}]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"
                 :disabled disabled?}
        "Save"]]]]))

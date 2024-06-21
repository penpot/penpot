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

;; Schemas ---------------------------------------------------------------------

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

;; Helpers ---------------------------------------------------------------------

(defn finalize-name [name]
  (str/trim name))

(defn finalize-value [name]
  (str/trim name))

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
        _ (js/console.log "render")

        ;; Name
        name (mf/use-var (or (:name token) ""))
        name-errors (mf/use-state nil)
        name-schema (mf/use-memo
                     (mf/deps existing-token-names)
                     #(token-name-schema existing-token-names))
        on-update-name (fn [e]
                         (let [value (dom/get-target-val e)
                               errors (->> (finalize-name value)
                                           (m/explain name-schema))]
                           (reset! name value)
                           (reset! name-errors errors)))
        disabled? (or
                   @name-errors
                   (empty? (finalize-name (:name state))))

        ;; Value
        value (mf/use-var (or (:value token) ""))
        resolved-value (mf/use-state (get-in tokens [(:id token) :resolved-value]))
        set-resolve-value (mf/use-callback
                           (fn [token]
                             (when (:resolved-value token)
                               (reset! resolved-value (:resolved-value token)))))
        value-errors (mf/use-state nil)
        on-update-value (sd/use-debonced-resolve-callback tokens set-resolve-value)]

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
                                        :error? @name-errors
                                        :input-props {:default-value @name
                                                      :auto-focus true
                                                      :on-change on-update-name}}]
       (when @name-errors
         [:p {:class (stl/css :error)}
          (me/humanize @name-errors)])]
      [:& tokens.common/labeled-input {:label "Value"
                                       :input-props {:default-value @value
                                                     :on-change on-update-value}}]
      [:div {:class (stl/css :resolved-value)}
       [:p @resolved-value]]
      [:& tokens.common/labeled-input {:label "Description"
                                       :input-props {:default-value (:description state)
                                                     #_#_:on-change #(on-update-description %)}}]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"
                 :disabled disabled?}
        "Save"]]]]))

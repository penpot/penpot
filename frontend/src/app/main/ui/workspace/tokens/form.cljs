;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.form
  (:require-macros [app.main.style :as stl])
  (:require
   ["lodash.debounce" :as debounce]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
   [promesa.core :as p]
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

(def token-description-schema
  (m/schema
   [:string {:max 2048}]))

;; Helpers ---------------------------------------------------------------------

(defn finalize-name [name]
  (str/trim name))

(defn valid-name? [name]
  (seq (finalize-name (str name))))

(defn finalize-value [value]
  (-> (str value)
      (str/trim)))

(defn valid-value? [value]
  (seq (finalize-value value)))

;; Component -------------------------------------------------------------------

(defn use-debonced-resolve-callback
  [name-ref token tokens callback & {:keys [timeout] :or {timeout 160}}]
  (let [timeout-id-ref (mf/use-ref nil)
        debounced-resolver-callback
        (mf/use-callback
         (mf/deps token callback tokens)
         (fn [event]
           (let [input (dom/get-target-val event)
                 timeout-id (js/Symbol)
                 ;; Dont execute callback when the timout-id-ref is outdated because this function got called again
                 timeout-outdated-cb? #(not= (mf/ref-val timeout-id-ref) timeout-id)]
             (mf/set-ref-val! timeout-id-ref timeout-id)
             (js/setTimeout
              (fn []
                (when (not (timeout-outdated-cb?))
                  (let [token-references (sd/find-token-references input)
                        ;; When creating a new token we dont have a token name yet,
                        ;; so we use a temporary token name that hopefully doesn't clash with any of the users token names.
                        token-name (if (empty? @name-ref) "__TOKEN_STUDIO_SYSTEM.TEMP" @name-ref)
                        direct-self-reference? (get token-references token-name)
                        empty-input? (empty? (str/trim input))]
                    (cond
                      empty-input? (callback nil)
                      direct-self-reference? (callback :error/token-direct-self-reference)
                      :else
                      (let [token-id (or (:id token) (random-uuid))
                            new-tokens (update tokens token-id merge {:id token-id
                                                                      :value input
                                                                      :name token-name})]
                        (-> (sd/resolve-tokens+ new-tokens)
                            (p/finally
                              (fn [resolved-tokens _err]
                                (when-not (timeout-outdated-cb?)
                                  (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens token-id)]
                                    (cond
                                      resolved-value (callback resolved-token)
                                      (= #{:style-dictionary/missing-reference} errors) (callback :error/token-missing-reference)
                                      :else (callback :error/unknown-error))))))))))))

              timeout))))]
    debounced-resolver-callback))

(mf/defc form
  {::mf/wrap-props false}
  [{:keys [token token-type] :as _args}]
  (let [tokens (sd/use-resolved-workspace-tokens)
        existing-token-names (mf/use-memo
                              (mf/deps tokens)
                              (fn []
                                (-> (into #{} (map (fn [[_ {:keys [name]}]] name) tokens))
                                     ;; Allow setting token to already used name
                                    (disj (:name token)))))

        ;; Name
        name-ref (mf/use-var (:name token))
        name-errors (mf/use-state nil)
        validate-name (mf/use-callback
                       (mf/deps existing-token-names)
                       (fn [value]
                         (let [schema (token-name-schema existing-token-names)]
                           (m/explain schema (finalize-name value)))))
        on-update-name-debounced (mf/use-callback
                                  (debounce (fn [e]
                                              (let [value (dom/get-target-val e)
                                                    errors (validate-name value)]
                                                (reset! name-errors errors)))))
        on-update-name (mf/use-callback
                        (mf/deps on-update-name-debounced)
                        (fn [e]
                          (reset! name-ref (dom/get-target-val e))
                          (on-update-name-debounced e)))
        valid-name-field? (and
                           (not @name-errors)
                           (valid-name? @name-ref))

        ;; Value
        value-ref (mf/use-var (:value token))
        token-resolve-result (mf/use-state (get-in tokens [(:id token) :resolved-value]))
        set-resolve-value (mf/use-callback
                           (fn [token-or-err]
                             (let [v (cond
                                       (= token-or-err :error/token-direct-self-reference) :error/token-self-reference
                                       (= token-or-err :error/token-missing-reference) :error/token-missing-reference
                                       (:resolved-value token-or-err) (:resolved-value token-or-err))]
                               (reset! token-resolve-result v))))
        on-update-value-debounced (use-debonced-resolve-callback name-ref token tokens set-resolve-value)
        on-update-value (mf/use-callback
                         (mf/deps on-update-value-debounced)
                         (fn [e]
                           (reset! value-ref (dom/get-target-val e))
                           (on-update-value-debounced e)))
        value-error? (when (keyword? @token-resolve-result)
                       (= (namespace @token-resolve-result) "error"))
        valid-value-field? (and
                            (not value-error?)
                            (valid-value? @token-resolve-result))

        ;; Description
        description-ref (mf/use-var (:description token))
        description-errors (mf/use-state nil)
        on-update-description-debounced (mf/use-callback
                                         (debounce (fn [e]
                                                     (let [value (dom/get-target-val e)
                                                           errors (m/explain token-description-schema value)]
                                                       (reset! description-errors errors)))))
        on-update-description (mf/use-callback
                               (mf/deps on-update-description-debounced)
                               (fn [e]
                                 (reset! description-ref (dom/get-target-val e))
                                 (on-update-description-debounced e)))
        valid-description-field? (not @description-errors)

        ;; Form
        disabled? (or (not valid-name-field?)
                      (not valid-value-field?)
                      (not valid-description-field?))

        on-submit (mf/use-callback
                   (fn [e]
                     (dom/prevent-default e)
                     (let [name (finalize-name @name-ref)
                           ;; Validate form before submitting
                           ;; As the form might still be evaluating due to debounce and async form state
                           invalid-form? (or (:errors (validate-name name)))]
                       (when-not invalid-form?
                         (let [token (cond-> {:name (finalize-name @name-ref)
                                              :type (or (:type token) token-type)
                                              :value (finalize-value @value-ref)}
                                       @description-ref (assoc :description @description-ref)
                                       (:id token) (assoc :id (:id token)))]
                           (st/emit! (dt/add-token token))
                           (modal/hide!))))))]
    [:form
     {:on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:div
       [:& tokens.common/labeled-input {:label "Name"
                                        :error? @name-errors
                                        :input-props {:default-value @name-ref
                                                      :auto-focus true
                                                      :on-blur on-update-name
                                                      :on-change on-update-name}}]
       (when @name-errors
         [:p {:class (stl/css :error)}
          (me/humanize @name-errors)])]
      [:& tokens.common/labeled-input {:label "Value"
                                       :input-props {:default-value @value-ref
                                                     :on-blur on-update-value
                                                     :on-change on-update-value}}]
      [:div {:class (stl/css-case :resolved-value true
                                  :resolved-value-placeholder (nil? @token-resolve-result)
                                  :resolved-value-error value-error?)}
       (case @token-resolve-result
         :error/token-self-reference "Token has self reference"
         :error/token-missing-reference "Token has missing reference"
         :error/unknown-error ""
         nil "Enter token value"
         [:p @token-resolve-result])]
      [:div
       [:& tokens.common/labeled-input {:label "Description"
                                        :input-props {:default-value @description-ref
                                                      :on-change on-update-description}}]
       (when @description-errors
         [:p {:class (stl/css :error)}
          (me/humanize @description-errors)])]
      [:div {:class (stl/css :button-row)}
       [:button {:class (stl/css :button)
                 :type "submit"
                 :disabled disabled?}
        "Save"]]]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.form
  (:require-macros [app.main.style :as stl])
  (:require
   ["lodash.debounce" :as debounce]
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.workspace.colorpicker :refer [colorpicker]]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector]]
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.update :as wtu]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [malli.core :as m]
   [malli.error :as me]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; Schemas ---------------------------------------------------------------------

(def valid-token-name-regexp
  "Only allow letters and digits for token names.
  Also allow one `.` for a namespace separator.

  Caution: This will allow a trailing dot like `token-name.`,
  But we will trim that in the `finalize-name`,
  to not throw too many errors while the user is editing."
  #"([a-zA-Z0-9-]+\.?)*")

(def valid-token-name-schema
  (m/-simple-schema
   {:type :token/invalid-token-name
    :pred #(re-matches valid-token-name-regexp %)
    :type-properties {:error/fn #(str (:value %) " is not a valid token name.
Token names should only contain letters and digits separated by . characters.")}}))

(defn token-name-schema
  "Generate a dynamic schema validation to check if a token path derived from the name already exists at `tokens-tree`."
  [{:keys [token tokens-tree]}]
  (let [path-exists-schema
        (m/-simple-schema
         {:type :token/name-exists
          :pred #(not (wtt/token-name-path-exists? % tokens-tree))
          :type-properties {:error/fn #(str "A token already exists at the path: " (:value %))}})]
    (m/schema
     [:and
      [:string {:min 1 :max 255}]
      valid-token-name-schema
      path-exists-schema])))

(def token-description-schema
  (m/schema
   [:string {:max 2048}]))

;; Helpers ---------------------------------------------------------------------

(defn finalize-name [name]
  (-> (str/trim name)
      ;; Remove trailing dots
      (str/replace #"\.+$" "")))

(defn valid-name? [name]
  (seq (finalize-name (str name))))

(defn finalize-value [value]
  (-> (str value)
      (str/trim)))

(defn valid-value? [value]
  (seq (finalize-value value)))

(defn schema-validation->promise [validated]
  (if (:errors validated)
    (p/rejected validated)
    (p/resolved validated)))

;; Component -------------------------------------------------------------------

(defn validate-token-value+
  "Validates token value by resolving the value `input` using `StyleDictionary`.
  Returns a promise of either resolved tokens or rejects with an error state."
  [{:keys [input name-value token tokens]}]
  (let [empty-input? (empty? (str/trim input))
        ;; Check if the given value contains a reference that is the current token-name
        ;; When creating a new token we dont have a token name yet,
        ;; so we use a temporary token name that hopefully doesn't clash with any of the users token names.
        token-name (if (str/empty? name-value) "__TOKEN_STUDIO_SYSTEM.TEMP" name-value)
        token-references (wtt/find-token-references input)
        direct-self-reference? (get token-references token-name)]
    (cond
      empty-input? (p/rejected nil)
      direct-self-reference? (p/rejected :error/token-direct-self-reference)
      :else (let [token-id (or (:id token) (random-uuid))
                  new-tokens (update tokens token-name merge {:id token-id
                                                              :value input
                                                              :name token-name
                                                              :type (:type token)})]
              (-> (sd/resolve-tokens+ new-tokens {:names-map? true
                                                  :debug? true})
                  (p/then
                   (fn [resolved-tokens]
                     (js/console.log "resolved-tokens" resolved-tokens)
                     (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens token-name)]
                       (cond
                         resolved-value (p/resolved resolved-token)
                         (sd/missing-reference-error? errors) (p/rejected :error/token-missing-reference)
                         :else (p/rejected :error/unknown-error)))))
                  (p/catch js/console.log))))))

(defn use-debonced-resolve-callback
  "Resolves a token values using `StyleDictionary`.
  This function is debounced as the resolving might be an expensive calculation.
  Uses a custom debouncing logic, as the resolve function is async."
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
                  (-> (validate-token-value+ {:input input
                                              :name-value @name-ref
                                              :token token
                                              :tokens tokens})
                      (p/finally (fn [x err]
                                   (when-not (timeout-outdated-cb?)
                                     (callback (or err x))))))))
              timeout))))]
    debounced-resolver-callback))

(defonce form-token-cache-atom (atom nil))

(mf/defc ramp
  [{:keys [color on-change]}]
  (let [value (mf/use-state nil)]
    (js/console.log "@value" @value)
    [:& ramp-selector {:color @value
                       :on-start-drag js/console.log
                       :on-finish-drag js/console.log
                       :on-change #(reset! value (:hex %))}]))

(mf/defc form
  {::mf/wrap-props false}
  [{:keys [token token-type]}]
  (let [token (or token {:type token-type})
        color? (wtt/color-token? token)
        selected-set-tokens (mf/deref refs/workspace-selected-token-set-tokens)
        active-theme-tokens (mf/deref refs/workspace-active-theme-sets-tokens)
        resolved-tokens (sd/use-resolved-tokens active-theme-tokens {:names-map? true
                                                                     :cache-atom form-token-cache-atom})
        token-path (mf/use-memo
                    (mf/deps (:name token))
                    #(wtt/token-name->path (:name token)))
        selected-set-tokens-tree (mf/use-memo
                                  (mf/deps token-path selected-set-tokens)
                                  (fn []
                                    (-> (wtt/token-names-tree selected-set-tokens)
                                        ;; Allow setting editing token to it's own path
                                        (d/dissoc-in token-path))))

        ;; Name
        name-ref (mf/use-var (:name token))
        name-errors (mf/use-state nil)
        validate-name (mf/use-callback
                       (mf/deps selected-set-tokens-tree)
                       (fn [value]
                         (let [schema (token-name-schema {:token token
                                                          :tokens-tree selected-set-tokens-tree})]
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
        color (mf/use-state (when color? (:value token)))
        value-ref (mf/use-var (:value token))
        token-resolve-result (mf/use-state (get-in resolved-tokens [(wtt/token-identifier token) :resolved-value]))
        set-resolve-value (mf/use-callback
                           (fn [token-or-err]
                             (let [v (cond
                                       (= token-or-err :error/token-direct-self-reference) token-or-err
                                       (= token-or-err :error/token-missing-reference) token-or-err
                                       (:resolved-value token-or-err) (:resolved-value token-or-err))]
                               (when color? (reset! color v))
                               (reset! token-resolve-result v))))
        on-update-value-debounced (use-debonced-resolve-callback name-ref token active-theme-tokens set-resolve-value)
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
        validate-descripion (mf/use-callback #(m/explain token-description-schema %))
        on-update-description-debounced (mf/use-callback
                                         (debounce (fn [e]
                                                     (let [value (dom/get-target-val e)
                                                           errors (validate-descripion value)]
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
                   (mf/deps validate-name validate-descripion token resolved-tokens)
                   (fn [e]
                     (dom/prevent-default e)
                     ;; We have to re-validate the current form values before submitting
                     ;; because the validation is asynchronous/debounced
                     ;; and the user might have edited a valid form to make it invalid,
                     ;; and press enter before the next validations could return.
                     (let [final-name (finalize-name @name-ref)
                           valid-name?+ (-> (validate-name final-name) schema-validation->promise)
                           final-value (finalize-value @value-ref)
                           final-description @description-ref
                           valid-description?+ (some-> final-description validate-descripion schema-validation->promise)]
                       (-> (p/all [valid-name?+
                                   valid-description?+
                                   (validate-token-value+ {:input final-value
                                                           :name-value final-name
                                                           :token token
                                                           :tokens resolved-tokens})])
                           (p/finally (fn [result err]
                                        ;; The result should be a vector of all resolved validations
                                        ;; We do not handle the error case as it will be handled by the components validations
                                        (when (and (seq result) (not err))
                                          (let [new-token (cond-> {:name final-name
                                                                   :type (or (:type token) token-type)
                                                                   :value final-value}
                                                            final-description (assoc :description final-description)
                                                            (:id token) (assoc :id (:id token)))]
                                            (st/emit! (dt/update-create-token new-token))
                                            (st/emit! (wtu/update-workspace-tokens))
                                            (modal/hide!)))))))))]
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
       (for [error (->> (:errors @name-errors)
                        (map #(-> (assoc @name-errors :errors [%])
                                  (me/humanize))))]
         [:p {:key error
              :class (stl/css :error)}
          error])]
      ;; (when color?
      ;;   [:& ramp {:color @value-ref
      ;;             :on-change on-update-value}])
      [:& tokens.common/labeled-input {:label "Value"
                                       :input-props {:default-value @value-ref
                                                     :on-blur on-update-value
                                                     :on-change on-update-value}
                                       :render-right (when color?
                                                       (mf/fnc []
                                                         [:div {:class (stl/css :color-bullet)}
                                                          (if @color
                                                            [:& color-bullet {:color @color
                                                                              :mini? true}]
                                                            [:div {:class (stl/css :color-bullet-placeholder)}])]))}]
      [:div {:class (stl/css-case :resolved-value true
                                  :resolved-value-placeholder (nil? @token-resolve-result)
                                  :resolved-value-error value-error?)}
       (case @token-resolve-result
         :error/token-direct-self-reference "Token has self reference"
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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as c]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector]]
   [app.main.ui.workspace.tokens.common :as tokens.common]
   [app.main.ui.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.tinycolor :as tinycolor]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.update :as wtu]
   [app.util.dom :as dom]
   [app.util.functions :as uf]
   [app.util.i18n :refer [tr]]
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
  [{:keys [tokens-tree]}]
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
  [{:keys [value name-value token tokens]}]
  (let [;; When creating a new token we dont have a token name yet,
        ;; so we use a temporary token name that hopefully doesn't clash with any of the users token names
        token-name (if (str/empty? name-value) "__TOKEN_STUDIO_SYSTEM.TEMP" name-value)]
    (cond
      (empty? (str/trim value))
      (p/rejected {:errors [{:error/code :error/empty-input}]})

      (ctob/token-value-self-reference? token-name value)
      (p/rejected {:errors [(wte/get-error-code :error.token/direct-self-reference)]})

      :else
      (let [tokens' (cond-> tokens
                      ;; Remove previous token when renaming a token
                      (not= name-value (:name token)) (dissoc (:name token))
                      :always (update token-name #(ctob/make-token (merge % {:value value
                                                                             :name token-name
                                                                             :type (:type token)}))))]
        (-> tokens'
            (sd/resolve-tokens-interactive+)
            (p/then
             (fn [resolved-tokens]
               (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens token-name)]
                 (cond
                   resolved-value (p/resolved resolved-token)
                   :else (p/rejected {:errors (or errors (wte/get-error-code :error/unknown-error))}))))))))))

(defn use-debonced-resolve-callback
  "Resolves a token values using `StyleDictionary`.
  This function is debounced as the resolving might be an expensive calculation.
  Uses a custom debouncing logic, as the resolve function is async."
  [name-ref token tokens callback & {:keys [timeout] :or {timeout 160}}]
  (let [timeout-id-ref (mf/use-ref nil)
        debounced-resolver-callback
        (mf/use-fn
         (mf/deps token callback tokens)
         (fn [value]
           (let [timeout-id (js/Symbol)
                 ;; Dont execute callback when the timout-id-ref is outdated because this function got called again
                 timeout-outdated-cb? #(not= (mf/ref-val timeout-id-ref) timeout-id)]
             (mf/set-ref-val! timeout-id-ref timeout-id)
             (js/setTimeout
              (fn []
                (when (not (timeout-outdated-cb?))
                  (-> (validate-token-value+ {:value value
                                              :name-value @name-ref
                                              :token token
                                              :tokens tokens})
                      (p/finally
                        (fn [x err]
                          (when-not (timeout-outdated-cb?)
                            (callback (or err x))))))))
              timeout))))]
    debounced-resolver-callback))

(defonce form-token-cache-atom (atom nil))

(mf/defc ramp
  [{:keys [color on-change]}]
  (let [wrapper-node-ref (mf/use-ref nil)
        dragging? (mf/use-state)
        hex->value (fn [hex]
                     (when-let [tc (tinycolor/valid-color hex)]
                       (let [hex (str "#" (tinycolor/->hex tc))
                             [r g b] (c/hex->rgb hex)
                             [h s v] (c/hex->hsv hex)]
                         {:hex hex
                          :r r :g g :b b
                          :h h :s s :v v
                          :alpha 1})))
        value (mf/use-state (hex->value color))
        on-change' (fn [{:keys [hex]}]
                     (reset! value (hex->value hex))
                     (when-not (and @dragging? hex)
                       (on-change hex)))]
    (colorpicker/use-color-picker-css-variables! wrapper-node-ref @value)
    [:div {:ref wrapper-node-ref}
     [:& ramp-selector
      {:color @value
       :disable-opacity true
       :on-start-drag #(reset! dragging? true)
       :on-finish-drag #(reset! dragging? false)
       :on-change on-change'}]]))

(mf/defc token-value-or-errors
  [{:keys [result-or-errors]}]
  (let [{:keys [errors]} result-or-errors
        empty-message? (or (nil? result-or-errors)
                           (wte/has-error-code? :error/empty-input errors))
        message (cond
                  empty-message? (dm/str (tr "workspace.token.resolved-value") "-")
                  errors (->> (wte/humanize-errors errors)
                              (str/join "\n"))
                  :else (dm/str (tr "workspace.token.resolved-value") result-or-errors))]
    [:> text* {:as "p"
               :typography "body-small"
               :class (stl/css-case :resolved-value true
                                    :resolved-value-placeholder empty-message?
                                    :resolved-value-error (seq errors))}
     message]))

(mf/defc form
  {::mf/wrap-props false}
  [{:keys [token token-type action selected-token-set-id]}]
  (let [token (or token {:type token-type})
        color? (wtt/color-token? token)
        selected-set-tokens (mf/deref refs/workspace-selected-token-set-tokens)
        active-theme-tokens (mf/deref refs/workspace-active-theme-sets-tokens)
        resolved-tokens (sd/use-resolved-tokens active-theme-tokens {:cache-atom form-token-cache-atom
                                                                     :interactive? true})
        token-path (mf/use-memo
                    (mf/deps (:name token))
                    #(wtt/token-name->path (:name token)))
        selected-set-tokens-tree (mf/use-memo
                                  (mf/deps token-path selected-set-tokens)
                                  (fn []
                                    (-> (ctob/tokens-tree selected-set-tokens)
                                        ;; Allow setting editing token to it's own path
                                        (d/dissoc-in token-path))))

        ;; Name
        touched-name? (mf/use-state false)
        name-ref (mf/use-var (:name token))
        name-errors (mf/use-state nil)
        validate-name
        (mf/use-fn
         (mf/deps selected-set-tokens-tree)
         (fn [value]
           (let [schema (token-name-schema {:token token
                                            :tokens-tree selected-set-tokens-tree})]
             (m/explain schema (finalize-name value)))))

        on-update-name-debounced
        (mf/use-fn
         (uf/debounce (fn [e]
                        (let [value (dom/get-target-val e)
                              errors (validate-name value)]
                          (when touched-name?
                            (reset! name-errors errors))))))

        on-update-name
        (mf/use-fn
         (mf/deps on-update-name-debounced)
         (fn [e]
           (reset! touched-name? true)
           (reset! name-ref (dom/get-target-val e))
           (on-update-name-debounced e)))

        valid-name-field? (and
                           (not @name-errors)
                           (valid-name? @name-ref))

        ;; Value
        color (mf/use-state (when color? (:value token)))
        color-ramp-open? (mf/use-state false)
        value-input-ref (mf/use-ref nil)
        value-ref (mf/use-var (:value token))
        token-resolve-result (mf/use-state (get-in resolved-tokens [(wtt/token-identifier token) :resolved-value]))
        set-resolve-value
        (mf/use-fn
         (fn [token-or-err]
           (let [error? (:errors token-or-err)
                 v (if error?
                     token-or-err
                     (:resolved-value token-or-err))]
             (when color? (reset! color (if error? nil v)))
             (reset! token-resolve-result v))))
        on-update-value-debounced (use-debonced-resolve-callback name-ref token active-theme-tokens set-resolve-value)
        on-update-value (mf/use-fn
                         (mf/deps on-update-value-debounced)
                         (fn [e]
                           (let [value (dom/get-target-val e)]
                             (reset! value-ref value)
                             (on-update-value-debounced value))))
        on-update-color (mf/use-fn
                         (mf/deps on-update-value-debounced)
                         (fn [hex-value]
                           (reset! value-ref hex-value)
                           (set! (.-value (mf/ref-val value-input-ref)) hex-value)
                           (on-update-value-debounced hex-value)))

        value-error? (seq (:errors @token-resolve-result))
        valid-value-field? (and
                            (not value-error?)
                            (valid-value? @token-resolve-result))

        ;; Description
        description-ref (mf/use-var (:description token))
        description-errors (mf/use-state nil)
        validate-descripion (mf/use-fn #(m/explain token-description-schema %))
        on-update-description-debounced (mf/use-fn
                                         (uf/debounce (fn [e]
                                                        (let [value (dom/get-target-val e)
                                                              errors (validate-descripion value)]
                                                          (reset! description-errors errors)))))
        on-update-description
        (mf/use-fn
         (mf/deps on-update-description-debounced)
         (fn [e]
           (reset! description-ref (dom/get-target-val e))
           (on-update-description-debounced e)))
        valid-description-field? (not @description-errors)

        ;; Form
        disabled? (or (not valid-name-field?)
                      (not valid-value-field?)
                      (not valid-description-field?))

        on-submit
        (mf/use-fn
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
                         (validate-token-value+ {:value final-value
                                                 :name-value final-name
                                                 :token token
                                                 :tokens resolved-tokens})])
                 (p/finally (fn [result err]
                              ;; The result should be a vector of all resolved validations
                              ;; We do not handle the error case as it will be handled by the components validations
                              (when (and (seq result) (not err))
                                (st/emit! (dt/update-create-token {:token (ctob/make-token :name final-name
                                                                                           :type (or (:type token) token-type)
                                                                                           :value final-value
                                                                                           :description final-description)
                                                                   :prev-token-name (:name token)}))
                                (st/emit! (wtu/update-workspace-tokens))
                                (modal/hide!))))))))
        on-delete-token
        (mf/use-fn
         (mf/deps selected-token-set-id)
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)
           (st/emit! (dt/delete-token selected-token-set-id (:name token)))))

        on-cancel
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)))]

    [:form  {:class (stl/css :form-wrapper)
             :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (if (= action "edit")
         (tr "workspace.token.edit-token")
         (tr "workspace.token.create-token" token-type))]

      [:div {:class (stl/css :input-row)}
       ;; This should be remove when labeled-imput is modified
       [:span {:class (stl/css :labeled-input-label)} "Name"]
       [:& tokens.common/labeled-input {:label "Name"
                                        :error? @name-errors
                                        :input-props {:default-value @name-ref
                                                      :auto-focus true
                                                      :on-blur on-update-name
                                                      :on-change on-update-name}}]
       (for [error (->> (:errors @name-errors)
                        (map #(-> (assoc @name-errors :errors [%])
                                  (me/humanize))))]
         [:> text* {:as "p"
                    :key error
                    :typography "body-small"
                    :class (stl/css :error)}
          error])]

      [:div {:class (stl/css :input-row)}
       ;; This should be remove when labeled-imput is modified
       [:span {:class (stl/css :labeled-input-label)}  "value"]
       [:& tokens.common/labeled-input {:label "Value"
                                        :input-props {:default-value @value-ref
                                                      :on-blur on-update-value
                                                      :on-change on-update-value
                                                      :ref value-input-ref}
                                        :render-right (when color?
                                                        (mf/fnc drop-down-button []
                                                          [:div {:class (stl/css :color-bullet)
                                                                 :on-click #(swap! color-ramp-open? not)}
                                                           (if-let [hex (some-> @color tinycolor/valid-color tinycolor/->hex)]
                                                             [:& color-bullet {:color hex
                                                                               :mini? true}]
                                                             [:div {:class (stl/css :color-bullet-placeholder)}])]))}]
       (when @color-ramp-open?
         [:& ramp {:color (some-> (or @token-resolve-result (:value token))
                                  (tinycolor/valid-color))
                   :on-change on-update-color}])
       [:& token-value-or-errors {:result-or-errors @token-resolve-result}]]


      [:div {:class (stl/css :input-row)}
       ;; This should be remove when labeled-imput is modified
       [:span {:class (stl/css :labeled-input-label)}  "Description"]
       [:& tokens.common/labeled-input {:label "Description"
                                        :input-props {:default-value @description-ref
                                                      :on-change on-update-description}}]
       (when @description-errors
         [:> text* {:as "p"
                    :typography "body-small"
                    :class (stl/css :error)}
          (me/humanize @description-errors)])]

      [:div {:class (stl/css-case :button-row true
                                  :with-delete (= action "edit"))}
       (when (= action "edit")
         [:> button* {:on-click on-delete-token
                      :class (stl/css :delete-btn)
                      :type "button"
                      :icon i/delete
                      :variant "secondary"}
          (tr "labels.delete")])
       [:> button* {:on-click on-cancel
                    :type "button"
                    :variant "secondary"}
        (tr "labels.cancel")]
       [:> button* {:type "submit"
                    :variant "primary"
                    :disabled disabled?}
        (tr "labels.save")]]]]))

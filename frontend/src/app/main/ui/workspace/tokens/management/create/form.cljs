;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.modal :as modal]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token*]]
   [app.main.ui.workspace.tokens.management.create.shadow :refer [shadow-value-inputs*]]
   [app.main.ui.workspace.tokens.management.create.shared.color-picker :refer [color-picker*]]
   [app.main.ui.workspace.tokens.management.create.shared.composite-tabs :refer [composite-tabs*]]
   [app.main.ui.workspace.tokens.management.create.shared.font-combobox :refer [font-picker-combobox*]]
   [app.main.ui.workspace.tokens.management.create.typography-composite :refer [typography-value-inputs*]]
   [app.util.dom :as dom]
   [app.util.functions :as uf]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Helpers ---------------------------------------------------------------------

(defn- clean-name [name]
  (-> (str/trim name)
      ;; Remove trailing dots
      (str/replace #"\.+$" "")))

(defn- valid-name? [name]
  (seq (clean-name (str name))))

;; Schemas ---------------------------------------------------------------------

(defn- make-token-name-schema
  "Generate a dynamic schema validation to check if a token path derived
  from the name already exists at `tokens-tree`."
  [tokens-tree]
  [:and
   [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
   (sm/update-properties cto/token-name-ref assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
   [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
    #(not (cft/token-name-path-exists? % tokens-tree))]])

(def ^:private schema:token-description
  [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}])

(def ^:private validate-token-description
  (let [explainer (sm/lazy-explainer schema:token-description)]
    (fn [description]
      (-> description explainer sm/simplify not-empty))))

;; Value Validation -------------------------------------------------------------

(defn check-empty-value [token-value]
  (when (empty? (str/trim token-value))
    (wte/get-error-code :error.token/empty-input)))

(defn check-token-empty-value [token]
  (check-empty-value (:value token)))

(defn check-self-reference [token-name token-value]
  (when (cto/token-value-self-reference? token-name token-value)
    (wte/get-error-code :error.token/direct-self-reference)))

(defn check-token-self-reference [token]
  (check-self-reference (:name token) (:value token)))

(defn validate-resolve-token
  [token prev-token tokens]
  (let [token (cond-> token
                ;; When creating a new token we dont have a name yet or invalid name,
                ;; but we still want to resolve the value to show in the form.
                ;; So we use a temporary token name that hopefully doesn't clash with any of the users token names
                (not (sm/valid? cto/token-name-ref (:name token))) (assoc :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"))
        tokens' (cond-> tokens
                  ;; Remove previous token when renaming a token
                  (not= (:name token) (:name prev-token))
                  (dissoc (:name prev-token))

                  :always
                  (update (:name token) #(ctob/make-token (merge % prev-token token))))]
    (->> tokens'
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (cond
                resolved-value (rx/of resolved-token)
                :else (rx/throw {:errors (or (seq errors)
                                             [(wte/get-error-code :error/unknown-error)])}))))))))

(defn- validate-token-with [token validators]
  (if-let [error (some (fn [validate] (validate token)) validators)]
    (rx/throw {:errors [error]})
    (rx/of token)))

(def ^:private default-validators
  [check-token-empty-value check-self-reference])

(defn- default-validate-token
  "Validates a token by confirming a list of `validator` predicates and resolving the token using `tokens` with StyleDictionary.
  Returns rx stream of either a valid resolved token or an errors map.

  Props:
  token-name, token-value, token-description: Values from the form inputs
  prev-token: The existing token currently being edited
  tokens: tokens map keyed by token-name
          Used to look up the editing token & resolving step.

  validators: A list of predicates that will be used to do simple validation on the unresolved token map.
              The validators get the token map as input and should either return:
                - An errors map .e.g: {:errors []}
                - nil (valid token predicate)
              Mostly used to do simple checks like invalidating empy token `:name`.
              Will default to `default-validators`."
  [{:keys [token-name token-value token-description prev-token tokens validators]
    :or {validators default-validators}}]
  (let [token (-> {:name token-name
                   :value token-value
                   :description token-description}
                  (d/without-nils))]
    (->> (rx/of token)
         ;; Simple validation of the editing token
         (rx/mapcat #(validate-token-with % validators))
         ;; Resolving token via StyleDictionary
         (rx/mapcat #(validate-resolve-token % prev-token tokens)))))

(defn- check-coll-self-reference
  "Invalidate a collection of `token-vals` for a self-refernce against `token-name`.,"
  [token-name token-vals]
  (when (some #(cto/token-value-self-reference? token-name %) token-vals)
    (wte/get-error-code :error.token/direct-self-reference)))

(defn- check-font-family-token-self-reference [token]
  (check-coll-self-reference (:name token) (:value token)))

(defn- validate-font-family-token
  [props]
  (-> props
      (update :token-value cto/split-font-family)
      (assoc :validators [(fn [token]
                            (when (empty? (:value token))
                              (wte/get-error-code :error.token/empty-input)))
                          check-font-family-token-self-reference])
      (default-validate-token)))

(defn- check-typography-token-self-reference
  "Check token when any of the attributes in token value have a self-reference."
  [token]
  (let [token-name (:name token)
        token-values (:value token)]
    (some (fn [[k v]]
            (when-let [err (case k
                             :font-family (check-coll-self-reference token-name v)
                             (check-self-reference token-name v))]
              (assoc err :typography-key k)))
          token-values)))

(defn- check-empty-typography-token [token]
  (when (empty? (:value token))
    (wte/get-error-code :error.token/empty-input)))

(defn- check-shadow-token-self-reference
  "Check token when any of the attributes in a shadow's value have a self-reference."
  [token]
  (let [token-name (:name token)
        shadow-values (:value token)]
    (some (fn [[shadow-idx shadow-map]]
            (some (fn [[k v]]
                    (when-let [err (check-self-reference token-name v)]
                      (assoc err :shadow-key k :shadow-index shadow-idx)))
                  shadow-map))
          (d/enumerate shadow-values))))

(defn- check-empty-shadow-token [token]
  (when (or (empty? (:value token))
            (some (fn [shadow] (not-every? #(contains? shadow %) [:offsetX :offsetY :blur :spread :color]))
                  (:value token)))
    (wte/get-error-code :error.token/empty-input)))

(defn- validate-typography-token
  [{:keys [token-value] :as props}]
  (cond
    ;; Entering form without a value - show no error just resolve nil
    (nil? token-value) (rx/of nil)
    ;; Validate refrence string
    (cto/typography-composite-token-reference? token-value) (default-validate-token props)
    ;; Validate composite token
    :else
    (-> props
        (update :token-value
                (fn [v]
                  (-> (or v {})
                      (d/update-when :font-family #(if (string? %) (cto/split-font-family %) %)))))
        (assoc :validators [check-empty-typography-token
                            check-typography-token-self-reference])
        (default-validate-token))))

(defn- validate-shadow-token
  [{:keys [token-value] :as props}]
  (cond
    ;; Entering form without a value - show no error just resolve nil
    (nil? token-value) (rx/of nil)
    ;; Validate refrence string
    (cto/shadow-composite-token-reference? token-value) (default-validate-token props)
    ;; Validate composite token
    :else
    (-> props
        (update :token-value (fn [value]
                               (->> (or value [])
                                    (mapv (fn [shadow]
                                            (d/update-when shadow :inset #(cond
                                                                            (boolean? %) %
                                                                            (= "true" %) true
                                                                            :else false)))))))
        (assoc :validators [check-empty-shadow-token
                            check-shadow-token-self-reference])
        (default-validate-token))))

(defn- use-debonced-resolve-callback
  "Resolves a token values using `StyleDictionary`.
   This function is debounced as the resolving might be an expensive calculation.
   Uses a custom debouncing logic, as the resolve function is async."
  [{:keys [timeout name-ref token tokens callback validate-token]
    :or {timeout 160}}]
  (let [timeout-id-ref (mf/use-ref nil)]
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
              (->> (validate-token {:token-value value
                                    :token-name (mf/ref-val name-ref)
                                    :prev-token token
                                    :tokens tokens})
                   (rx/filter #(not (timeout-outdated-cb?)))
                   (rx/subs! callback callback))))
          timeout))))))

(defonce form-token-cache-atom (atom nil))

;; Form Component --------------------------------------------------------------

(mf/defc form*
  "Form component to edit or create a token of any token type.

   Callback props:
   validate-token: Function to validate and resolve an editing token, see `default-validate-token`.
   on-value-resolve: Will be called when a token value is resolved
                     Used to sync external state (like color picker)
   on-get-token-value: Custom function to get the input value from the dom
                       (As there might be multiple inputs passed for `custom-input-token-value`)
                       Can also be used to manipulate the value (E.g.: Auto-prepending # for hex colors)

   Custom component props:
   custom-input-token-value: Custom component for editing/displaying the token value
   custom-input-token-value-props: Custom props passed to the custom-input-token-value merged with the default props"
  [{:keys [is-create
           token
           token-type
           selected-token-set-id
           action
           input-value-placeholder

           ;; Callbacks
           validate-token
           on-value-resolve
           on-get-token-value

           ;; Custom component props
           custom-input-token-value
           custom-input-token-value-props]
    :or {validate-token default-validate-token}}]

  (let [token
        (mf/with-memo [token]
          (or token {:type token-type}))

        token-name        (get token :name)
        token-description (get token :description)
        token-name-ref    (mf/use-ref token-name)

        name-ref          (mf/use-ref nil)

        name-errors*      (mf/use-state nil)
        name-errors       (deref name-errors*)

        touched-name*     (mf/use-state false)
        touched-name?     (deref touched-name*)

        warning-name-change*
        (mf/use-state false)

        warning-name-change?
        (deref warning-name-change*)

        token-properties
        (dwta/get-token-properties token)

        tokens-in-selected-set
        (mf/deref refs/workspace-all-tokens-in-selected-set)

        active-theme-tokens
        (cond-> (mf/deref refs/workspace-active-theme-sets-tokens)
          ;; Ensure that the resolved value uses the currently editing token
          ;; even if the name has been overriden by a token with the same name
          ;; in another set below.
          (and (:name token) (:value token))
          (assoc (:name token) token)

          ;; Style dictionary resolver needs font families to be an array of strings
          (= :font-family (or (:type token) token-type))
          (update-in [(:name token) :value] cto/split-font-family)

          (= :typography (or (:type token) token-type))
          (d/update-in-when [(:name token) :font-family :value] cto/split-font-family))

        resolved-tokens
        (sd/use-resolved-tokens active-theme-tokens
                                {:cache-atom form-token-cache-atom
                                 :interactive? true})

        token-path
        (mf/with-memo [token-name]
          (cft/token-name->path token-name))

        tokens-tree-in-selected-set
        (mf/with-memo [token-path tokens-in-selected-set]
          (-> (ctob/tokens-tree tokens-in-selected-set)
              ;; Allow setting editing token to it's own path
              (d/dissoc-in token-path)))

        validate-token-name
        (mf/with-memo [tokens-tree-in-selected-set]
          (let [schema    (make-token-name-schema tokens-tree-in-selected-set)
                explainer (sm/explainer schema)]
            (fn [name]
              (-> name explainer sm/simplify not-empty))))

        on-blur-name
        (mf/use-fn
         (mf/deps touched-name? validate-token-name)
         (fn [e]
           (let [value  (dom/get-target-val e)
                 errors (validate-token-name value)]
             (when touched-name? (reset! warning-name-change* true))
             (reset! name-errors* errors))))

        on-update-name-debounced
        (mf/with-memo [touched-name? validate-token-name]
          (uf/debounce (fn [token-name]
                         (when touched-name?
                           (reset! name-errors* (validate-token-name token-name))))
                       300))

        on-update-name
        (mf/use-fn
         (mf/deps on-update-name-debounced name-ref)
         (fn []
           (let [ref (mf/ref-val name-ref)
                 token-name (dom/get-value ref)]
             (reset! touched-name* true)

             (mf/set-ref-val! token-name-ref token-name)
             (on-update-name-debounced token-name))))

        valid-name-field?
        (and
         (not name-errors)
         (valid-name? (mf/ref-val token-name-ref)))

        ;; Value
        value-input-ref (mf/use-ref nil)
        value-ref       (mf/use-ref (:value token))

        token-resolve-result*
        (mf/use-state #(get resolved-tokens (cft/token-identifier token)))

        token-resolve-result
        (deref token-resolve-result*)

        clear-resolve-value
        (mf/use-fn
         (fn []
           (reset! token-resolve-result* nil)))

        set-resolve-value
        (mf/use-fn
         (mf/deps on-value-resolve)
         (fn [token-or-err]
           (when on-value-resolve
             (cond
               (:errors token-or-err) (on-value-resolve nil)
               :else (on-value-resolve (:resolved-value token-or-err))))
           (reset! token-resolve-result* token-or-err)))

        on-update-value-debounced
        (use-debonced-resolve-callback
         {:name-ref token-name-ref
          :token token
          :tokens active-theme-tokens
          :callback set-resolve-value
          :validate-token validate-token})

        ;; Callback to update the value state via on of the inputs :on-change
        on-update-value
        (mf/use-fn
         (mf/deps on-update-value-debounced on-get-token-value)
         (fn [e]
           (let [value (if (fn? on-get-token-value)
                         (on-get-token-value e (mf/ref-val value-ref))
                         (dom/get-target-val e))]
             (mf/set-ref-val! value-ref value)
             (on-update-value-debounced value))))

        ;; Calback to update the value state from the outside (e.g.: color picker)
        on-external-update-value
        (mf/use-fn
         (mf/deps on-update-value-debounced)
         (fn [next-value]
           (mf/set-ref-val! value-ref next-value)
           (on-update-value-debounced next-value)))

        value-error?        (seq (:errors token-resolve-result))
        valid-value-field?  (and token-resolve-result (not value-error?))

        ;; Description
        description-ref     (mf/use-ref token-description)
        description-errors* (mf/use-state nil)
        description-errors  (deref description-errors*)

        on-update-description-debounced
        (mf/with-memo []
          (uf/debounce (fn [e]
                         (let [value  (dom/get-target-val e)
                               errors (validate-token-description value)]
                           (reset! description-errors* errors)))))

        on-update-description
        (mf/use-fn
         (mf/deps on-update-description-debounced)
         (fn [e]
           (mf/set-ref-val! description-ref (dom/get-target-val e))
           (on-update-description-debounced e)))

        valid-description-field?
        (empty? description-errors)

        ;; Form
        disabled?
        (or (not valid-name-field?)
            (not valid-value-field?)
            (not valid-description-field?))

        on-submit
        (mf/use-fn
         (mf/deps is-create token active-theme-tokens validate-token validate-token-name validate-token-description)
         (fn [e]
           (dom/prevent-default e)
           ;; We have to re-validate the current form values before submitting
           ;; because the validation is asynchronous/debounced
           ;; and the user might have edited a valid form to make it invalid,
           ;; and press enter before the next validations could return.

           (let [clean-name         (clean-name (mf/ref-val token-name-ref))
                 valid-name?        (empty? (validate-token-name clean-name))

                 value              (mf/ref-val value-ref)
                 clean-description  (mf/ref-val description-ref)
                 valid-description? (or (some-> clean-description validate-token-description empty?) true)]

             (when (and valid-name? valid-description?)
               (->> (validate-token {:token-value value
                                     :token-name clean-name
                                     :token-description clean-description
                                     :prev-token token
                                     :tokens active-theme-tokens})
                    (rx/subs!
                     (fn [valid-token]
                       (st/emit!
                        (if is-create
                          (dwtl/create-token {:name clean-name
                                              :type token-type
                                              :value (:value valid-token)
                                              :description clean-description})

                          (dwtl/update-token (:id token)
                                             {:name clean-name
                                              :value (:value valid-token)
                                              :description clean-description}))
                        (dwtp/propagate-workspace-tokens)
                        (modal/hide)))))))))

        on-delete-token
        (mf/use-fn
         (mf/deps selected-token-set-id)
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)
           (st/emit! (dwtl/delete-token selected-token-set-id (:id token)))))

        on-cancel
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)))

        handle-key-down-delete
        (mf/use-fn
         (mf/deps on-delete-token)
         (fn [e]
           (when (k/enter? e)
             (on-delete-token e))))

        handle-key-down-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [e]
           (when (k/enter? e)
             (on-cancel e))))

        handle-key-down-save
        (mf/use-fn
         (fn [e]
           (mf/deps on-submit)
           (when (k/enter? e)
             (on-submit e))))]

    ;; Clear form token cache on unmount
    (mf/with-effect []
      #(reset! form-token-cache-atom nil))

    ;; Update the value when editing an existing token
    ;; so the user doesn't have to interact with the form to validate the token
    (mf/with-effect [is-create token resolved-tokens token-resolve-result set-resolve-value]
      (when (and (not is-create)
                 (:value token) ;; Don't retrigger this effect when switching tabs on composite tokens
                 (not token-resolve-result)
                 resolved-tokens)
        (-> (get resolved-tokens (mf/ref-val token-name-ref))
            (set-resolve-value))))

    [:form {:class (stl/css :form-wrapper)
            :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}
      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (if (= action "edit")
         (tr "workspace.tokens.edit-token" token-type)
         (tr "workspace.tokens.create-token" token-type))]

      [:div {:class (stl/css :input-row)}
       (let [token-title (str/lower (:title token-properties))]
         [:> input* {:id "token-name"
                     :label (tr "workspace.tokens.token-name")
                     :placeholder (tr "workspace.tokens.enter-token-name", token-title)
                     :max-length max-input-length
                     :variant "comfortable"
                     :auto-focus true
                     :default-value (mf/ref-val token-name-ref)
                     :hint-type (when-not (empty? name-errors) "error")
                     :hint-message (first name-errors)
                     :ref name-ref
                     :on-blur on-blur-name
                     :on-change on-update-name}])

       (when (and warning-name-change? (= action "edit"))
         [:div {:class (stl/css :warning-name-change-notification-wrapper)}
          [:> context-notification*
           {:level :warning :appearance :ghost} (tr "workspace.tokens.warning-name-change")]])]

      [:div {:class (stl/css :input-row)}
       (let [placeholder (or input-value-placeholder (tr "workspace.tokens.token-value-enter"))
             label (tr "workspace.tokens.token-value")
             default-value (mf/ref-val value-ref)
             ref value-input-ref]
         (if (fn? custom-input-token-value)
           [:> custom-input-token-value
            {:placeholder placeholder
             :label label
             :default-value default-value
             :input-ref ref
             :on-update-value on-update-value
             :on-external-update-value on-external-update-value
             :custom-input-token-value-props custom-input-token-value-props
             :token-resolve-result token-resolve-result
             :clear-resolve-value clear-resolve-value}]
           [:> input-token*
            {:placeholder placeholder
             :label label
             :default-value default-value
             :ref ref
             :on-blur on-update-value
             :on-change on-update-value
             :token-resolve-result token-resolve-result}]))]
      [:div {:class (stl/css :input-row)}
       [:> input* {:label (tr "workspace.tokens.token-description")
                   :placeholder (tr "workspace.tokens.token-description")
                   :is-optional true
                   :max-length max-input-length
                   :variant "comfortable"
                   :default-value (mf/ref-val description-ref)
                   :hint-type (when-not (empty? description-errors) "error")
                   :hint-message (first description-errors)
                   :on-blur on-update-description
                   :on-change on-update-description}]]

      [:div {:class (stl/css-case :button-row true
                                  :with-delete (= action "edit"))}
       (when (= action "edit")
         [:> button* {:on-click on-delete-token
                      :on-key-down handle-key-down-delete
                      :class (stl/css :delete-btn)
                      :type "button"
                      :icon i/delete
                      :variant "secondary"}
          (tr "labels.delete")])
       [:> button* {:on-click on-cancel
                    :on-key-down handle-key-down-cancel
                    :type "button"
                    :id "token-modal-cancel"
                    :variant "secondary"}
        (tr "labels.cancel")]
       [:> button* {:type "submit"
                    :on-key-down handle-key-down-save
                    :variant "primary"
                    :disabled disabled?}
        (tr "labels.save")]]]]))

(mf/defc composite-form*
  "Wrapper around form* that manages composite/reference tab state.
   Takes the same props as form* plus a function to determine if a token value is a reference."
  [{:keys [token is-reference-fn composite-tab reference-icon title update-composite-backup-value] :rest props}]
  (let [active-tab* (mf/use-state (if (is-reference-fn (:value token)) :reference :composite))
        active-tab (deref active-tab*)

        custom-input-token-value-props
        (mf/use-memo
         (mf/deps active-tab composite-tab reference-icon title update-composite-backup-value is-reference-fn)
         (fn []
           {:active-tab active-tab
            :set-active-tab #(reset! active-tab* %)
            :composite-tab composite-tab
            :reference-icon reference-icon
            :reference-label (tr "workspace.tokens.reference-composite")
            :title title
            :update-composite-backup-value update-composite-backup-value
            :is-reference-fn is-reference-fn}))

        ;; Remove the value from a stored token when it doesn't match the tab type
        ;; We need this to keep the form disabled when there's an existing value that doesn't match the tab type
        token
        (mf/use-memo
         (mf/deps token active-tab is-reference-fn)
         (fn []
           (let [token-tab-type (if (is-reference-fn (:value token)) :reference :composite)]
             (cond-> token
               (not= token-tab-type active-tab) (dissoc :value token)))))]
    [:> form*
     (mf/spread-props props {:token token
                             :custom-input-token-value composite-tabs*
                             :custom-input-token-value-props custom-input-token-value-props})]))

;; Token Type Forms ------------------------------------------------------------

(mf/defc color-form*
  [{:keys [token on-display-colorpicker] :rest props}]
  (let [color* (mf/use-state (:value token))
        color (deref color*)
        on-value-resolve (mf/use-fn
                          (mf/deps color)
                          (fn [value]
                            (reset! color* value)
                            value))

        custom-input-token-value-props
        (mf/use-memo
         (mf/deps color on-display-colorpicker)
         (fn []
           {:color color
            :on-display-colorpicker on-display-colorpicker}))

        on-get-token-value
        (mf/use-fn
         (fn [e]
           (let [value (dom/get-target-val e)]
             (if (tinycolor/hex-without-hash-prefix? value)
               (let [hex-value (dm/str "#" value)]
                 (dom/set-value! (dom/get-target e) hex-value)
                 hex-value)
               value))))]

    [:> form*
     (mf/spread-props props {:token token
                             :on-get-token-value on-get-token-value
                             :on-value-resolve on-value-resolve
                             :custom-input-token-value color-picker*
                             :custom-input-token-value-props custom-input-token-value-props})]))

(mf/defc shadow-form*
  [{:keys [token] :rest props}]
  (let [on-get-token-value
        (mf/use-callback
         (fn [e prev-composite-value]
           (let [prev-composite-value (or prev-composite-value [])
                 [idx token-type :as token-type-at-index] (obj/get e "tokenTypeAtIndex")
                 input-value (case token-type
                               :inset (obj/get e "tokenValue")
                               (dom/get-target-val e))
                 reference-value-input? (not token-type-at-index)]
             (cond
               reference-value-input? input-value
               (and (string? input-value) (empty? input-value)) (update prev-composite-value idx dissoc token-type)
               :else (assoc-in prev-composite-value token-type-at-index input-value)))))

        update-composite-backup-value
        (mf/use-callback
         (fn [prev-composite-value e]
           (let [[idx token-type :as token-type-at-index] (obj/get e "tokenTypeAtIndex")
                 token-value (case token-type
                               :inset (obj/get e "tokenValue")
                               (dom/get-target-val e))
                 valid? (case token-type
                          :inset (boolean? token-value)
                          (seq token-value))]
             (if valid?
               (assoc-in (or prev-composite-value []) token-type-at-index token-value)
               ;; Remove empty values so they don't retrigger validation when switching tabs
               (update prev-composite-value idx dissoc token-type)))))]
    [:> composite-form*
     (mf/spread-props props {:token token
                             :composite-tab shadow-value-inputs*
                             :reference-icon i/text-typography
                             :is-reference-fn cto/typography-composite-token-reference?
                             :title (tr "workspace.tokens.shadow-title")
                             :validate-token validate-shadow-token
                             :on-get-token-value on-get-token-value
                             :update-composite-backup-value update-composite-backup-value})]))

(mf/defc font-family-form*
  [{:keys [token] :rest props}]
  (let [on-value-resolve
        (mf/use-fn
         (fn [value]
           (when value
             (cto/join-font-family value))))]
    [:> form*
     (mf/spread-props props {:token (when token (update token :value cto/join-font-family))
                             :custom-input-token-value font-picker-combobox*
                             :on-value-resolve on-value-resolve
                             :validate-token validate-font-family-token})]))

(mf/defc text-case-form*
  [{:keys [token] :rest props}]
  [:> form*
   (mf/spread-props props {:token token
                           :input-value-placeholder (tr "workspace.tokens.text-case-value-enter")})])

(mf/defc text-decoration-form*
  [{:keys [token] :rest props}]
  [:> form*
   (mf/spread-props props {:token token
                           :input-value-placeholder (tr "workspace.tokens.text-decoration-value-enter")})])

(mf/defc font-weight-form*
  [{:keys [token] :rest props}]
  [:> form*
   (mf/spread-props props {:token token
                           :input-value-placeholder (tr "workspace.tokens.font-weight-value-enter")})])

(mf/defc typography-form*
  [{:keys [token] :rest props}]
  (let [on-get-token-value
        (mf/use-fn
         (fn [e prev-composite-value]
           (let [token-type (obj/get e "tokenType")
                 input-value (dom/get-target-val e)
                 reference-value-input? (not token-type)]
             (cond
               reference-value-input? input-value

               (empty? input-value) (dissoc prev-composite-value token-type)
               :else (assoc prev-composite-value token-type input-value)))))

        update-composite-backup-value
        (mf/use-fn
         (fn [prev-composite-value e]
           (let [token-type (obj/get e "tokenType")
                 token-value (dom/get-target-val e)
                 token-value (cond-> token-value
                               (= :font-family token-type) (cto/split-font-family))]
             (if (seq token-value)
               (assoc prev-composite-value token-type token-value)
               ;; Remove empty values so they don't retrigger validation when switching tabs
               (dissoc prev-composite-value token-type)))))]
    [:> composite-form*
     (mf/spread-props props {:token token
                             :composite-tab typography-value-inputs*
                             :reference-icon i/text-typography
                             :is-reference-fn cto/typography-composite-token-reference?
                             :title (tr "labels.typography")
                             :validate-token validate-typography-token
                             :on-get-token-value on-get-token-value
                             :update-composite-backup-value update-composite-backup-value})]))

(mf/defc form-wrapper*
  [{:keys [token token-type] :rest props}]
  (let [token-type' (or (:type token) token-type)
        props (mf/spread-props props {:token-type token-type'
                                      :token token})]
    (case token-type'
      :color [:> color-form* props]
      :typography [:> typography-form* props]
      :shadow [:> shadow-form* props]
      :font-family [:> font-family-form* props]
      :text-case [:> text-case-form* props]
      :text-decoration [:> text-decoration-form* props]
      :font-weight [:> font-weight-form* props]
      [:> form* props])))

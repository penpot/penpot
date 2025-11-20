;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.color :as c]
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
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [font-selector*]]
   [app.main.ui.workspace.tokens.management.create.border-radius :as border-radius]
   [app.main.ui.workspace.tokens.management.create.color :as color]
   [app.main.ui.workspace.tokens.management.create.dimensions :as dimensions]
   [app.main.ui.workspace.tokens.management.create.input-token-color-bullet :refer [input-token-color-bullet*]]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token* token-value-hint*]]
   [app.main.ui.workspace.tokens.management.create.text-case :as text-case]
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

(defn validate-token-name
  [tokens-tree name]
  (let [schema    (make-token-name-schema tokens-tree)
        explainer (sm/explainer schema)]
    (-> name explainer sm/simplify not-empty)))

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

        on-blur-name
        (mf/use-fn
         (mf/deps touched-name?)
         (fn [e]
           (let [value  (dom/get-target-val e)
                 errors (validate-token-name tokens-tree-in-selected-set value)]
             (when touched-name? (reset! warning-name-change* true))
             (reset! name-errors* errors))))

        on-update-name-debounced
        (mf/with-memo [touched-name?]
          (uf/debounce (fn [token-name]
                         (when touched-name?
                           (reset! name-errors* (validate-token-name tokens-tree-in-selected-set token-name))))
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
         (mf/deps is-create token active-theme-tokens validate-token validate-token-description)
         (fn [e]
           (dom/prevent-default e)
           ;; We have to re-validate the current form values before submitting
           ;; because the validation is asynchronous/debounced
           ;; and the user might have edited a valid form to make it invalid,
           ;; and press enter before the next validations could return.

           (let [clean-name         (clean-name (mf/ref-val token-name-ref))
                 valid-name?        (empty? (validate-token-name tokens-tree-in-selected-set clean-name))

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
                          (dwtl/create-token (ctob/make-token {:name clean-name
                                                               :type token-type
                                                               :value (:value valid-token)
                                                               :description clean-description}))

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

;; Tabs Component --------------------------------------------------------------

(mf/defc composite-reference-input*
  [{:keys [default-value on-blur on-update-value token-resolve-result reference-label reference-icon is-reference-fn]}]
  [:> input-token*
   {:aria-label (tr "labels.reference")
    :placeholder reference-label
    :icon reference-icon
    :default-value (when (is-reference-fn default-value) default-value)
    :on-blur on-blur
    :on-change on-update-value
    :token-resolve-result (when (or
                                 (:errors token-resolve-result)
                                 (string? (:value token-resolve-result)))
                            token-resolve-result)}])

(mf/defc composite-tabs*
  [{:keys [default-value
           on-update-value
           on-external-update-value
           on-value-resolve
           clear-resolve-value
           custom-input-token-value-props]
    :rest props}]
  (let [;; Active Tab State
        {:keys [active-tab
                composite-tab
                is-reference-fn
                reference-icon
                reference-label
                set-active-tab
                title
                update-composite-backup-value]} custom-input-token-value-props
        reference-tab-active? (= :reference active-tab)
        ;; Backup value ref
        ;; Used to restore the previously entered value when switching tabs
        ;; Uses ref to not trigger state updates during update
        backup-state-ref (mf/use-var
                          (if reference-tab-active?
                            {:reference default-value}
                            {:composite default-value}))
        default-value (get @backup-state-ref active-tab)

        on-toggle-tab
        (mf/use-fn
         (mf/deps active-tab on-external-update-value on-value-resolve clear-resolve-value)
         (fn []
           (let [next-tab (if (= active-tab :composite) :reference :composite)]
             ;; Clear the resolved value so it wont show up before the next-tab value has resolved
             (clear-resolve-value)
             ;; Restore the internal value from backup
             (on-external-update-value (get @backup-state-ref next-tab))
             (set-active-tab next-tab))))

        update-composite-value
        (mf/use-fn
         (fn [f]
           (clear-resolve-value)
           (swap! backup-state-ref f)
           (on-external-update-value (get @backup-state-ref :composite))))

        ;; Store updated value in backup-state-ref
        on-update-value'
        (mf/use-fn
         (mf/deps on-update-value reference-tab-active? update-composite-backup-value)
         (fn [e]
           (if reference-tab-active?
             (swap! backup-state-ref assoc :reference (dom/get-target-val e))
             (swap! backup-state-ref update :composite #(update-composite-backup-value % e)))
           (on-update-value e)))]
    [:div {:class (stl/css :typography-inputs-row)}
     [:div {:class (stl/css :title-bar)}
      [:div {:class (stl/css :title)} title]
      [:& radio-buttons {:class (stl/css :listing-options)
                         :selected (if reference-tab-active? "reference" "composite")
                         :on-change on-toggle-tab
                         :name "reference-composite-tab"}
       [:& radio-button {:icon deprecated-icon/layers
                         :value "composite"
                         :title (tr "workspace.tokens.individual-tokens")
                         :id "composite-opt"}]
       [:& radio-button {:icon deprecated-icon/tokens
                         :value "reference"
                         :title (tr "workspace.tokens.use-reference")
                         :id "reference-opt"}]]]
     [:div {:class (stl/css :typography-inputs)}
      (if reference-tab-active?
        [:> composite-reference-input*
         (mf/spread-props props {:default-value default-value
                                 :on-update-value on-update-value'
                                 :reference-icon reference-icon
                                 :reference-label reference-label
                                 :is-reference-fn is-reference-fn})]
        [:> composite-tab
         (mf/spread-props props {:default-value default-value
                                 :on-update-value on-update-value'
                                 :update-composite-value update-composite-value})])]]))

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

;; FIXME: this function has confusing name
(defn- hex->value
  [hex]
  (when-let [tc (tinycolor/valid-color hex)]
    (let [hex (tinycolor/->hex-string tc)
          alpha (tinycolor/alpha tc)
          [r g b] (c/hex->rgb hex)
          [h s v] (c/hex->hsv hex)]
      {:hex hex
       :r r :g g :b b
       :h h :s s :v v
       :alpha alpha})))

(mf/defc ramp*
  [{:keys [color on-change]}]
  (let [wrapper-node-ref (mf/use-ref nil)
        dragging-ref     (mf/use-ref false)

        on-start-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref true))

        on-finish-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref false))

        internal-color*
        (mf/use-state #(hex->value color))

        internal-color
        (deref internal-color*)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [{:keys [hex alpha] :as selector-color}]
           (let [dragging? (mf/ref-val dragging-ref)]
             (when-not (and dragging? hex)
               (reset! internal-color* selector-color)
               (on-change hex alpha)))))]
    (mf/use-effect
     (mf/deps color)
     (fn []
       ;; Update internal color when user changes input value
       (when-let [color (tinycolor/valid-color color)]
         (when-not (= (tinycolor/->hex-string color) (:hex internal-color))
           (reset! internal-color* (hex->value color))))))

    (colorpicker/use-color-picker-css-variables! wrapper-node-ref internal-color)
    [:div {:ref wrapper-node-ref}
     [:> ramp-selector*
      {:color internal-color
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag
       :on-change on-change'}]]))

(mf/defc color-picker*
  [{:keys [placeholder label default-value input-ref on-blur on-update-value on-external-update-value custom-input-token-value-props token-resolve-result]}]
  (let [{:keys [color on-display-colorpicker]} custom-input-token-value-props
        color-ramp-open* (mf/use-state false)
        color-ramp-open? (deref color-ramp-open*)

        on-click-swatch
        (mf/use-fn
         (mf/deps color-ramp-open? on-display-colorpicker)
         (fn []
           (let [open? (not color-ramp-open?)]
             (reset! color-ramp-open* open?)
             (when on-display-colorpicker
               (on-display-colorpicker open?)))))

        swatch
        (mf/html
         [:> input-token-color-bullet*
          {:color color
           :class (stl/css :slot-start)
           :on-click on-click-swatch}])

        on-change'
        (mf/use-fn
         (mf/deps color on-external-update-value)
         (fn [hex-value alpha]
           (let [;; StyleDictionary will always convert to hex/rgba, so we take the format from the value input field
                 prev-input-color (some-> (dom/get-value (mf/ref-val input-ref))
                                          (tinycolor/valid-color))
                  ;; If the input is a reference we will take the format from the computed value
                 prev-computed-color (when-not prev-input-color
                                       (some-> color (tinycolor/valid-color)))
                 prev-format (some-> (or prev-input-color prev-computed-color)
                                     (tinycolor/color-format))
                 to-rgba? (and
                           (< alpha 1)
                           (or (= prev-format "hex") (not prev-format)))
                 to-hex? (and (not prev-format) (= alpha 1))
                 format (cond
                          to-rgba? "rgba"
                          to-hex? "hex"
                          prev-format prev-format
                          :else "hex")
                 color-value (-> (tinycolor/valid-color hex-value)
                                 (tinycolor/set-alpha (or alpha 1))
                                 (tinycolor/->string format))]
             (dom/set-value! (mf/ref-val input-ref) color-value)
             (on-external-update-value color-value))))]

    [:*
     [:> input-token*
      {:placeholder placeholder
       :label label
       :default-value default-value
       :ref input-ref
       :on-blur on-blur
       :on-change on-update-value
       :slot-start swatch}]
     (when color-ramp-open?
       [:> ramp*
        {:color (some-> color (tinycolor/valid-color))
         :on-change on-change'}])
     [:> token-value-hint* {:result token-resolve-result}]]))

(mf/defc shadow-color-picker-wrapper*
  "Wrapper for color-picker* that passes shadow color state from parent.
   Similar to color-form* but receives color state from shadow-value-inputs*."
  [{:keys [placeholder label default-value input-ref on-update-value on-external-update-value token-resolve-result shadow-color]}]
  (let [;; Use the color state passed from parent (shadow-value-inputs*)
        resolved-color (get token-resolve-result :resolved-value)
        color (or shadow-color resolved-color default-value "")

        custom-input-token-value-props
        (mf/use-memo
         (mf/deps color)
         (fn []
           {:color color}))]

    [:> color-picker*
     {:placeholder placeholder
      :label label
      :default-value default-value
      :input-ref input-ref
      :on-update-value on-update-value
      :on-external-update-value on-external-update-value
      :custom-input-token-value-props custom-input-token-value-props
      :token-resolve-result token-resolve-result}]))

(def ^:private shadow-inputs
  #(d/ordered-map
    :offsetX
    {:label (tr "workspace.tokens.shadow-x")
     :placeholder (tr "workspace.tokens.shadow-x")}
    :offsetY
    {:label (tr "workspace.tokens.shadow-y")
     :placeholder (tr "workspace.tokens.shadow-y")}
    :blur
    {:label (tr "workspace.tokens.shadow-blur")
     :placeholder (tr "workspace.tokens.shadow-blur")}
    :spread
    {:label (tr "workspace.tokens.shadow-spread")
     :placeholder (tr "workspace.tokens.shadow-spread")}
    :color
    {:label (tr "workspace.tokens.shadow-color")
     :placeholder (tr "workspace.tokens.shadow-color")}
    :inset
    {:label (tr "workspace.tokens.shadow-inset")
     :placeholder (tr "workspace.tokens.shadow-inset")}))

(mf/defc inset-type-select*
  [{:keys [default-value shadow-idx label on-change]}]
  (let [selected* (mf/use-state (or (str default-value) "false"))
        selected (deref selected*)

        on-change
        (mf/use-fn
         (mf/deps on-change selected shadow-idx)
         (fn [value e]
           (obj/set! e "tokenValue" (if (= "true" value) true false))
           (on-change e)
           (reset! selected* (str value))))]
    [:div {:class (stl/css :input-row)}
     [:div {:class (stl/css :inset-label)} label]
     [:& radio-buttons {:selected selected
                        :on-change on-change
                        :name (str "inset-select-" shadow-idx)}
      [:& radio-button {:value "false"
                        :title "false"
                        :icon "❌"
                        :id (str "inset-default-" shadow-idx)}]
      [:& radio-button {:value "true"
                        :title "true"
                        :icon "✅"
                        :id (str "inset-false-" shadow-idx)}]]]))

(mf/defc shadow-input*
  [{:keys [default-value label placeholder shadow-idx input-type on-update-value on-external-update-value token-resolve-result errors-by-key shadow-color]}]
  (let [color-input-ref (mf/use-ref)

        on-change
        (mf/use-fn
         (mf/deps shadow-idx input-type on-update-value)
         (fn [e]
           (-> (obj/set! e "tokenTypeAtIndex" [shadow-idx input-type])
               (on-update-value))))

        on-external-update-value'
        (mf/use-fn
         (mf/deps shadow-idx input-type on-external-update-value)
         (fn [v]
           (on-external-update-value [shadow-idx input-type] v)))

        resolved (get-in token-resolve-result [:resolved-value shadow-idx input-type])

        errors (get errors-by-key input-type)

        should-show? (or (some? resolved) (seq errors))

        token-prop (when should-show?
                     (d/without-nils
                      {:resolved-value resolved
                       :errors errors}))]
    (case input-type
      :inset
      [:> inset-type-select*
       {:default-value default-value
        :shadow-idx shadow-idx
        :label label
        :on-change on-change}]
      :color
      [:> shadow-color-picker-wrapper*
       {:placeholder placeholder
        :label label
        :default-value default-value
        :input-ref color-input-ref
        :on-update-value on-change
        :on-external-update-value on-external-update-value'
        :token-resolve-result token-prop
        :shadow-color shadow-color
        :data-testid (str "shadow-color-input-" shadow-idx)}]
      [:div {:class (stl/css :input-row)
             :data-testid (str "shadow-" (name input-type) "-input-" shadow-idx)}
       [:> input-token*
        {:label label
         :placeholder placeholder
         :default-value default-value
         :on-change on-change
         :token-resolve-result token-prop}]])))

(mf/defc shadow-input-fields*
  [{:keys [shadow shadow-idx on-remove-shadow on-add-shadow is-remove-disabled on-update-value token-resolve-result errors-by-key on-external-update-value shadow-color] :as props}]
  (let [on-remove-shadow
        (mf/use-fn
         (mf/deps shadow-idx on-remove-shadow)
         #(on-remove-shadow shadow-idx))]
    [:div {:data-testid (str "shadow-input-fields-" shadow-idx)}
     [:> icon-button* {:icon i/add
                       :type "button"
                       :on-click on-add-shadow
                       :data-testid (str "shadow-add-button-" shadow-idx)
                       :aria-label (tr "workspace.tokens.shadow-add-shadow")}]
     [:> icon-button* {:variant "ghost"
                       :type "button"
                       :icon i/remove
                       :on-click on-remove-shadow
                       :disabled is-remove-disabled
                       :data-testid (str "shadow-remove-button-" shadow-idx)
                       :aria-label (tr "workspace.tokens.shadow-remove-shadow")}]
     (for [[input-type {:keys [label placeholder]}] (shadow-inputs)]
       [:> shadow-input*
        {:key (str input-type shadow-idx)
         :input-type input-type
         :label label
         :placeholder placeholder
         :shadow-idx shadow-idx
         :default-value (get shadow input-type)
         :on-update-value on-update-value
         :token-resolve-result token-resolve-result
         :errors-by-key errors-by-key
         :on-external-update-value on-external-update-value
         :shadow-color shadow-color}])]))

(mf/defc shadow-value-inputs*
  [{:keys [default-value on-update-value token-resolve-result update-composite-value] :as props}]
  (let [shadows* (mf/use-state (or default-value [{}]))
        shadows (deref shadows*)
        shadows-count (count shadows)
        composite-token? (not (cto/typography-composite-token-reference? (:value token-resolve-result)))

        ;; Maintain a map of color states for each shadow to prevent reset on add/remove
        shadow-colors* (mf/use-state {})
        shadow-colors (deref shadow-colors*)

        ;; Initialize color states for each shadow index
        _ (mf/use-effect
           (mf/deps shadows)
           (fn []
             (doseq [[idx shadow] (d/enumerate shadows)]
               (when-not (contains? shadow-colors idx)
                 (let [resolved-color (get-in token-resolve-result [:resolved-value idx :color])
                       initial-color (or resolved-color (get shadow :color) "")]
                   (swap! shadow-colors* assoc idx initial-color))))))

        ;; Define on-external-update-value here where we have access to on-update-value
        on-external-update-value
        (mf/use-callback
         (mf/deps on-update-value shadow-colors*)
         (fn [token-type-at-index value]
           (let [[idx token-type] token-type-at-index
                 e (js-obj)]
             ;; Update shadow color state if this is a color update
             (when (= token-type :color)
               (swap! shadow-colors* assoc idx value))
             (obj/set! e "tokenTypeAtIndex" token-type-at-index)
             (obj/set! e "target" #js {:value value})
             (on-update-value e))))

        on-add-shadow
        (mf/use-fn
         (mf/deps shadows update-composite-value)
         (fn []
           (update-composite-value
            (fn [state]
              (let [new-state (update state :composite (fnil conj []) {})]
                (reset! shadows* (:composite new-state))
                new-state)))))

        on-remove-shadow
        (mf/use-fn
         (mf/deps shadows update-composite-value)
         (fn [idx]
           (update-composite-value
            (fn [state]
              (let [new-state (update state :composite d/remove-at-index idx)]
                (reset! shadows* (:composite new-state))
                new-state)))))]
    [:div {:class (stl/css :nested-input-row)}
     (for [[shadow-idx shadow] (d/enumerate shadows)
           :let [is-remove-disabled (= shadows-count 1)
                 key (str shadows-count shadow-idx)
                 errors-by-key (when composite-token?
                                 (sd/collect-shadow-errors token-resolve-result shadow-idx))]]
       [:div {:key key
              :class (stl/css :nested-input-row)}
        [:> shadow-input-fields*
         {:is-remove-disabled is-remove-disabled
          :shadow-idx shadow-idx
          :on-add-shadow on-add-shadow
          :on-remove-shadow on-remove-shadow
          :shadow shadow
          :on-update-value on-update-value
          :token-resolve-result token-resolve-result
          :errors-by-key errors-by-key
          :on-external-update-value on-external-update-value
          :shadow-color (get shadow-colors shadow-idx "")}]])]))

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

(mf/defc font-selector-wrapper*
  [{:keys [font input-ref on-select-font on-close-font-selector]}]
  (let [current-font* (mf/use-state (or font
                                        (some-> (mf/ref-val input-ref)
                                                (dom/get-value)
                                                (cto/split-font-family)
                                                (first)
                                                (fonts/find-font-family))))
        current-font (deref current-font*)]
    [:div {:class (stl/css :font-select-wrapper)}
     [:> font-selector* {:current-font current-font
                         :on-select on-select-font
                         :on-close on-close-font-selector
                         :full-size true}]]))

(mf/defc font-picker-combobox*
  [{:keys [default-value label aria-label input-ref on-blur on-update-value on-external-update-value token-resolve-result placeholder]}]
  (let [font* (mf/use-state (fonts/find-font-family default-value))
        font (deref font*)
        set-font (mf/use-fn
                  (mf/deps font)
                  #(reset! font* %))

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        on-select-font
        (mf/use-fn
         (mf/deps on-external-update-value set-font font)
         (fn [{:keys [family] :as font}]
           (when font
             (set-font font)
             (on-external-update-value family))))

        on-update-value'
        (mf/use-fn
         (mf/deps on-update-value set-font)
         (fn [value]
           (set-font nil)
           (on-update-value value)))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon i/arrow-down
           :variant "action"
           :type "button"}])]
    [:*
     [:> input-token*
      {:placeholder (or placeholder (tr "workspace.tokens.token-font-family-value-enter"))
       :label label
       :aria-label aria-label
       :default-value (or (:name font) default-value)
       :ref input-ref
       :on-blur on-blur
       :on-change on-update-value'
       :icon i/text-font-family
       :slot-end font-selector-button
       :token-resolve-result token-resolve-result}]
     (when font-selector-open?
       [:> font-selector-wrapper* {:font font
                                   :input-ref input-ref
                                   :on-select-font on-select-font
                                   :on-close-font-selector on-close-font-selector}])]))

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

(def ^:private typography-inputs
  #(d/ordered-map
    :font-family
    {:label (tr "workspace.tokens.token-font-family-value")
     :icon i/text-font-family
     :placeholder (tr "workspace.tokens.token-font-family-value-enter")}
    :font-size
    {:label "Font Size"
     :icon i/text-font-size
     :placeholder (tr "workspace.tokens.font-size-value-enter")}
    :font-weight
    {:label "Font Weight"
     :icon i/text-font-weight
     :placeholder (tr "workspace.tokens.font-weight-value-enter")}
    :line-height
    {:label "Line Height"
     :icon i/text-lineheight
     :placeholder (tr "workspace.tokens.line-height-value-enter")}
    :letter-spacing
    {:label "Letter Spacing"
     :icon i/text-letterspacing
     :placeholder (tr "workspace.tokens.letter-spacing-value-enter-composite")}
    :text-case
    {:label "Text Case"
     :icon i/text-mixed
     :placeholder (tr "workspace.tokens.text-case-value-enter")}
    :text-decoration
    {:label "Text Decoration"
     :icon i/text-underlined
     :placeholder (tr "workspace.tokens.text-decoration-value-enter")}))

(mf/defc typography-value-inputs*
  [{:keys [default-value on-blur on-update-value token-resolve-result]}]
  (let [composite-token? (not (cto/typography-composite-token-reference? (:value token-resolve-result)))
        typography-inputs (mf/use-memo typography-inputs)
        errors-by-key (sd/collect-typography-errors token-resolve-result)]
    [:div {:class (stl/css :nested-input-row)}
     (for [[token-type {:keys [label placeholder icon]}] typography-inputs]
       (let [value (get default-value token-type)
             resolved (get-in token-resolve-result [:resolved-value token-type])
             errors   (get errors-by-key token-type)

             should-show? (or (and (some? resolved)
                                   (not= value (str resolved)))
                              (seq errors))

             token-prop  (when (and composite-token? should-show?)
                           (d/without-nils
                            {:resolved-value (when-not (str/empty? resolved) resolved)
                             :errors errors}))

             input-ref (mf/use-ref)

             on-external-update-value
             (mf/use-fn
              (mf/deps on-update-value)
              (fn [next-value]
                (let [element (mf/ref-val input-ref)]
                  (dom/set-value! element next-value)
                  (on-update-value #js {:target element
                                        :tokenType :font-family}))))

             on-change
             (mf/use-fn
              (mf/deps token-type)
              ;; Passing token-type via event to prevent deep function adapting & passing of type
              (fn [event]
                (-> (obj/set! event "tokenType" token-type)
                    (on-update-value))))]

         [:div {:key (str token-type)
                :class (stl/css :input-row)}
          (case token-type
            :font-family
            [:> font-picker-combobox*
             {:aria-label label
              :placeholder placeholder
              :input-ref input-ref
              :default-value (when value (cto/join-font-family value))
              :on-blur on-blur
              :on-update-value on-change
              :on-external-update-value on-external-update-value
              :token-resolve-result token-prop}]
            [:> input-token*
             {:aria-label label
              :placeholder placeholder
              :default-value value
              :on-blur on-blur
              :icon icon
              :on-change on-change
              :token-resolve-result token-prop}])]))]))

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
  (let [token-type
        (or (:type token) token-type)
        ;; NOTE: All this references to tokens can be
        ;; provided via context for
        ;; avoid duplicate code among each form, this is because it is
        ;; a common code and is probably will be needed on all forms

        tokens-in-selected-set
        (mf/deref refs/workspace-all-tokens-in-selected-set)

        token-path
        (mf/with-memo [token]
          (cft/token-name->path (:name token)))

        tokens-tree-in-selected-set
        (mf/with-memo [token-path tokens-in-selected-set]
          (-> (ctob/tokens-tree tokens-in-selected-set)
              (d/dissoc-in token-path)))
        props
        (mf/spread-props props {:token-type token-type
                                :validate-token default-validate-token
                                :tokens-tree-in-selected-set tokens-tree-in-selected-set
                                :token token})]

    (case token-type
      :color [:> color/form* props]
      :typography [:> typography-form* props]
      :shadow [:> shadow-form* props]
      :font-family [:> font-family-form* props]
      :text-case [:> text-case/form* props]
      :text-decoration [:> text-decoration-form* props]
      :font-weight [:> font-weight-form* props]
      :border-radius [:> border-radius/form* props]
      :dimensions [:> dimensions/form* props]
      [:> form* props])))

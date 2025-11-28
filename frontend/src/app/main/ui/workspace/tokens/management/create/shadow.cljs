;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.forms :as forms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.tokens.management.create.form-color-input-token :refer [color-input-indexed*]]
   [app.main.ui.workspace.tokens.management.create.form-input-token :refer [form-input-token-composite* form-input-token-indexed*]]
   [app.main.ui.workspace.tokens.management.create.form-select-token :refer [select-composite*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; TODO: Review if code has this implementation
(defn vec-remove
  "remove elem in coll"
  [pos coll]
  (into (subvec coll 0 pos) (subvec coll (inc pos))))

;; TODO: Put this in a common file, and use ir for shadow option menu as well
(def ^:private default-token-shadow
  {:offsetX "4"
   :offsetY "4"
   :blur "4"
   :spread "0"})

(defn get-subtoken
  [token index prop]
  (let [value (get-in token [:value :shadows index prop])]
    (d/without-nils
     {:type  (if (= prop :color) :color :number)
      :value value})))

(mf/defc shadow-formset*
  [{:keys [index token tokens remove-shadow-block show-button] :as props}]
  (let [inset-token (get-subtoken token index :inset)
        inset-token (hooks/use-equal-memo inset-token)

        color-token (get-subtoken token index :color)
        color-token (hooks/use-equal-memo color-token)

        offset-x-token (get-subtoken token index :offsetX)
        offset-x-token (hooks/use-equal-memo offset-x-token)

        offset-y-token (get-subtoken token index :offsetY)
        offset-y-token (hooks/use-equal-memo offset-y-token)

        blur-token (get-subtoken token index :blur)
        blur-token (hooks/use-equal-memo blur-token)

        spread-token (get-subtoken token index :spreadX)
        spread-token (hooks/use-equal-memo spread-token)

        on-button-click
        (mf/use-fn
         (mf/deps index)
         (fn [event]
           (remove-shadow-block index event)))]

    [:div {:class (stl/css :shadow-block)
           :data-testid (str "shadow-input-fields-" index)}
     [:div {:class (stl/css :select-wrapper)}
      [:> select-composite* {:options [{:id "drop" :label "drop shadow" :icon i/drop-shadow}
                                       {:id "inner" :label "inner shadow" :icon i/inner-shadow}]
                             :aria-label (tr "workspace.tokens.shadow-inset")
                             :token inset-token
                             :tokens tokens
                             :index index
                             :name :inset}]
      (when show-button
        [:> icon-button* {:variant "ghost"
                          :type "button"
                          :aria-label (tr "workspace.tokens.shadow-remove-shadow")
                          :on-click on-button-click
                          :icon i/remove}])]
     [:div {:class (stl/css :inputs-wrapper)}
      [:div {:class (stl/css :input-row)}
       [:> color-input-indexed*
        {:placeholder (tr "workspace.tokens.token-value-enter")
         :aria-label (tr "workspace.tokens.color")
         :name :color
         :token color-token
         :index index
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> form-input-token-indexed*
        {:aria-label (tr "workspace.tokens.shadow-x")
         :icon i/character-x
         :placeholder (tr "workspace.tokens.shadow-x")
         :name :offsetX
         :token offset-x-token
         :index index
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> form-input-token-indexed*
        {:aria-label (tr "workspace.tokens.shadow-y")
         :icon i/character-y
         :placeholder (tr "workspace.tokens.shadow-y")
         :name :offsetY
         :token offset-y-token
         :index index
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> form-input-token-indexed*
        {:aria-label (tr "workspace.tokens.shadow-blur")
         :placeholder (tr "workspace.tokens.shadow-blur")
         :name :blur
         :slot-start (mf/html [:span {:class (stl/css :visible-label)} "Blur:"])
         :token blur-token
         :index index
         :tokens tokens}]]

      [:div {:class (stl/css :input-row)}
       [:> form-input-token-indexed*
        {:aria-label (tr "workspace.tokens.shadow-spread")
         :placeholder (tr "workspace.tokens.shadow-spread")
         :name :spread
         :slot-start (mf/html [:span {:class (stl/css :visible-label)} "Spread:"])
         :token spread-token
         :index index
         :tokens tokens}]]]]))

(mf/defc composite-form*
  [{:keys [token tokens remove-shadow-block] :as props}]
  (let [form
        (mf/use-ctx forms/context)

        length
        (-> form deref :data :value :shadows count)]

    (for [index (range length)]
      [:> shadow-formset* {:key index
                           :index index
                           :token token
                           :tokens tokens
                           :remove-shadow-block remove-shadow-block
                           :show-button (> length 1)}])))

(mf/defc reference-form*
  [{:keys [token tokens] :as props}]
  [:div {:class (stl/css :input-row-reference)}
   [:> form-input-token-composite*
    {:placeholder (tr "workspace.tokens.reference-composite-shadow")
     :aria-label (tr "labels.reference")
     :icon i/drop-shadow
     :name :reference
     :token token
     :tokens tokens}]])

(defn- make-schema
  [tokens-tree active-tab]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255
                 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/token-name-ref assoc
                             :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (cft/token-name-path-exists? % tokens-tree))]]]

     [:value
      [:map
       [:shadows {:optinal true}
        [:vector
         [:map
          ;; TODO: cambiar offsetX por offset-x
          [:offsetX {:optional true} [:maybe :string]]
          [:offsetY {:optional true} [:maybe :string]]
          [:blur {:optional true} [:maybe :string]]
          [:spread {:optional true} [:maybe :string]]
          [:color {:optional true} [:maybe :string]]
          [:color-result {:optional true} ::sm/any]
          [:inset {:optional true} [:maybe :boolean]]]]]
       (if (= active-tab :reference)
         [:reference {:optional false} ::sm/text]
         [:reference {:optional true} [:maybe :string]])]]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field [:value :reference]
          :error/fn #(tr "workspace.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (let [reference (get value :reference)]
         (if (and reference name)
           (not (cto/token-value-self-reference? name reference))
           true)))]

    [:fn {:error/fn (fn [_] "Must be a valid shadow or reference")
          :error/field :value}
     (fn [{:keys [value]}]
       (let [reference  (get value :reference)
             ref-valid? (and reference (not (str/blank? reference)))

             shadows (get value :shadows)
             ;; To be a valid shadow it must contain one on each valid values
             valid-composite-shadow?
             (and (seq shadows)
                  (every?
                   (fn [{:keys [offsetX offsetY blur spread color]}]
                     (and (not (str/blank? offsetX))
                          (not (str/blank? offsetY))
                          (not (str/blank? blur))
                          (not (str/blank? spread))
                          (not (str/blank? color))))
                   shadows))]

         (or ref-valid? valid-composite-shadow?)))]]))

(mf/defc form*
  [{:keys [token validate-token action is-create selected-token-set-id tokens-tree-in-selected-set] :as props}]

  (let [active-tab* (mf/use-state #(if (cft/is-reference? token) :reference :composite))
        active-tab (deref active-tab*)
        token
        (mf/with-memo [token]
          (or token
              (if-let [value (get token :value)]
                (cond
                  (string? value)
                  {:value {:reference value
                           :shadows   []}
                   :type :shadow}

                  (vector? value)
                  {:value {:reference nil
                           :shadows   value}
                   :type :shadow})
                {:type :shadow
                 :value {:reference nil
                         :shadows   [default-token-shadow]}})))

        token-type
        (get token :type)

        token-properties
        (dwta/get-token-properties token)

        token-title (str/lower (:title token-properties))

        ;; TODO: review
        show-button* (mf/use-state false)
        show-button (deref show-button*)

        tokens
        (mf/deref refs/workspace-active-theme-sets-tokens)

        tokens
        (mf/with-memo [tokens token]
          ;; Ensure that the resolved value uses the currently editing token
          ;; even if the name has been overriden by a token with the same name
          ;; in another set below.
          (cond-> tokens
            (and (:name token) (:value token))
            (assoc (:name token) token)))

        schema
        (mf/with-memo [tokens-tree-in-selected-set active-tab]
          (make-schema tokens-tree-in-selected-set active-tab))

        initial
        (mf/with-memo [token]
          (let [raw-value (:value token)

                value
                (cond
                  (string? raw-value)
                  {:reference raw-value
                   :shadows   []}

                  (vector? raw-value)
                  {:reference nil
                   :shadows   raw-value}

                  :else
                  {:reference nil
                   :shadows   [default-token-shadow]})]

            {:name        (:name token "")
             :description (:description token "")
             :value       value}))

        form
        (fm/use-form :schema schema
                     :initial initial)

        warning-name-change?
        (not= (get-in @form [:data :name])
              (:name initial))

        on-toggle-tab
        (mf/use-fn
         (mf/deps)
         (fn [new-tab]
           (let [new-tab (keyword new-tab)]
             (reset! active-tab* new-tab))))

        on-cancel
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)))

        on-delete-token
        (mf/use-fn
         (mf/deps selected-token-set-id token)
         (fn [e]
           (dom/prevent-default e)
           (modal/hide!)
           (st/emit! (dwtl/delete-token selected-token-set-id (:id token)))))

        handle-key-down-delete
        (mf/use-fn
         (mf/deps on-delete-token)
         (fn [e]
           (when (or (k/enter? e) (k/space? e))
             (on-delete-token e))))

        handle-key-down-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [e]
           (when (or (k/enter? e) (k/space? e))
             (on-cancel e))))

        on-add-shadow-block
        (mf/use-fn
         (fn []
           (swap! form  update-in [:data :value :shadows] conj default-token-shadow)))

        remove-shadow-block
        (mf/use-fn
         (fn [index event]
           (dom/prevent-default event)
           (swap! form update-in [:data :value :shadows] #(vec-remove index %))))

        on-submit
        (mf/use-fn
         (mf/deps validate-token token tokens token-type active-tab)
         (fn [form _event]
           (let [name (get-in @form [:clean-data :name])
                 description (get-in @form [:clean-data :description])
                 value       (get-in @form [:clean-data :value])]

             (->> (validate-token {:token-value (if (= active-tab :reference)
                                                  (:reference value)
                                                  (:shadows value))
                                   :token-name name
                                   :token-description description
                                   :prev-token token
                                   :tokens tokens})
                  (rx/subs!
                   (fn [valid-token]
                     (st/emit!
                      (if is-create
                        (dwtl/create-token (ctob/make-token {:name name
                                                             :type token-type
                                                             :value (:value valid-token)
                                                             :description description}))

                        (dwtl/update-token (:id token)
                                           {:name name
                                            :value (:value valid-token)
                                            :description description}))
                      (dwtp/propagate-workspace-tokens)
                      (modal/hide))))))))]

    [:> forms/form* {:class (stl/css :form-wrapper)
                     :form form
                     :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}

      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (if (= action "edit")
         (tr "workspace.tokens.edit-token" token-type)
         (tr "workspace.tokens.create-token" token-type))]

      [:div {:class (stl/css :input-row)}
       [:> forms/form-input* {:id "token-name"
                              :name :name
                              :label (tr "workspace.tokens.token-name")
                              :placeholder (tr "workspace.tokens.enter-token-name" token-title)
                              :max-length max-input-length
                              :variant "comfortable"
                              :auto-focus true}]

       (when (and warning-name-change? (= action "edit"))
         [:div {:class (stl/css :warning-name-change-notification-wrapper)}
          [:> context-notification*
           {:level :warning :appearance :ghost} (tr "workspace.tokens.warning-name-change")]])]

      [:div {:class (stl/css :title-bar)}
       [:div {:class (stl/css :title)} (tr "labels.shadow")]
       [:> icon-button* {:variant "ghost"
                         :type "button"
                         :aria-label (tr "workspace.tokens.shadow-add-shadow")
                         :on-click on-add-shadow-block
                         :icon i/add}]
       [:& radio-buttons {:class (stl/css :listing-options)
                          :selected (d/name active-tab)
                          :on-change on-toggle-tab
                          :name "reference-composite-tab"}
        [:& radio-button {:icon i/layers
                          :value "composite"
                          :title (tr "workspace.tokens.individual-tokens")
                          :id "composite-opt"}]
        [:& radio-button {:icon i/tokens
                          :value "reference"
                          :title (tr "workspace.tokens.use-reference")
                          :id "reference-opt"}]]]

      (if (= active-tab :composite)
        [:> composite-form* {:token token
                             :tokens tokens
                             :remove-shadow-block remove-shadow-block
                             :show-button show-button}]

        [:> reference-form* {:token token
                             :tokens tokens}])

      [:div {:class (stl/css :input-row)}
       [:> forms/form-input* {:id "token-description"
                              :name :description
                              :label (tr "workspace.tokens.token-description")
                              :placeholder (tr "workspace.tokens.token-description")
                              :max-length max-input-length
                              :variant "comfortable"
                              :is-optional true}]]

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

       [:> forms/form-submit* {:variant "primary"
                               :on-submit on-submit}
        (tr "labels.save")]]]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.typography
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
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.forms :as forms]
   [app.main.ui.workspace.tokens.management.create.combobox-token-fonts :refer [font-picker-composite-combobox*]]
   [app.main.ui.workspace.tokens.management.create.input-token :refer [input-token-composite*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc composite-form*
  [{:keys [token tokens] :as props}]
  (let [letter-spacing-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :letter-spacing
             :value (cto/join-font-family (get value :letter-spacing))}
            {:type :letter-spacing}))

        font-family-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-family
             :value (get value :font-family)}
            {:type :font-family}))

        font-size-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-size
             :value (get value :font-size)}
            {:type :font-size}))

        font-weight-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :font-weight
             :value (get value :font-weight)}
            {:type :font-weight}))

        ;; TODO: Review this type
        line-height-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :number
             :value (get value :line-height)}
            {:type :number}))

        text-case-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :text-case
             :value (get value :text-case)}
            {:type :text-case}))

        text-decoration-sub-token
        (mf/with-memo [token]
          (if-let [value (get token :value)]
            {:type :text-decoration
             :value (get value :text-decoration)}
            {:type :text-decoration}))]

    [:*
     [:div {:class (stl/css :input-row)}
      [:> font-picker-composite-combobox*
       {:icon i/text-font-family
        :placeholder (tr "workspace.tokens.token-font-family-value-enter")
        :aria-label  (tr "workspace.tokens.token-font-family-value")
        :name :font-family
        :token font-family-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Font Size"
        :icon i/text-font-size
        :placeholder (tr "workspace.tokens.font-size-value-enter")
        :name :font-size
        :token font-size-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Font Weight"
        :icon i/text-font-weight
        :placeholder (tr "workspace.tokens.font-weight-value-enter")
        :name :font-weight
        :token font-weight-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Line Height"
        :icon i/text-lineheight
        :placeholder (tr "workspace.tokens.line-height-value-enter")
        :name :line-height
        :token line-height-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Letter Spacing"
        :icon i/text-letterspacing
        :placeholder (tr "workspace.tokens.letter-spacing-value-enter-composite")
        :name :letter-spacing
        :token letter-spacing-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Text Case"
        :icon i/text-mixed
        :placeholder (tr "workspace.tokens.text-case-value-enter")
        :name :text-case
        :token text-case-sub-token
        :tokens tokens}]]
     [:div {:class (stl/css :input-row)}
      [:> input-token-composite*
       {:aria-label "Text Decoration"
        :icon i/text-underlined
        :placeholder (tr "workspace.tokens.text-decoration-value-enter")
        :name :text-decoration
        :token text-decoration-sub-token
        :tokens tokens}]]]))

(mf/defc reference-form*
  [{:keys [token tokens] :as props}]
  [:div {:class (stl/css :input-row)}
   [:> input-token-composite*
    {:placeholder (tr "workspace.tokens.reference-composite")
     :aria-label (tr "labels.reference")
     :icon i/text-typography
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
       [:font-family {:optional true} [:maybe :string]]
       [:font-size {:optional true} [:maybe :string]]
       [:font-weight {:optional true} [:maybe :string]]
       [:line-height {:optional true} [:maybe :string]]
       [:letter-spacing {:optional true} [:maybe :string]]
       [:text-case {:optional true} [:maybe :string]]
       [:text-decoration {:optional true} [:maybe :string]]
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

    [:fn {:error/field [:value :line-height]
          :error/fn #(tr "workspace.tokens.composite-line-height-needs-font-size")}
     (fn [{:keys [value]}]
       (let [line-heigh (get value :line-height)
             font-size (get value :font-size)]
         (if (and line-heigh (not font-size))
           false
           true)))]

    ;; This error does not shown on interface, it's just to avoid saving empty composite tokens
    ;; We don't need to translate it.
    [:fn {:error/fn (fn [_] "At least one composite field must be set")
          :error/field :value}
     (fn [attrs]
       (let [result (reduce-kv (fn [_ _ v]
                                 (if (str/empty? v)
                                   false
                                   (reduced true)))
                               false
                               (get attrs :value))]
         result))]]))

(mf/defc form*
  [{:keys [token validate-token action is-create selected-token-set-id tokens-tree-in-selected-set] :as props}]

  (let [token
        (mf/with-memo [token]
          (or token {:type :typography}))

        active-tab* (mf/use-state #(if (cft/is-reference? token) :reference :composite))
        active-tab (deref active-tab*)

        token-type
        (get token :type)

        token-properties
        (dwta/get-token-properties token)

        token-title (str/lower (:title token-properties))

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
          (let [value (:value token)
                processed-value
                (cond
                  (string? value)
                  {:reference value}

                  (map? value)
                  (let [value (cond-> value
                                (:font-family value)
                                (update :font-family cto/join-font-family))]
                    (select-keys value
                                 [:font-family
                                  :font-size
                                  :font-weight
                                  :line-height
                                  :letter-spacing
                                  :text-case
                                  :text-decoration]))
                  :else
                  {})]

            {:name        (:name token "")
             :value       processed-value
             :description (:description token "")}))

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

        on-submit
        (mf/use-fn
         (mf/deps validate-token token tokens token-type)
         (fn [form _event]
           (let [name (get-in @form [:clean-data :name])
                 description (get-in @form [:clean-data :description])
                 value       (get-in @form [:clean-data :value])]

             (->> (validate-token {:token-value (if (contains? value :reference)
                                                  (get value :reference)
                                                  value)
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
       [:div {:class (stl/css :title)} (tr "labels.typography")]
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
      [:div {:class (stl/css :inputs-wrapper)}
       (if (= active-tab :composite)
         [:> composite-form* {:token token
                              :tokens tokens}]

         [:> reference-form* {:token token
                              :tokens tokens}])]

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

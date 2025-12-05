;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.generic-form
  (:require-macros [app.main.style :as stl])
  (:require
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
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.validators :refer [default-validate-token]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- token-value-error-fn
  [{:keys [value]}]
  (when (or (str/empty? value)
            (str/blank? value))
    (tr "workspace.tokens.empty-input")))


(defn get-value-for-validator
  [active-tab value value-subfield form-type]

  (case form-type
    :indexed
    (if (= active-tab :reference)
      (:reference value)
      (value-subfield value))

    :composite
    (if (= active-tab :reference)
      (get value :reference)
      value)

    value))

(defn- default-make-schema
  [tokens-tree _]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/token-name-ref assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (cft/token-name-path-exists? % tokens-tree))]]]

     [:value [::sm/text {:error/fn token-value-error-fn}]]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field :value
          :error/fn #(tr "workspace.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (when (and name value)
         (nil? (cto/token-value-self-reference? name value))))]]))

(mf/defc form*
  [{:keys [token
           validator
           action
           is-create
           selected-token-set-id
           tokens-tree-in-selected-set
           token-type
           make-schema
           input-component
           initial
           type
           value-subfield
           input-value-placeholder] :as props}]

  (let [make-schema           (or make-schema default-make-schema)
        input-component (or input-component token.controls/input*)
        validate-token (or validator default-validate-token)

        active-tab* (mf/use-state #(if (cft/is-reference? token) :reference :composite))
        active-tab (deref active-tab*)

        on-toggle-tab
        (mf/use-fn
         (mf/deps)
         (fn [new-tab]
           (let [new-tab (keyword new-tab)]
             (reset! active-tab* new-tab))))

        token
        (mf/with-memo [token]
          (or token {:type token-type}))

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
          (or initial
              {:name (:name token "")
               :value (:value token "")
               :description (:description token "")}))

        form
        (fm/use-form :schema schema
                     :initial initial)

        warning-name-change?
        (not= (get-in @form [:data :name])
              (:name initial))

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
         (mf/deps validate-token token tokens token-type value-subfield type active-tab)
         (fn [form _event]
           (let [name (get-in @form [:clean-data :name])
                 description (get-in @form [:clean-data :description])
                 value (get-in @form [:clean-data :value])
                 value-for-validation (get-value-for-validator active-tab value value-subfield type)]
             (->> (validate-token {:token-value value-for-validation
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

    [:> fc/form* {:class (stl/css :form-wrapper)
                  :form form
                  :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}

      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (if (= action "edit")
         (tr "workspace.tokens.edit-token" token-type)
         (tr "workspace.tokens.create-token" token-type))]

      [:div {:class (stl/css :input-row)}
       [:> fc/form-input* {:id "token-name"
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

      [:div {:class (stl/css :input-row)}
       [:> input-component
        {:placeholder (or input-value-placeholder (tr "workspace.tokens.token-value-enter"))
         :label (tr "workspace.tokens.token-value")
         :name :value
         :form form
         :token token
         :tokens tokens
         :tab active-tab
         :subfield value-subfield
         :toggle on-toggle-tab}]]

      [:div {:class (stl/css :input-row)}
       [:> fc/form-input* {:id "token-description"
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

       [:> fc/form-submit* {:variant "primary"
                            :on-submit on-submit}
        (tr "labels.save")]]]]))

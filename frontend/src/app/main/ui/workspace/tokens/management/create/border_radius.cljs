;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.border-radius
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
   [app.main.ui.workspace.tokens.management.create.form-input-token :refer [form-input-token*]]
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

(defn- make-schema
  [tokens-tree]
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

     [:resolved-value ::sm/any]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field :value
          :error/fn #(tr "workspace.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (when (and name value)
         (nil? (cto/token-value-self-reference? name value))))]]))

(mf/defc form*
  [{:keys [token validate-token action is-create selected-token-set-id tokens-tree-in-selected-set] :as props}]

  (let [token
        (mf/with-memo [token]
          (or token {:type :border-radius}))

        token-type
        (get token :type)

        token-properties
        (dwta/get-token-properties token)

        token-title (str/lower (:title token-properties))

        tokens
        (mf/deref refs/workspace-active-theme-sets-tokens)

        tokens
        (mf/with-memo [tokens]
          ;; Ensure that the resolved value uses the currently editing token
          ;; even if the name has been overriden by a token with the same name
          ;; in another set below.
          (cond-> tokens
            (and (:name token) (:value token))
            (assoc (:name token) token)))

        schema
        (mf/with-memo [tokens-tree-in-selected-set]
          (make-schema tokens-tree-in-selected-set))

        initial
        (mf/with-memo [token]
          {:name (:name token "")
           :value (:value token "")
           :description (:description token "")})

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
         (mf/deps validate-token token tokens token-type)
         (fn [form _event]
           (let [name (get-in @form [:clean-data :name])
                 description (get-in @form [:clean-data :description])
                 value (get-in @form [:clean-data :value])]
             (->> (validate-token {:token-value value
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
       (tr "workspace.tokens.create-token" token-type)]

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
       [:> form-input-token*
        {:placeholder (tr "workspace.tokens.token-value-enter")
         :label (tr "workspace.tokens.token-value")
         :name :value
         :token token
         :tokens tokens}]]

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

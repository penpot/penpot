;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.border-radius
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.modal :as modal]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as forms]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- resolve-value
  [tokens prev-token value]
  (let [token
        {:value value
         :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"}

        tokens
        (-> tokens
            ;; Remove previous token when renaming a token
            (dissoc (:name prev-token))
            (update (:name token) #(ctob/make-token (merge % prev-token token))))]

    (->> tokens
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

;; Wrappers for DS components

(mf/defc form-input*
  [{:keys [name] :rest props}]

  (let [form       (mf/use-ctx forms/form-ctx)
        input-name name

        touched?   (and (contains? (:data @form) input-name)
                        (get-in @form [:touched input-name]))
        error      (get-in @form [:errors input-name])

        value      (get-in @form [:data input-name] "")

        on-change
        (mf/use-fn
         (mf/deps input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (swap! form assoc-in [:touched input-name] true)
             (swap! form (fn [state]
                           (-> state
                               (assoc-in [:data input-name] value)
                               (update :errors dissoc input-name))))
             (fm/on-input-change form input-name value true))))


        props
        (mf/spread-props props {:on-change on-change
                                :default-value value})

        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    [:> input* props]))

(mf/defc form-input-token*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx forms/form-ctx)
        input-name name

        touched?   (and (contains? (:data @form) input-name)
                        (get-in @form [:touched input-name]))

        error      (get-in @form [:errors input-name])

        value      (get-in @form [:data input-name] "")

        resolve-stream
        (mf/with-memo [token]
          (let [subject (rx/behavior-subject (:value token))]
            subject))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (swap! form assoc-in [:touched input-name] true)
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props {:on-change on-change
                                :default-value value
                                :hint-message (:message hint)
                                :hint-type (:type hint)})

        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (if error
                                    (do
                                      (swap! form assoc-in [:errors input-name] {:message error})
                                      (swap! form assoc-in [:errors :resolved-value] {:message error})
                                      (swap! form update :data dissoc :resolved-value)
                                      (reset! hint* {}))
                                    (let [message (tr "workspace.tokens.resolved-value" value)]
                                      (swap! form update :errors dissoc input-name :resolved-value)
                                      (swap! form update :data assoc :resolved-value value)
                                      (reset! hint* {:message message :type "hint"}))))))]

        (fn []
          (rx/dispose! subs))))

    [:> input* props]))

(mf/defc form-submit*
  [{:keys [disabled] :rest props}]

  (let [form      (mf/use-ctx forms/form-ctx)
        disabled? (or (and (some? form)
                           (or (not (:valid @form))
                               (seq (:external-errors @form))))
                      (true? disabled))
        props
        (mf/spread-props props {:disabled disabled?
                                :type "submit"})]

    [:> button* props]))

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

     [:value ::sm/text]

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
        (cond-> (mf/deref refs/workspace-active-theme-sets-tokens)
          ;; Ensure that the resolved value uses the currently editing token
          ;; even if the name has been overriden by a token with the same name
          ;; in another set below.
          (and (:name token) (:value token))
          (assoc (:name token) token))

        schema
        (mf/with-memo [tokens-tree-in-selected-set]
          (make-schema tokens-tree-in-selected-set))

        initial
        (mf/with-memo [token]
          (if token
            {:name (:name token)
             :value (:value token)
             :resolved-value (:value token)
             :description (:description token)}

            {:name ""
             :value ""
             :description ""}))

        form
        (fm/use-form :schema schema
                     :initial initial)

        warning-name-change?
        (not= (get-in @form [:clean-data :name])
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
                        (dwtl/create-token {:name name
                                            :type token-type
                                            :value (:value valid-token)
                                            :description description})

                        (dwtl/update-token (:id token)
                                           {:name name
                                            :value (:value valid-token)
                                            :description description}))
                      (dwtp/propagate-workspace-tokens)
                      (modal/hide))))))))

        handle-key-down-save
        (mf/use-fn
         (mf/deps on-submit form)
         (fn [e]
           (when (or (k/enter? e) (k/space? e))
             (dom/prevent-default e)
             (on-submit form e))))]

    [:> forms/form* {:class (stl/css :form-wrapper)
                     :form form
                     :on-submit on-submit}
     [:div {:class (stl/css :token-rows)}

      [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :form-modal-title)}
       (tr "workspace.tokens.create-token" token-type)]

      [:div {:class (stl/css :input-row)}
       [:> form-input* {:id "token-name"
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
       [:> form-input* {:id "token-name"
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

       [:> form-submit* {:variant "primary"
                         :on-key-down handle-key-down-save}
        (tr "labels.save")]]]]))

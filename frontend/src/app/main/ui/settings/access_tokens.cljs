;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.access-tokens
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-ref
  (l/derived :access-tokens st/state))

(def token-created-ref
  (l/derived :access-token-created st/state))

(s/def ::name ::us/not-empty-string)
(s/def ::expiration-date ::us/not-empty-string)
(s/def ::access-token-form
  (s/keys :req-un [::name ::expiration-date]))

(defn- name-validator
  [errors data]
  (let [name (:name data)]
    (cond-> errors
      (str/blank? name)
      (assoc :name {:message (tr "dashboard.access-tokens.errors-required-name")}))))

(def initial-data
  {:name "" :expiration-date "never"})

(mf/defc access-token-modal
  {::mf/register modal/components
   ::mf/register-as :access-token}
  []
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        form    (fm/use-form
                 :initial initial-data
                 :spec ::access-token-form
                 :validators [name-validator
                              (fm/validate-not-empty :name (tr "auth.name.not-all-space"))
                              (fm/validate-length :name fm/max-length-allowed (tr "auth.name.too-long"))])
        created  (mf/deref token-created-ref)
        created? (mf/use-state false)
        locale   (mf/deref i18n/locale)

        on-success
        (mf/use-fn
         (mf/deps created)
         (fn [_]
           (let [message (tr "dashboard.access-tokens.create.success")]
             (st/emit! (du/fetch-access-tokens)
                       (dm/success message)
                       (reset! created? true)))))

        on-close
        (mf/use-fn
         (mf/deps created)
         (fn [_]
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-error
        (mf/use-fn
         (fn [_]
           (st/emit! (dm/error (tr "errors.generic"))
                     (modal/hide))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [cdata      (:clean-data @form)
                 mdata      {:on-success (partial on-success form)
                             :on-error   (partial on-error form)}
                 expiration (:expiration-date cdata)
                 params     (cond-> {:name       (:name cdata)
                                     :perms      (:perms cdata)}
                              (not= "never" expiration) (assoc :expiration expiration))]
             (st/emit! (du/create-access-token
                        (with-meta params mdata))))))

        copy-token
        (mf/use-fn
         (mf/deps created)
         (fn [event]
           (dom/prevent-default event)
           (wapi/write-to-clipboard (:token created))
           (st/emit! (dm/show {:type :info
                               :content (tr "dashboard.access-tokens.copied-success")
                               :timeout 1000}))))]

    (if new-css-system
      [:div {:class (stl/css :modal-overlay)}
       [:div {:class (stl/css :modal-container)}
        [:& fm/form {:form form :on-submit on-submit}

         [:div {:class (stl/css :modal-header)}
          [:h2 {:class (stl/css :modal-title)} (tr "modals.create-access-token.title")]

          [:button {:class (stl/css :modal-close-btn)
                    :on-click on-close} i/close-refactor]]

         [:div {:class (stl/css :modal-content)}
          [:div {:class (stl/css :fields-row)}
           [:& fm/input {:type "text"
                         :auto-focus? true
                         :form form
                         :name :name
                         :disabled @created?
                         :label (tr "modals.create-access-token.name.label")
                         :show-success? true
                         :placeholder (tr "modals.create-access-token.name.placeholder")}]]

          [:div {:class (stl/css :fields-row)}
           [:div {:class (stl/css :select-title)}  (tr "modals.create-access-token.expiration-date.label")]
           [:& fm/select {:options [{:label (tr "dashboard.access-tokens.expiration-never")    :value "never" :key "never"}
                                    {:label (tr "dashboard.access-tokens.expiration-30-days")  :value "720h"  :key "720h"}
                                    {:label (tr "dashboard.access-tokens.expiration-60-days")  :value "1440h" :key "1440h"}
                                    {:label (tr "dashboard.access-tokens.expiration-90-days")  :value "2160h" :key "2160h"}
                                    {:label (tr "dashboard.access-tokens.expiration-180-days") :value "4320h" :key "4320h"}]
                          :default "never"
                          :disabled @created?
                          :name :expiration-date}]
           (when @created?
             [:span.token-created-info
              (if (:expires-at created)
                (tr "dashboard.access-tokens.token-will-expire" (dt/format-date-locale (:expires-at created) {:locale locale}))
                (tr "dashboard.access-tokens.token-will-not-expire"))])]

          [:div {:class (stl/css :fields-row)}
           (when @created?
             [:div {:class (stl/css :custon-input-wrapper)}
              [:input {:type "text"
                       :value (:token created "")
                       :class (stl/css :custom-input-token)
                       :placeholder (tr "modals.create-access-token.token")
                       :read-only true}]
              [:button {:title (tr "modals.create-access-token.copy-token")
                        :class (stl/css :copy-btn)
                        :on-click copy-token}
               i/clipboard-refactor]])
           #_(when @created?
             [:button {:class (stl/css :copy-btn)
                       :title (tr "modals.create-access-token.copy-token")
                       :on-click copy-token}
              [:span {:class (stl/css :token-value)}(:token created "")]
              [:span {:class (stl/css :icon)}
               i/clipboard-refactor]])]]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}

           (if @created?
             [:input {:class (stl/css :cancel-button)
                      :type "button"
                      :value (tr "labels.close")
                      :on-click #(modal/hide!)}]
             [:*
              [:input {:class (stl/css :cancel-button)
                       :type "button"
                       :value (tr "labels.cancel")
                       :on-click #(modal/hide!)}]
              [:> fm/submit-button*
               {:label (tr "modals.create-access-token.submit-label")}]])]]]]]


      [:div.modal-overlay
       [:div.modal-container.access-tokens-modal
        [:& fm/form {:form form :on-submit on-submit}

         [:div.modal-header
          [:div.modal-header-title
           [:h2 (tr "modals.create-access-token.title")]]

          [:div.modal-close-button
           {:on-click on-close} i/close]]

         [:div.modal-content.generic-form
          [:div.fields-container
           [:div.fields-row
            [:& fm/input {:type "text"
                          :auto-focus? true
                          :form form
                          :name :name
                          :disabled @created?
                          :label (tr "modals.create-access-token.name.label")
                          :placeholder (tr "modals.create-access-token.name.placeholder")}]]

           [:div.fields-row
            [:& fm/select {:options [{:label (tr "dashboard.access-tokens.expiration-never")    :value "never" :key "never"}
                                     {:label (tr "dashboard.access-tokens.expiration-30-days")  :value "720h"  :key "720h"}
                                     {:label (tr "dashboard.access-tokens.expiration-60-days")  :value "1440h" :key "1440h"}
                                     {:label (tr "dashboard.access-tokens.expiration-90-days")  :value "2160h" :key "2160h"}
                                     {:label (tr "dashboard.access-tokens.expiration-180-days") :value "4320h" :key "4320h"}]
                           :label (tr "modals.create-access-token.expiration-date.label")
                           :default "never"
                           :disabled @created?
                           :name :expiration-date}]
            (when @created?
              [:span.token-created-info
               (if (:expires-at created)
                 (tr "dashboard.access-tokens.token-will-expire" (dt/format-date-locale (:expires-at created) {:locale locale}))
                 (tr "dashboard.access-tokens.token-will-not-expire"))])]

           [:div.fields-row.access-token-created
            (when @created?
              [:div.custom-input.with-icon
               [:input {:type "text"
                        :value (:token created "")
                        :placeholder (tr "modals.create-access-token.token")
                        :read-only true}]
               [:button.help-icon {:title (tr "modals.create-access-token.copy-token")
                                   :on-click copy-token}
                i/copy]])]]]

         [:div.modal-footer
          [:div.action-buttons
           (if @created?
             [:input.cancel-button
              {:type "button"
               :value (tr "labels.close")
               :on-click #(modal/hide!)}]
             [:*
              [:input.cancel-button
               {:type "button"
                :value (tr "labels.cancel")
                :on-click #(modal/hide!)}]
              [:> fm/submit-button*
               {:label (tr "modals.create-access-token.submit-label")}]])]]]]])))

(mf/defc access-tokens-hero
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        on-click (mf/use-fn #(st/emit! (modal/show :access-token {})))]
    (if new-css-system
      [:div {:class (stl/css :access-tokens-hero-container)}
       [:div {:class (stl/css :access-tokens-hero)}
        [:div {:class (stl/css :desc)}
         [:h2 (tr "dashboard.access-tokens.personal")]
         [:p (tr "dashboard.access-tokens.personal.description")]]

        [:button
         {:class (stl/css :btn-primary)
          :on-click on-click}
         [:span (tr "dashboard.access-tokens.create")]]]]

      ;; OLD
      [:div.access-tokens-hero-container
       [:div.access-tokens-hero
        [:div.desc
         [:h2 (tr "dashboard.access-tokens.personal")]
         [:p (tr "dashboard.access-tokens.personal.description")]]

        [:button.btn-primary
         {:on-click on-click}
         [:span (tr "dashboard.access-tokens.create")]]]])))

(mf/defc access-token-actions
  [{:keys [on-delete]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        local    (mf/use-state {:menu-open false})
        show?    (:menu-open @local)
        options  (mf/with-memo [on-delete]
                   [{:option-name    (tr "labels.delete")
                     :id             "access-token-delete"
                     :option-handler on-delete}])

        menu-ref (mf/use-ref)

        on-menu-close
        (mf/use-fn #(swap! local assoc :menu-open false))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (swap! local assoc :menu-open true)))

        on-keydown
        (mf/use-callback
         (mf/deps on-menu-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-menu-click event))))]

    (if new-css-system
      [:div
       {:class (stl/css :icon)
        :tab-index "0"
        :ref menu-ref
        :on-click on-menu-click
        :on-key-down on-keydown}
       i/actions
       [:& context-menu-a11y
        {:on-close on-menu-close
         :show show?
         :fixed? true
         :min-width? true
         :top "auto"
         :left "auto"
         :options options}]]

      ;; OLD
      [:div.icon
       {:tab-index "0"
        :ref menu-ref
        :on-click on-menu-click
        :on-key-down (fn [event]
                       (when (kbd/enter? event)
                         (dom/stop-propagation event)
                         (on-menu-click event)))}
       i/actions
       [:& context-menu-a11y
        {:on-close on-menu-close
         :show show?
         :fixed? true
         :min-width? true
         :top "auto"
         :left "auto"
         :options options}]])))

(mf/defc access-token-item
  {::mf/wrap [mf/memo]}
  [{:keys [token] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        locale      (mf/deref i18n/locale)
        expires-at  (:expires-at token)
        expires-txt (some-> expires-at (dt/format-date-locale {:locale locale}))
        expired?    (and (some? expires-at) (> (dt/now) expires-at))

        delete-fn
        (mf/use-fn
         (mf/deps token)
         (fn []
           (let [params {:id (:id token)}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata))))))

        on-delete
        (mf/use-fn
         (mf/deps delete-fn)
         (fn []
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-acces-token.title")
                       :message (tr "modals.delete-acces-token.message")
                       :accept-label (tr "modals.delete-acces-token.accept")
                       :on-accept delete-fn}))))]

    (if new-css-system
      [:div {:class (stl/css :table-row)}
       [:div {:class (stl/css :table-field :name)}
        (str (:name token))]

       [:div {:class (stl/css :table-field :expiration-date)}
        [:span {:class (stl/css-case :expired expired? :content true)} 
         (cond
           (nil? expires-at) (tr "dashboard.access-tokens.no-expiration")
           expired? (tr "dashboard.access-tokens.expired-on" expires-txt)
           :else (tr "dashboard.access-tokens.expires-on" expires-txt))]]
       [:div {:class (stl/css :table-field :actions)}
        [:& access-token-actions
         {:on-delete on-delete}]]]

      ;; OLD
      [:div.table-row
       [:div.table-field.name
        (str (:name token))]
       [:div.table-field.expiration-date
        [:span.content {:class (when expired? "expired")}
         (cond
           (nil? expires-at) (tr "dashboard.access-tokens.no-expiration")
           expired? (tr "dashboard.access-tokens.expired-on" expires-txt)
           :else (tr "dashboard.access-tokens.expires-on" expires-txt))]]
       [:div.table-field.actions
        [:& access-token-actions
         {:on-delete on-delete}]]])))

(mf/defc access-tokens-page
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        tokens (mf/deref tokens-ref)]
    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.access-tokens"))
      (st/emit! (du/fetch-access-tokens)))

    (if new-css-system
      [:div {:class (stl/css :dashboard-access-tokens)}
       [:div
        [:& access-tokens-hero]
        (if (empty? tokens)
          [:div {:class (stl/css :access-tokens-empty)}
           [:div (tr "dashboard.access-tokens.empty.no-access-tokens")]
           [:div (tr "dashboard.access-tokens.empty.add-one")]]
          [:div {:class (stl/css :dashboard-table)}
           [:div {:class (stl/css :table-rows)}
            (for [token tokens]
              [:& access-token-item {:token token :key (:id token)}])]])]]

      ;; OLD
      [:div.dashboard-access-tokens
       [:div
        [:& access-tokens-hero]
        (if (empty? tokens)
          [:div.access-tokens-empty
           [:div (tr "dashboard.access-tokens.empty.no-access-tokens")]
           [:div (tr "dashboard.access-tokens.empty.add-one")]]
          [:div.dashboard-table
           [:div.table-rows
            (for [token tokens]
              [:& access-token-item {:token token :key (:id token)}])]])]])))


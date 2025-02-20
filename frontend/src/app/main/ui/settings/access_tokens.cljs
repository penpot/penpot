;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.access-tokens
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private clipboard-icon
  (i/icon-xref :clipboard (stl/css :clipboard-icon)))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(def ^:private menu-icon
  (i/icon-xref :menu (stl/css :menu-icon)))

(def tokens-ref
  (l/derived :access-tokens st/state))

(def token-created-ref
  (l/derived :access-token-created st/state))

(def ^:private schema:form
  [:map {:title "AccessTokenForm"}
   [:name [::sm/text {:max 250}]]
   [:expiration-date [::sm/text {:max 250}]]])

(def initial-data
  {:name "" :expiration-date "never"})

(mf/defc access-token-modal
  {::mf/register modal/components
   ::mf/register-as :access-token}
  []
  (let [form    (fm/use-form
                 :initial initial-data
                 :schema schema:form)

        created  (mf/deref token-created-ref)
        created? (mf/use-state false)
        locale   (mf/deref i18n/locale)

        on-success
        (mf/use-fn
         (mf/deps created)
         (fn [_]
           (let [message (tr "dashboard.access-tokens.create.success")]
             (st/emit! (du/fetch-access-tokens)
                       (ntf/success message)
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
           (st/emit! (ntf/error (tr "errors.generic"))
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
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (tr "dashboard.access-tokens.copied-success")
                                :timeout 7000}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form :on-submit on-submit}

       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)} (tr "modals.create-access-token.title")]

        [:button {:class (stl/css :modal-close-btn)
                  :on-click on-close}
         close-icon]]

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
         [:div {:class (stl/css :select-title)}
          (tr "modals.create-access-token.expiration-date.label")]
         [:& fm/select {:options [{:label (tr "dashboard.access-tokens.expiration-never")    :value "never" :key "never"}
                                  {:label (tr "dashboard.access-tokens.expiration-30-days")  :value "720h"  :key "720h"}
                                  {:label (tr "dashboard.access-tokens.expiration-60-days")  :value "1440h" :key "1440h"}
                                  {:label (tr "dashboard.access-tokens.expiration-90-days")  :value "2160h" :key "2160h"}
                                  {:label (tr "dashboard.access-tokens.expiration-180-days") :value "4320h" :key "4320h"}]
                        :default "never"
                        :disabled @created?
                        :name :expiration-date}]
         (when @created?
           [:span {:class (stl/css :token-created-info)}
            (if (:expires-at created)
              (tr "dashboard.access-tokens.token-will-expire" (dt/format-date-locale (:expires-at created) {:locale locale}))
              (tr "dashboard.access-tokens.token-will-not-expire"))])]

        [:div {:class (stl/css :fields-row)}
         (when @created?
           [:div {:class (stl/css :custon-input-wrapper)}
            [:input {:type "text"
                     :value (:token created "")
                     :class (stl/css :custom-input-token)
                     :read-only true}]
            [:button {:title (tr "modals.create-access-token.copy-token")
                      :class (stl/css :copy-btn)
                      :on-click copy-token}
             clipboard-icon]])
         #_(when @created?
             [:button {:class (stl/css :copy-btn)
                       :title (tr "modals.create-access-token.copy-token")
                       :on-click copy-token}
              [:span {:class (stl/css :token-value)} (:token created "")]
              [:span {:class (stl/css :icon)}
               i/clipboard]])]]

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
             {:large? false :label (tr "modals.create-access-token.submit-label")}]])]]]]]))

(mf/defc access-tokens-hero
  []
  (let [on-click (mf/use-fn #(st/emit! (modal/show :access-token {})))]
    [:div {:class (stl/css :access-tokens-hero)}
     [:h2 {:class (stl/css :hero-title)} (tr "dashboard.access-tokens.personal")]
     [:p {:class (stl/css :hero-desc)} (tr "dashboard.access-tokens.personal.description")]

     [:button {:class (stl/css :hero-btn)
               :on-click on-click}
      (tr "dashboard.access-tokens.create")]]))

(mf/defc access-token-actions
  [{:keys [on-delete]}]
  (let [local    (mf/use-state {:menu-open false})
        show?    (:menu-open @local)
        options  (mf/with-memo [on-delete]
                   [{:name    (tr "labels.delete")
                     :id      "access-token-delete"
                     :handler on-delete}])

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

    [:button {:class (stl/css :menu-btn)
              :tab-index "0"
              :ref menu-ref
              :on-click on-menu-click
              :on-key-down on-keydown}
     menu-icon
     [:> context-menu*
      {:on-close on-menu-close
       :show show?
       :fixed true
       :min-width true
       :top "auto"
       :left "auto"
       :options options}]]))

(mf/defc access-token-item
  {::mf/wrap [mf/memo]}
  [{:keys [token] :as props}]
  (let [locale      (mf/deref i18n/locale)
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

    [:div {:class (stl/css :table-row)}
     [:div {:class (stl/css :table-field :field-name)}
      (str (:name token))]

     [:div {:class (stl/css-case :expiration-date true
                                 :expired expired?)}
      (cond
        (nil? expires-at) (tr "dashboard.access-tokens.no-expiration")
        expired? (tr "dashboard.access-tokens.expired-on" expires-txt)
        :else (tr "dashboard.access-tokens.expires-on" expires-txt))]
     [:div {:class (stl/css :table-field :actions)}
      [:& access-token-actions
       {:on-delete on-delete}]]]))

(mf/defc access-tokens-page
  []
  (let [tokens (mf/deref tokens-ref)]
    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.access-tokens"))
      (st/emit! (du/fetch-access-tokens)))

    [:div {:class (stl/css :dashboard-access-tokens)}
     [:& access-tokens-hero]
     (if (empty? tokens)
       [:div {:class (stl/css :access-tokens-empty)}
        [:div (tr "dashboard.access-tokens.empty.no-access-tokens")]
        [:div (tr "dashboard.access-tokens.empty.add-one")]]
       [:div {:class (stl/css :dashboard-table)}
        [:div {:class (stl/css :table-rows)}
         (for [token tokens]
           [:& access-token-item {:token token :key (:id token)}])]])]))


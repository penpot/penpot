;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.integrations
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.shared.notification-pill :refer [notification-pill*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.forms :as fc]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-ref
  (l/derived :access-tokens st/state))

(def token-created-ref
  (l/derived :access-token-created st/state))

(def mcp-server-url "https://mcp.penpot.dev")
(def mcp-server-tech-guide "https://help.penpot.app/technical-guide/")

(def notification-timeout 7000)

(def ^:private schema:form
  [:map {:title "AccessTokenForm"}
   [:name [::sm/text {:max 250}]]
   [:expiration-date [::sm/text {:max 250}]]])

(def initial-data
  {:name ""
   :expiration-date "never"})

(mf/defc create-token-modal
  {::mf/register modal/components
   ::mf/register-as :create-token}
  [{:keys [token-type title-create title-created notification-create remove-token-id]}]
  (let [form    (fm/use-form
                 :initial initial-data
                 :schema schema:form)

        created  (mf/deref token-created-ref)
        created? (mf/use-state false)

        on-success
        (mf/use-fn
         (mf/deps created)
         (fn []
           (when (some? remove-token-id)
             (st/emit! (du/delete-access-token {:id remove-token-id})))
           (st/emit! (du/fetch-access-tokens)
                     (ntf/success (tr "dashboard.integrations.notification.success.created"))
                     (reset! created? true))))

        on-close
        (mf/use-fn
         (mf/deps created)
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-error
        (mf/use-fn
         (fn []
           (st/emit! (ntf/error (tr "errors.generic"))
                     (modal/hide))))

        on-submit
        (mf/use-fn
         (fn [form]
           (let [cdata      (:clean-data @form)
                 mdata      {:on-success (partial on-success form)
                             :on-error   (partial on-error form)}
                 expiration (:expiration-date cdata)
                 params     (cond-> {:name  (:name cdata)
                                     :perms (:perms cdata)}
                              (not= "never" expiration) (assoc :expiration expiration)
                              (some? token-type)        (assoc :type token-type))]
             (st/emit! (du/create-access-token (with-meta params mdata))))))

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps created)
         (fn [event]
           (dom/prevent-default event)
           (clipboard/to-clipboard (:token created))
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (tr "dashboard.integrations.notification.success.copied")
                                :timeout notification-timeout}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:div {:class (stl/css :modal-form)}
         [:> text* {:as "h2"
                    :typography t/headline-large
                    :class (stl/css :color-primary)}
          title-created]

         [:> notification-pill* {:level :info
                                 :type :context}
          (tr "modals.integrations.info.non-recuperable")]

         [:div {:class (stl/css :modal-content)}
          [:div {:class (stl/css :modal-token)}
           [:> input* {:type "text"
                       :default-value (:token created "")
                       :read-only true}]
           [:div {:class (stl/css :modal-token-button)}
            [:> icon-button* {:variant "secondary"
                              :aria-label (tr "modals.integrations.copy-token")
                              :on-click on-copy-to-clipboard
                              :icon i/clipboard}]]]

          [:> text* {:as "div"
                     :typography t/body-small
                     :class (stl/css :color-secondary)}
           (if (:expires-at created)
             (tr "modals.integrations.token-will-expire" (ct/format-inst (:expires-at created) "PPP"))
             (tr "modals.integrations.token-will-not-expire"))]]

         [:div {:class (stl/css :modal-footer)}
          [:> button* {:variant "secondary"
                       :on-click modal/hide!}
           (tr "labels.close")]]]

        [:> fc/form* {:form form
                      :class (stl/css :modal-form)
                      :on-submit on-submit}

         [:> text* {:as "h2"
                    :typography t/headline-large
                    :class (stl/css :color-primary)}
          title-create]

         (when (some? notification-create)
           [:> notification-pill* {:level :info
                                   :type :context}
            notification-create])

         [:div {:class (stl/css :modal-content)}
          [:> fc/form-input* {:type "text"
                              :auto-focus? true
                              :form form
                              :name :name
                              :label (tr "modals.integrations.name.label")
                              :placeholder (tr "modals.integrations.name.placeholder")}]]

         [:div {:class (stl/css :modal-content)}
          [:> text* {:as "label"
                     :typography t/body-small
                     :for :expiration-date
                     :class (stl/css :color-primary)}
           (tr "modals.integrations.expiration-date.label")]
          [:> fc/form-select* {:options [{:label (tr "modals.integrations.expiration-never")    :value "never" :id "never"}
                                         {:label (tr "modals.integrations.expiration-30-days")  :value "720h"  :id "720h"}
                                         {:label (tr "modals.integrations.expiration-60-days")  :value "1440h" :id "1440h"}
                                         {:label (tr "modals.integrations.expiration-90-days")  :value "2160h" :id "2160h"}
                                         {:label (tr "modals.integrations.expiration-180-days") :value "4320h" :id "4320h"}]
                               :default-selected "never"
                               :name :expiration-date}]]

         [:div {:class (stl/css :modal-footer)}
          [:> button* {:variant "secondary"
                       :on-click modal/hide!}
           (tr "labels.cancel")]
          [:> fc/form-submit* {:variant "primary"}
           title-create]]])]]))

(mf/defc token-item*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [name expires-at on-delete]}]
  (let [expires-txt (some-> expires-at (ct/format-inst "PPP"))
        expired?    (and (some? expires-at) (> (ct/now) expires-at))

        menu-open*  (mf/use-state false)
        menu-open?  (deref menu-open*)

        handle-menu-close
        (mf/use-fn
         #(reset! menu-open* false))

        handle-menu-click
        (mf/use-fn
         #(reset! menu-open* (not menu-open?)))

        handle-open-confirm-modal
        (mf/use-fn
         (mf/deps on-delete)
         (fn []
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "modals.integrations.delete-token.title")
                                  :message (tr "modals.integrations.delete-token.message")
                                  :accept-label (tr "modals.integrations.delete-token.accept")
                                  :on-accept on-delete}))))

        options
        (mf/with-memo [on-delete]
          [{:name    (tr "labels.delete")
            :id      "token-delete"
            :handler handle-open-confirm-modal}])]

    [:div {:class (stl/css :item)}
     [:> text* {:as "div"
                :typography t/body-medium
                :title name
                :class (stl/css :item-title)}
      name]

     [:> text* {:as "div"
                :typography t/body-small
                :class (stl/css-case :item-subtitle true
                                     :warning expired?)}
      (cond
        (nil? expires-at) (tr "modals.integrations.no-expiration")
        expired?          (tr "modals.integrations.expired-on" expires-txt)
        :else             (tr "modals.integrations.expires-on" expires-txt))]

     [:div {:class (stl/css :item-actions)}
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :item-button)
                        :aria-pressed menu-open?
                        :aria-label (tr "labels.options")
                        :on-click handle-menu-click
                        :icon i/menu}]
      [:> context-menu* {:on-close handle-menu-close
                         :show menu-open?
                         :fixed true
                         :min-width true
                         :top "auto"
                         :left "auto"
                         :options options}]]]))

(mf/defc mcp-server-section*
  {::mf/private true}
  []
  (let [tokens  (mf/deref tokens-ref)
        profile (mf/deref refs/profile)

        mcp-token   (some #(when (= (:type %) "mcp") %) tokens)
        mcp-active? (d/nilv (-> profile :props :mcp-status) false)

        expires-at  (:expires-at mcp-token)
        expired?    (and (some? expires-at) (> (ct/now) expires-at))

        tooltip-id
        (mf/use-id)

        handle-mcp-status-change
        (mf/use-fn
         (mf/deps tokens)
         (fn [mcp-status]
           (st/emit! (du/update-profile-props {:mcp-status mcp-status}))
           (if (true? mcp-status)
             (if (nil? mcp-token)
               (st/emit! (modal/show {:type :create-token
                                      :token-type "mcp"
                                      :title-create (tr "modals.integrations.mcp-create.title")
                                      :title-created (tr "modals.integrations.mcp-create.title.created")}))
               (st/emit! (ntf/show {:level :info
                                    :type :toast
                                    :content (tr "dashboard.integrations.notification.success.mcp-server-enabled")
                                    :timeout notification-timeout})))

             (st/emit! (ntf/show {:level :info
                                  :type :toast
                                  :content (tr "dashboard.integrations.notification.success.mcp-server-disabled")
                                  :timeout notification-timeout})))))

        handle-delete
        (mf/use-fn
         (mf/deps mcp-token)
         (fn []
           (let [params {:id (:id mcp-token)}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata)))
             (st/emit! (du/update-profile-props {:mcp-status false})))))

        handle-regenerate-mcp-token
        (mf/use-fn
         (mf/deps mcp-token)
         (fn []
           (st/emit! (modal/show {:type :create-token
                                  :token-type "mcp"
                                  :title-create (tr "modals.integrations.mcp-regenerate.title")
                                  :title-created (tr "modals.integrations.mcp-regenerate.title.created")
                                  :notification-create (tr "modals.integrations.mcp-regenerate.info")
                                  :remove-token-id (:id mcp-token)}))))

        on-copy-to-clipboard
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (clipboard/to-clipboard mcp-server-url)
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (tr "dashboard.integrations.notification.success.copied-link")
                                :timeout notification-timeout}))))]

    [:section {:class (stl/css :mcp-server-section)}
     [:div
      [:div {:class (stl/css :title)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-primary :mcp-server-title)}
        (tr "dashboard.integrations.mcp-server.title")]
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :beta)}
        (tr "dashboard.integrations.mcp-server.title.beta")]]

      [:> text* {:as "div"
                 :typography t/body-medium
                 :class (stl/css :color-secondary)}
       (tr "dashboard.integrations.mcp-server.description")]]

     [:div
      [:> text* {:as "h3"
                 :typography t/headline-small
                 :class (stl/css :color-primary)}
       (tr "dashboard.integrations.mcp-server.status")]

      [:div {:class (stl/css :mcp-server-block)}
       (when expired?
         [:> notification-pill* {:level :error
                                 :type :context}
          [:div {:class (stl/css :mcp-server-notification)}
           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "dashboard.integrations.mcp-server.status.expired.0")]

           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "dashboard.integrations.mcp-server.status.expired.1")]]])

       [:> switch* {:label (if mcp-active?
                             (tr "dashboard.integrations.mcp-server.status.enabled")
                             (tr "dashboard.integrations.mcp-server.status.disabled"))
                    :default-checked mcp-active?
                    :on-change handle-mcp-status-change}]]]

     (when (some? mcp-token)
       [:div {:class (stl/css :mcp-server-block)}
        [:> text* {:as "h3"
                   :typography t/headline-small
                   :class (stl/css :color-primary)}
         (tr "dashboard.integrations.mcp-server.mcp-keys.title")]

        [:div {:class (stl/css :mcp-server-regenerate)}
         [:> button* {:variant "primary"
                      :class (stl/css :fit-content)
                      :on-click handle-regenerate-mcp-token}
          (tr "dashboard.integrations.mcp-server.mcp-keys.regenerate")]
         [:> tooltip* {:content (tr "dashboard.integrations.mcp-server.mcp-keys.tootip")
                       :id tooltip-id}
          [:> icon* {:icon-id i/info
                     :class (stl/css :color-secondary)}]]]

        [:div {:class (stl/css :list)}
         [:> token-item* {:key (:id mcp-token)
                          :name (:name mcp-token)
                          :expires-at (:expires-at mcp-token)
                          :on-delete handle-delete}]]])

     [:> notification-pill* {:level :default
                             :type :context}
      [:div {:class (stl/css :mcp-server-notification)}
       [:> text* {:as "div"
                  :typography t/body-medium
                  :class (stl/css :color-secondary)}
        (tr "dashboard.integrations.mcp-server.mcp-keys.info")]
       [:div {:class (stl/css :mcp-server-notification-line)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-primary)}
         mcp-server-url]
        [:> text* {:as "div"
                   :typography t/body-medium
                   :on-click on-copy-to-clipboard
                   :class (stl/css :mcp-server-notification-link)}
         [:> icon* {:icon-id i/clipboard}] (tr "dashboard.integrations.mcp-server.mcp-keys.copy")]]

       [:> text* {:as "div"
                  :typography t/body-medium
                  :class (stl/css :color-secondary)}
        [:a {:href mcp-server-tech-guide
             :class (stl/css :mcp-server-notification-link)}
         (tr "dashboard.integrations.mcp-server.mcp-keys.help") [:> icon* {:icon-id i/open-link}]]]]]]))

(mf/defc access-tokens-section*
  {::mf/private true}
  []
  (let [tokens (mf/deref tokens-ref)

        handle-click
        (mf/use-fn
         #(st/emit! (modal/show {:type :create-token
                                 :title-create (tr "modals.integrations.access-token.title")
                                 :title-created (tr "modals.integrations.access-token.title.created")})))

        handle-delete
        (mf/use-fn
         (fn [token-id]
           (let [params {:id token-id}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata))))))]

    [:section {:class (stl/css :access-tokens-section)}
     [:> heading* {:level 2
                   :typography t/title-medium
                   :class (stl/css :color-primary)}
      (tr "dashboard.integrations.access-tokens.personal")]

     [:> text* {:as "div"
                :typography t/body-medium
                :class (stl/css :color-secondary)}
      (tr "dashboard.integrations.access-tokens.personal.description")]

     [:> button* {:variant "primary"
                  :class (stl/css :fit-content)
                  :on-click handle-click}
      (tr "dashboard.integrations.access-tokens.create")]

     (if (empty? tokens)
       [:div {:class (stl/css :frame)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-secondary :text-center)}
         [:div (tr "dashboard.integrations.access-tokens.empty.no-access-tokens")]
         [:div (tr "dashboard.integrations.access-tokens.empty.add-one")]]]

       [:div {:class (stl/css :list)}
        (for [token tokens]
          (when (nil? (:type token))
            [:> token-item* {:key (:id token)
                             :name (:name token)
                             :expires-at (:expires-at token)
                             :on-delete (partial handle-delete (:id token))}]))])]))

(mf/defc integrations-page*
  []
  (mf/with-effect []
    (dom/set-html-title (tr "title.settings.integrations"))
    (st/emit! (du/fetch-access-tokens)))

  [:div {:class (stl/css :integrations)}
   [:> heading* {:level 1
                 :typography t/title-large
                 :class (stl/css :color-primary)}
    (tr "dashboard.integrations.title")]

   (when (contains? cf/flags :mcp-server)
     [:> mcp-server-section*])

   (when (and (contains? cf/flags :mcp-server)
              (contains? cf/flags :access-tokens))
     [:hr {:class (stl/css :separator)}])

   (when (contains? cf/flags :access-tokens)
     [:> access-tokens-section*])])

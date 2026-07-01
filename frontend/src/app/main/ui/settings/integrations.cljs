;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.settings.integrations
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.main.broadcast :as mbc]
   [app.main.data.event :as ev]
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
   [rumext.v2 :as mf]))

(def notification-timeout 7000)

(def ^:private schema:form-access-token
  [:map
   [:name [::sm/text {:max 250}]]
   [:expiration-date [::sm/text {:max 250}]]])

(def ^:private schema:form-mcp-token
  [:map
   [:expiration-date [::sm/text {:max 250}]]])

(def form-initial-data-access-token
  {:name ""
   :expiration-date "never"})

(def form-initial-data-mcp-token
  {:expiration-date "never"})

(mf/defc input-copy*
  {::mf/private true}
  [{:keys [value on-copy-to-clipboard]}]
  [:div {:class (stl/css :input-copy)}
   [:> input* {:type "text"
               :default-value value
               :read-only true}]
   [:div {:class (stl/css :input-copy-button-wrapper)}
    [:> icon-button* {:variant "secondary"
                      :class (stl/css :input-copy-button)
                      :aria-label (tr "integrations.copy-to-clipboard")
                      :on-click on-copy-to-clipboard
                      :icon i/clipboard}]]])

(mf/defc token-created*
  {::mf/private true}
  [{:keys [title is-mcp]}]
  (let [token-created (mf/deref refs/access-token-created)

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps token-created)
         (fn [event]
           (dom/prevent-default event)
           (clipboard/to-clipboard (:token token-created))
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (if is-mcp
                                           (tr "integrations.notification.success.mcp-key-copied")
                                           (tr "integrations.notification.success.token-copied"))
                                :timeout notification-timeout}))))]

    [:div {:class (stl/css :modal-form)}
     [:> text* {:as "h2"
                :typography t/headline-large
                :class (stl/css :color-primary)}
      title]

     (when-not is-mcp
       [:> notification-pill* {:level :info
                               :type :context}
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :color-primary)}
         (tr "integrations.token.info.non-recuperable")]])

     [:div {:class (stl/css :modal-content)}
      [:> input-copy* {:value (:token token-created "")
                       :on-copy-to-clipboard on-copy-to-clipboard}]

      [:> text* {:as "div"
                 :typography t/body-small
                 :class (stl/css :color-secondary)}
       (if (:expires-at token-created)
         (if is-mcp
           (tr "integrations.mcp-key.will-expire" (ct/format-inst (:expires-at token-created) "PPP"))
           (tr "integrations.token.will-expire" (ct/format-inst (:expires-at token-created) "PPP")))
         (if is-mcp
           (tr "integrations.mcp-key.will-not-expire")
           (tr "integrations.token.will-not-expire")))]]

     (when is-mcp
       [:div {:class (stl/css :modal-content)}
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :color-primary)}
         (tr "integrations.info.mcp-client-config")]
        [:textarea {:class (stl/css :textarea)
                    :wrap "off"
                    :rows 7
                    :read-only true
                    :value (dm/str
                            "{\n"
                            "  \"mcpServers\": {\n"
                            "    \"penpot\": {\n"
                            "      \"url\": \"" cf/mcp-server-url "?userToken=" (:token token-created "") "\"\n"
                            "    }\n"
                            "  }"
                            "\n}")}]])

     [:div {:class (stl/css :modal-footer)}
      [:> button* {:variant "secondary"
                   :on-click modal/hide!}
       (tr "labels.close")]]]))

(mf/defc create-token*
  {::mf/private true}
  [{:keys [title info is-mcp on-created]}]
  (let [form (fm/use-form
              :initial (if is-mcp
                         form-initial-data-mcp-token
                         form-initial-data-access-token)
              :schema (if is-mcp
                        schema:form-mcp-token
                        schema:form-access-token))

        on-error
        (mf/use-fn
         #(st/emit! (ntf/error (tr "errors.generic"))
                    (modal/hide)))

        on-success
        (mf/use-fn
         (mf/deps on-created)
         (fn []
           (when (fn? on-created)
             (on-created))))

        on-submit
        (mf/use-fn
         (mf/deps is-mcp)
         (fn [form]
           (let [cdata      (:clean-data @form)
                 mdata      {:on-success (partial on-success form)
                             :on-error   (partial on-error form)}
                 expiration (:expiration-date cdata)
                 params     (cond-> {:name  (:name cdata)
                                     :perms (:perms cdata)}
                              (not= "never" expiration)
                              (assoc :expiration expiration)

                              (true? is-mcp)
                              (assoc :type "mcp" :name "MCP key"))]

             (st/emit! (du/create-access-token (with-meta params mdata))))))]

    [:> fc/form* {:form form
                  :class (stl/css :modal-form)
                  :on-submit on-submit}

     [:> text* {:as "h2"
                :typography t/headline-large
                :class (stl/css :color-primary)}
      title]

     (when (some? info)
       [:> notification-pill* {:level :info
                               :type :context}
        [:> text* {:as "div"
                   :typography t/body-small
                   :class (stl/css :color-primary)}
         info]])

     (if is-mcp
       [:div {:class (stl/css :modal-content)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-secondary)}
         (tr "integrations.info.mcp-server")]]

       [:div {:class (stl/css :modal-content)}
        [:> fc/form-input* {:type "text"
                            :auto-focus? true
                            :form form
                            :name :name
                            :label (tr "integrations.name.label")
                            :placeholder (tr "integrations.name.placeholder")}]])

     [:div {:class (stl/css :modal-content)}
      [:> text* {:as "label"
                 :typography t/body-small
                 :for :expiration-date
                 :class (stl/css :color-primary)}
       (tr "integrations.expiration-date.label")]
      [:> fc/form-select* {:options [{:label (tr "integrations.expiration-never")    :value "never" :id "never"}
                                     {:label (tr "integrations.expiration-30-days")  :value "720h"  :id "720h"}
                                     {:label (tr "integrations.expiration-60-days")  :value "1440h" :id "1440h"}
                                     {:label (tr "integrations.expiration-90-days")  :value "2160h" :id "2160h"}
                                     {:label (tr "integrations.expiration-180-days") :value "4320h" :id "4320h"}]
                           :default-selected "never"
                           :name :expiration-date}]]

     [:div {:class (stl/css :modal-footer)}
      [:> button* {:variant "secondary"
                   :on-click modal/hide!}
       (tr "labels.cancel")]
      [:> fc/form-submit* {:variant "primary"}
       title]]]))

(mf/defc create-access-token-modal
  {::mf/register modal/components
   ::mf/register-as :create-access-token}
  []
  (let [created?
        (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           (reset! created? true)
           (st/emit! (ntf/success (tr "integrations.notification.success.created")))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.create-access-token.title.created")}]
        [:> create-token* {:title (tr "integrations.create-access-token.title")
                           :on-created on-created}])]]))

(mf/defc generate-mcp-token-modal
  {::mf/register modal/components
   ::mf/register-as :generate-mcp-token}
  []
  (let [created? (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           ;; NOTE: Analytics events use "key" terminology for historical reasons;
           ;; these names are immutable to avoid breaking analytics dashboards.
           (st/emit! (du/update-profile-props {:mcp-enabled true})
                     (ntf/success (tr "integrations.notification.success.created"))
                     (ev/event {::ev/name "generate-mcp-key"
                                ::ev/origin "integrations"})
                     (ev/event {::ev/name "enable-mcp"
                                ::ev/origin "integrations"
                                :source "key-creation"})
                     (mbc/event :mcp/enable {}))
           (reset! created? true)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.generate-mcp-key.title.created")
                            :is-mcp true}]
        [:> create-token* {:title (tr "integrations.generate-mcp-key.title")
                           :is-mcp true
                           :on-created on-created}])]]))

(defn- mcp-access-token?
  [atoken]
  (= "mcp" (:type atoken)))

(mf/defc regenerate-mcp-token-modal
  {::mf/register modal/components
   ::mf/register-as :regenerate-mcp-token}
  []
  (let [created?      (mf/use-state false)

        on-close
        (mf/use-fn
         (fn []
           (reset! created? false)
           (st/emit! (modal/hide))))

        on-created
        (mf/use-fn
         (fn []
           ;; NOTE: Analytics event uses "key" terminology for historical reasons.
           (st/emit! (du/update-profile-props {:mcp-enabled true})
                     (ntf/success (tr "integrations.notification.success.created"))
                     (ev/event {::ev/name "regenerate-mcp-key"
                                ::ev/origin "integrations"})
                     (mbc/event :mcp/enable {}))
           (reset! created? true)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-close-button)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-close
                         :icon i/close}]]

      (if @created?
        [:> token-created* {:title (tr "integrations.regenerate-mcp-key.title.created")
                            :is-mcp true}]
        [:> create-token* {:title (tr "integrations.regenerate-mcp-key.title")
                           :info (tr "integrations.regenerate-mcp-key.info")
                           :is-mcp true
                           :on-created on-created}])]]))

(mf/defc token-item*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [id name expires-at on-delete]}]
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

        handle-delete
        (mf/use-fn
         (mf/deps on-delete id)
         #(on-delete id))

        handle-open-confirm-modal
        (mf/use-fn
         (mf/deps handle-delete)
         (fn []
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "integrations.delete-token.title")
                                  :message (tr "integrations.delete-token.message")
                                  :accept-label (tr "integrations.delete-token.accept")
                                  :on-accept handle-delete}))))

        options
        (mf/with-memo [handle-delete]
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
        (nil? expires-at) (tr "integrations.no-expiration")
        expired?          (tr "integrations.expired-on" expires-txt)
        :else             (tr "integrations.expires-on" expires-txt))]

     [:div {:class (stl/css :item-actions)}
      [:> icon-button* {:variant "ghost"
                        :class (stl/css :item-button)
                        :aria-pressed menu-open?
                        :aria-label (tr "labels.options")
                        :on-click handle-menu-click
                        :icon i/menu}]
      [:> context-menu* {:on-close handle-menu-close
                         :show menu-open?
                         :min-width true
                         :top -10
                         :left -138
                         :options options}]]]))

(defn valid-mcp-token?
  "Given access tokens list, return if it contains a valid (not expired) mcp key"
  [{:keys [expires-at] :as token}]
  (if token
    (or (nil? expires-at)
        (> expires-at (ct/now)))
    false))

(mf/defc mcp-server-section*
  {::mf/private true}
  [{:keys [access-tokens]}]
  (let [profile       (mf/deref refs/profile)

        token         (mf/with-memo [access-tokens]
                        (when-let [token (d/seek mcp-access-token? access-tokens)]
                          (assoc token :is-valid (valid-mcp-token? token))))

        token-valid?  (get token :is-valid)
        token-str     (get token :token)
        enabled?      (-> profile :props :mcp-enabled boolean)

        url           (dm/str cf/mcp-server-url "?userToken=" token-str)

        show-enabled? (and enabled? token-valid?)
        tooltip-id    (mf/use-id)

        handle-mcp-change
        (mf/use-fn
         (fn [value]
           (st/emit! (du/update-profile-props {:mcp-enabled value})
                     (ntf/show {:level :info
                                :type :toast
                                :content (if (true? value)
                                           (tr "integrations.notification.success.mcp-server-enabled")
                                           (tr "integrations.notification.success.mcp-server-disabled"))
                                :timeout notification-timeout})
                     (ev/event {::ev/name (if (true? value) "enable-mcp" "disable-mcp")
                                ::ev/origin "integrations"
                                :source "toggle"})
                     (if value
                       (mbc/event :mcp/enable {})
                       (mbc/event :mcp/disable {})))))

        handle-open-modal-generate
        (mf/use-fn
         #(st/emit! (modal/show {:type :generate-mcp-token})))

        handle-open-modal-regenerate
        (mf/use-fn
         #(st/emit! (modal/show {:type :regenerate-mcp-token})))

        handle-delete
        (mf/use-fn
         (fn [id]
           (let [params {:id id}
                 mdata  {:on-success #(st/emit! (du/fetch-access-tokens))}]
             (st/emit! (du/delete-access-token (with-meta params mdata))
                       (du/update-profile-props {:mcp-enabled false})
                       (mbc/event :mcp/disable {})))))

        on-copy-to-clipboard
        (mf/use-fn
         (mf/deps url)
         (fn [event]
           (dom/prevent-default event)
           (clipboard/to-clipboard url)
           (st/emit! (ntf/show {:level :info
                                :type :toast
                                :content (tr "integrations.notification.success.copied-link")
                                :timeout notification-timeout})
                     (ev/event {::ev/name "copy-mcp-url"
                                ::ev/origin "integrations"}))))]

    [:section {:class (stl/css :mcp-server-section)}
     [:div
      [:div {:class (stl/css :title)}
       [:> heading* {:level 2
                     :typography t/title-medium
                     :class (stl/css :color-primary :mcp-server-title)}
        (tr "integrations.mcp-server.title")]
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css :beta)}
        (tr "integrations.mcp-server.title.beta")]]

      [:> text* {:as "div"
                 :typography t/body-medium
                 :class (stl/css :color-secondary)}
       (tr "integrations.mcp-server.description")]]

     [:div
      [:> text* {:as "h3"
                 :typography t/headline-small
                 :class (stl/css :color-primary)}
       (tr "integrations.mcp-server.status")]

      [:div {:class (stl/css :mcp-server-block)}
       (when (and (some? token) (not token-valid?))
         [:> notification-pill* {:level :error
                                 :type :context}
          [:div {:class (stl/css :mcp-server-notification)}
           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "integrations.mcp-server.status.expired.0")]

           [:> text* {:as "div"
                      :typography t/body-medium
                      :class (stl/css :color-primary)}
            (tr "integrations.mcp-server.status.expired.1")]]])

       [:div {:class (stl/css :mcp-server-switch)}
        [:> switch* {:label (if show-enabled?
                              (tr "integrations.mcp-server.status.enabled")
                              (tr "integrations.mcp-server.status.disabled"))
                     :default-checked show-enabled?
                     :on-change handle-mcp-change}]

        (when-not token
          [:div {:class (stl/css :mcp-server-switch-cover)
                 :on-click handle-open-modal-generate}])

        (when (and token (not token-valid?))
          [:div {:class (stl/css :mcp-server-switch-cover)
                 :on-click handle-open-modal-regenerate}])]]]

     (when (some? token)
       [:div {:class (stl/css :mcp-server-key)}
        [:> text* {:as "h3"
                   :typography t/headline-small
                   :class (stl/css :color-primary)}
         (tr "integrations.mcp-server.mcp-keys.title")]

        [:div {:class (stl/css :mcp-server-block)}
         [:div {:class (stl/css :mcp-server-regenerate)}
          [:> button* {:variant "primary"
                       :class (stl/css :fit-content)
                       :on-click handle-open-modal-regenerate}
           (tr "integrations.mcp-server.mcp-keys.regenerate")]
          [:> tooltip* {:content (tr "integrations.mcp-server.mcp-keys.tootip")
                        :id tooltip-id}
           [:> icon* {:icon-id i/info
                      :class (stl/css :color-secondary)}]]]

         [:div {:class (stl/css :list)}
          [:> token-item* {:key (:id token)
                           :id (:id token)
                           :name (:name token)
                           :expires-at (:expires-at token)
                           :on-delete handle-delete}]]]])

     [:> notification-pill* {:level :default
                             :type :context}
      [:div {:class (stl/css :mcp-server-notification)}
       (when token
         [:> text* {:as "div"
                    :typography t/body-medium
                    :class (stl/css :color-secondary)}
          (tr "integrations.mcp-server.mcp-keys.info")])

       (when token
         [:> input-copy* {:value url
                          :on-copy-to-clipboard on-copy-to-clipboard}])

       [:> text* {:as "div"
                  :typography t/body-medium
                  :class (stl/css :color-secondary)}
        [:a {:href cf/mcp-help-center-uri
             :target "_blank"
             :rel "noopener noreferrer"
             :class (stl/css :mcp-server-notification-link)}
         (tr "integrations.mcp-server.mcp-keys.help")
         [:> icon* {:icon-id i/open-link}]]]]]]))

(mf/defc access-tokens-section*
  {::mf/private true}
  [{:keys [access-tokens]}]
  (let [access-tokens
        (mf/with-memo [access-tokens]
          (not-empty (filter #(nil? (:type %)) access-tokens)))

        handle-click
        (mf/use-fn
         #(st/emit! (modal/show {:type :create-access-token})))

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
      (tr "integrations.access-tokens.personal")]

     [:> text* {:as "div"
                :typography t/body-medium
                :class (stl/css :color-secondary)}
      (tr "integrations.access-tokens.personal.description")]

     [:> button* {:variant "primary"
                  :class (stl/css :fit-content)
                  :on-click handle-click}
      (tr "integrations.access-tokens.create")]

     (if access-tokens
       [:div {:class (stl/css :list)}
        (for [{:keys [id] :as token} access-tokens]
          [:> token-item* {:key (dm/str id)
                           :id id
                           :name (:name token)
                           :expires-at (:expires-at token)
                           :on-delete handle-delete}])]

       [:div {:class (stl/css :frame)}
        [:> text* {:as "div"
                   :typography t/body-medium
                   :class (stl/css :color-secondary :text-center)}
         [:div (tr "integrations.access-tokens.empty.no-access-tokens")]
         [:div (tr "integrations.access-tokens.empty.add-one")]]])]))

(mf/defc integrations-page*
  []
  (let [access-tokens (mf/deref refs/access-tokens)
        props         (mf/props {:access-tokens access-tokens})

        access-tokens-enabled?
        (contains? cf/flags :access-tokens)

        mcp-enabled?
        (contains? cf/flags :mcp)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.integrations"))
      (st/emit! (du/fetch-access-tokens)))

    [:section {:class (stl/css :integrations)
               :aria-labelledby "integrations-section-title"}
     [:> heading* {:level 2
                   :typography t/title-large
                   :class (stl/css :color-primary)
                   :id "integrations-section-title"}
      (tr "integrations.title")]

     (when ^boolean mcp-enabled?
       [:> mcp-server-section* props])

     (when (and ^boolean mcp-enabled?
                ^boolean access-tokens-enabled?)
       [:hr {:class (stl/css :separator)}])

     (when ^boolean access-tokens-enabled?
       [:> access-tokens-section* props])]))

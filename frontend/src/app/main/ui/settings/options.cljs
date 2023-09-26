;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.options
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::lang (s/nilable ::us/string))
(s/def ::theme (s/nilable ::us/not-empty-string))
(s/def ::2fa ::us/keyword)
(s/def ::passkey ::us/keyword)

(s/def ::options-form
  (s/keys :opt-un [::lang ::theme ::2fa ::passkey]))

(defn- on-success
  [_]
  (st/emit! (msg/success (tr "notifications.profile-saved"))))

(defn- on-submit
  [form _event]
  (let [fdata (:clean-data @form)

        data  (d/without-nils
               {:theme (:theme fdata)
                :lang  (:lang fdata)
                :props {:passkey (:passkey fdata)
                        :2fa (:2fa fdata)}})
        mdata {:on-success (partial on-success form)}]
    (st/emit! (du/update-profile (with-meta data mdata)))))

(mf/defc settings
  {::mf/wrap-props false}
  [_props]
  (let [profile (mf/deref refs/profile)
        initial (mf/with-memo [profile]
                  (let [props (:props profile)]
                    (d/without-nils
                     {:lang  (d/nilv (:lang profile) "")
                      :theme (:theme profile)
                      :passkey (:passkey props :all)
                      :2fa     (:2fa props :none)})))

        form    (fm/use-form :spec ::options-form :initial initial)
        totp?   (= :totp (dm/get-in profile [:props :2fa]))
        new-css-system (features/use-feature :new-css-system)

        on-show-totp-secret
        (mf/use-fn #(st/emit! (modal/show! :two-factor-qrcode {})))]

    [:div.form-container
     {:data-test "settings-form"}
     [:& fm/form {:class "options-form"
                  :on-submit on-submit
                  :form form}

      [:h2 (tr "labels.language")]

      [:div.fields-row
       [:& fm/select
        {:options (into [{:label "Auto (browser)" :value ""}] i18n/supported-locales)
         :label (tr "dashboard.select-ui-language")
         :default ""
         :name :lang
         :data-test "setting-lang"}]]

      (when new-css-system
        [:*
         [:h2 (tr "dashboard.theme-change")]
         [:div.fields-row
          [:& fm/select
           {:label (tr "dashboard.select-ui-theme")
            :name :theme
            :default "default"
            :options [{:label "Penpot Dark (default)" :value "default"}
                      {:label "Penpot Light" :value "light"}]
            :data-test "setting-theme"}]]])

      [:h2 "PassKey"]
      [:div.fields-row
       [:& fm/radio-buttons
        {:name :passkey
         :encode-fn d/name
         :decode-fn keyword
         :options [{:label "Auth & 2FA" :value :all}
                   {:label "Only 2FA" :value :2fa}]}]]

      [:h2 "2FA"]
      [:div.fields-row
       [:& fm/radio-buttons
        {:name :2fa
         :encode-fn d/name
         :decode-fn keyword
         :options [{:label "NONE" :value :none}
                   {:label "TOTP" :value :totp}
                   {:label "PASSKEY" :value :passkey}]}]
       (when ^boolean totp?
         [:a {:on-click on-show-totp-secret} "(show secret)"])]

      [:> fm/submit-button*
       {:label (tr "dashboard.update-settings")
        :data-test "submit-lang-change"}]]]))

(mf/defc two-factor-qrcode-modal
  {::mf/register modal/components
   ::mf/register-as :two-factor-qrcode}
  []
  (let [on-close (mf/use-fn #(st/emit! (modal/hide)))
        image*   (mf/use-state nil)
        secret*  (mf/use-state nil)]


    (mf/with-effect []
      (->> (rp/cmd! :get-profile-2fa-secret)
           (rx/subs (fn [{:keys [secret image] :as result}]
                      (prn "result" result)
                      (reset! image* image)
                      (reset! secret* secret)))))

    [:div.modal-overlay
     [:div.modal-container.change-email-modal
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "modals.two-factor-qrcode.title")]]
       [:div.modal-close-button
        {:on-click on-close}
        i/close]]

      (when-let [uri @image*]
        [:div.modal-content
         [:img {:width "300"
                :height "300"
                :src uri}]])

      [:div.modal-footer]]]))

     (when new-css-system
       [:h2 (tr "dashboard.theme-change")]
       [:div.fields-row
        [:& fm/select {:label (tr "dashboard.select-ui-theme")
                       :name :theme
                       :default "default"
                       :options [{:label "Penpot Dark (default)" :value "default"}
                                 {:label "Penpot Light" :value "light"}]
                       :data-test "setting-theme"}]])
     [:> fm/submit-button*
      {:label (tr "dashboard.update-settings")
       :data-test "submit-lang-change"}]]))

;; --- Password Page

(mf/defc options-page
  []
  (mf/use-effect
   #(dom/set-html-title (tr "title.settings.options")))

  [:div.dashboard-settings
   [:& settings]])

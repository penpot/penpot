;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.settings.profile
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr t]]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::fullname ::us/not-empty-string)
(s/def ::email ::us/email)

(s/def ::profile-form
  (s/keys :req-un [::fullname ::email]))

(defn- on-success
  [_]
  (st/emit! (dm/success (tr "notifications.profile-saved"))))

(defn- on-submit
  [form _event]
  (let [data  (:clean-data @form)
        mdata {:on-success (partial on-success form)}]
    (st/emit! (du/update-profile (with-meta data mdata)))))

;; --- Profile Form

(mf/defc profile-form
  [{:keys [locale] :as props}]
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::profile-form
                             :initial profile)]
    [:& fm/form {:on-submit on-submit
                 :form form
                 :class "profile-form"}
     [:div.fields-row
      [:& fm/input
       {:type "text"
        :name :fullname
        :label (t locale "dashboard.your-name")}]]

     [:div.fields-row
      [:& fm/input
       {:type "email"
        :name :email
        :disabled true
        :help-icon i/at
        :label (t locale "dashboard.your-email")}]

      [:div.options
       [:div.change-email
        [:a {:on-click #(modal/show! :change-email {})}
         (t locale "dashboard.change-email")]]]]

     [:& fm/submit-button
      {:label (t locale "dashboard.update-settings")}]

     [:div.links
      [:div.link-item
       [:a {:on-click #(modal/show! :delete-account {})}
        (t locale "dashboard.remove-account")]]]]))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  [{:keys [locale] :as props}]
  (let [file-input (mf/use-ref nil)
        profile (mf/deref refs/profile)
        photo   (cfg/resolve-profile-photo-url profile)
        on-image-click #(dom/click (mf/ref-val file-input))

        on-file-selected
        (fn [file]
          (st/emit! (du/update-photo file)))]

    [:form.avatar-form
     [:div.image-change-field
      [:span.update-overlay {:on-click on-image-click} (t locale "labels.update")]
      [:img {:src photo}]
      [:& file-uploader {:accept "image/jpeg,image/png"
                         :multi false
                         :ref file-input
                         :on-selected on-file-selected}]]]))

;; --- Profile Page

(mf/defc profile-page
  [{:keys [locale]}]

  (mf/use-effect
    #(dom/set-html-title (tr "title.settings.profile")))

  [:div.dashboard-settings
   [:div.form-container.two-columns
    [:& profile-photo-form {:locale locale}]
    [:& profile-form {:locale locale}]]])


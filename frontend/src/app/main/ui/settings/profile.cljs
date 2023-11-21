;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.profile
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

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
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)

        profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::profile-form
                             :initial profile
                             :validators [(fm/validate-length :fullname fm/max-length-allowed (tr "auth.name.too-long"))
                                          (fm/validate-not-empty :fullname (tr "auth.name.not-all-space"))])]

    (if new-css-system
      [:& fm/form {:on-submit on-submit
                   :form form
                   :class (stl/css :profile-form)}
       [:div {:class (stl/css :fields-row)}
        [:& fm/input
         {:type "text"
          :name :fullname
          :label (tr "dashboard.your-name")}]]

       [:div {:class (stl/css :fields-row)
              :on-click #(modal/show! :change-email {})}
        [:& fm/input
         {:type "email"
          :name :email
          :disabled true
          :help-icon i/at
          :label (tr "dashboard.your-email")}]

        [:div {:class (stl/css :options)}
         [:div.change-email
          [:a {:on-click #(modal/show! :change-email {})}
           (tr "dashboard.change-email")]]]]

       [:> fm/submit-button*
        {:label (tr "dashboard.save-settings")
         :disabled (empty? (:touched @form))
         :className (stl/css :btn-primary)}]

       [:div {:class (stl/css :links)}
        [:div {:class (stl/css :link-item)}
         [:a {:on-click #(modal/show! :delete-account {})
              :data-test "remove-acount-btn"}
          (tr "dashboard.remove-account")]]]]

      ;; OLD
      [:& fm/form {:on-submit on-submit
                   :form form
                   :class "profile-form"}
       [:div.fields-row
        [:& fm/input
         {:type "text"
          :name :fullname
          :label (tr "dashboard.your-name")}]]

       [:div.fields-row
        [:& fm/input
         {:type "email"
          :name :email
          :disabled true
          :help-icon i/at
          :label (tr "dashboard.your-email")}]

        [:div.options
         [:div.change-email
          [:a {:on-click #(modal/show! :change-email {})}
           (tr "dashboard.change-email")]]]]

       [:> fm/submit-button*
        {:label (tr "dashboard.save-settings")
         :disabled (empty? (:touched @form))}]

       [:div.links
        [:div.link-item
         [:a {:on-click #(modal/show! :delete-account {})
              :data-test "remove-acount-btn"}
          (tr "dashboard.remove-account")]]]])))

;; --- Profile Photo Form

(mf/defc profile-photo-form []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        file-input     (mf/use-ref nil)
        profile        (mf/deref refs/profile)
        photo          (cf/resolve-profile-photo-url profile)
        on-image-click #(dom/click (mf/ref-val file-input))

        on-file-selected
        (fn [file]
          (st/emit! (du/update-photo file)))]

    (if new-css-system
      [:form {:class (stl/css :avatar-form)}
       [:div {:class (stl/css :image-change-field)}
        [:span {:class (stl/css :update-overlay)
                :on-click on-image-click} (tr "labels.update")]
        [:img {:src photo}]
        [:& file-uploader {:accept "image/jpeg,image/png"
                           :multi false
                           :ref file-input
                           :on-selected on-file-selected
                           :data-test "profile-image-input"}]]]
      ;; OLD
      [:form.avatar-form
       [:div.image-change-field
        [:span.update-overlay {:on-click on-image-click} (tr "labels.update")]
        [:img {:src photo}]
        [:& file-uploader {:accept "image/jpeg,image/png"
                           :multi false
                           :ref file-input
                           :on-selected on-file-selected
                           :data-test "profile-image-input"}]]])))

;; --- Profile Page

(mf/defc profile-page []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (mf/with-effect []
      (dom/set-html-title (tr "title.settings.profile")))
    (if new-css-system
      [:div {:class (stl/css :dashboard-settings)}
       [:div {:class (stl/css :form-container)}
        [:h2 (tr "labels.profile")]
        [:& profile-photo-form]
        [:& profile-form]]]
      
      ;; OLD
      [:div.dashboard-settings
       [:div.form-container.two-columns
        [:& profile-photo-form]
        [:& profile-form]]])))


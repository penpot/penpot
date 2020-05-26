;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.profile
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.ui.components.forms :refer [input submit-button form]]
   [uxbox.main.ui.settings.change-email :refer [change-email-modal]]
   [uxbox.main.ui.settings.delete-account :refer [delete-account-modal]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.messages :as msgs]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(s/def ::fullname ::fm/not-empty-string)
(s/def ::email ::fm/email)

(s/def ::profile-form
  (s/keys :req-un [::fullname ::lang ::theme ::email]))

(defn- on-error
  [error form]
  (st/emit! (dm/error (tr "errors.generic"))))

(defn- on-submit
  [form event]
  (let [data (:clean-data form)
        on-success #(st/emit! (dm/info (tr "settings.notifications.profile-saved")))
        on-error #(on-error % form)]
    (st/emit! (udu/update-profile (with-meta data
                                    {:on-success on-success
                                     :on-error on-error})))))

;; --- Profile Form

(mf/defc profile-form
  [{:keys [locale] :as props}]
  (let [prof (mf/deref refs/profile)]
    [:& form {:on-submit on-submit
              :class "profile-form"
              :spec ::profile-form
              :initial prof}
     [:& input
      {:type "text"
       :name :fullname
       :label (t locale "settings.fullname-label")}]

     [:& input
      {:type "email"
       :name :email
       :disabled true
       :help-icon i/at
       :label (t locale "settings.email-label")}]

     (cond
       (nil? (:pending-email prof))
       [:div.change-email
        [:a {:on-click #(modal/show! change-email-modal {})}
         (t locale "settings.change-email-label")]]

       (not= (:pending-email prof) (:email prof))
       [:& msgs/inline-banner
        {:type :info
         :content (t locale "settings.change-email-info3" (:pending-email prof))}
        [:div.btn-secondary.btn-small
         {:on-click #(st/emit! udu/cancel-email-change)}
         (t locale "settings.cancel-email-change")]]

       :else
       [:& msgs/inline-banner
        {:type :info
         :content (t locale "settings.email-verification-pending")}])

     [:& submit-button
      {:label (t locale "settings.profile-submit-label")}]

     [:div.links
      [:div.link-item
       [:a {:on-click #(modal/show! delete-account-modal {})}
        (t locale "settings.remove-account-label")]]]]))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  [{:keys [locale] :as props}]
  (let [profile (mf/deref refs/profile)
        photo (:photo-uri profile)
        photo (if (or (str/empty? photo) (nil? photo))
                "images/avatar.jpg"
                photo)

        on-change
        (fn [event]
          (let [target (dom/get-target event)
                file (-> (dom/get-files target)
                         (array-seq)
                         (first))]
            (st/emit! (udu/update-photo {:file file}))
            (dom/clean-value! target)))]
    [:form.avatar-form
     [:div.image-change-field
      [:span.update-overlay (t locale "settings.update-photo-label")]
      [:img {:src photo}]
      [:input {:type "file"
               :value ""
               :on-change on-change}]]]))

;; --- Profile Page

(mf/defc profile-page
  {::mf/wrap-props false}
  [props]
  (let [locale (i18n/use-locale)]
    [:section.settings-profile.generic-form
     [:div.forms-container
      [:& profile-photo-form {:locale locale}]
      [:& profile-form {:locale locale}]]]))

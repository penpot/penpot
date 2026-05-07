;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.profile
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:profile-form
  [:map {:title "ProfileForm"}
   [:fullname [::sm/text {:max 250}]]
   [:email ::sm/email]])

(defn- on-success
  [_]
  (st/emit! (ntf/success (tr "notifications.profile-saved"))))

(defn- on-submit
  [form _event]
  (let [data  (:clean-data @form)]
    (st/emit! (du/update-profile data)
              (du/persist-profile {:on-success on-success}))))

;; --- Profile Form

(mf/defc profile-form
  {::mf/private true}
  []
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :schema schema:profile-form
                             :initial profile)

        ;; In x-auth-request-headers (oauth2-proxy / Cognito SSO) mode the
        ;; email is the user's identity, owned by the upstream IdP. Letting
        ;; a user change it locally would diverge it from the IdP-supplied
        ;; X-Auth-Request-Email and lock them out of their own workspace
        ;; on the very next request. The backend rejects the change at the
        ;; RPC layer (rpc/commands/profile.clj + verify_token.clj) — this
        ;; is just the UX guard so the user doesn't see a button that
        ;; would only fail. cf/flags is hydrated by nginx-entrypoint.sh
        ;; from PENPOT_FLAGS, the same env that flips the backend gate, so
        ;; UI and backend stay aligned even if MPASS_SIGNOUT_URL is unset.
        sso-mode? (contains? cf/flags :x-auth-request-headers)

        on-show-change-email
        (mf/use-fn
         #(modal/show! :change-email {}))

        on-show-delete-account
        (mf/use-fn
         #(modal/show! :delete-account {}))]

    [:& fm/form {:on-submit on-submit
                 :form form
                 :class (stl/css :profile-form)}
     [:div {:class (stl/css :fields-row)}
      [:& fm/input
       {:type "text"
        :name :fullname
        :label (tr "dashboard.your-name")}]]

     (if sso-mode?
       ;; SSO mode — read-only email row, no click handler, no link.
       [:div {:class (stl/css :fields-row)}
        [:& fm/input
         {:type "email"
          :name :email
          :disabled true
          :label (tr "dashboard.your-email")}]]
       ;; LOCAL/GOOGLE mode — original upstream behaviour preserved.
       [:div {:class (stl/css :fields-row)
              :on-click on-show-change-email}
        [:& fm/input
         {:type "email"
          :name :email
          :disabled true
          :label (tr "dashboard.your-email")}]
        [:div {:class (stl/css :options)}
         [:div.change-email
          [:a {:on-click on-show-change-email}
           (tr "dashboard.change-email")]]]])

     [:> fm/submit-button*
      {:label (tr "dashboard.save-settings")
       :disabled (empty? (:touched @form))
       :class (stl/css :btn-primary)}]

     [:div {:class (stl/css :links)}
      [:div {:class (stl/css :link-item)}
       [:a {:on-click on-show-delete-account
            :data-testid "remove-acount-btn"}
        (tr "dashboard.remove-account")]]]]))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  {::mf/private true}
  []
  (let [input-ref  (mf/use-ref nil)
        profile    (mf/deref refs/profile)

        photo
        (mf/with-memo [profile]
          (cf/resolve-profile-photo-url profile))

        on-image-click
        (mf/use-fn
         #(dom/click (mf/ref-val input-ref)))

        on-file-selected
        (fn [file]
          (st/emit! (du/update-photo file)))]

    [:form {:class (stl/css :avatar-form)}
     [:div {:class (stl/css :image-change-field)}
      [:span {:class (stl/css :update-overlay)
              :on-click on-image-click} (tr "labels.update")]
      [:img {:src photo}]
      [:& file-uploader {:accept "image/jpeg,image/png"
                         :multi false
                         :ref input-ref
                         :on-selected on-file-selected
                         :data-testid "profile-image-input"}]]]))

;; --- Profile Page

(mf/defc profile-page
  []
  (mf/with-effect []
    (dom/set-html-title (tr "title.settings.profile")))

  [:div {:class (stl/css :dashboard-settings)}
   [:div {:class (stl/css :form-container)}
    [:h2 (tr "labels.profile")]
    [:& profile-photo-form]
    [:& profile-form]]])


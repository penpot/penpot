;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.change-email
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- on-error
  [form cause]
  (let [{:keys [code] :as error} (ex-data cause)]
    (case code
      :email-already-exists
      (swap! form (fn [data]
                    (let [error {:message (tr "errors.email-already-exists")}]
                      (assoc-in data [:errors :email-1] error))))

      :profile-is-muted
      (rx/of (ntf/error (tr "errors.profile-is-muted")))

      (:email-has-permanent-bounces
       :email-has-complaints)
      (rx/of (ntf/error (tr "errors.email-has-permanent-bounces" (:email error))))

      (rx/throw cause))))

(defn- on-success
  [profile data]
  (if (:changed data)
    (st/emit! (du/refresh-profile)
              (modal/hide))
    (let [message (tr "notifications.validation-email-sent" (:email profile))]
      (st/emit! (ntf/info message)
                (modal/hide)))))

(defn- on-submit
  [profile form _event]
  (let [params {:email (get-in @form [:clean-data :email-1])}
        mdata  {:on-error (partial on-error form)
                :on-success (partial on-success profile)}]
    (st/emit! (du/request-email-change (with-meta params mdata)))))

(def ^:private schema:email-change-form
  [:and
   [:map {:title "EmailChangeForm"}
    [:email-1 ::sm/email]
    [:email-2 ::sm/email]]
   [:fn {:error/fn #(tr "errors.invalid-email-confirmation")
         :error/field :email-2}
    (fn [data]
      (let [email-1 (:email-1 data)
            email-2 (:email-2 data)]
        (= email-1 email-2)))]])

(mf/defc change-email-modal
  {::mf/register modal/components
   ::mf/register-as :change-email}
  []
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :schema schema:email-change-form
                             :initial profile)

        on-submit
        (mf/use-fn
         (mf/deps profile)
         (partial on-submit profile))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form
                   :on-submit on-submit}

       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)
              :data-testid "change-email-title"}
         (tr "modals.change-email.title")]
        [:button {:class (stl/css :modal-close-btn)
                  :on-click modal/hide!} i/close]]

       [:div {:class (stl/css :modal-content)}
        [:& context-notification
         {:level :info
          :content (tr "modals.change-email.info" (:email profile))}]

        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "email"
                       :name :email-1
                       :label (tr "modals.change-email.new-email")
                       :trim true
                       :show-success? true}]]

        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "email"
                       :name :email-2
                       :label (tr "modals.change-email.confirm-email")
                       :trim true
                       :show-success? true}]]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)
               :data-testid "change-email-submit"}
         [:> fm/submit-button*
          {:label (tr "modals.change-email.submit")}]]]]]]))




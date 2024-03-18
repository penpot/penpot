;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.change-email
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dma]
   [app.common.spec :as us]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::email-1 ::us/email)
(s/def ::email-2 ::us/email)

(defn- email-equality
  [errors data]
  (let [email-1 (:email-1 data)
        email-2 (:email-2 data)]
    (cond-> errors
      (and email-1 email-2 (not= email-1 email-2))
      (assoc :email-2 {:message (tr "errors.email-invalid-confirmation")
                       :code :different-emails}))))

(s/def ::email-change-form
  (s/keys :req-un [::email-1 ::email-2]))

(defn- on-error
  [form {:keys [code] :as error}]
  (case code
    :email-already-exists
    (swap! form (fn [data]
                  (let [error {:message (tr "errors.email-already-exists")}]
                    (assoc-in data [:errors :email-1] error))))

    :profile-is-muted
    (rx/of (msg/error (tr "errors.profile-is-muted")))

    :email-has-permanent-bounces
    (let [email (get @form [:data :email-1])]
      (rx/of (msg/error (tr "errors.email-has-permanent-bounces" email))))

    (rx/throw error)))

(defn- on-success
  [profile data]
  (if (:changed data)
    (st/emit! (du/fetch-profile)
              (modal/hide))
    (let [message (tr "notifications.validation-email-sent" (:email profile))]
      (st/emit! (msg/info message)
                (modal/hide)))))

(defn- on-submit
  [profile form _event]
  (let [params {:email (get-in @form [:clean-data :email-1])}
        mdata  {:on-error (partial on-error form)
                :on-success (partial on-success profile)}]
    (st/emit! (du/request-email-change (with-meta params mdata)))))

(mf/defc change-email-modal
  {::mf/register modal/components
   ::mf/register-as :change-email}
  []
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::email-change-form
                             :validators [email-equality]
                             :initial profile)
        on-close
        (mf/use-callback #(st/emit! (modal/hide)))

        on-submit
        (mf/use-callback
         (mf/deps profile)
         (partial on-submit profile))

        on-email-change
        (mf/use-callback
         (fn [_ _]
           (let [different-emails-error? (= (dma/get-in @form [:errors :email-2 :code]) :different-emails)
                 email-1                 (dma/get-in @form [:clean-data :email-1])
                 email-2                 (dma/get-in @form [:clean-data :email-2])]
             (println "different-emails-error?" (and different-emails-error? (= email-1 email-2)))
             (when (and different-emails-error? (= email-1 email-2))
               (swap! form d/dissoc-in [:errors :email-2])))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form
                   :on-submit on-submit}

       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)
              :data-test "change-email-title"}
         (tr "modals.change-email.title")]
        [:button {:class (stl/css :modal-close-btn)
                  :on-click on-close} i/close]]

       [:div {:class (stl/css :modal-content)}
        [:& context-notification
         {:type :info
          :content (tr "modals.change-email.info" (:email profile))}]

        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "email"
                       :name :email-1
                       :label (tr "modals.change-email.new-email")
                       :trim true
                       :show-success? true
                       :on-change-value on-email-change}]]

        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "email"
                       :name :email-2
                       :label (tr "modals.change-email.confirm-email")
                       :trim true
                       :show-success? true
                       :on-change-value on-email-change}]]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)
               :data-test "change-email-submit"}
         [:> fm/submit-button*
          {:label (tr "modals.change-email.submit")}]]]]]]))




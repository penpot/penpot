;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth.login
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [app.config :as cfg]
   [app.common.spec :as us]
   [app.main.ui.icons :as i]
   [app.main.data.auth :as da]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.messages :as msgs]
   [app.main.data.messages :as dm]
   [app.main.ui.components.forms :as fm]
   [app.util.object :as obj]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr t]]
   [app.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(defn- login-with-google
  [event]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-google {})
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(defn- login-with-gitlab
  [event]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-gitlab {})
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(defn- login-with-github
  [event]
  (dom/prevent-default event)
  (->> (rp/mutation! :login-with-github {})
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (.replace js/location redirect-uri)))))

(mf/defc login-form
  []
  (let [error? (mf/use-state false)
        form   (fm/use-form :spec ::login-form
                            :inital {})

        on-error
        (fn [form event]
          (js/console.log error?)
          (reset! error? true))

        on-submit
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (reset! error? false)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error})]
             (st/emit! (da/login params)))))

        on-submit-ldap
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (reset! error? false)
           (let [params (with-meta (:clean-data @form)
                          {:on-error on-error})]
             (st/emit! (da/login-with-ldap params)))))]

    [:*
     (when @error?
       [:& msgs/inline-banner
        {:type :warning
         :content (tr "errors.auth.unauthorized")
         :on-close #(reset! error? false)}])

     [:& fm/form {:on-submit on-submit :form form}
      [:div.fields-row
       [:& fm/input
        {:name :email
         :type "text"
         :tab-index "2"
         :help-icon i/at
         :label (tr "auth.email")}]]
      [:div.fields-row
       [:& fm/input
        {:type "password"
         :name :password
         :tab-index "3"
         :help-icon i/eye
         :label (tr "auth.password")}]]
      [:& fm/submit-button
       {:label (tr "auth.login-submit")
        :on-click on-submit}]

      (when cfg/login-with-ldap
        [:& fm/submit-button
         {:label (tr "auth.login-with-ldap-submit")
          :on-click on-submit-ldap}])]]))

(mf/defc login-page
  []
  [:div.generic-form.login-form
   [:div.form-container
    [:h1 (tr "auth.login-title")]
    [:div.subtitle (tr "auth.login-subtitle")]

    [:& login-form {}]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-recovery-request))
           :tab-index "5"}
       (tr "auth.forgot-password")]]

     [:div.link-entry
      [:span (tr "auth.register") " "]
      [:a {:on-click #(st/emit! (rt/nav :auth-register))
           :tab-index "6"}
       (tr "auth.register-submit")]]]

    (when cfg/google-client-id
      [:a.btn-ocean.btn-large.btn-google-auth
       {:on-click login-with-google}
       "Login with Google"])

    (when cfg/gitlab-client-id
      [:a.btn-ocean.btn-large.btn-gitlab-auth
       {:on-click login-with-gitlab}
       [:img.logo
        {:src "/images/icons/brand-gitlab.svg"}]
       (tr "auth.login-with-gitlab-submit")])

    (when cfg/github-client-id
      [:a.btn-ocean.btn-large.btn-github-auth
       {:on-click login-with-github}
       [:img.logo
        {:src "/images/icons/brand-github.svg"}]
       (tr "auth.login-with-github-submit")])

    (when cfg/allow-demo-users
      [:div.links.demo
       [:div.link-entry
        [:span (tr "auth.create-demo-profile") " "]
        [:a {:on-click (st/emitf da/create-demo-profile)
             :tab-index "6"}
         (tr "auth.create-demo-account")]]])]])

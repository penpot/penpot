;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.auth
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.users :as du]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.ui.messages :refer [messages]]
   [uxbox.main.ui.auth.login :refer [login-page]]
   [uxbox.main.ui.auth.recovery :refer [recovery-page]]
   [uxbox.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [uxbox.main.ui.auth.register :refer [register-page]]
   [uxbox.main.repo :as rp]
   [uxbox.util.timers :as ts]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.router :as rt]))

(mf/defc goodbye-page
  [{:keys [locale] :as props}]
  [:div.goodbay
   [:h1 (t locale "auth.goodbye-title")]])

(mf/defc auth
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        locale (mf/deref i18n/locale)]
    [:*
     [:& messages]
     [:div.auth
      [:section.auth-sidebar
       [:a.logo i/logo]
       [:span.tagline (t locale "auth.sidebar-tagline")]]

      [:section.auth-content
       (case section
         :auth-register [:& register-page {:locale locale}]
         :auth-login    [:& login-page {:locale locale}]
         :auth-goodbye  [:& goodbye-page {:locale locale}]
         :auth-recovery-request [:& recovery-request-page {:locale locale}]
         :auth-recovery [:& recovery-page {:locale locale
                                           :params (:query-params route)}])]]]))

(defn- handle-email-verified
  [data]
  (let [msg (tr "settings.notifications.email-verified-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (rt/nav :settings-profile)
              du/fetch-profile)))

(defn- handle-email-changed
  [data]
  (let [msg (tr "settings.notifications.email-changed-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (rt/nav :settings-profile)
              du/fetch-profile)))

(defn- handle-authentication
  [tdata]
  (st/emit! (da/login-from-token tdata)))

(mf/defc verify-token
  [{:keys [route] :as props}]
  (let [token (get-in route [:query-params :token])]
    (mf/use-effect
     (fn []
       (->> (rp/mutation :verify-profile-token {:token token})
            (rx/subs
             (fn [tdata]
               (case (:type tdata)
                 :verify-email   (handle-email-verified tdata)
                 :change-email   (handle-email-changed tdata)
                 :authentication (handle-authentication tdata)
                 nil))
             (fn [error]
               (case (:code error)
                 :uxbox.services.mutations.profile/email-already-exists
                 (let [msg (tr "errors.email-already-exists")]
                   (ts/schedule 100 #(st/emit! (dm/error msg)))
                   (st/emit! (rt/nav :settings-profile)))

                 (let [msg (tr "errors.generic")]
                   (ts/schedule 100 #(st/emit! (dm/error msg)))
                   (st/emit! (rt/nav :settings-profile)))))))))

    [:div.verify-token
     i/loader-pencil]))

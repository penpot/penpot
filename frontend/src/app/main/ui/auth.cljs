;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth
  (:require
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :refer [login-page]]
   [app.main.ui.auth.recovery :refer [recovery-page]]
   [app.main.ui.auth.recovery-request :refer [recovery-request-page]]
   [app.main.ui.auth.register :refer [register-page]]
   [app.main.ui.icons :as i]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc goodbye-page
  [{:keys [locale] :as props}]
  [:div.goodbay
   [:h1 (t locale "auth.goodbye-title")]])

(mf/defc auth
  [{:keys [route] :as props}]
  (let [section (get-in route [:data :name])
        locale (mf/deref i18n/locale)]
    [:div.auth
     [:section.auth-sidebar
      [:a.logo {:href "/#/"} i/logo]
      [:span.tagline (t locale "auth.sidebar-tagline")]]

     [:section.auth-content
      (case section
        :auth-register [:& register-page {:locale locale}]
        :auth-login    [:& login-page {:locale locale}]
        :auth-goodbye  [:& goodbye-page {:locale locale}]
        :auth-recovery-request [:& recovery-request-page {:locale locale}]
        :auth-recovery [:& recovery-page {:locale locale
                                          :params (:query-params route)}])]]))

(defmulti handle-token (fn [token] (:iss token)))

(defmethod handle-token :verify-email
  [data]
  (let [msg (tr "settings.notifications.email-verified-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (rt/nav :auth-login))))

(defmethod handle-token :change-email
  [data]
  (let [msg (tr "settings.notifications.email-changed-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (rt/nav :settings-profile)
              du/fetch-profile)))

(defmethod handle-token :auth
  [tdata]
  (st/emit! (da/login-from-token tdata)))

(defmethod handle-token :default
  [tdata]
  (js/console.log "Unhandled token:" (pr-str tdata))
  (st/emit! (rt/nav :auth-login)))

(mf/defc verify-token
  [{:keys [route] :as props}]
  (let [token (get-in route [:query-params :token])]
    (mf/use-effect
     (fn []
       (->> (rp/mutation :verify-profile-token {:token token})
            (rx/subs
             (fn [tdata]
               (handle-token tdata))
             (fn [error]
               (case (:code error)
                 :email-already-exists
                 (let [msg (tr "errors.email-already-exists")]
                   (ts/schedule 100 #(st/emit! (dm/error msg)))
                   (st/emit! (rt/nav :auth-login)))

                 :email-already-validated
                 (let [msg (tr "errors.email-already-validated")]
                   (ts/schedule 100 #(st/emit! (dm/warn msg)))
                   (st/emit! (rt/nav :auth-login)))

                 (let [msg (tr "errors.generic")]
                   (ts/schedule 100 #(st/emit! (dm/error msg)))
                   (st/emit! (rt/nav :auth-login)))))))))

    [:div.verify-token
     i/loader-pencil]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.verify-token
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.static :as static]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(defmulti handle-token (fn [token] (:iss token)))

(defmethod handle-token :verify-email
  [data]
  (let [msg (tr "dashboard.notifications.email-verified-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (du/login-from-token data))))

(defmethod handle-token :change-email
  [_data]
  (let [msg (tr "dashboard.notifications.email-changed-successfully")]
    (ts/schedule 100 #(st/emit! (dm/success msg)))
    (st/emit! (rt/nav :settings-profile)
              (du/fetch-profile))))

(defmethod handle-token :auth
  [tdata]
  (st/emit! (du/login-from-token tdata)))

(defmethod handle-token :team-invitation
  [tdata]
  (case (:state tdata)
    :created
    (st/emit!
     (dm/success (tr "auth.notifications.team-invitation-accepted"))
     (du/fetch-profile)
     (rt/nav :dashboard-projects {:team-id (:team-id tdata)}))

    :pending
    (let [token    (:invitation-token tdata)
          route-id (:redirect-to tdata :auth-register)]
      (st/emit! (rt/nav route-id {} {:invitation-token token})))))

(defmethod handle-token :default
  [_tdata]
  (st/emit!
   (rt/nav :auth-login)
   (dm/warn (tr "errors.unexpected-token"))))

(mf/defc verify-token
  [{:keys [route] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        token (get-in route [:query-params :token])
        bad-token (mf/use-state false)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.default"))
      (->> (rp/cmd! :verify-token {:token token})
           (rx/subs
            (fn [tdata]
              (handle-token tdata))
            (fn [{:keys [type code] :as error}]
              (cond
                (or (= :validation type)
                    (= :invalid-token code)
                    (= :token-expired (:reason error)))
                (reset! bad-token true)

                (= :email-already-exists code)
                (let [msg (tr "errors.email-already-exists")]
                  (ts/schedule 100 #(st/emit! (dm/error msg)))
                  (st/emit! (rt/nav :auth-login)))

                (= :email-already-validated code)
                (let [msg (tr "errors.email-already-validated")]
                  (ts/schedule 100 #(st/emit! (dm/warn msg)))
                  (st/emit! (rt/nav :auth-login)))

                :else
                (let [msg (tr "errors.generic")]
                  (ts/schedule 100 #(st/emit! (dm/error msg)))
                  (st/emit! (rt/nav :auth-login))))))))

    (if @bad-token
      [:> static/invalid-token {}]
      [:div {:class (stl/css-case :verify-token new-css-system
                                  :global/verify-token (not new-css-system))}
       i/loader-pencil])))

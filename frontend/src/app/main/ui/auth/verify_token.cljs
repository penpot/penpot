;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.verify-token
  (:require
   [app.main.data.auth :as da]
   [app.main.data.common :as dcm]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.static :as static]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defmulti handle-token (fn [token] (:iss token)))

(defmethod handle-token :verify-email
  [data]
  (let [msg (tr "dashboard.notifications.email-verified-successfully")]
    (ts/schedule 1000 #(st/emit! (ntf/success msg)))
    (st/emit! (da/login-from-token data))))

(defmethod handle-token :change-email
  [_data]
  (let [msg (tr "dashboard.notifications.email-changed-successfully")]
    (ts/schedule 100 #(st/emit! (ntf/success msg)))
    (st/emit! (rt/nav :settings-profile)
              (du/refresh-profile))))

(defmethod handle-token :auth
  [tdata]
  (st/emit! (da/login-from-token tdata)))

(defmethod handle-token :team-invitation
  [tdata]
  (case (:state tdata)
    :created
    (let [team-id (:team-id tdata)]
      (st/emit!
       (ntf/success (tr "auth.notifications.team-invitation-accepted"))
       (du/refresh-profile)
       (dcm/go-to-dashboard-recent :team-id team-id)))

    :pending
    (let [token    (:invitation-token tdata)
          route-id (:redirect-to tdata :auth-register)]
      (st/emit! (rt/nav route-id {:invitation-token token})))))

(defmethod handle-token :default
  [_tdata]
  (st/emit!
   (rt/nav :auth-login)
   (ntf/warn (tr "errors.unexpected-token"))))

(mf/defc verify-token
  [{:keys [route] :as props}]
  (let [token (get-in route [:query-params :token])
        bad-token (mf/use-state false)]

    (mf/with-effect []
      (dom/set-html-title (tr "title.default"))
      (->> (rp/cmd! :verify-token {:token token})
           (rx/subs!
            (fn [tdata]
              (handle-token tdata))
            (fn [cause]
              (let [{:keys [type code] :as error} (ex-data cause)]
                (cond
                  (or (= :validation type)
                      (= :invalid-token code)
                      (= :token-expired (:reason error)))
                  (reset! bad-token true)

                  (= :email-already-exists code)
                  (let [msg (tr "errors.email-already-exists")]
                    (ts/schedule 100 #(st/emit! (ntf/error msg)))
                    (st/emit! (rt/nav :auth-login)))

                  (= :email-already-validated code)
                  (let [msg (tr "errors.email-already-validated")]
                    (ts/schedule 100 #(st/emit! (ntf/warn msg)))
                    (st/emit! (rt/nav :auth-login)))

                  :else
                  (let [msg (tr "errors.generic")]
                    (ts/schedule 100 #(st/emit! (ntf/error msg)))
                    (st/emit! (rt/nav :auth-login)))))))))

    (if @bad-token
      [:> static/invalid-token {}]
      [:> loader*  {:title (tr "labels.loading")
                    :overlay true}])))

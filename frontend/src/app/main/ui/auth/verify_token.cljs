;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.auth.verify-token
  (:require
   [app.config :as cf]
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
  (cf/external-notify-register-success (:profile-id data))
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
  [{:keys [state team-id org-team-id organization-name invitation-token] :as tdata}]
  (case state
    :created
    (if org-team-id
      (st/emit!
       (du/refresh-profile)
       (dcm/go-to-dashboard-recent :team-id org-team-id)
       (ntf/success (tr "auth.notifications.org-invitation-accepted" organization-name)))
      (st/emit!
       (du/refresh-profile)
       (dcm/go-to-dashboard-recent :team-id team-id)
       (ntf/success (tr "auth.notifications.team-invitation-accepted"))))

    :pending
    (let [route-id (:redirect-to tdata :auth-register)]
      (st/emit! (rt/nav route-id {:invitation-token invitation-token})))))

(defmethod handle-token :default
  [_tdata]
  (st/emit!
   (rt/nav :auth-login)
   (ntf/warn (tr "errors.unexpected-token"))))

(mf/defc verify-token*
  [{:keys [route]}]
  (let [token            (get-in route [:query-params :token])
        ;; Holds the specific failure reason when the token fails, or
        ;; nil while still loading / on success. Any non-nil keyword is
        ;; truthy, so this single state replaces the previous pair of
        ;; (bad-token? + bad-token-reason) hooks. Reasons:
        ;;   :token-expired   -> JWT past its :exp
        ;;   :email-mismatch  -> invitation email != logged-in email
        ;;   :invalid-token   -> corrupted / unknown / fallback
        bad-token-reason (mf/use-state nil)]

    (mf/with-effect [token]
      (dom/set-html-title (tr "title.default"))
      (->> (rp/cmd! :verify-token {:token token})
           (rx/subs!
            (fn [tdata]
              (handle-token tdata))
            (fn [cause]
              (let [{:keys [type code team-id reason] :as error} (ex-data cause)]
                (cond
                  (= :invalid-token-already-member code)
                  (st/emit!
                   (rt/nav :dashboard-recent {:team-id team-id}))

                  (= :org-not-found code)
                  (st/emit!
                   (rt/nav :dashboard-recent {:team-id team-id})
                   (ntf/error (tr "errors.org-not-found")))

                  (or (= :validation type)
                      (= :invalid-token code)
                      (= :token-expired reason))
                  (reset! bad-token-reason
                          (cond
                            (= :token-expired reason)  :token-expired
                            (= :email-mismatch reason) :email-mismatch
                            :else                      :invalid-token))

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

    (if @bad-token-reason
      [:> static/invalid-token {:reason @bad-token-reason}]
      [:> loader*  {:title (tr "labels.loading")
                    :overlay true}])))

(mf/defc verify-token-page*
  {::mf/lazy-load true}
  [props]
  [:> verify-token* props])

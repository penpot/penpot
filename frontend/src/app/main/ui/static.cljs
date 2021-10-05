;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.static
  (:require
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [rumext.alpha :as mf]))

(mf/defc static-header
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")
        on-click (mf/use-callback
                  (fn []
                    (let [profile (deref refs/profile)]
                      (if (du/is-authenticated? profile)
                        (let [team-id (du/get-current-team-id profile)]
                          (st/emit! (rt/nav :dashboard-projects {:team-id team-id})))
                        (st/emit! (rt/nav :auth-login {}))))))]

    [:section.exception-layout
     [:div.exception-header
      {:on-click on-click}
      i/logo]
     [:div.exception-content
      [:div.container children]]]))

(mf/defc not-found
  []
  [:> static-header {}
   [:div.image i/icon-empty]
   [:div.main-message (tr "labels.not-found.main-message")]
   [:div.desc-message (tr "labels.not-found.desc-message")]])

(mf/defc bad-gateway
  []
  [:> static-header {}
   [:div.image i/icon-empty]
   [:div.main-message (tr "labels.bad-gateway.main-message")]
   [:div.desc-message (tr "labels.bad-gateway.desc-message")]
   [:div.sign-info
    [:a.btn-primary.btn-small
     {:on-click (st/emitf #(dissoc % :exception))}
     (tr "labels.retry")]]])

(mf/defc service-unavailable
  []
  [:> static-header {}
   [:div.image i/icon-empty]
   [:div.main-message (tr "labels.service-unavailable.main-message")]
   [:div.desc-message (tr "labels.service-unavailable.desc-message")]
   [:div.sign-info
    [:a.btn-primary.btn-small
     {:on-click (st/emitf #(dissoc % :exception))}
     (tr "labels.retry")]]])

(mf/defc internal-error
  []
  [:> static-header {}
   [:div.image i/icon-empty]
   [:div.main-message (tr "labels.internal-error.main-message")]
   [:div.desc-message (tr "labels.internal-error.desc-message")]
   [:div.sign-info
    [:a.btn-primary.btn-small
     {:on-click (st/emitf (rt/assign-exception nil))}
     (tr "labels.retry")]]])

(mf/defc exception-page
  [{:keys [data] :as props}]
  (case (:type data)
    :not-found
    [:& not-found]

    :bad-gateway
    [:& bad-gateway]

    :service-unavailable
    [:& service-unavailable]

    [:& internal-error]))


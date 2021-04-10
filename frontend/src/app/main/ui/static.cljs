;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.static
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [app.main.ui.context :as ctx]
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [cuerdas.core :as str]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.main.ui.icons :as i]))

(defn- go-to-dashboard
  [profile]
  (let [team-id (da/current-team-id profile)]
    (st/emit! (rt/nav :dashboard-projects {:team-id team-id}))))

(mf/defc not-found
  [{:keys [error] :as props}]
  (let [profile (mf/deref refs/profile)]
    [:section.exception-layout
     [:div.exception-header
      {:on-click (partial go-to-dashboard profile)}
      i/logo]
     [:div.exception-content
      [:div.container
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.not-found.main-message")]
       [:div.desc-message (tr "labels.not-found.desc-message")]
       [:div.sign-info
        [:span (tr "labels.not-found.auth-info") " " [:b (:email profile)]]
        [:a.btn-primary.btn-small
         {:on-click (st/emitf (da/logout))}
         (tr "labels.sign-out")]]]]]))

(mf/defc bad-gateway
  [{:keys [error] :as props}]
  (let [profile (mf/deref refs/profile)]
    [:section.exception-layout
     [:div.exception-header
      {:on-click (partial go-to-dashboard profile)}
      i/logo]
     [:div.exception-content
      [:div.container
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.bad-gateway.main-message")]
       [:div.desc-message (tr "labels.bad-gateway.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small
         {:on-click (st/emitf #(dissoc % :exception))}
         (tr "labels.retry")]]]]]))

(mf/defc service-unavailable
  [{:keys [error] :as props}]
  (let [profile (mf/deref refs/profile)]
    [:section.exception-layout
     [:div.exception-header
      {:on-click (partial go-to-dashboard profile)}
      i/logo]
     [:div.exception-content
      [:div.container
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.service-unavailable.main-message")]
       [:div.desc-message (tr "labels.service-unavailable.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small
         {:on-click (st/emitf #(dissoc % :exception))}
         (tr "labels.retry")]]]]]))

(mf/defc internal-error
  [props]
  (let [profile (mf/deref refs/profile)]
    [:section.exception-layout
     [:div.exception-header
      {:on-click (partial go-to-dashboard profile)}
      i/logo]
     [:div.exception-content
      [:div.container
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.internal-error.main-message")]
       [:div.desc-message (tr "labels.internal-error.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small
         {:on-click (st/emitf (dm/assign-exception nil))}
         (tr "labels.retry")]]]]]))

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


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.static
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.globals :as globals]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [rumext.v2 :as mf]))

(mf/defc static-header
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")
        on-click (mf/use-callback #(set! (.-href globals/location) "/"))]
    [:section {:class (stl/css :exception-layout)}
     [:button
      {:class (stl/css :exception-header)
       :on-click on-click}
      i/logo-icon]
     [:div {:class (stl/css :deco-before)} i/logo-error-screen]

     [:div {:class (stl/css :exception-content)}
      [:div {:class (stl/css :container)} children]]

     [:div {:class (stl/css :deco-after)} i/logo-error-screen]]))

(mf/defc invalid-token
  []
  [:> static-header {}
   [:div {:class (stl/css :main-message)} (tr "errors.invite-invalid")]
   [:div {:class (stl/css :desc-message)} (tr "errors.invite-invalid.info")]])

(mf/defc not-found
  []
  [:> static-header {}
   [:div {:class (stl/css :main-message)} (tr "labels.not-found.main-message")]
   [:div {:class (stl/css :desc-message)} (tr "labels.not-found.desc-message")]])

(mf/defc bad-gateway
  []
  (let [handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> static-header {}
     [:div {:class (stl/css :main-message)} (tr "labels.bad-gateway.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.bad-gateway.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

(mf/defc service-unavailable
  []
  (let [handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> static-header {}
     [:div {:class (stl/css :main-message)} (tr "labels.service-unavailable.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.service-unavailable.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

(mf/defc internal-error
  []
  (let [handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> static-header {}
     [:div {:class (stl/css :main-message)} (tr "labels.internal-error.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.internal-error.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

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

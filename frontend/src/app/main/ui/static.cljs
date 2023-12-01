;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.static
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.globals :as globals]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [rumext.v2 :as mf]))

(mf/defc static-header
  {::mf/wrap-props false}
  [props]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        children (obj/get props "children")
        on-click (mf/use-callback #(set! (.-href globals/location) "/"))]
    (if new-css-system
      [:section {:class (stl/css :exception-layout)}
       [:button
        {:class (stl/css :exception-header)
         :on-click on-click}
        i/logo-icon]
       [:div {:class (stl/css :deco-before)} i/logo-error-screen]

       [:div {:class (stl/css :exception-content)}
        [:div {:class (stl/css :container)} children]]

       [:div {:class (stl/css :deco-after)} i/logo-error-screen]]
      [:section.exception-layout
       [:div.exception-header
        {:on-click on-click}
        i/logo]
       [:div.exception-content
        [:div.container children]]])))

(mf/defc invalid-token
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:> static-header {}
       [:div {:class (stl/css :main-message)} (tr "errors.invite-invalid")]
       [:div {:class (stl/css :desc-message)} (tr "errors.invite-invalid.info")]]

      [:> static-header {}
       [:div.image i/unchain]
       [:div.main-message (tr "errors.invite-invalid")]
       [:div.desc-message (tr "errors.invite-invalid.info")]])))

(mf/defc not-found
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:> static-header {}
       [:div {:class (stl/css :main-message)} (tr "labels.not-found.main-message")]
       [:div {:class (stl/css :desc-message)} (tr "labels.not-found.desc-message")]]

      [:> static-header {}
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.not-found.main-message")]
       [:div.desc-message (tr "labels.not-found.desc-message")]])))

(mf/defc bad-gateway
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    (if new-css-system
      [:> static-header {}
       [:div {:class (stl/css :main-message)} (tr "labels.bad-gateway.main-message")]
       [:div {:class (stl/css :desc-message)} (tr "labels.bad-gateway.desc-message")]
       [:div {:class (stl/css :sign-info)}
        [:button {:on-click handle-retry} (tr "labels.retry")]]]

      [:> static-header {}
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.bad-gateway.main-message")]
       [:div.desc-message (tr "labels.bad-gateway.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small {:on-click handle-retry} (tr "labels.retry")]]])))

(mf/defc service-unavailable
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    (if new-css-system
      [:> static-header {}
       [:div {:class (stl/css :main-message)} (tr "labels.service-unavailable.main-message")]
       [:div {:class (stl/css :desc-message)} (tr "labels.service-unavailable.desc-message")]
       [:div {:class (stl/css :sign-info)}
        [:button {:on-click handle-retry} (tr "labels.retry")]]]

      [:> static-header {}
       [:div.main-message (tr "labels.service-unavailable.main-message")]
       [:div.desc-message (tr "labels.service-unavailable.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small {:on-click handle-retry} (tr "labels.retry")]]])))

(mf/defc internal-error
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    (if new-css-system
      [:> static-header {}
       [:div {:class (stl/css :main-message)} (tr "labels.internal-error.main-message")]
       [:div {:class (stl/css :desc-message)} (tr "labels.internal-error.desc-message")]
       [:div {:class (stl/css :sign-info)}
        [:button {:on-click handle-retry} (tr "labels.retry")]]]

      [:> static-header {}
       [:div.image i/icon-empty]
       [:div.main-message (tr "labels.internal-error.main-message")]
       [:div.desc-message (tr "labels.internal-error.desc-message")]
       [:div.sign-info
        [:a.btn-primary.btn-small {:on-click handle-retry} (tr "labels.retry")]]])))

(mf/defc exception-page
  [{:keys [data] :as props}]
  (let [new-css-system   (features/use-feature "styles/v2")]
    [:& (mf/provider ctx/new-css-system) {:value new-css-system}
     (case (:type data)
       :not-found
       [:& not-found]

       :bad-gateway
       [:& bad-gateway]

       :service-unavailable
       [:& service-unavailable]

       [:& internal-error])]))

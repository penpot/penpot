;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.static
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]))

(mf/defc not-found-page
  [{:keys [error] :as props}]
  [:section.not-found-layout
   [:div.not-found-header i/logo]
   [:div.not-found-content
    [:div.message-container
     [:div.error-img i/icon-empty]
     [:div.main-message "404"]
     [:div.desc-message "Oops! Page not found"]
     [:a.btn-primary.btn-small "Go back"]]]])

(mf/defc not-authorized-page
  [{:keys [error] :as props}]
  [:section.not-found-layout
   [:div.not-found-header i/logo]
   [:div.not-found-content
    [:div.message-container
     [:div.error-img i/icon-lock]
     [:div.main-message "403"]
     [:div.desc-message "Sorry, you are not authorized to access this page."]
     #_[:a.btn-primary.btn-small "Go back"]]]])


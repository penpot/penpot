;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.emails.users
  (:require [uxbox.media :as md]
            [uxbox.emails.core :refer (defemail)]
            [uxbox.emails.layouts :as layouts]))

;; --- User Register

(defn- register-body-html
  [{:keys [name] :as ctx}]
  [:div
   [:img.img-header {:src (md/resolve-asset "images/email/img-header.jpg")
                     :alt "UXBOX"}]
   [:div.content
    [:table
     [:tbody
      [:tr
       [:td
        [:h1 "Hi " name]
        [:p "Welcome to uxbox."]
        [:p
         [:a.btn-primary {:href "#"} "Sign in"]]
        [:p "Sincerely," [:br] [:strong "The UXBOX Team"]]
        #_[:p "P.S. Having trouble signing up? please contact "
         [:a {:href "#"} "Support email"]]]]]]]])

(defn- register-body-text
  [{:keys [name] :as ctx}]
  (str "Hi " name "\n\n"
       "Welcome to uxbox!\n\n"
       "Sincerely, the UXBOX team.\n"))

(defemail :users/register
  :layout layouts/default
  :subject "UXBOX: Welcome!"
  :body {:text/html register-body-html
         :text/plain register-body-text})

;; --- Password Recovery

(defn- password-recovery-body-html
  [{:keys [name token] :as ctx}]
  [:div
   [:img.img-header {:src (md/resolve-asset "images/img-header.jpg")
                     :alt "UXBOX"}]
   [:div.content
    [:table
     [:tbody
      [:tr
       [:td
        [:h1 "Hi " name]
        [:p "A password recovery is requested."]
        [:p
         "Please, follow the following url in order to"
         "change your password."
         [:a {:href "#"} "http://uxbox.io/..."]]
        [:p "Sincerely," [:br] [:strong "The UXBOX Team"]]]]]]]])

(defn- password-recovery-body-text
  [{:keys [name token] :as ctx}]
  (str "Hi " name "\n\n"
       "A password recovery is requested.\n\n"
       "Please follow the following url in order to change the password:\n\n"
       "  http://uxbox.io/recovery/" token "\n\n\n"
       "Sincerely, the UXBOX team.\n"))

(defemail :users/password-recovery
  :layout layouts/default
  :subject "Password recovery requested."
  :body {:text/html password-recovery-body-html
         :text/plain password-recovery-body-text})


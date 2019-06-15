;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.api.debug-emails
  "A helper namespace for just render emails."
  (:require [clojure.edn :as edn]
            [hiccup.page :refer (html5)]
            [uxbox.emails :as emails]
            [uxbox.emails.core :as emails-core]))

;; (def +available-emails+
;;   {:users/register
;;    {:name "Cirilla"}
;;    :users/password-recovery
;;    {:name "Cirilla"
;;     :token "agNFhA6SolcFb4Us2NOTNWh0cfFDquVLAav400xQPjw"}})

;; (defn- render-emails-list
;;   []
;;   (html5
;;    [:section {:style "font-family: Monoid, monospace; font-size: 14px;"}
;;     [:h1 "Available emails"]
;;     [:table {:style "width: 500px;"}
;;      [:tbody
;;       [:tr
;;        (for [[type email] @emails-core/emails]
;;          [:tr
;;           [:td (pr-str type)]
;;           [:td
;;            [:a {:href (str "/debug/emails/email?id="
;;                            (pr-str type)
;;                            "&type=:text/html")}
;;             "(html)"]]
;;           [:td
;;            [:a {:href (str "/debug/emails/email?id="
;;                            (pr-str type)
;;                            "&type=:text/plain")}
;;             "(text)"]]])]]]]))

;; (defn list-emails
;;   [context]
;;   (http/ok (render-emails-list)
;;            {:content-type "text/html; charset=utf-8"}))

;; (defn- render-email
;;   [type content]
;;   (if (= type :text/html)
;;     content
;;     (html5
;;      [:pre content])))

;; (defn show-email
;;   [{params :query-params}]
;;   (let [id (edn/read-string (:id params))
;;         type (or (edn/read-string (:type params)) :text/html)
;;         params (-> (get +available-emails+ id)
;;                          (assoc :email/name id))
;;         email (emails/render params)
;;         content (->> (:body email)
;;                      (filter #(= (:uxbox.emails.core/type %) type))
;;                      (first)
;;                      (:content))]
;;     (-> (render-email type content)
;;         (http/ok {:content-type "text/html; charset=utf-8"}))))

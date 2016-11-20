;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.emails.core
  (:require [hiccup.core :refer (html)]
            [hiccup.page :refer (html4)]
            [suricatta.core :as sc]
            [uxbox.db :as db]
            [uxbox.config :as cfg]
            [uxbox.sql :as sql]
            [uxbox.emails.layouts :as layouts]
            [uxbox.util.blob :as blob]
            [uxbox.util.transit :as t]))

(def emails
  "A global state for registring emails."
  (atom {}))

(defmacro defemail
  [type & args]
  (let [email (apply hash-map args)]
    `(do
       (swap! emails assoc ~type ~email)
       nil)))

(defn- render-subject
  [{:keys [subject]} context]
  (cond
    (delay? subject) (deref subject)
    (ifn? subject) (subject context)
    (string? subject) subject
    :else (throw (ex-info "Invalid subject." {}))))

(defn- render-body
  [[type bodyfn] layout context]
  (let [layoutfn (get layout type)]
    {:content (cond-> (bodyfn context)
                layoutfn (layoutfn context)
                (= type :text/html) (html4))
     ::type type
     :type (subs (str type) 1)}))

(defn- render-body-alternatives
  [{:keys [layout body] :as email} context]
  (reduce #(conj %1 (render-body %2 layout context)) [:alternatives] body))

(defn render-email
  [email context]
  (let [config (:email cfg/config)
        from (or (:email/from context)
                 (:from config))
        reply-to (or (:email/reply-to context)
                     (:reply-to config)
                     from)]
    {:subject (render-subject email context)
     :body (render-body-alternatives email context)
     :to (:email/to context)
     :from from
     :reply-to reply-to}))

(def valid-priority? #{:high :low})
(def valid-email-identifier? #(contains? @emails %))

(defn render
  "Render a email as data structure."
  [{name :email/name :as context}]
  {:pre [(valid-email-identifier? name)]}
  (let [email (get @emails name)]
    (render-email email context)))

(defn send!
  "Schedule the email for sending."
  [{name :email/name
    priority :email/priority
    :or {priority :high}
    :as context}]
  {:pre [(valid-priority? priority)
         (valid-email-identifier? name)]}
  (let [email (get @emails name)
        email (render-email email context)
        data (-> email t/encode blob/encode)
        priority (case priority :low 1 :high 10)
        sqlv (sql/insert-email {:data data :priority priority})]
    (with-open [conn (db/connection)]
      (sc/atomic conn
        (sc/execute conn sqlv)))))

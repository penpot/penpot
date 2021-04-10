;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.settings.feedback
  "Feedback form."
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [app.main.repo :as rp]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::content ::us/not-empty-string)
(s/def ::subject ::us/not-empty-string)

(s/def ::feedback-form
  (s/keys :req-un [::subject ::content]))

(mf/defc feedback-form
  []
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::feedback-form)

        loading (mf/use-state false)

        on-succes
        (mf/use-callback
         (mf/deps profile)
         (fn [event]
           (reset! loading false)
           (st/emit! (dm/success (tr "labels.feedback-sent")))
           (swap! form assoc :data {} :touched {} :errors {})))

        on-error
        (mf/use-callback
         (mf/deps profile)
         (fn [{:keys [code] :as error}]
           (reset! loading false)
           (if (= code :feedback-disabled)
             (st/emit! (dm/error (tr "labels.feedback-disabled")))
             (st/emit! (dm/error (tr "errors.generic"))))))

        on-submit
        (mf/use-callback
         (mf/deps profile)
         (fn [form event]
           (reset! loading true)
           (let [data (:clean-data @form)]
             (->> (rp/mutation! :send-feedback data)
                  (rx/subs on-succes on-error)))))]

    [:& fm/form {:class "feedback-form"
                 :on-submit on-submit
                 :form form}

     ;; --- Feedback section
     [:h2 (tr "feedback.title")]
     [:p (tr "feedback.subtitle")]

     [:div.fields-row
      [:& fm/input {:label (tr "feedback.subject")
                    :name :subject}]]
     [:div.fields-row
      [:& fm/textarea
       {:label (tr "feedback.description")
        :name :content
        :rows 5}]]

     [:& fm/submit-button
      {:label (if @loading (tr "labels.sending") (tr "labels.send"))
       :disabled @loading}]

     [:hr]

     [:h2 (tr "feedback.discussions-title")]
     [:p (tr "feedback.discussions-subtitle1")]
     [:p (tr "feedback.discussions-subtitle2")]

     [:a.btn-secondary.btn-large
      {:href "https://github.com/penpot/penpot/discussions" :target "_blank"}
      (tr "feedback.discussions-go-to")]

     [:hr]

     [:h2 "Gitter"]
     [:p (tr "feedback.chat-subtitle")]
     [:a.btn-secondary.btn-large
      {:href "https://gitter.im/penpot/community" :target "_blank"}
      (tr "feedback.chat-start")]

     ]))

(mf/defc feedback-page
  []
  (mf/use-effect
    #(dom/set-html-title (tr "title.settings.feedback")))

  [:div.dashboard-settings
   [:div.form-container
    [:& feedback-form]]])

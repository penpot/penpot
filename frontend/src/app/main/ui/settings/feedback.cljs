;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.feedback
  "Feedback form."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::content ::us/not-empty-string)
(s/def ::subject ::us/not-empty-string)

(s/def ::feedback-form
  (s/keys :req-un [::subject ::content]))

(mf/defc feedback-form
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::feedback-form
                             :validators [(fm/validate-length :subject fm/max-length-allowed (tr "auth.name.too-long"))
                                          (fm/validate-not-empty :subject (tr "auth.name.not-all-space"))])
        loading (mf/use-state false)

        on-succes
        (mf/use-callback
         (mf/deps profile)
         (fn [_]
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
         (fn [form _]
           (reset! loading true)
           (let [data (:clean-data @form)]
             (->> (rp/cmd! :send-user-feedback data)
                  (rx/subs! on-succes on-error)))))]

    (if new-css-system
      [:& fm/form {:class (stl/css :feedback-form)
                   :on-submit on-submit
                   :form form}

       ;; --- Feedback section
       [:h2 {:class (stl/css :field-title)} (tr "feedback.title")]
       [:p {:class (stl/css :field-text)} (tr "feedback.subtitle")]

       [:div {:class (stl/css :fields-row)}
        [:& fm/input {:label (tr "feedback.subject")
                      :name :subject
                      :show-success? true}]]
       [:div {:class (stl/css :fields-row :description)}
        [:& fm/textarea
         {:label (tr "feedback.description")
          :name :content
          :rows 5}]]

       [:> fm/submit-button*
        {:label (if @loading (tr "labels.sending") (tr "labels.send"))
         :disabled @loading}]

       [:hr]

       [:h2 {:class (stl/css :field-title)} (tr "feedback.discourse-title")]
       [:p {:class (stl/css :field-text)} (tr "feedback.discourse-subtitle1")]

       [:a
        {:class (stl/css :btn-secondary :btn-large)
         :href "https://community.penpot.app"
         :target "_blank"}
        (tr "feedback.discourse-go-to")]
       [:hr]

       [:h2 {:class (stl/css :field-title)} (tr "feedback.twitter-title")]
       [:p {:class (stl/css :field-text)} (tr "feedback.twitter-subtitle1")]

       [:a
        {:class (stl/css :btn-secondary :btn-large)
         :href "https://twitter.com/penpotapp"
         :target "_blank"}
        (tr "feedback.twitter-go-to")]]

      ;; OLD
      [:& fm/form {:class "feedback-form"
                   :on-submit on-submit
                   :form form}

       ;; --- Feedback section
       [:h2.field-title (tr "feedback.title")]
       [:p.field-text (tr "feedback.subtitle")]

       [:div.fields-row
        [:& fm/input {:label (tr "feedback.subject")
                      :name :subject}]]
       [:div.fields-row
        [:& fm/textarea
         {:label (tr "feedback.description")
          :name :content
          :rows 5}]]

       [:> fm/submit-button*
        {:label (if @loading (tr "labels.sending") (tr "labels.send"))
         :disabled @loading}]

       [:hr]

       [:h2.field-title (tr "feedback.discourse-title")]
       [:p.field-text (tr "feedback.discourse-subtitle1")]

       [:a.btn-secondary.btn-large
        {:href "https://community.penpot.app" :target "_blank"}
        (tr "feedback.discourse-go-to")]
       [:hr]

       [:h2.field-title (tr "feedback.twitter-title")]
       [:p.field-text (tr "feedback.twitter-subtitle1")]

       [:a.btn-secondary.btn-large
        {:href "https://twitter.com/penpotapp" :target "_blank"}
        (tr "feedback.twitter-go-to")]])))

(mf/defc feedback-page
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (mf/use-effect
     #(dom/set-html-title (tr "title.settings.feedback")))

    (if new-css-system
      [:div {:class (stl/css :dashboard-settings)}
       [:div {:class (stl/css :form-container)}
        [:& feedback-form]]]

      ;; OLD
      [:div.dashboard-settings
       [:div.form-container
        [:& feedback-form]]])))

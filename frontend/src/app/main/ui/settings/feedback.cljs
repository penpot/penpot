;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.feedback
  "Feedback form."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.main.data.notifications :as ntf]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private schema:feedback-form
  [:map {:title "FeedbackForm"}
   [:subject [::sm/text {:max 250}]]
   [:type [:string {:max 250}]]
   [:content [::sm/text {:max 5000}]]
   [:error-report {:optional true} ::sm/text]
   [:error-href {:optional true} [::sm/text {:max 2048}]]])

(mf/defc feedback-form*
  {::mf/private true}
  [{:keys [error-report type error-href]}]
  (let [profile (mf/deref refs/profile)

        initial
        (mf/with-memo [error-href error-report]
          (d/without-nils
           {:subject ""
            :type (d/nilv type "")
            :content ""
            :error-href error-href
            :error-report error-report}))

        form
        (fm/use-form :schema schema:feedback-form
                     :initial initial)

        loading
        (mf/use-state false)

        report
        (mf/with-memo [error-report]
          (when error-report (wapi/create-blob error-report "text/plain")))

        on-download
        (mf/use-fn
         (mf/deps report)
         (fn [event]
           (dom/prevent-default event)
           (let [uri (wapi/create-uri report)]
             (dom/trigger-download-uri "report" "text/plain" uri)
             (tm/schedule-on-idle #(wapi/revoke-uri uri)))))


        on-succes
        (mf/use-fn
         (mf/deps profile)
         (fn [_]
           (reset! loading false)
           (st/emit! (ntf/success (tr "labels.feedback-sent")))
           (swap! form assoc :data {} :touched {} :errors {})))

        on-error
        (mf/use-fn
         (mf/deps profile)
         (fn [{:keys [code] :as error}]
           (reset! loading false)
           (if (= code :feedback-disabled)
             (st/emit! (ntf/error (tr "labels.feedback-disabled")))
             (st/emit! (ntf/error (tr "errors.generic"))))))

        on-submit
        (mf/use-fn
         (mf/deps profile)
         (fn [form _]
           (reset! loading true)
           (let [data (:clean-data @form)]
             (->> (rp/cmd! :send-user-feedback data)
                  (rx/subs! on-succes on-error)))))]


    [:& fm/form {:class (stl/css :feedback-form)
                 :on-submit on-submit
                 :form form}

     ;; --- Feedback section
     [:h2 {:class (stl/css :field-title :feedback-title)} (tr "feedback.title-contact-us")]
     [:p {:class (stl/css :field-text :feedback-title)} (tr "feedback.subtitle")]

     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:label (tr "feedback.subject")
                    :name :subject
                    :show-success? true}]]

     [:div {:class (stl/css :fields-row)}
      [:label {:class (stl/css :field-label)} (tr "feedback.type")]
      [:& fm/select {:label (tr "feedback.type")
                     :name :type
                     :options [{:label (tr "feedback.type.idea") :value "idea"}
                               {:label (tr "feedback.type.issue") :value "issue"}
                               {:label (tr "feedback.type.doubt") :value "doubt"}]}]]

     [:div {:class (stl/css :fields-row :description)}
      [:& fm/textarea
       {:class (stl/css :feedback-description)
        :label (tr "feedback.description")
        :name :content
        :placeholder (tr "feedback.description-placeholder")
        :rows 5}]]

     [:div {:class (stl/css :fields-row)}
      [:p {:class (stl/css :field-text)} (tr "feedback.penpot.link")]
      [:& fm/input {:label ""
                    :name :error-href
                    :placeholder "https://penpot.app/"
                    :show-success? true}]

      (when report
        [:a {:class (stl/css :link :download-button) :on-click on-download}
         (tr "labels.download" "report.txt")])]

     [:> fm/submit-button*
      {:label (if @loading (tr "labels.sending") (tr "labels.send"))
       :class (stl/css :feedback-button-link)
       :disabled @loading}]

     [:hr]

     [:h2 {:class (stl/css :feedback-title)} (tr "feedback.other-ways-contact")]


     [:a {:class (stl/css :link)
          :href "https://community.penpot.app"
          :target "_blank"}
      (tr "feedback.discourse-title")]
     [:p {:class (stl/css :field-text :bottom-margin)} (tr "feedback.discourse-subtitle1")]

     [:a {:class (stl/css :link)
          :href "https://x.com/penpotapp"
          :target "_blank"}
      (tr "feedback.twitter-title")]
     [:p {:class (stl/css :field-text)} (tr "feedback.twitter-subtitle1")]]))

(mf/defc feedback-page*
  [{:keys [error-report-id] :as props}]
  (mf/with-effect []
    (dom/set-html-title (tr "title.settings.feedback")))

  (let [report (when (= error-report-id (:id errors/last-report))
                 (:content errors/last-report))
        props  (mf/spread-props props {:error-report report})]

    [:div {:class (stl/css :dashboard-settings)}
     [:div {:class (stl/css :form-container)}
      [:> feedback-form* props]]]))

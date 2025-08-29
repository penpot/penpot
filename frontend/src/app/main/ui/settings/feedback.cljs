;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.feedback
  "Feedback form."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn schema:feedback-form [url-error]
  [:map {:title "FeedbackForm"}
   [:subject [::sm/text {:max 250}]]
   [:type [:string {:max 250}]]
   [:content [::sm/text {:max 5000}]]
   [:penpot-link [::sm/text {:max 2048 :value (or url-error "") :optional true}]]])

(mf/defc feedback-form
  {::mf/private true}
  [{:keys [report type url-error]}]
  (let [profile    (mf/deref refs/profile)
        initial    (mf/with-memo [url-error]
                     {:subject ""
                      :type (or type "")
                      :content ""
                      :penpot-link url-error})
        form       (fm/use-form :schema (schema:feedback-form url-error) :initial initial)
        loading    (mf/use-state false)
        report     (wapi/create-blob report "text/plain")
        report-uri (wapi/create-uri report)

        on-download
        (mf/use-fn
         (mf/deps report)
         (fn [event]
           (dom/prevent-default event)
           (dom/trigger-download-uri "report" "text/plain" report-uri)))

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
                    :name :penpot-link
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

(mf/defc feedback-page
  [{:keys [type report-id url-error]}]
  (mf/with-effect []
    (dom/set-html-title (tr "title.settings.feedback")))
  (let [report (.getItem js/localStorage report-id)]
    [:div {:class (stl/css :dashboard-settings)}
     [:div {:class (stl/css :form-container)}
      [:& feedback-form {:report report
                         :type type
                         :url-error url-error}]]]))

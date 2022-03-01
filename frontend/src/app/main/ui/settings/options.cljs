;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.settings.options
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::lang (s/nilable ::us/string))
(s/def ::theme (s/nilable ::us/not-empty-string))

(s/def ::options-form
  (s/keys :opt-un [::lang ::theme]))

(defn- on-success
  [_]
  (st/emit! (dm/success (tr "notifications.profile-saved"))))

(defn- on-submit
  [form _event]
  (let [data  (:clean-data @form)
        data  (cond-> data
                (empty? (:lang data))
                (assoc :lang nil))
        mdata {:on-success (partial on-success form)}]
    (st/emit! (du/update-profile (with-meta data mdata)))))

(mf/defc options-form
  [{:keys [locale] :as props}]
  (let [profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::options-form
                             :initial profile)]
    [:& fm/form {:class "options-form"
                 :on-submit on-submit
                 :form form}

     [:h2 (t locale "labels.language")]

     [:div.fields-row
      [:& fm/select {:options (into [{:label "Auto (browser)" :value "default"}]
                                    i18n/supported-locales)
                     :label (t locale "dashboard.select-ui-language")
                     :default ""
                     :name :lang
                     :data-test "setting-lang"}]]
     
     ;; TODO: Do not show as long as we only have one theme
     #_[:h2 (t locale "dashboard.theme-change")]
     #_[:div.fields-row
      [:& fm/select {:label (t locale "dashboard.select-ui-theme")
                     :name :theme
                     :default "default"
                     :options [{:label "Default" :value "default"}]
                     :data-test "theme-lang"}]]
     [:& fm/submit-button
      {:label (t locale "dashboard.update-settings")
       :data-test "submit-lang-change"}]]))

;; --- Password Page

(mf/defc options-page
  [{:keys [locale]}]
  (mf/use-effect
    #(dom/set-html-title (tr "title.settings.options")))

  [:div.dashboard-settings
   [:div.form-container
    {:data-test "settings-form"}
    [:& options-form {:locale locale}]]])

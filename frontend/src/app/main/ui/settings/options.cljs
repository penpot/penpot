;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.options
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.theme :as theme]
   [rumext.v2 :as mf]))

(def ^:private schema:options-form
  [:map {:title "OptionsForm"}
   [:lang {:optional true} [:string {:max 20}]]
   [:theme {:optional true} [:string {:max 250}]]])

(defn- on-success
  [_]
  (st/emit! (ntf/success (tr "notifications.profile-saved"))))

(defn- on-submit
  [form _event]
  (let [data  (:clean-data @form)]
    (st/emit! (du/update-profile data)
              (du/persist-profile {:on-success on-success}))))

(mf/defc options-form
  {::mf/wrap-props false}
  []
  (let [profile (mf/deref refs/profile)
        initial (mf/with-memo [profile]
                  (-> profile
                      (update :lang #(or % ""))
                      (update :theme #(if (= % "default")
                                        "dark"
                                        (or % "dark")))))

        form    (fm/use-form :schema schema:options-form
                             :initial initial)]

    [:& fm/form {:class (stl/css :options-form)
                 :on-submit on-submit
                 :form form}

     [:h3 (tr "labels.language")]

     [:div {:class (stl/css :fields-row)}
      [:& fm/select {:options (into [{:label "Auto (browser)" :value ""}]
                                    i18n/supported-locales)
                     :label (tr "dashboard.select-ui-language")
                     :default ""
                     :name :lang
                     :data-testid "setting-lang"}]]

     [:h3 (tr "dashboard.theme-change")]
     [:div {:class (stl/css :fields-row)}
      [:& fm/select {:label (tr "dashboard.select-ui-theme")
                     :name :theme
                     :default theme/default
                     :options [{:label (tr "dashboard.select-ui-theme.dark") :value "dark"}
                               {:label (tr "dashboard.select-ui-theme.light") :value "light"}
                               {:label (tr "dashboard.select-ui-theme.system") :value "system"}]
                     :data-testid "setting-theme"}]]

     [:> fm/submit-button*
      {:label (tr "dashboard.update-settings")
       :data-testid "submit-lang-change"
       :class (stl/css :btn-primary)}]]))

;; --- Password Page

(mf/defc options-page
  []
  (mf/use-effect
   #(dom/set-html-title (tr "title.settings.options")))

  [:div {:class (stl/css :dashboard-settings)}
   [:div {:class (stl/css :form-container) :data-testid "settings-form"}
    [:h2 (tr "labels.settings")]
    [:& options-form {}]]])


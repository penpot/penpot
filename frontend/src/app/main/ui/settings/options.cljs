;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.settings.options
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.text.layout :as tl]
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

(mf/defc options-form*
  []
  (let [profile (mf/deref refs/profile)
        initial (mf/with-memo [profile]
                  (-> profile
                      (update :lang #(or % ""))
                      (update :theme #(if (= % "default")
                                        "dark"
                                        (or % theme/default)))))

        form    (fm/use-form :schema schema:options-form
                             :initial initial)]

    [:& fm/form {:class (stl/css :options-form)
                 :on-submit on-submit
                 :form form}

     [:> heading* {:level 3
                   :typography t/headline-small
                   :class (stl/css :section-title)}
      (tr "labels.language")]

     [:div {:class (stl/css :fields-row)}
      [:& fm/select {:options (into [{:label "Auto (browser)" :value ""}]
                                    i18n/supported-locales)
                     :label (tr "dashboard.select-ui-language")
                     :default ""
                     :name :lang
                     :data-testid "setting-lang"}]]

     [:> heading* {:level 3
                   :typography t/headline-small
                   :class (stl/css :section-title)}
      (tr "dashboard.theme-change")]
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
       :disabled (= (:data @form) (:initial @form))
       :data-testid "submit-lang-change"
       :class (stl/css :btn-primary)}]]))

(defn ^:private go-settings-feedback
  [event]
  (dom/prevent-default event)
  (st/emit! (rt/nav :settings-feedback)))

(mf/defc webgl-settings*
  [{:keys [renderer]}]
  (let [wasm-renderer? (= renderer :wasm)
        handle-render-change
        (mf/use-fn
         (fn [enabled?]
           (st/emit! (ev/event {::ev/name (if enabled?
                                            "enable-webgl-rendering"
                                            "disable-webgl-rendering")
                                ::ev/origin "settings"})
                     (du/update-profile-props {:renderer (if enabled? :wasm :svg)})
                     (ntf/success (if enabled?
                                    (tr "webgl.toast.webgl-render-enabled")
                                    (tr "webgl.toast.webgl-render-disabled"))))))]
    [:section {:class (stl/css :webgl-container)}
     [:header {:class (stl/css :webgl-header)}
      [:> heading* {:class (stl/css :title) :level 2 :typography t/title-large} (tr "dashboard.webgl-switch.title")]
      [:> text* {:as "span" :class (stl/css :beta) :typography t/body-small} (tr "dashboard.webgl-switch.beta")]]
     [:> text* {:class (stl/css :description) :typography t/body-medium} (tr "dashboard.webgl-switch.description")]
     [:form {:class (stl/css :webgl-form)}
      [:> heading* {:level 3 :typography t/headline-small} (tr "dashboard.webgl-switch.status")]
      [:> switch* {:label (if wasm-renderer? (tr "dashboard.webgl-switch.enabled") (tr "dashboard.webgl-switch.disabled"))
                   :default-checked wasm-renderer?
                   :on-change handle-render-change}]]
     [:> text* {:typography t/body-medium :class (stl/css :feedback)} [:a {:href "#" :on-click go-settings-feedback :class (stl/css :link)} (tr "dashboard.webgl-switch.feedback") [:> icon* {:icon-id "arrow-up-right" :size "s"}]]]]))

(def ^:private no-text-language
  "Id of the \"None\" choice: the DS select shows \"--\" for a blank id."
  "none")

(mf/defc text-language-settings*
  "Text language the new files of the profile start with."
  {::mf/private true}
  [{:keys [language]}]
  (let [options
        (mf/with-memo []
          (into [{:id no-text-language :label (tr "dashboard.text-language.none")}]
                (comp (remove :header)
                      (map (fn [{:keys [id]}] {:id id :label (tl/language-label id)})))
                (tl/language-options nil)))

        handle-change
        (mf/use-fn
         (fn [id]
           (st/emit! (du/update-profile-props
                      {:default-text-language (when (not= no-text-language id) id)})
                     (ntf/success (tr "notifications.profile-saved")))))]

    [:section {:class (stl/css :text-language-container)}
     [:> heading* {:class (stl/css :title) :level 2 :typography t/title-large}
      (tr "dashboard.text-language.title")]
     [:> text* {:class (stl/css :description) :typography t/body-medium}
      (tr "dashboard.text-language.description")]
     [:div {:class (stl/css :text-language-form)}
      [:> select* {:default-selected (or language no-text-language)
                   :aria-label (tr "dashboard.text-language.title")
                   :options options
                   :on-change handle-change
                   :data-testid "setting-text-language"}]]]))

(mf/defc options-page*
  []
  (let [profile      (mf/deref refs/profile)
        renderer     (or (-> profile :props :renderer) :svg)
        cjk-feature? (features/use-feature "cjk-support")]
    (mf/use-effect
     #(dom/set-html-title (tr "title.settings.options")))

    [:section {:class (stl/css :dashboard-settings)
               :aria-labelledby "options-section-title"}
     [:*
      [:div {:class (stl/css :form-container) :data-testid "settings-form"}
       [:> heading* {:level 2
                     :typography t/title-large
                     :class (stl/css :page-title)
                     :id "options-section-title"}
        (tr "labels.settings")]
       [:> options-form*]]
      (when ^boolean cjk-feature?
        [:> text-language-settings* {:language (-> profile :props :default-text-language)}])
      (when (contains? cf/flags :render-switch)
        [:> webgl-settings* {:renderer renderer}])]]))

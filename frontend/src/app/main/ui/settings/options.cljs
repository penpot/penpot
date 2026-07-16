;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.settings.options
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.theme :as theme]
   [rumext.v2 :as mf]))


(def ^:private schema:options-form
  [:map {:title "OptionsForm"}
   [:lang {:optional true} [:string {:max 20}]]
   [:theme {:optional true} [:string {:max 250}]]])

(def ^:private schema:ui-scale-form
  [:map {:title "UiScaleForm"}
   [:ui-scale [::sm/one-of #{:compact :comfortable}]]])

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
                     (ntf/success (tr (if enabled?
                                        "webgl.toast.webgl-render-enabled"
                                        "webgl.toast.webgl-render-disabled"))))))]
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

(mf/defc ui-scale-settings*
  [{:keys [ui-scale]}]
  (let [initial (mf/with-memo [ui-scale]
                  {:ui-scale (if (= ui-scale 1.15) "comfortable" "compact")})

        ;; The form only holds the radio group state: there is no submit step,
        ;; the change is applied (and persisted) as soon as an option is picked.
        form    (fm/use-form :schema schema:ui-scale-form
                             :initial initial)

        handle-scale-change
        (mf/use-fn
         (fn [_ value]
           (let [scale (if (= value "comfortable") 1.15 1.0)]
             (st/emit! (ev/event {::ev/name "change-ui-scale"
                                  ::ev/origin "settings"
                                  :scale scale})
                       (du/update-profile-props {:ui-scale scale})
                       (ntf/success (tr "dashboard.ui-scale.updated"))))))]
    [:section {:class (stl/css :ui-scale-container)}
     [:header {:class (stl/css :ui-scale-header)}
      [:> heading* {:class (stl/css :title) :level 2 :typography t/title-large} (tr "dashboard.ui-scale.title")]]
     [:> text* {:class (stl/css :description) :typography t/body-medium} (tr "dashboard.ui-scale.description")]
     [:& fm/radio-buttons
      {:options [{:label (tr "dashboard.ui-scale.compact") :value "compact"}
                 {:label (tr "dashboard.ui-scale.comfortable") :value "comfortable"}]
       :name :ui-scale
       :form form
       :on-change handle-scale-change
       :class (stl/css :ui-scale-radio-btns)}]]))

(mf/defc options-page*
  []
  (let [profile  (mf/deref refs/profile)
        renderer (or (-> profile :props :renderer) :svg)
        ui-scale (or (-> profile :props :ui-scale) 1.0)]
    (mf/use-effect
     #(dom/set-html-title (tr "title.settings.options")))

    [:div {:class (stl/css :dashboard-settings)}
     [:*
      [:div {:class (stl/css :form-container) :data-testid "settings-form"}
       [:h2 (tr "labels.settings")]
       [:> options-form*]]
      (when (contains? cf/flags :render-switch)
        [:> webgl-settings* {:renderer renderer}])
      (when (contains? cf/flags :ui-scale)
        [:> ui-scale-settings* {:ui-scale ui-scale}])]]))

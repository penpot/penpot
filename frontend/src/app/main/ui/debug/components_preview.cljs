;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.debug.components-preview
  (:require-macros [app.main.style :refer [css styles]])
  (:require [app.common.data :as d]
            [app.main.data.users :as du]
            [app.main.refs :as refs]
            [app.main.store :as st]
            [app.main.ui.components.tests.test-component :as tc]
            [app.util.dom :as dom]
            [rumext.v2 :as mf]))

(mf/defc components-preview
  {::mf/wrap-props false}
  []
  (let [profile (mf/deref refs/profile)
        initial (mf/with-memo [profile]
                  (update profile :lang #(or % "")))
        initial-theme (:theme initial)
        on-change (fn [event]
                    (let [theme (dom/event->value event)
                          data (assoc initial :theme theme)]
                      (st/emit! (du/update-profile data))))

        colors [:bg-primary
                :bg-secondary
                :bg-tertiary
                :bg-cuaternary
                :fg-primary
                :fg-secondary
                :acc
                :acc-muted
                :acc-secondary
                :acc-tertiary]]

    [:section.debug-components-preview
     [:div {:class (css :themes-row)}
      [:h2 "Themes"]
      [:select {:label "Select theme color"
                :name :theme
                :default "default"
                :value initial-theme
                :on-change on-change}
       [:option {:label "Penpot Dark (default)" :value "default"}]
       [:option  {:label "Penpot Light" :value "light"}]]
      [:div {:class (css :wrapper)}
       (let [css (styles)]
         (for [color colors]
           [:div {:class (dom/classnames (get css color) true
                                         (get css :rect) true)}
            (d/name color)]))]]
     [:div {:class (css :components-row)}
      [:h2 {:class (css :title)} "Components"]
      [:div {:class (css :component-wrapper)}
       [:& tc/test-component
        {:action #(prn "ey soy un botón") :name "Click me"}]]]]))
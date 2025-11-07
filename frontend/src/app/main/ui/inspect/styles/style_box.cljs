;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.style-box
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- panel->title
  [type]
  (case type
    :variant    (tr "inspect.tabs.styles.variants-panel")
    :token      (tr "inspect.tabs.styles.token-panel")
    :geometry   (tr "inspect.tabs.styles.geometry-panel")
    :fill       (tr "labels.fill")
    :stroke     (tr "labels.stroke")
    :text       (tr "labels.text")
    :blur       (tr "labels.blur")
    :shadow     (tr "labels.shadow")
    :layout     (tr "labels.layout")
    :flex-element "Flex Element"
    :grid-element "Grid Element"
    :layout-element "Layout Element"
    :visibility (tr "labels.visibility")
    :svg        (tr "labels.svg")
    nil))

(mf/defc style-box*
  [{:keys [panel shorthand children]}]
  (let [expanded* (mf/use-state true)
        expanded (deref expanded*)

        title (panel->title panel)

        toggle-panel
        (mf/use-fn
         (mf/deps expanded)
         (fn []
           (reset! expanded* (not expanded))))

        copy-shorthand
        (mf/use-fn
         (mf/deps shorthand)
         (fn []
           (clipboard/to-clipboard (str shorthand))))]
    [:article {:class (stl/css :style-box)}
     [:header {:class (stl/css :disclosure-header)}
      [:button {:class (stl/css :disclosure-button)
                :aria-expanded expanded
                :aria-controls (str "style-box-" (d/name panel))
                :on-click toggle-panel
                :aria-label (tr "inspect.tabs.styles.toggle-style" title)}
       [:> icon* {:icon-id (if expanded "arrow-down" "arrow")
                  :class (stl/css :disclosure-icon)
                  :size "s"}]]
      [:span {:class (stl/css :panel-title)} title]
      (when shorthand
        [:> icon-button* {:variant "ghost"
                          :tooltip-placement "top-left"
                          :aria-label (tr "inspect.tabs.styles.copy-shorthand")
                          :on-click copy-shorthand
                          :icon i/clipboard}])]
     (when expanded
       [:div {:class (stl/css :style-box-content) :id (str "style-box-" (d/name panel))}
        [:div {:class (stl/css :style-box-panel-wrapper)} children]])]))

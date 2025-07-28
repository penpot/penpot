;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.drawing.frame
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.constants :refer [size-presets]]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc options*
  [{:keys [drawing-state]}]

  (let [show*          (mf/use-state false)
        show?          (deref show*)

        on-open
        (mf/use-fn
         (fn [] (reset! show* true)))

        on-close
        (mf/use-fn
         (fn [] (reset! show* false)))

        on-preset-selected
        (mf/use-fn
         (fn [event]
           (let [width (-> (dom/get-current-target event)
                           (dom/get-data "width")
                           (d/read-string))
                 height (-> (dom/get-current-target event)
                            (dom/get-data "height")
                            (d/read-string))]
             (st/emit! (dwd/set-default-size width height)))))

        orientation
        (when (:width drawing-state)
          (if (> (:width drawing-state) (:height drawing-state))
            :horizontal
            :vertical))

        on-orientation-change
        (mf/use-fn
         (fn [orientation]
           (let [orientation (keyword orientation)]
             (st/emit! (dwd/change-orientation orientation)))))]

    [:div {:class (stl/css :presets)}
     [:div {:class (stl/css-case  :presets-wrapper true
                                  :opened show?)
            :on-click on-open}
      [:span {:class (stl/css :select-name)} (tr "workspace.options.size-presets")]
      [:span {:class (stl/css :collapsed-icon)} i/arrow]
      [:& dropdown {:show show?
                    :on-close on-close}
       [:ul {:class (stl/css :custom-select-dropdown)}
        (for [preset size-presets]
          (if-not (:width preset)
            [:li {:key (:name preset)
                  :class (stl/css-case :dropdown-element true
                                       :disabled true)}
             [:span {:class (stl/css :preset-name)} (:name preset)]]

            (let [preset-match (and (= (:width preset) (:width drawing-state))
                                    (= (:height preset) (:height drawing-state)))]
              [:li {:key (:name preset)
                    :class (stl/css-case :dropdown-element true
                                         :match preset-match)
                    :data-width (str (:width preset))
                    :data-height (str (:height preset))
                    :on-click on-preset-selected}
               [:div {:class (stl/css :name-wrapper)}
                [:span {:class (stl/css :preset-name)} (:name preset)]
                [:span {:class (stl/css :preset-size)} (:width preset) " x " (:height preset)]]
               (when preset-match
                 [:span {:class (stl/css :check-icon)} i/tick])])))]]]

     [:& radio-buttons {:selected (or (d/name orientation) "")
                        :on-change on-orientation-change
                        :name "frame-orientation"
                        :wide true
                        :class (stl/css :radio-buttons)}
      [:& radio-button {:icon i/size-vertical
                        :value "vertical"
                        :id "size-vertical"}]
      [:& radio-button {:icon i/size-horizontal
                        :value "horizontal"
                        :id "size-horizontal"}]]]))


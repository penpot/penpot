;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.bool
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))



(mf/defc bool-options
  []
  (let [new-css-system         (mf/use-ctx ctx/new-css-system)

        selected               (mf/deref refs/selected-objects)
        head                   (first selected)
        selected-with-children (mf/deref refs/selected-shapes-with-children)
        has-invalid-shapes?    (->> selected-with-children
                                    (some (comp #{:frame :text} :type)))
        is-group? (and (some? head) (= :group (:type head)))
        is-bool?  (and (some? head) (= :bool (:type head)))
        head-bool-type (and (some? head) is-bool? (:bool-type head))

        first-not-group-like?
        (and (= (count selected) 1)
             (not (contains? #{:group :bool} (:type (first selected)))))

        disabled-bool-btns (or (empty? selected) has-invalid-shapes? first-not-group-like?)
        disabled-flatten   (or (empty? selected) has-invalid-shapes?)

        set-bool
        (mf/use-fn
         (mf/deps head head-bool-type selected)
         (fn [event]
           (let [bool-type (-> (dom/get-current-target event)
                               (dom/get-data "value")
                               (keyword))]
             (cond
               (> (count selected) 1)
               (st/emit! (dw/create-bool (if new-css-system
                                           (keyword bool-type)
                                           bool-type)))

               (and (= (count selected) 1) is-group?)
               (st/emit! (dw/group-to-bool (:id head) (if new-css-system
                                                        (keyword bool-type)
                                                        bool-type)))

               (and (= (count selected) 1) is-bool?)
               (if (= head-bool-type (if new-css-system
                                       (keyword bool-type)
                                       bool-type))
                 (st/emit! (dw/bool-to-group (:id head)))
                 (st/emit! (dw/change-bool-type (:id head) (if new-css-system
                                                             (keyword bool-type)
                                                             bool-type))))))))

        set-bool-refactor
        (mf/use-fn
         (mf/deps  selected is-group?  is-bool?)
         (fn [bool-type]
           (let [bool-type (if new-css-system
                             (keyword bool-type)
                             bool-type)]
             (cond
               (> (count selected) 1)
               (st/emit! (dw/create-bool bool-type))

               (and (= (count selected) 1) is-group?)
               (st/emit! (dw/group-to-bool (:id head) bool-type))

               (and (= (count selected) 1) is-bool?)
               (if (= head-bool-type bool-type)
                 (st/emit! (dw/bool-to-group (:id head)))
                 (st/emit! (dw/change-bool-type (:id head) bool-type)))))))

        flatten-objects (mf/use-fn  #(st/emit! (dw/convert-selected-to-path)))]

    (when (not (and disabled-bool-btns disabled-flatten))
      (if new-css-system
        [:div {:class (stl/css :boolean-options)}
         [:div {:class (stl/css :bool-group)}
          [:& radio-buttons {:selected (d/name head-bool-type)
                             :on-change set-bool-refactor
                             :name "bool-options"}
           [:& radio-button {:icon i/boolean-union-refactor
                             :value "union"
                             :disabled disabled-bool-btns
                             :title (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
                             :id :union}]
           [:& radio-button {:icon i/boolean-difference-refactor
                             :value "difference"
                             :disabled disabled-bool-btns
                             :title (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
                             :id :difference}]
           [:& radio-button {:icon i/boolean-intersection-refactor
                             :value "intersection"
                             :disabled disabled-bool-btns
                             :title (str (tr "intersection") " (" (sc/get-tooltip :bool-intersection) ")")
                             :id :intersection}]
           [:& radio-button {:icon i/boolean-exclude-refactor
                             :value "exclude"
                             :disabled disabled-bool-btns
                             :title (str (tr "exclude") " (" (sc/get-tooltip :bool-exclude) ")")
                             :id :exclude}]]]

         [:div {:class (stl/css :bool-group)}
          [:button
           {:title (tr "workspace.shape.menu.flatten")
            :class (stl/css-case
                    :flatten true
                    :disabled disabled-flatten)
            :disabled disabled-flatten
            :on-click flatten-objects}
           i/boolean-flatten-refactor]]]

        [:div.align-options
         [:div.align-group
          [:div.align-button.tooltip.tooltip-bottom
           {:alt (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
            :class (dom/classnames :disabled disabled-bool-btns
                                   :selected (= head-bool-type :union))
            :data-value :union
            :on-click set-bool}
           i/bool-union]

          [:div.align-button.tooltip.tooltip-bottom
           {:alt (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
            :class (dom/classnames :disabled disabled-bool-btns
                                   :selected (= head-bool-type :difference))
            :data-value :difference
            :on-click set-bool}
           i/bool-difference]

          [:div.align-button.tooltip.tooltip-bottom
           {:alt (str (tr "workspace.shape.menu.intersection") " (" (sc/get-tooltip :bool-intersection) ")")
            :class (dom/classnames :disabled disabled-bool-btns
                                   :selected (= head-bool-type :intersection))
            :data-value :intersection
            :on-click set-bool}
           i/bool-intersection]

          [:div.align-button.tooltip.tooltip-bottom
           {:alt (str (tr "workspace.shape.menu.exclude") " (" (sc/get-tooltip :bool-exclude) ")")
            :class (dom/classnames :disabled disabled-bool-btns
                                   :selected (= head-bool-type :exclude))
            :data-value :exclude
            :on-click (set-bool :intersection)}
           i/bool-exclude]]

         [:div.align-group
          [:div.align-button.tooltip.tooltip-bottom
           {:alt (tr "workspace.shape.menu.flatten")
            :class (dom/classnames :disabled disabled-flatten)
            :on-click flatten-objects}
           i/bool-flatten]]]))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.bool
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc bool-options
  []
  (let [selected (mf/deref refs/selected-objects)
        selected-with-children (mf/deref refs/selected-shapes-with-children)

        has-invalid-shapes? (->> selected-with-children
                                 (some (comp #{:frame :text} :type)))

        first-not-group-like?
        (and (= (count selected) 1)
             (not (contains? #{:group :bool} (:type (first selected)))))

        disabled-bool-btns (or (empty? selected) has-invalid-shapes? first-not-group-like?)
        disabled-flatten (or (empty? selected) has-invalid-shapes?)

        head (first selected)
        is-group? (and (some? head) (= :group (:type head)))
        is-bool?  (and (some? head) (= :bool (:type head)))
        head-bool-type (and (some? head) is-bool? (:bool-type head))

        set-bool
        (fn [bool-type]
          #(cond
             (> (count selected) 1)
             (st/emit! (dw/create-bool bool-type))

             (and (= (count selected) 1) is-group?)
             (st/emit! (dw/group-to-bool (:id head) bool-type))

             (and (= (count selected) 1) is-bool?)
             (if (= head-bool-type bool-type)
               (st/emit! (dw/bool-to-group (:id head)))
               (st/emit! (dw/change-bool-type (:id head) bool-type)))))]

    [:div.align-options
     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
        :class (dom/classnames :disabled disabled-bool-btns
                               :selected (= head-bool-type :union))
        :on-click (set-bool :union)}
       i/bool-union]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
        :class (dom/classnames :disabled disabled-bool-btns
                               :selected (= head-bool-type :difference))
        :on-click (set-bool :difference)}
       i/bool-difference]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (str (tr "workspace.shape.menu.intersection") " (" (sc/get-tooltip :bool-intersection) ")")
        :class (dom/classnames :disabled disabled-bool-btns
                               :selected (= head-bool-type :intersection))
        :on-click (set-bool :intersection)}
       i/bool-intersection]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (str (tr "workspace.shape.menu.exclude") " (" (sc/get-tooltip :bool-exclude) ")")
        :class (dom/classnames :disabled disabled-bool-btns
                               :selected (= head-bool-type :exclude))
        :on-click (set-bool :exclude)}
       i/bool-exclude]]

     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.shape.menu.flatten")
        :class (dom/classnames :disabled disabled-flatten)
        :on-click (st/emitf (dw/convert-selected-to-path))}
       i/bool-flatten]]]))


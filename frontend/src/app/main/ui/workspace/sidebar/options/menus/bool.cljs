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
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private flatten-icon
  (i/icon-xref :boolean-flatten (stl/css :flatten-icon)))

(mf/defc bool-options
  []
  (let [selected               (mf/deref refs/selected-objects)
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
         (mf/deps  selected is-group?  is-bool?)
         (fn [bool-type]
           (let [bool-type (keyword bool-type)]
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
      [:div {:class (stl/css :boolean-options)}
       [:div {:class (stl/css :bool-group)}
        [:& radio-buttons {:selected (d/name head-bool-type)
                           :class (stl/css :boolean-radio-btn)
                           :on-change set-bool
                           :name "bool-options"}
         [:& radio-button {:icon i/boolean-union
                           :value "union"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
                           :id "bool-opt-union"}]
         [:& radio-button {:icon i/boolean-difference
                           :value "difference"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
                           :id "bool-opt-differente"}]
         [:& radio-button {:icon i/boolean-intersection
                           :value "intersection"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.intersection") " (" (sc/get-tooltip :bool-intersection) ")")
                           :id "bool-opt-intersection"}]
         [:& radio-button {:icon i/boolean-exclude
                           :value "exclude"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.exclude") " (" (sc/get-tooltip :bool-exclude) ")")
                           :id "bool-opt-exclude"}]]]

       [:button
        {:title (tr "workspace.shape.menu.flatten")
         :class (stl/css-case
                 :flatten-button true
                 :disabled disabled-flatten)
         :disabled disabled-flatten
         :on-click flatten-objects}
        flatten-icon]])))

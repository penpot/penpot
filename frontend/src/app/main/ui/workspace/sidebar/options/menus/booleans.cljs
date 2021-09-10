;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.booleans
  (:require

   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]
   ))

(mf/defc booleans-options
  []
  (let [selected (mf/deref refs/selected-shapes)
        disabled (and (some? selected)
                      (<= (count selected) 1))

        do-boolean-union (st/emitf (dw/create-bool :union))
        do-boolean-difference (st/emitf (dw/create-bool :difference))
        do-boolean-intersection (st/emitf (dw/create-bool :intersection))
        do-boolean-exclude (st/emitf (dw/create-bool :exclude))]

    [:div.align-options
     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.shape.menu.union")
        :class (when disabled "disabled")
        :on-click do-boolean-union}
       i/boolean-union]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.shape.menu.difference")
        :class (when disabled "disabled")
        :on-click do-boolean-difference}
       i/boolean-difference]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.shape.menu.intersection")
        :class (when disabled "disabled")
        :on-click do-boolean-intersection}
       i/boolean-intersection]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.shape.menu.exclude")
        :class (when disabled "disabled")
        :on-click do-boolean-exclude}
       i/boolean-exclude]]]))


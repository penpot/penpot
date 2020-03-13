;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i :include-macros true]))

;; --- Left toolbar (Component)

(mf/defc left-sidebar
  {:wrap [mf/wrap-memo]}
   [:div.left-toolbar
    [:div.left-toolbar-inside
     [:ul.left-toolbar-options
      [:li.tooltip.tooltip-right
       {:alt "Artboard"}
       i/artboard]
      [:li.tooltip.tooltip-right
       {:alt "Box"}
       i/box]
      [:li.tooltip.tooltip-right
       {:alt "Circle"}
       i/circle]
      [:li.tooltip.tooltip-right
       {:alt "Text"}
       i/text]
      [:li.tooltip.tooltip-right
       {:alt "Insert image"}
       i/image]
      [:li.tooltip.tooltip-right
       {:alt "Pencil tool"}
       i/pencil]
      [:li.tooltip.tooltip-right
       {:alt "Curves tool"}
       i/curve]]

     [:ul.left-toolbar-options.panels
      [:li.tooltip.tooltip-right
       {:alt "Layers"}
       i/layers]
      [:li.tooltip.tooltip-right
       {:alt "Libraries"}
       i/icon-set]
      [:li.tooltip.tooltip-right
       {:alt "History"}
       i/undo-history]
      [:li.tooltip.tooltip-right
       {:alt "Palette"}
       i/palette]]]])

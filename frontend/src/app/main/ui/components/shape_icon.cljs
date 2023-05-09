;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.shape-icon
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))


(mf/defc element-icon
  [{:keys [shape main-instance?] :as props}]
  (if (ctk/instance-head? shape)
    (if main-instance?
      i/component
      i/component-copy)
    (case (:type shape)
      :frame (cond
               (and (ctl/flex-layout? shape) (ctl/col? shape))
               i/layout-columns

               (and (ctl/flex-layout? shape) (ctl/row? shape))
               i/layout-rows

               (ctl/grid-layout? shape)
               i/grid-layout-mode

               :else
               i/artboard)
      :image i/image
      :line i/line
      :circle i/circle
      :path i/curve
      :rect i/box
      :text i/text
      :group (if (:masked-group? shape)
               i/mask
               i/folder)
      :bool (case (:bool-type shape)
              :difference   i/bool-difference
              :exclude      i/bool-exclude
              :intersection i/bool-intersection
              #_:default    i/bool-union)
      :svg-raw i/file-svg
      nil)))

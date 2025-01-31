;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.shape-icon
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(mf/defc element-icon
  {::mf/wrap-props false}
  [{:keys [shape main-instance?]}]
  (if (ctk/instance-head? shape)
    (if main-instance?
      (if (ctk/is-variant? shape)
        i/variant
        i/component)
      i/component-copy)
    (case (:type shape)
      :frame (cond
               (ctk/is-variant-container? shape)
               i/component

               (and (ctl/flex-layout? shape) (ctl/col? shape))
               i/flex-horizontal

               (and (ctl/flex-layout? shape) (ctl/row? shape))
               i/flex-vertical

               (ctl/grid-layout? shape)
               i/flex-grid

               :else
               i/board)
      ;; TODO -> THUMBNAIL ICON
      :image i/img
      :line (if (cts/has-images? shape) i/img i/path)
      :circle (if (cts/has-images? shape) i/img i/elipse)
      :path (if (cts/has-images? shape) i/img i/path)
      :rect (if (cts/has-images? shape) i/img i/rectangle)
      :text i/text
      :group (if (:masked-group shape)
               i/mask
               i/group)
      :bool (case (:bool-type shape)
              :difference   i/boolean-difference
              :exclude      i/boolean-exclude
              :intersection i/boolean-intersection
              #_:default    i/boolean-union)
      :svg-raw i/img
      nil)))


(mf/defc element-icon-by-type
  [{:keys [type main-instance?] :as props}]
  (if main-instance?
    i/component
    (case type
      :frame i/board
      :image i/img
      :shape i/path
      :text i/text
      :mask i/mask
      :group i/group
      nil)))

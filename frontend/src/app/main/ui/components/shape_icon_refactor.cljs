;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.shape-icon-refactor
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))


(mf/defc element-icon-refactor
  {::mf/wrap-props false}
  [{:keys [shape main-instance?]}]
  (if (ctk/instance-head? shape)
    (if main-instance?
      i/component-refactor
      i/copy-refactor)
    (case (:type shape)
      :frame (cond
               (and (ctl/flex-layout? shape) (ctl/col? shape))
               i/flex-vertical-refactor

               (and (ctl/flex-layout? shape) (ctl/row? shape))
               i/flex-horizontal-refactor

               ;; TODO: GRID ICON

               :else
               i/board-refactor)
      ;; TODO -> THUMBNAIL ICON
      :image i/img-refactor
      :line i/path-refactor
      :circle i/elipse-refactor
      :path i/path-refactor
      :rect i/rectangle-refactor
      :text i/text-refactor
      :group (if (:masked-group shape)
               i/mask-refactor
               i/group-refactor)
      :bool (case (:bool-type shape)
              :difference   i/boolean-difference-refactor
              :exclude      i/boolean-exclude-refactor
              :intersection i/boolean-intersection-refactor
              #_:default    i/boolean-union-refactor)
      :svg-raw i/file-svg
      nil)))


(mf/defc element-icon-refactor-by-type
  [{:keys [type main-instance?] :as props}]
  (if main-instance?
    i/component-refactor
    (case type
      :frame i/board-refactor
      :image i/img-refactor
      :shape i/path-refactor
      :text i/text-refactor
      :mask i/mask-refactor
      :group i/group-refactor
      nil)))

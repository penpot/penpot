;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.positions
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.modifiers :as ctm]))

(defn child-modifiers
  [_parent _transformed-parent-bounds _child child-bounds cell-data]
  (ctm/move-modifiers
   (gpt/subtract (:start-p cell-data) (gpo/origin child-bounds))))

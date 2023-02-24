;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.grid-layout.layout-data :as glld]
   [app.common.geom.shapes.grid-layout.positions :as glp]))

(dm/export glld/calc-layout-data)
(dm/export glld/get-cell-data)
(dm/export glp/child-modifiers)

(defn get-drop-index
  [frame objects _position]
  (dec (count (get-in objects [frame :shapes]))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.frame :as frame]
   [uxbox.main.ui.shapes.shape :as shape]))

(def shape-wrapper shape/shape-wrapper)
(def frame-wrapper shape/frame-wrapper)

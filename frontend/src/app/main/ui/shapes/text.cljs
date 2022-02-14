;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.text
  (:require
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc text-shape
  {::mf/wrap-props false}
  [props]

  (let [{:keys [position-data]} (obj/get props "shape")]
    (if (some? position-data)
      [:> svg/text-shape props]
      [:> fo/text-shape props])))

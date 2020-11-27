;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.common
  (:require
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.effects :as we]
   [rumext.alpha :as mf]))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")]
      [:> shape-container {:shape shape
                           :on-mouse-down (we/use-mouse-down shape)
                           :on-context-menu (we/use-context-menu shape)
                           :on-pointer-enter (we/use-pointer-enter shape)
                           :on-pointer-leave (we/use-pointer-leave shape)}
       [:& component {:shape shape}]])))

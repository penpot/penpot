;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.path
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.main.constants :as c]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.workspace.shapes.common :as common]
   [app.main.data.workspace.drawing :as dr]
   [app.util.dom :as dom]
   [app.main.streams :as ms]
   [app.util.timers :as ts]))

(mf/defc path-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        hover? (or (mf/deref refs/current-hover) #{})
        on-mouse-down   (mf/use-callback
                         (mf/deps shape)
                         #(common/on-mouse-down % shape))
        on-context-menu (mf/use-callback
                         (mf/deps shape)
                         #(common/on-context-menu % shape))
        on-double-click (mf/use-callback
                         (mf/deps shape)
                         (fn [event]
                           (when (and (not (::dr/initialized? shape)) (hover? (:id shape)))
                             (do
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (st/emit! (dw/start-edition-mode (:id shape)))))))]

    [:g.shape {:on-double-click on-double-click
               :on-mouse-down on-mouse-down
               :on-context-menu on-context-menu}
     [:& path/path-shape {:shape shape :background? true}]]))


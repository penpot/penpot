; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.coordinates
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [rumext.v2 :as mf]))

(mf/defc coordinates
  [{:keys [colorpalette?]}]
  (let [coords (hooks/use-rxsub ms/mouse-position)]
    [:div {:class (stl/css-case :container-color-palette-open colorpalette?
                                :container true)}
     [:span {:alt "x" :class (stl/css :coordinate)}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y" :class (stl/css :coordinate)}
      (str "Y: " (:y coords "-"))]]))

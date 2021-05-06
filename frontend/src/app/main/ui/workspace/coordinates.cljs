; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.coordinates
  (:require
   [app.main.ui.hooks :as hooks]
   [app.main.streams :as ms]
   [rumext.alpha :as mf]))

(mf/defc coordinates
  [{:keys [colorpalette?]}]
  (let [coords (hooks/use-rxsub ms/mouse-position)]
    [:ul.coordinates {:class (when colorpalette? "color-palette-open")}
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

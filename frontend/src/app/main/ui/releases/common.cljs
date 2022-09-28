;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.common
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defmulti render-release-notes :version)

(mf/defc navigation-bullets
  [{:keys [slide navigate total]}]
  [:ul.step-dots
   (for [i (range total)]
     [:li {:class (dom/classnames :current (= slide i))
           :on-click #(navigate i)}])])

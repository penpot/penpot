;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.reorder-handler
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as ic]
   [rumext.v2 :as mf]))

(mf/defc reorder-handler
  {::mf/forward-ref true}
  [_ ref]
  [:*
   [:div {:ref ref :class (stl/css :reorder)}
    [:> icon*
     {:icon-id ic/reorder
      :class (stl/css :reorder-icon)
      :aria-hidden true}]]
   [:hr {:class (stl/css :reorder-separator-top)}]
   [:hr {:class (stl/css :reorder-separator-bottom)}]])

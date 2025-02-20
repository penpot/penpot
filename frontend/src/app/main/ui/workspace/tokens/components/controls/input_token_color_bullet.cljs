;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.components.controls.input-token-color-bullet
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.workspace.tokens.token :as wtt]
   [rumext.v2 :as mf]))

(def ^:private schema::input-token-color-bullet
  [:map
   [:color [:maybe :string]]
   [:on-click fn?]])

(mf/defc input-token-color-bullet*
  {::mf/props :obj
   ::mf/schema schema::input-token-color-bullet}
  [{:keys [color on-click]}]
  [:div {:class (stl/css :input-token-color-bullet)
         :on-click on-click}
   (if-let [color' (wtt/color-bullet-color color)]
     [:> color-bullet {:color color' :mini true}]
     [:div {:class (stl/css :input-token-color-bullet-placeholder)}])])

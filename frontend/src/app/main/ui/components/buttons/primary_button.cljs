;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.buttons.primary-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

(mf/defc primary-button
  {::mf/wrap-props false}
  [{:keys [children type on-click ]}]
  (let []
    [:button {:class (dm/str type " " (stl/css :button)) :type type :on-click on-click } children]))

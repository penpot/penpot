;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.tests.test-component
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc test-component [{:keys [action icon name ]}]
  [:button.test-component
   {:class (css :button)
    :tab-index "0"
    :on-click action
    :on-key-down (fn [event]
                   (when (kbd/enter? event)
                     (action event)))}
   
   (when icon [:span.logo icon])
   name])

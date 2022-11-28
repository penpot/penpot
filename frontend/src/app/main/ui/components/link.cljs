;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.link
  (:require
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc link [{:keys [action name klass data-test]}]
  [:a {:on-click action
       :klass klass
       :on-key-down (fn [event]
                      (when (kbd/enter? event)
                        (action event)))
       :tabindex "0"
       :data-test data-test}
   name])

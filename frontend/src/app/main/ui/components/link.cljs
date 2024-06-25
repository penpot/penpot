;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.link
  (:require
   [app.common.data :as d]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc link
  {::mf/wrap-props false}
  [{:keys [action class data-testid keyboard-action children]}]
  (let [keyboard-action (d/nilv keyboard-action action)]
    [:a {:on-click action
         :class class
         :on-key-down (fn [event]
                        (when ^boolean (kbd/enter? event)
                          (keyboard-action event)))
         :tab-index "0"
         :data-testid data-testid}
     children]))

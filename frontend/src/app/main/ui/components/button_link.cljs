;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.button-link
  (:require
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc button-link
  {::mf/wrap-props false}
  [{:keys [action icon name klass]}]
  (let [on-key-down (mf/use-fn
                     (mf/deps action)
                     (fn [event]
                       (when (kbd/enter? event)
                         (action event))))]
    [:a.btn-primary.btn-large.button-link
     {:class klass
      :tab-index "0"
      :on-click action
      :on-key-down on-key-down}
     [:span.logo icon]
     name]))

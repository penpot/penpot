;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.link-button
  (:require
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc link-button
  {::mf/wrap-props false}
  [{:keys [on-click class value data-testid]}]
  (let [on-key-down (mf/use-fn
                     (mf/deps on-click)
                     (fn [event]
                       (when (kbd/enter? event)
                         (when (fn? on-click)
                           (on-click event)))))]
    [:input {:type "button"
             :class class
             :value value
             :tab-index "0"
             :on-click on-click
             :on-key-down on-key-down
             :data-testid data-testid}]))

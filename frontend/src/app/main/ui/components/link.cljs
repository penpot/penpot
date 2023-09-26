;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.link
  (:require
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc link
  {::mf/wrap-props false}
  [{:keys [on-click class data-test on-key-enter children]}]
  (let [on-key-enter (or on-key-enter on-click)
        on-key-down
        (mf/use-fn
         (mf/deps on-key-enter)
         (fn [event]
           (when (and (kbd/enter? event) (fn? on-key-enter))
             (on-key-enter event))))]
    [:a {:on-click on-click
         :on-key-down on-key-down
         :class class
         :tab-index "0"
         :data-test data-test}
     children]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.copy-button
  (:require
   [app.main.ui.icons :as i]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc copy-button [{:keys [data on-copied]}]
  (let [just-copied (mf/use-state false)]
    (mf/use-effect
     (mf/deps @just-copied)
     (fn []
       (when @just-copied
         (when (fn? on-copied)
           (on-copied))
         (let [sub (timers/schedule 1000 #(reset! just-copied false))]
           ;; On unmount we dispose the timer
           #(rx/-dispose sub)))))

    [:button.copy-button
     {:on-click #(when-not @just-copied
                   (reset! just-copied true)
                   (wapi/write-to-clipboard (if (fn? data) (data) data)))}
     (if @just-copied
       i/tick
       i/copy)]))

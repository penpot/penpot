;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.copy-button
  (:require
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [app.util.webapi :as wapi]
   [app.util.timers :as timers]
   [app.main.ui.icons :as i]))

(mf/defc copy-button [{:keys [data]}]
  (let [just-copied (mf/use-state false)]
    (mf/use-effect
     (mf/deps @just-copied)
     (fn []
       (when @just-copied
         (let [sub (timers/schedule 1000 #(reset! just-copied false))]
           ;; On umounto we dispose the timer
           #(rx/-dispose sub)))))

    [:button.copy-button
     {:on-click #(when-not @just-copied
                   (do
                     (reset! just-copied true)
                     (wapi/write-to-clipboard data)))}
     (if @just-copied
       i/tick
       i/copy)]))

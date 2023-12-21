;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.copy-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(mf/defc copy-button [{:keys [data on-copied children class]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        just-copied (mf/use-state false)]
    (mf/use-effect
     (mf/deps @just-copied)
     (fn []
       (when @just-copied
         (when (fn? on-copied)
           (on-copied))
         (let [sub (timers/schedule 1000 #(reset! just-copied false))]
           ;; On unmount we dispose the timer
           #(rx/-dispose sub)))))
    (if new-css-system
      [:button {:class (dm/str class " " (stl/css-case :copy-button true
                                     :copy-wrapper (some? children)))
                :on-click #(when-not @just-copied
                             (reset! just-copied true)
                             (wapi/write-to-clipboard (if (fn? data) (data) data)))}

       (when children
         children)
       [:span {:class (stl/css :icon-btn)}
        (if @just-copied
         i/tick-refactor
         i/clipboard-refactor)]]

      [:button.copy-button
       {:on-click #(when-not @just-copied
                     (reset! just-copied true)
                     (wapi/write-to-clipboard (if (fn? data) (data) data)))}
       (if @just-copied
         i/tick
         i/copy)])))

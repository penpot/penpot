;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.copy-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.events :as-alias ev]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [rumext.v2 :as mf]))

(mf/defc copy-button
  {::mf/props :obj}
  [{:keys [data on-copied children class]}]
  (let [active* (mf/use-state false)
        active? (deref active*)

        class   (dm/str class " "
                        (stl/css-case
                         :copy-button  (not (some? children))
                         :copy-wrapper (some? children)))

        on-click
        (mf/use-fn
         (mf/deps data)
         (fn [event]
           (when-not (dom/get-boolean-data event "active")
             (reset! active* true)
             (tm/schedule 1000 #(reset! active* false))
             (when (fn? on-copied) (on-copied event))
             (wapi/write-to-clipboard
              (if (fn? data) (data) data)))))]

    [:button {:class class
              :data-active (dm/str active?)
              :on-click on-click}
     children
     [:span {:class (stl/css :icon-btn)}
      (if active?
        i/tick
        i/clipboard)]]))

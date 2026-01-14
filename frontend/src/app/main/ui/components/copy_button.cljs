;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.copy-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.event :as-alias ev]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

(mf/defc copy-button*
  [{:keys [data on-copied children class aria-label]}]
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
             (clipboard/to-clipboard
              (if (fn? data) (data) data)))))]

    [:button {:class class
              :aria-label (or aria-label (tr "labels.copy"))
              :data-active (dm/str active?)
              :on-click on-click}
     children
     [:span {:class (stl/css :icon-btn)}
      (if active?
        deprecated-icon/tick
        deprecated-icon/clipboard)]]))

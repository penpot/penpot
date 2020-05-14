;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.components.editable-label
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as timers]
   [uxbox.util.data :refer [classnames]]))

(mf/defc editable-label
  [{:keys [ value on-change on-cancel edit readonly class-name]}]
  (let [input (mf/use-ref nil)
        state (mf/use-state (:editing false))
        is-editing (or edit (:editing @state))
        start-editing (fn []
                        (swap! state assoc :editing true)
                        (timers/schedule 100 #(dom/focus! (mf/ref-val input))))
        stop-editing (fn [] (swap! state assoc :editing false))
        cancel-editing (fn []
                         (stop-editing)
                         (when on-cancel (on-cancel)))
        on-dbl-click (fn [e] (when (not readonly) (start-editing)))
        on-key-up (fn [e]
                    (cond
                      (kbd/esc? e)
                      (cancel-editing)

                      (kbd/enter? e)
                      (let [value (-> e dom/get-target dom/get-value)]
                        (on-change value)
                        (stop-editing))))
        ]

    (if is-editing
      [:div.editable-label {:class class-name}
       [:input.editable-label-input {:ref input
                                     :default-value value
                                     :on-key-down on-key-up}]
       [:span.editable-label-close {:on-click cancel-editing} i/close]]
      [:span.editable-label {:class class-name
                             :on-double-click on-dbl-click} value]
      )))

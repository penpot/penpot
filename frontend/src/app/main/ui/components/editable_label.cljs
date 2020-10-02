;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.editable-label
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [app.util.data :refer [classnames]]))

(mf/defc editable-label
  [{:keys [value on-change on-cancel editing? disable-dbl-click? class-name]}]
  (let [input (mf/use-ref nil)
        state (mf/use-state (:editing false))
        is-editing (:editing @state)
        start-editing (fn []
                        (swap! state assoc :editing true)
                        (timers/schedule 100 #(dom/focus! (mf/ref-val input))))
        stop-editing (fn [] (swap! state assoc :editing false))
        accept-editing (fn []
                         (when (:editing @state)
                           (let [value (-> (mf/ref-val input) dom/get-value)]
                             (on-change value)
                             (stop-editing))))
        cancel-editing (fn []
                         (stop-editing)
                         (when on-cancel (on-cancel)))
        on-dbl-click (fn [e] (when (not disable-dbl-click?) (start-editing)))
        on-key-up (fn [e]
                    (cond
                      (kbd/esc? e)
                      (cancel-editing)

                      (kbd/enter? e)
                      (accept-editing)))]

    (mf/use-effect
     (mf/deps editing?)
     (fn []
       (when (and editing? (not (:editing @state)))
         (start-editing))))

    (if is-editing
      [:div.editable-label {:class class-name}
       [:input.editable-label-input {:ref input
                                     :default-value value
                                     :on-key-up on-key-up
                                     :on-blur cancel-editing}]
       [:span.editable-label-close {:on-click cancel-editing} i/close]]
      [:span.editable-label {:class class-name
                             :on-double-click on-dbl-click} value])))

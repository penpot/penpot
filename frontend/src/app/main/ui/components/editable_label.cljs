;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.editable-label
  (:require
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [rumext.v2 :as mf]))

(mf/defc editable-label
  [{:keys [value on-change on-cancel editing? disable-dbl-click? class-name] :as props}]
  (let [display-value (get props :display-value value)
        tooltip (get props :tooltip)
        input (mf/use-ref nil)
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
        on-dbl-click (fn [_] (when (not disable-dbl-click?) (start-editing)))
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
                             :title tooltip
                             :on-double-click on-dbl-click} display-value])))

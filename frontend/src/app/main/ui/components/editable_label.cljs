;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.editable-label
  (:require
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [rumext.v2 :as mf]))

(mf/defc editable-label
  {::mf/wrap-props false}
  [props]
  (let [display-value     (unchecked-get props "display-value")
        value             (unchecked-get props "value")
        on-change         (unchecked-get props "on-change")
        on-cancel         (unchecked-get props "on-cancel")
        editing?          (unchecked-get props "editing")
        dbl-click?        (unchecked-get props "disable-dbl-click")
        class             (unchecked-get props "class")
        tooltip           (unchecked-get props "tooltip")

        new-css-system    (mf/use-ctx ctx/new-css-system)
        input-ref         (mf/use-ref nil)
        internal-editing* (mf/use-state false)
        internal-editing? (deref internal-editing*)

        start-edition
        (mf/use-fn
         (fn []
           (reset! internal-editing* true)
           (timers/schedule 100 (fn []
                                  (when-let [node (mf/ref-val input-ref)]
                                    (dom/focus! node))))))

        stop-edition
        (mf/use-fn #(reset! internal-editing* false))

        accept-edition
        (mf/use-fn
         (mf/deps internal-editing? on-change stop-edition)
         (fn []
           (when internal-editing?
             (let [value (dom/get-value (mf/ref-val input-ref))]
               (when (fn? on-change)
                 (on-change value))

               (stop-edition)))))

        cancel-edition
        (mf/use-fn
         (mf/deps stop-edition on-cancel)
         (fn []
           (stop-edition)
           (when (fn? on-cancel)
             (on-cancel))))


        on-dbl-click
        (mf/use-fn
         (mf/deps dbl-click? start-edition)
         (fn [_]
           (when-not dbl-click?
             (start-edition))))

        on-key-up
        (mf/use-fn
         (mf/deps cancel-edition accept-edition)
         (fn [event]
           (cond
             (kbd/esc? event)
             (cancel-edition)

             (kbd/enter? event)
             (accept-edition))))]

    (mf/with-effect [editing? internal-editing? start-edition]
      (when (and editing? (not internal-editing?))
        (start-edition)))

    (if ^boolean internal-editing?
      [:div.editable-label {:class class}
       [:input.editable-label-input
        {:ref input-ref
         :default-value value
         :on-key-up on-key-up
         :on-blur cancel-edition}]

       [:span.editable-label-close {:on-click cancel-edition}
        (if ^boolean new-css-system
          i/delete-text-refactor
          i/close)]]
      [:span.editable-label
       {:class class
        :title tooltip
        :on-double-click on-dbl-click}
       display-value])))

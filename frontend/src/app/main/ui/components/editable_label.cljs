;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.editable-label
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.constants :refer [max-input-length]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [rumext.v2 :as mf]))

(mf/defc editable-label*
  [{:keys [value class-input class-label is-editing tooltip display-value on-change on-cancel]}]
  (let [input-ref         (mf/use-ref nil)
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

        on-key-up
        (mf/use-fn
         (mf/deps cancel-edition accept-edition)
         (fn [event]
           (cond
             (kbd/esc? event)
             (cancel-edition)

             (kbd/enter? event)
             (accept-edition))))]

    (mf/with-effect [is-editing internal-editing? start-edition]
      (when (and is-editing (not internal-editing?))
        (start-edition)))

    (if ^boolean internal-editing?
      [:input {:class [(stl/css :editable-label-input) class-input]
               :ref input-ref
               :default-value value
               :on-key-up on-key-up
               :max-length max-input-length
               :on-blur cancel-edition}]

      [:span {:class [(stl/css :editable-label-text) class-label]
              :title tooltip}
       display-value])))

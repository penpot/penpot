;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.inline-edition
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc inline-edition
  [{:keys [content on-end max-length] :as props}]
  (let [name      (mf/use-state content)
        input-ref (mf/use-ref)

        on-input
        (mf/use-callback
         (fn [event]
           (->> (dom/get-target-val event)
                (reset! name))))

        on-cancel
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (on-end @name)))

        on-click
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)))

        on-blur
        (mf/use-callback
         (fn [event]
           (let [name (dom/get-target-val event)]
             (on-end name))))

        on-keyup
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (cond
             (kbd/esc? event)
             (on-cancel)

             (kbd/enter? event)
             (let [name (dom/get-target-val event)]
               (on-end name)))))]

    (mf/use-effect
     (fn []
       (let [node (mf/ref-val input-ref)]
         (dom/focus! node)
         (dom/select-text! node))))

    [:div {:class (stl/css :edit-wrapper)}
     [:input {:class       (stl/css :element-title)
              :value       @name
              :ref         input-ref
              :on-click    on-click
              :on-change   on-input
              :on-key-down on-keyup
              :on-blur     on-blur
              :max-length  max-length}]
     [:span {:class (stl/css :close)
             :on-click on-cancel} i/close]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.search-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc search-bar
  {::mf/wrap-props false}
  [props]
  (let [children    (unchecked-get props "children")
        on-change   (unchecked-get props "on-change")
        value       (unchecked-get props "value")
        on-clear    (unchecked-get props "clear-action")
        placeholder (unchecked-get props "placeholder")
        icon        (unchecked-get props "icon")
        autofocus   (unchecked-get props "auto-focus")
        id          (unchecked-get props "id")
        input-class (unchecked-get props "class")

        handle-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (on-change value event))))

        handle-clear
        (mf/use-fn
         (mf/deps on-clear on-change)
         (fn [event]
           (if on-clear
             (on-clear event)
             (on-change "" event))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (dom/get-target event)]
             (when ^boolean enter? (dom/blur! node))
             (when ^boolean esc? (dom/blur! node)))))]
    [:span {:class (stl/css-case :search-box true
                                 :has-children (some? children))}
     children
     [:div {:class (dm/str input-class " " (stl/css :search-input-wrapper))}
      icon
      [:input {:id id
               :on-change handle-change
               :value value
               :auto-focus autofocus
               :placeholder placeholder
               :on-key-down handle-key-down}]
      (when (not= "" value)
        [:button {:class (stl/css :clear)
                  :on-click handle-clear}
         i/delete-text])]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.search-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc search-bar*
  [{:keys [id class value placeholder icon-id auto-focus on-change on-clear children]}]
  (let [handle-change
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
     [:div {:class [class (stl/css :search-input-wrapper)]}
      (when icon-id
        [:> icon* {:icon-id icon-id
                   :size "s"
                   :class (stl/css :icon)}])
      [:input {:id id
               :class (stl/css :search-input)
               :on-change handle-change
               :value value
               :auto-focus auto-focus
               :auto-complete "off"
               :placeholder placeholder
               :on-key-down handle-key-down}]
      (when (not= "" value)
        [:button {:class (stl/css :clear-icon)
                  :on-click handle-clear}
         [:> icon* {:icon-id i/delete-text
                    :size "s"}]])]]))

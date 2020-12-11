;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.effects
  (:require
   [rumext.alpha :as mf]
   [app.util.dom :as dom]
   [app.main.data.workspace.selection :as dws]
   [app.main.store :as st]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.ui.keyboard :as kbd]))

(defn use-pointer-enter
  [{:keys [id]}]
  (mf/use-callback
   (mf/deps id)
   (fn []
     (st/emit! (dws/change-hover-state id true)))))

(defn use-pointer-leave
  [{:keys [id]}]
  (mf/use-callback
   (mf/deps id)
   (fn []
     (st/emit! (dws/change-hover-state id false)))))

(defn use-context-menu
  [shape]
  (mf/use-callback
   (mf/deps shape)
   (fn [event]
     (dom/prevent-default event)
     (dom/stop-propagation event)
     (let [position (dom/get-client-position event)]
       (st/emit! (dw/show-shape-context-menu {:position position :shape shape}))))))

(defn use-mouse-down
  [{:keys [id type blocked]}]
  (mf/use-callback
   (mf/deps id type blocked)
   (fn [event]
     (let [selected @refs/selected-shapes
           edition @refs/selected-edition
           selected? (contains? selected id)
           drawing? @refs/selected-drawing-tool
           button (.-which (.-nativeEvent event))]
       (when-not blocked
         (cond
           (not= 1 button)
           nil

           drawing?
           nil

           (= type :frame)
           (when selected?
             (do
               (dom/stop-propagation event)
               (st/emit! (dw/start-move-selected))))

           :else
           (do
             (dom/stop-propagation event)
             (if selected?
               (when (kbd/shift? event)
                 (st/emit! (dw/select-shape id true)))
               (do
                 (when-not (or (empty? selected) (kbd/shift? event))
                   (st/emit! (dw/deselect-all)))
                 (st/emit! (dw/select-shape id))))

             (when (not= edition id)
               (st/emit! (dw/start-move-selected))))))))))

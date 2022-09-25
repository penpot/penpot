;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.effects
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.selection :as dws]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

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

(defn use-mouse-down
  [{:keys [id type blocked]}]
  (mf/use-callback
   (mf/deps id type blocked)
   (fn [event]
     (let [selected @refs/selected-shapes
           edition @refs/selected-edition
           selected? (contains? selected id)
           drawing? @refs/selected-drawing-tool
           button (.-which (.-nativeEvent event))
           shift? (kbd/shift? event)

           allow-click? (and (not blocked)
                             (not drawing?)
                             (not edition))]

       (when (and (= button 1) allow-click?)
         (cond
           (and (= type :frame) selected?)
           (do
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (st/emit! (dw/start-move-selected)))

           (not= type :frame)
           (do
             (dom/prevent-default event)
             (dom/stop-propagation event)

             (let [toggle-selected? (and selected? shift?)
                   deselect? (and (not selected?) (seq selected) (not shift?))]
               (apply
                st/emit!
                (cond-> []
                  ;; Deselect shapes before doing a selection or click outside
                  deselect?
                  (conj (dw/deselect-all))

                  ;; Shift click to add a shape to the selection
                  toggle-selected?
                  (conj (dw/select-shape id true))

                  ;; Simple click to select
                  (not selected?)
                  (conj (dw/select-shape id))

                  ;; Mouse down to start moving a shape
                  (not= edition id)
                  (conj (dw/start-move-selected))))))))))))

(defn use-double-click
  "This effect will consume the event and stop the propagation so double clicks on shapes
  will not select the frame"
  [{:keys [id]}]
  (mf/use-callback
   (mf/deps id)
   (fn [event]
     (dom/stop-propagation event)
     (dom/prevent-default event))))

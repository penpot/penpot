;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.common
  (:require
   [rumext.alpha :as mf]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.keyboard :as kbd]
   [app.util.dom :as dom]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]))

(defn- on-mouse-down
  [event {:keys [id type] :as shape}]
  (let [selected @refs/selected-shapes
        selected? (contains? selected id)
        drawing? @refs/selected-drawing-tool
        button (.-which (.-nativeEvent event))]
    (when-not (:blocked shape)
      (cond
        (not= 1 button)
        nil

        drawing?
        nil

        (= type :frame)
        (when selected?
          (dom/stop-propagation event)
          (st/emit! (dw/start-move-selected)))

        :else
        (do
          (dom/stop-propagation event)
          (if selected?
            (when (kbd/shift? event)
              (st/emit! (dw/select-shape id true)))
            (do
              (when-not (or (empty? selected) (kbd/shift? event))
                (st/emit! dw/deselect-all))
              (st/emit! (dw/select-shape id))))

          (st/emit! (dw/start-move-selected)))))))

(defn on-context-menu
  [event shape]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [position (dom/get-client-position event)]
    (st/emit! (dw/show-shape-context-menu {:position position :shape shape}))))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")
          on-mouse-down (mf/use-callback
                         (mf/deps shape)
                         #(on-mouse-down % shape))
          on-context-menu (mf/use-callback
                           (mf/deps shape)
                           #(on-context-menu % shape))]
      [:g.shape {:on-mouse-down on-mouse-down
                 :on-context-menu on-context-menu}
       [:& component {:shape shape}]])))



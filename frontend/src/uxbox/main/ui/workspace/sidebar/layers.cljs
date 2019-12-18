;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.layers
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.data :refer [classnames enumerate]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer (tr)]))

(def ^:private shapes-iref
  (-> (l/key :shapes)
      (l/derive st/state)))

;; --- Helpers

(defn- element-icon
  [item]
  (case (:type item)
    :icon (icon/icon-svg item)
    :image i/image
    :line i/line
    :circle i/circle
    :path i/curve
    :rect i/box
    :text i/text
    :group i/folder
    nil))

;; --- Layer Name

(mf/defc layer-name
  [{:keys [shape] :as props}]
  (let [local (mf/use-state {})
        on-blur (fn [event]
                  (let [target (dom/event->target event)
                        parent (.-parentNode target)
                        parent (.-parentNode parent)
                        name (dom/get-value target)]
                    (set! (.-draggable parent) true)
                    (st/emit! (dw/rename-shape (:id shape) name))
                    (swap! local assoc :edition false)))
        on-key-down (fn [event]
                      (js/console.log event)
                      (when (kbd/enter? event)
                        (on-blur event)))
        on-click (fn [event]
                   (dom/prevent-default event)
                   (let [parent (.-parentNode (.-target event))
                         parent (.-parentNode parent)]
                     (set! (.-draggable parent) false))
                   (swap! local assoc :edition true))]
    (if (:edition @local)
      [:input.element-name
       {:type "text"
        :on-blur on-blur
        :on-key-down on-key-down
        :auto-focus true
        :default-value (:name shape "")}]
      [:span.element-name
       {:on-double-click on-click}
       (:name shape "")])))

;; --- Layer Item

(mf/defc layer-item
  [{:keys [shape selected index] :as props}]
  ;; (prn "layer-item" index (:name shape))
  (letfn [(toggle-blocking [event]
            (dom/stop-propagation event)
            (let [{:keys [id blocked]} shape]
              (st/emit! (dw/set-blocked-attr id (not blocked)))))

          (toggle-visibility [event]
            (dom/stop-propagation event)
            (let [{:keys [id hidden]} shape]
              (st/emit! (dw/set-hidden-attr id (not hidden)))))

          (select-shape [event]
            (dom/prevent-default event)
            (let [id (:id shape)]
              (cond
                (or (:blocked shape)
                    (:hidden shape))
                nil

                (.-ctrlKey event)
                (st/emit! (dw/select-shape id))

                (> (count selected) 1)
                (st/emit! dw/deselect-all
                          (dw/select-shape id))
                :else
                (st/emit! dw/deselect-all
                          (dw/select-shape id)))))

          (on-drop [item monitor]
            (st/emit! ::dw/page-data-update))

          (on-hover [item monitor]
            (st/emit! (dw/change-shape-order {:id (:shape-id item)
                                              :index index})))]
    (let [selected? (contains? selected (:id shape))
          [dprops dnd-ref] (use-sortable
                            {:type "layer-item"
                             :data {:shape-id (:id shape)
                                    :page-id (:page shape)
                                    :index index}
                             :on-hover on-hover
                             :on-drop on-drop})]
      [:li {:ref dnd-ref
            :class (classnames
                    :selected selected?
                    :dragging-TODO (:dragging? dprops))}
       [:div.element-list-body {:class (classnames :selected selected?)
                                :on-click select-shape
                                :on-double-click #(dom/stop-propagation %)}
        [:div.element-actions
         [:div.toggle-element {:class (when-not (:hidden shape) "selected")
                               :on-click toggle-visibility}
          i/eye]
         [:div.block-element {:class (when (:blocked shape) "selected")
                              :on-click toggle-blocking}
          i/lock]]
        [:div.element-icon (element-icon shape)]
        [:& layer-name {:shape shape}]]])))

(mf/defc canvas-item
  [{:keys [canvas shapes selected index] :as props}]
  (letfn [(toggle-blocking [event]
            (dom/stop-propagation event)
            (let [{:keys [id blocked]} canvas]
              (st/emit! (dw/set-blocked-attr id (not blocked)))))

          (toggle-visibility [event]
            (dom/stop-propagation event)
            (let [{:keys [id hidden]} canvas]
              (st/emit! (dw/set-hidden-attr id (not hidden)))))

          (select-shape [event]
            (dom/prevent-default event)
            (let [id (:id canvas)]
              (cond
                (or (:blocked canvas)
                    (:hidden canvas))
                nil

                (.-ctrlKey event)
                (st/emit! (dw/select-shape id))

                (> (count selected) 1)
                (st/emit! dw/deselect-all
                          (dw/select-shape id))
                :else
                (st/emit! dw/deselect-all
                          (dw/select-shape id)))))

          (on-drop [item monitor]
            (st/emit! ::dw/page-data-update))

          (on-hover [item monitor]
            (st/emit! (dw/change-canvas-order {:id (:canvas-id item)
                                               :index index})))]
    (let [selected? (contains? selected (:id canvas))
          collapsed? (:collapsed canvas false)

          shapes (filter #(= (:canvas (second %)) (:id canvas)) shapes)
          [dprops dnd-ref] (use-sortable
                            {:type "canvas-item"
                             :data {:canvas-id (:id canvas)
                                    :page-id (:page canvas)
                                    :index index}
                             :on-hover on-hover
                             :on-drop on-drop})]
      [:li.group {:ref dnd-ref
                  :class (classnames
                          :selected selected?
                          :dragging-TODO (:dragging? dprops))}
       [:div.element-list-body {:class (classnames :selected selected?)
                                :on-click select-shape
                                :on-double-click #(dom/stop-propagation %)}
        [:div.element-actions
         [:div.toggle-element {:class (when-not (:hidden canvas) "selected")
                               :on-click toggle-visibility}
          i/eye]
         [:div.block-element {:class (when (:blocked canvas) "selected")
                              :on-click toggle-blocking}
          i/lock]]
        [:div.element-icon i/folder]
        [:& layer-name {:shape canvas}]
        [:span.toggle-content
         { ;; :on-click toggle-collapse
          :class (when-not collapsed? "inverse")}
         i/arrow-slide]]
       [:ul
        (for [[index shape] shapes]
          [:& layer-item {:shape shape
                          :selected selected
                          :index index
                          :key (:id shape)}])]])))

;; --- Layers List

(mf/defc layers-list
  [{:keys [shapes selected] :as props}]
  [:ul.element-list
   (for [[index shape] shapes]
     [:& layer-item {:shape shape
                     :selected selected
                     :index index
                     :key (:id shape)}])])

(mf/defc canvas-list
  [{:keys [shapes canvas selected] :as props}]
  [:ul.element-list
   (for [[index item] canvas]
     [:& canvas-item {:canvas item
                      :shapes shapes
                      :selected selected
                      :index index
                      :key (:id item)}])])

;; --- Layers Toolbox

(mf/defc layers-toolbox
  [{:keys [page] :as props}]
  (let [on-click #(st/emit! (dw/toggle-layout-flag :layers))

        selected (mf/deref refs/selected-shapes)
        data (mf/deref refs/workspace-data)

        shapes-by-id (:shapes-by-id data)

        canvas (->> (:canvas data)
                    (map #(get shapes-by-id %))
                    (enumerate))

        shapes (->> (:shapes data)
                    (map #(get shapes-by-id %)))

        all-shapes (enumerate shapes)
        unc-shapes (->> shapes
                        (filter #(nil? (:canvas %)))
                        (enumerate))]

    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span (tr "ds.settings.layers")]
      ;; [:div.tool-window-close {:on-click on-click} i/close]
     ]
     [:div.tool-window-content
      [:& canvas-list {:canvas canvas
                       :shapes all-shapes
                       :selected selected}]
      [:& layers-list {:shapes unc-shapes
                       :selected selected}]]]))

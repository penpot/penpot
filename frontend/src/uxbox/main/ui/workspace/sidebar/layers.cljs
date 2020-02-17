;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.layers
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]))

(def ^:private shapes-iref
  (-> (l/key :shapes)
      (l/derive st/state)))

;; --- Helpers

(mf/defc element-icon
  [{:keys [shape] :as props}]
  (case (:type shape)
    :icon [:& icon/icon-svg {:shape shape}]
    :image i/image
    :line i/line
    :circle i/circle
    :path i/curve
    :rect i/box
    :curve i/curve
    :text i/text
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
  (let [selected? (contains? selected (:id shape))

        toggle-blocking
        (fn [event]
          (prn "toggle-blocking" (:blocked shape))
          (dom/stop-propagation event)
          (if (:blocked shape)
            (st/emit! (dw/unblock-shape (:id shape)))
            (st/emit! (dw/block-shape (:id shape)))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden shape)
            (st/emit! (dw/show-shape (:id shape)))
            (st/emit! (dw/hide-shape (:id shape)))))

        select-shape
        (fn [event]
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

        on-drop
        (fn [item monitor]
          (prn "index" index)
          (st/emit! (dw/commit-shape-order-change (:shape-id item))))

        on-hover
        (fn [item monitor]
          (st/emit! (dw/shape-order-change (:shape-id item) index)))

        [dprops dnd-ref] (use-sortable
                          {:type "layer-item"
                           :data {:shape-id (:id shape)
                                  :page-id (:page shape)
                                  :index index}
                           :on-hover on-hover
                           :on-drop on-drop})]
    [:li {:ref dnd-ref
          :class (dom/classnames
                  :selected selected?
                  :dragging-TODO (:dragging? dprops))}
     [:div.element-list-body {:class (dom/classnames :selected selected?)
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:div.element-actions
       [:div.toggle-element {:class (when-not (:hidden shape) "selected")
                             :on-click toggle-visibility}
        i/eye]
       [:div.block-element {:class (when (:blocked shape) "selected")
                            :on-click toggle-blocking}
        i/lock]]
      [:& element-icon {:shape shape}]
      [:& layer-name {:shape shape}]]]))

(mf/defc canvas-item
  [{:keys [canvas shapes selected index] :as props}]
  (let [selected? (contains? selected (:id canvas))
        local (mf/use-state {:collapsed false})
        collapsed? (:collapsed @local)

        shapes (filter #(= (:canvas (second %)) (:id canvas)) shapes)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (swap! local update :collapsed not))

        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked canvas)
            (st/emit! (dw/unblock-shape (:id canvas)))
            (st/emit! (dw/block-shape (:id canvas)))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden canvas)
            (st/emit! (dw/show-shape (:id canvas)))
            (st/emit! (dw/hide-shape (:id canvas)))))

        select-shape
        (fn [event]
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

        on-drop
        (fn [item monitor]
          (st/emit! ::dw/page-data-update))

        on-hover
        (fn [item monitor]
          (st/emit! (dw/change-canvas-order {:id (:canvas-id item)
                                             :index index})))

        [dprops dnd-ref] (use-sortable
                          {:type "canvas-item"
                           :data {:canvas-id (:id canvas)
                                  :page-id (:page canvas)
                                  :index index}
                           :on-hover on-hover
                           :on-drop on-drop})]
    [:li.group {:ref dnd-ref
                :class (dom/classnames
                        :selected selected?
                        :dragging-TODO (:dragging? dprops))}
     [:div.element-list-body {:class (dom/classnames :selected selected?)
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:div.element-actions
       [:div.toggle-element {:class (when-not (:hidden canvas) "selected")
                             :on-click toggle-visibility}
        i/eye]
       #_[:div.block-element {:class (when (:blocked canvas) "selected")
                            :on-click toggle-blocking}
          i/lock]]
      [:div.element-icon i/folder]
      [:& layer-name {:shape canvas}]
      [:span.toggle-content
       {:on-click toggle-collapse
        :class (when-not collapsed? "inverse")}
       i/arrow-slide]]
     (when-not collapsed?
       [:ul
        (for [[index shape] (reverse shapes)]
          [:& layer-item {:shape shape
                          :selected selected
                          :index index
                          :key (:id shape)}])])]))

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
  (let [locale (i18n/use-locale)
        on-click #(st/emit! (dw/toggle-layout-flag :layers))

        selected (mf/deref refs/selected-shapes)
        data (mf/deref refs/workspace-data)

        shapes-by-id (:shapes-by-id data)

        canvas (->> (:canvas data)
                    (map #(get shapes-by-id %))
                    (d/enumerate))

        shapes (->> (:shapes data)
                    (map #(get shapes-by-id %)))

        all-shapes (d/enumerate shapes)
        unc-shapes (->> shapes
                        (filter #(nil? (:canvas %)))
                        (d/enumerate))]

    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span (t locale "workspace.sidebar.layers")]
      #_[:div.tool-window-close {:on-click on-click} i/close]]
     [:div.tool-window-content
      [:& canvas-list {:canvas canvas
                       :shapes all-shapes
                       :selected selected}]
      [:& layers-list {:shapes unc-shapes
                       :selected selected}]]]))

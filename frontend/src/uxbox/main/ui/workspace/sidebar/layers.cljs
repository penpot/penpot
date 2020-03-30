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
   [uxbox.util.uuid :as uuid]
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


(def strip-attrs
  #(select-keys % [:id :frame :name :type :hidden :blocked]))

(mf/defc layer-item
  {:wrap [mf/wrap-memo]}
  [{:keys [index item selected] :as props}]
  (let [selected? (contains? selected (:id item))
        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked item)
            (st/emit! (dw/unblock-shape (:id item)))
            (st/emit! (dw/block-shape (:id item)))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden item)
            (st/emit! (dw/show-shape (:id item)))
            (st/emit! (dw/hide-shape (:id item)))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (cond
              (or (:blocked item)
                  (:hidden item))
              nil

              (.-ctrlKey event)
              (st/emit! (dw/select-shape id))

              (> (count selected) 1)
              (st/emit! dw/deselect-all
                        (dw/select-shape id))
              :else
              (st/emit! dw/deselect-all
                        (dw/select-shape id)))))

        on-context-menu
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [pos (dom/get-client-position event)]
            (st/emit! (dw/show-shape-context-menu {:position pos
                                                   :shape item}))))

        on-hover
        (fn [item monitor]
          (st/emit! (dw/shape-order-change (:obj-id item) index)))

        on-drop
        (fn [item monitor]
          (st/emit! (dw/commit-shape-order-change (:obj-id item))))

        [dprops dnd-ref] (use-sortable
                          {:type (str "layer-item" (:frame-id item))
                           :data {:obj-id (:id item)
                                  :page-id (:page item)
                                  :index index}
                           :on-hover on-hover
                           :on-drop on-drop})]
    [:li {:ref dnd-ref
          :on-context-menu on-context-menu
          :class (dom/classnames
                  :selected selected?
                  :dragging-TODO (:dragging? dprops))}
     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:& element-icon {:shape item}]
      [:& layer-name {:shape item}]
      [:div.element-actions
       [:div.toggle-element {:class (when (:hidden item) "selected")
                             :on-click toggle-visibility}
        i/eye]
       [:div.block-element {:class (when (:blocked item) "selected")
                            :on-click toggle-blocking}
        i/lock]]]]))

(mf/defc layer-frame-item
  {:wrap [#(mf/wrap-memo % =)]}
  [{:keys [item selected index objects] :as props}]
  (let [selected? (contains? selected (:id item))
        local (mf/use-state {:collapsed false})
        collapsed? (:collapsed @local)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (swap! local update :collapsed not))

        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked item)
            (st/emit! (dw/unblock-shape (:id item)))
            (st/emit! (dw/block-shape (:id item)))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden item)
            (st/emit! (dw/show-frame (:id item)))
            (st/emit! (dw/hide-frame (:id item)))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (cond
              (or (:blocked item)
                  (:hidden item))
              nil

              (.-ctrlKey event)
              (st/emit! (dw/select-shape id))

              (> (count selected) 1)
              (st/emit! dw/deselect-all
                        (dw/select-shape id))
              :else
              (st/emit! dw/deselect-all
                        (dw/select-shape id)))))

        on-context-menu
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (let [pos (dom/get-client-position event)]
            (st/emit! (dw/show-shape-context-menu {:position pos
                                                   :shape item}))))

        on-drop
        (fn [item monitor]
          (st/emit! (dw/commit-shape-order-change (:obj-id item))))

        on-hover
        (fn [item monitor]
          (st/emit! (dw/shape-order-change (:obj-id item) index)))

        [dprops dnd-ref] (use-sortable
                          {:type (str "layer-item" (:frame-id item))
                           :data {:obj-id (:id item)
                                  :page-id (:page item)
                                  :index index}
                           :on-hover on-hover
                           :on-drop on-drop})]
    [:li.group {:ref dnd-ref
                :on-context-menu on-context-menu
                :class (dom/classnames
                        :selected selected?
                        :dragging-TODO (:dragging? dprops))}
     [:div.element-list-body {:class (dom/classnames :selected selected?)
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:div.element-icon i/artboard]
      [:& layer-name {:shape item}]
      [:div.element-actions
       [:div.toggle-element {:class (when (:hidden item) "selected")
                             :on-click toggle-visibility}
        i/eye]
       #_[:div.block-element {:class (when (:blocked item) "selected")
                            :on-click toggle-blocking}
          i/lock]]
      [:span.toggle-content
       {:on-click toggle-collapse
        :class (when-not collapsed? "inverse")}
       i/arrow-slide]]
     (when-not collapsed?
       [:ul
        (for [[index id] (d/enumerate (:shapes item))]
          (let [item (get objects id)]
            (if (= (:type item) :frame)
              [:& layer-frame-item
               {:item item
                :key (:id item)
                :selected selected
                :objects objects
                :index index}]
              [:& layer-item
               {:item item
                :selected selected
                :index index
                :key (:id item)}])))])]))

(mf/defc layers-tree
  {::mf/wrap [mf/wrap-memo]}
  [props]
  (let [selected (mf/deref refs/selected-shapes)
        data (mf/deref refs/workspace-data)
        objects (:objects data)
        root (get objects uuid/zero)]
    [:ul.element-list
     (for [[index id] (d/enumerate (:shapes root))]
       (let [item (get objects id)]
         (if (= (:type item) :frame)
           [:& layer-frame-item
            {:item item
             :key (:id item)
             :selected selected
             :objects objects
             :index index}]
           [:& layer-item
            {:item item
             :selected selected
             :index index
             :key (:id item)}])))]))

;; --- Layers Toolbox

;; NOTE: we need to consider using something like react window for
;; only render visible items instead of all.

(mf/defc layers-toolbox
  {:wrap [mf/wrap-memo]}
  [{:keys [page] :as props}]
  (let [locale (i18n/use-locale)
        on-click #(st/emit! (dw/toggle-layout-flag :layers))]
    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span (:name page)]
      #_[:div.tool-window-close {:on-click on-click} i/close]]
     [:div.tool-window-content
      [:& layers-tree]]]))

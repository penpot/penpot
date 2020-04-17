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
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.dom :as dom]
   [uxbox.util.perf :as perf]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.i18n :as i18n :refer [t]]))

;; --- Helpers

(mf/defc element-icon
  [{:keys [shape] :as props}]
  (case (:type shape)
    :frame i/artboard
    :icon [:& icon/icon-svg {:shape shape}]
    :image i/image
    :line i/line
    :circle i/circle
    :path i/curve
    :rect i/box
    :curve i/curve
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

(defn- layer-item-memo-equals?
  [nprops oprops]
  (let [n-item (unchecked-get nprops "item")
        o-item (unchecked-get oprops "item")
        n-selc (unchecked-get nprops "selected")
        o-selc (unchecked-get oprops "selected")
        n-indx (unchecked-get nprops "index")
        o-indx (unchecked-get oprops "index")]
    ;; (js/console.log "FOR" (:name n-item)
    ;;                 "NEW SEL" n-selc
    ;;                 "OLD SEL" o-selc)6
    (and (identical? n-item o-item)
         (identical? n-indx o-indx)
         (identical? n-selc o-selc))))

(declare layer-item)

(mf/defc layer-item
  ;; {::mf/wrap [#(mf/memo' % layer-item-memo-equals?)]}
  [{:keys [index item selected objects] :as props}]
  (let [selected? (contains? selected (:id item))
        collapsed? (mf/use-state false)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (swap! collapsed? not))

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

        on-drag
        (fn [{:keys [id]}]
          (when (not (contains? selected id))
            (st/emit! dw/deselect-all
                      (dw/select-shape id))))

        on-drop
        (fn [side {:keys [id name] :as data}]
          (let [index (if (= :top side) (inc index) index)]
            ;; (println "droping" name "on" side "of" (:name item) "/" index)
            (st/emit! (dw/relocate-shape id (:id item) index))))

        [dprops dref] (hooks/use-sortable
                       :type (str (:frame-id item))
                       :on-drop on-drop
                       :on-drag on-drag
                       :data {:id (:id item)
                              :index index
                              :name (:name item)})
        ]
    ;; (prn "layer-item" (:name item) index)
    [:li {:on-context-menu on-context-menu
          :ref dref
          :data-index index
          :class (dom/classnames
                  :dnd-over-top (= (:over dprops) :top)
                  :dnd-over-bot (= (:over dprops) :bot)
                  :selected selected?
                  )}
     [:div.element-list-body {:class (dom/classnames :selected selected?
                                                     :icon-layer (= (:type item) :icon))
                              :on-click select-shape
                              :on-double-click #(dom/stop-propagation %)}
      [:& element-icon {:shape item}]
      [:& layer-name {:shape item}]

      [:div.element-actions
       [:div.toggle-element {:class (when (:hidden item) "selected")
                             :on-click toggle-visibility}
        (if (:hidden item) i/eye-closed i/eye)]
       [:div.block-element {:class (when (:blocked item) "selected")
                            :on-click toggle-blocking}
        (if (:blocked item) i/lock i/lock-open)]]

      (when (:shapes item)
        [:span.toggle-content
         {:on-click toggle-collapse
          :class (when-not @collapsed? "inverse")}
         i/arrow-slide])]
     (when (and (:shapes item) (not @collapsed?))
       [:ul.element-children
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& uxbox.main.ui.workspace.sidebar.layers/layer-item
             {:item item
              :selected selected
              :index index
              :objects objects
              :key (:id item)}]))])]))

(mf/defc layers-tree
  {::mf/wrap [mf/memo]}
  [props]
  (let [selected (mf/deref refs/selected-shapes)
        data (mf/deref refs/workspace-data)
        objects (:objects data)
        root (get objects uuid/zero)]

    ;; [:& perf/profiler {:label "layers-tree" :enabled false}
    [:ul.element-list
     (for [[index id] (reverse (d/enumerate (:shapes root)))]
       [:& layer-item
        {:item (get objects id)
         :selected selected
         :index index
         :objects objects
         :key id}])]))

;; --- Layers Toolbox

;; NOTE: we need to consider using something like react window for
;; only render visible items instead of all.

(mf/defc layers-toolbox
  {:wrap [mf/memo]}
  [{:keys [page] :as props}]
  (let [locale (i18n/use-locale)
        on-click #(st/emit! (dw/toggle-layout-flag :layers))]
    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span (:name page)]
      #_[:div.tool-window-close {:on-click on-click} i/close]]
     [:div.tool-window-content
      [:& layers-tree {:key (:id page)}]]]))

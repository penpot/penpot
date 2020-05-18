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
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.pages :as cp]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.object :as obj]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as ts]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.perf :as perf]))

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

(defn- make-collapsed-iref
  [id]
  #(-> (l/in [:expanded id])
       (l/derived refs/workspace-local)))

(mf/defc layer-item
  [{:keys [index item selected objects] :as props}]
  (let [id        (:id item)
        selected? (contains? selected id)

        expanded-iref (mf/use-memo
                        (mf/deps id)
                        (make-collapsed-iref id))

        expanded? (mf/deref expanded-iref)

        toggle-collapse
        (fn [event]
          (dom/stop-propagation event)
          (if (and expanded? (kbd/shift? event))
            (st/emit! dw/collapse-all)
            (st/emit! (dw/toggle-collapse id))))

        toggle-blocking
        (fn [event]
          (dom/stop-propagation event)
          (if (:blocked item)
            (st/emit! (dw/recursive-assign id :blocked false))
            (st/emit! (dw/recursive-assign id :blocked true))))

        toggle-visibility
        (fn [event]
          (dom/stop-propagation event)
          (if (:hidden item)
            (st/emit! (dw/recursive-assign id :hidden false))
            (st/emit! (dw/recursive-assign id :hidden true))))

        select-shape
        (fn [event]
          (dom/prevent-default event)
          (let [id (:id item)]
            (cond
              (or (:blocked item)
                  (:hidden item))
              nil

              (.-ctrlKey event)
              (st/emit! (dw/select-shape id true))

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
            (st/emit! (dw/relocate-shape id (:id item) index))))

        [dprops dref] (hooks/use-sortable
                       :type (str (:frame-id item))
                       :on-drop on-drop
                       :on-drag on-drag
                       :data {:id (:id item)
                              :index index
                              :name (:name item)})
        ]
    [:li {:on-context-menu on-context-menu
          :ref dref
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
          :class (when expanded? "inverse")}
         i/arrow-slide])]
     (when (and (:shapes item) expanded?)
       [:ul.element-children
        (for [[index id] (reverse (d/enumerate (:shapes item)))]
          (when-let [item (get objects id)]
            [:& layer-item
             {:item item
              :selected selected
              :index index
              :objects objects
              :key (:id item)}]))])]))

(defn frame-wrapper-memo-equals?
  [oprops nprops]
  (let [new-sel (unchecked-get nprops "selected")
        old-sel (unchecked-get oprops "selected")
        new-itm (unchecked-get nprops "item")
        old-itm (unchecked-get oprops "item")
        new-idx (unchecked-get nprops "index")
        old-idx (unchecked-get oprops "index")
        new-obs (unchecked-get nprops "objects")
        old-obs (unchecked-get oprops "objects")]
    (and (= new-itm old-itm)
         (identical? new-idx old-idx)
         (let [childs (cp/get-children (:id new-itm) new-obs)
               childs' (conj childs (:id new-itm))]
           (and (or (= new-sel old-sel)
                    (not (or (boolean (some new-sel childs'))
                             (boolean (some old-sel childs')))))
                (loop [ids (rest childs)
                       id (first childs)]
                  (if (nil? id)
                    true
                    (if (= (get new-obs id)
                           (get old-obs id))
                      (recur (rest ids)
                             (first ids))
                      false))))))))

;; This components is a piece for sharding equality check between top
;; level frames and try to avoid rerender frames that are does not
;; affected by the selected set.

(mf/defc frame-wrapper
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % frame-wrapper-memo-equals?)
              #(mf/deferred % ts/idle-then-raf)]}
  [props]
  [:> layer-item props])

(mf/defc layers-tree
  {::mf/wrap [#(mf/memo % =)]}
  [{:keys [objects] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        root (get objects uuid/zero)]
    [:ul.element-list
     (for [[index id] (reverse (d/enumerate (:shapes root)))]
       (let [obj (get objects id)]
         (if (= :frame (:type obj))
           [:& frame-wrapper
            {:item (get objects id)
             :selected selected
             :index index
             :objects objects
             :key id}]
           [:& layer-item
            {:item (get objects id)
             :selected selected
             :index index
             :objects objects
             :key id}])))]))

(defn- strip-objects
  [objects]
  (let [strip-data #(select-keys % [:id :name :blocked :hidden :shapes :type])]
    (persistent!
     (reduce-kv (fn [res id obj]
                  (assoc! res id (strip-data obj)))
                (transient {})
                objects))))

(mf/defc layers-tree-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo #(mf/throttle % 200)]}
  [props]
  (let [objects (obj/get props "objects")
        objects (strip-objects objects)]
    [:& layers-tree {:objects objects}]))

;; --- Layers Toolbox

(mf/defc layers-toolbox
  {:wrap [mf/memo]}
  [{:keys [page] :as props}]
  (let [locale   (mf/deref i18n/locale)
        data     (mf/deref refs/workspace-data)
        on-click #(st/emit! (dw/toggle-layout-flag :layers))]
    [:div#layers.tool-window
     [:div.tool-window-bar
      [:div.tool-window-icon i/layers]
      [:span (:name page)]
      #_[:div.tool-window-close {:on-click on-click} i/close]]
     [:div.tool-window-content
      [:& layers-tree-wrapper {:key (:id page)
                               :objects (:objects data)}]]]))

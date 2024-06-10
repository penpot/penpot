;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [clojure.set :as set]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title shortcut on-click on-pointer-enter on-pointer-leave
           on-unmount children selected? icon disabled value]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
        on-pointer-enter
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? true)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (dom/set-css-property! submenu-node "display" "block")))
           (when on-pointer-enter (on-pointer-enter))))

        on-pointer-leave
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? false)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (timers/schedule
                50
                #(when-not (mf/ref-val hovering?)
                   (dom/set-css-property! submenu-node "display" "none")))))
           (when on-pointer-leave (on-pointer-leave))))

        set-dom-node
        (mf/use-callback
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node))
               (dom/set-css-property! submenu-node "top" (str (.-offsetTop dom) "px"))))))]

    (mf/use-effect
     (mf/deps on-unmount)
     (constantly on-unmount))

    (if icon
      [:li {:class (stl/css :icon-menu-item)
            :disabled disabled
            :data-value value
            :ref set-dom-node
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span
        {:class (stl/css :icon-wrapper)}
        (if selected? [:span {:class (stl/css :selected-icon)}
                       i/tick]
            [:span {:class (stl/css :selected-icon)}])
        [:span {:class (stl/css :shape-icon)} icon]]
       [:span {:class (stl/css :title)} title]]
      [:li {:class (stl/css :context-menu-item)
            :disabled disabled
            :ref set-dom-node
            :data-value value
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span {:class (stl/css :title)} title]
       (when shortcut
         [:span   {:class (stl/css :shortcut)}
          (for [[idx sc] (d/enumerate (scd/split-sc shortcut))]
            [:span {:key (dm/str shortcut "-" idx)
                    :class (stl/css :shortcut-key)} sc])])

       (when (> (count children) 1)
         [:span {:class (stl/css :submenu-icon)} i/arrow])

       (when (> (count children) 1)
         [:ul {:class (stl/css :token-context-submenu)
               :ref submenu-ref
               :style {:display "none" :left 235}
               :on-context-menu prevent-default}
          children])])))

(mf/defc menu-separator
  []
  [:li {:class (stl/css :separator)}])

(defn update-shape-radius-single-corner [value shape-ids attribute]
  (st/emit!
   (dch/update-shapes shape-ids
                      (fn [shape]
                        (when (ctsr/has-radius? shape)
                          (ctsr/set-radius-4 shape (first attribute) value)))
                      {:reg-objects? true
                       :attrs [:rx :ry :r1 :r2 :r3 :r4]})))

(defn apply-border-radius-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (if (set/superset? #{:r1 :r2 :r3 :r4} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-radius-single-corner
                                          :attributes attributes)
                                   token-type-props)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-layout-spacing [value shape-ids attributes]
  (if-let [layout-gap (cond
                        (:row-gap attributes) {:row-gap value}
                        (:column-gap attributes) {:column-gap value})]
    (st/emit! (dwsl/update-layout shape-ids {:layout-gap layout-gap}))
    (st/emit! (dwsl/update-layout shape-ids {:layout-padding (zipmap attributes (repeat value))}))))


(defn apply-spacing-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        attributes (set attributes)
        updated-token-type-props (assoc token-type-props
                                        :on-update-shape update-layout-spacing
                                        :attributes attributes)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-shape-position [value shape-ids attributes]
  (doseq [shape-id shape-ids]
    (st/emit! (dw/update-position shape-id {(first attributes) value}))))

(defn apply-dimensions-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        attributes (set attributes)
        updated-token-type-props (if (set/superset? #{:x :y} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-position
                                          :attributes attributes)
                                   token-type-props)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-shape-dimensions [value shape-ids attributes]
  (st/emit! (dwt/update-dimensions shape-ids (first attributes) value)))

(defn update-layout-sizing-limits [value shape-ids attributes]
  (st/emit! (dwsl/update-layout-child shape-ids {(first attributes) value})))

(defn apply-sizing-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (cond
                                   (set/superset? #{:width :height} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-dimensions
                                          :attributes attributes)

                                   (set/superset? {:layout-item-min-w :layout-item-max-w
                                                   :layout-item-min-h :layout-item-max-h} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-layout-sizing-limits
                                          :attributes attributes))]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn additional-actions [{:keys [token-id token-type selected-shapes] :as context-data}]
  (let [attributes->actions (fn [update-fn coll]
                              (for [{:keys [attributes] :as item} coll]
                                (let [selected? (wtc/tokens-applied? {:id token-id} selected-shapes attributes)]
                                  (assoc item
                                         :action #(update-fn context-data attributes)
                                         :selected? selected?))))]
    (case token-type
      :border-radius (attributes->actions
                      apply-border-radius-token
                      [{:title "All" :attributes #{:r1 :r2 :r3 :r4}}
                       {:title "Top Left" :attributes #{:r1}}
                       {:title "Top Right" :attributes #{:r2}}
                       {:title "Bottom Right" :attributes #{:r3}}
                       {:title "Bottom Left" :attributes #{:r4}}])
      :spacing       (attributes->actions
                      apply-spacing-token
                      [{:title "All" :attributes #{:p1 :p2 :p3 :p4}}
                       {:title "Column Gap" :attributes #{:column-gap}}
                       {:title "Vertical padding" :attributes #{:p1 :p3}}
                       {:title "Horizontal padding" :attributes #{:p2 :p4}}
                       {:title "Row Gap" :attributes #{:row-gap}}
                       {:title "Top" :attributes #{:p1}}
                       {:title "Right" :attributes #{:p2}}
                       {:title "Bottom" :attributes #{:p3}}
                       {:title "Left" :attributes #{:p4}}])

      :sizing       (attributes->actions
                     apply-sizing-token
                     [{:title "All" :attributes #{:width :height :layout-item-min-w :layout-item-max-w :layout-item-min-h :layout-item-max-h}}
                      {:title "Width" :attributes #{:width}}
                      {:title "Height" :attributes #{:height}}
                      {:title "Min width" :attributes #{:layout-item-min-w}}
                      {:title "Max width" :attributes #{:layout-item-max-w}}
                      {:title "Min height" :attributes #{:layout-item-min-h}}
                      {:title "Max height" :attributes #{:layout-item-max-h}}])

      :dimensions    (attributes->actions
                      apply-dimensions-token
                      [{:title "Spacing" :submenu :spacing}
                       {:title "Sizing" :submenu :sizing}
                       {:title "Border Radius" :submenu :border-radius}
                      ;; TODO: BORDER_WIDTH {:title "Border Width" :attributes #{:width} :children true}
                       {:title "x" :attributes #{:x}}
                       {:title "y" :attributes #{:y}}])
                      ;;TODO: Background blur {:title "Background blur" :attributes #{:width}}])

      [])))

(defn generate-menu-entries [{:keys [token-id token-type-props token-type selected-shapes] :as context-data}]
  (let [{:keys [modal]} token-type-props
        default-actions [{:title "Delete Token" :action #(st/emit! (dt/delete-token token-id))}
                         {:title "Duplicate Token" :action #(st/emit! (dt/duplicate-token token-id))}
                         {:title "Edit Token" :action (fn [event]
                                                        (let [{:keys [key fields]} modal
                                                              token (dt/get-token-data-from-token-id token-id)]
                                                          (st/emit! dt/hide-token-context-menu)
                                                          (dom/stop-propagation event)
                                                          (modal/show! key {:x (.-clientX ^js event)
                                                                            :y (.-clientY ^js event)
                                                                            :position :right
                                                                            :fields fields
                                                                            :token token})))}]
        specific-actions (additional-actions context-data)
        all-actions (concat specific-actions default-actions)]
    all-actions))

(mf/defc token-pill-context-menu
  [context-data]
  (let [menu-entries (generate-menu-entries context-data)]
    (for [[index {:keys [title action selected? children submenu]}] (d/enumerate menu-entries)]
      [:& menu-entry (cond-> {:key index
                              :title title}
                       (not submenu) (assoc :on-click action
                      ;; TODO: Allow selected items wihtout an icon for the context menu
                                            :icon (mf/html [:div {:class (stl/css-case :empty-icon true
                                                                                       :hidden-icon (not selected?))}])
                                            :selected? selected?))
       (when submenu
         (let [submenu-entries (additional-actions (assoc context-data :token-type submenu))]
           (for [[index {:keys [title action selected?]}] (d/enumerate submenu-entries)]
             [:& menu-entry {:key index
                             :title title
                             :on-click action
                             :icon  (mf/html [:div {:class (stl/css-case :empty-icon true
                                                                         :hidden-icon (not selected?))}])
                             :selected? selected?}])))])))

(mf/defc token-context-menu
  []
  (let [mdata          (mf/deref tokens-menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)
        objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)]

    (mf/use-effect
     (mf/deps mdata)
     #(let [dropdown (mf/ref-val dropdown-ref)]
        (when dropdown
          (let [bounding-rect (dom/get-bounding-rect dropdown)
                window-size (dom/get-window-size)
                delta-x (max (- (+ (:right bounding-rect) 250) (:width window-size)) 0)
                delta-y (max (- (:bottom bounding-rect) (:height window-size)) 0)
                new-style (str "top: " (- top delta-y) "px; "
                               "left: " (- left delta-x) "px;")]
            (when (or (> delta-x 0) (> delta-y 0))
              (.setAttribute ^js dropdown "style" new-style))))))
    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! dt/hide-token-context-menu)}
     [:div {:class (stl/css :token-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}
      (when  (= :token (:type mdata))
        [:ul {:class (stl/css :context-list)}
         [:& token-pill-context-menu {:token-id (:token-id mdata)
                                      :token-type-props (:token-type-props mdata)
                                      :token-type (:token-type mdata)
                                      :selected-shapes selected-shapes}]])]]))

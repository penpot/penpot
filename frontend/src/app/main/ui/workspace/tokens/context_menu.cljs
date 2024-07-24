;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; Events ----------------------------------------------------------------------

(defn update-layout-spacing [value shape-ids attributes]
  (if-let [layout-gap (cond
                          (:row-gap attributes) {:row-gap value}
                          (:column-gap attributes) {:column-gap value})]
    (dwsl/update-layout shape-ids {:layout-gap layout-gap})
    (dwsl/update-layout shape-ids {:layout-padding (zipmap attributes (repeat value))})))

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
    (st/emit! (dwt/update-position shape-id {(first attributes) value}))))

(defn apply-dimensions-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        attributes (set attributes)
        updated-token-type-props (cond
                                   (set/superset? #{:x :y} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-position
                                          :attributes attributes)

                                   (set/superset? #{:stroke-width} attributes)
                                   (assoc token-type-props
                                          :on-update-shape wtc/update-stroke-width
                                          :attributes attributes)

                                   :else token-type-props)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-shape-dimensions [value shape-ids attributes]
  (ptk/reify ::update-shape-dimensions
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (when (:width attributes) (dwt/update-dimensions shape-ids :width value))
       (when (:height attributes) (dwt/update-dimensions shape-ids :height value))))))

(defn update-layout-sizing-limits [value shape-ids attributes]
  (let [props (-> {:layout-item-min-w value
                   :layout-item-min-h value
                   :layout-item-max-w value
                   :layout-item-max-h value}
                  (select-keys attributes))]
    (dwsl/update-layout-child shape-ids props)))

;; Actions ---------------------------------------------------------------------

(defn attribute-actions [token selected-shapes attributes]
  (let [ids-by-attributes (wtt/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids (into #{} (map :id selected-shapes))]
    {:all-selected? (wtt/shapes-applied-all? ids-by-attributes shape-ids attributes)
     :shape-ids shape-ids
     :selected-pred #(seq (% ids-by-attributes))}))

(defn border-radius-attribute-actions [{:keys [token selected-shapes]}]
  (let [all-attributes #{:r1 :r2 :r3 :r4}
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes all-attributes)
        single-attributes (->> {:r1 "Top Left"
                                :r2 "Top Right"
                                :r3 "Bottom Left"
                                :r4 "Bottom Right"}
                               (map (fn [[attr title]]
                                      (let [selected? (selected-pred attr)]
                                        {:title title
                                         :selected? (and (not all-selected?) selected?)
                                         :action #(let [props {:attributes #{attr}
                                                               :token token
                                                               :shape-ids shape-ids}
                                                        event (cond
                                                                all-selected? (-> (assoc props :attributes-to-remove #{:r1 :r2 :r3 :r4 :rx :ry})
                                                                                  (wtc/apply-token))
                                                                selected? (wtc/unapply-token props)
                                                                :else (-> (assoc props :on-update-shape wtc/update-shape-radius-single-corner)
                                                                          (wtc/apply-token)))]
                                                    (st/emit! event))}))))
        all-attribute (let [props {:attributes all-attributes
                                   :token token
                                   :shape-ids shape-ids}]
                        {:title "All"
                         :selected? all-selected?
                         :action #(if all-selected?
                                    (st/emit! (wtc/unapply-token props))
                                    (st/emit! (wtc/apply-token (assoc props :on-update-shape wtc/update-shape-radius-all))))})]
    (concat [all-attribute] single-attributes)))

(def spacing
  {:padding {:p1 "Top"
             :p2 "Right"
             :p3 "Bottom"
             :p4 "Left"}
   :gap {:column-gap "Column Gap"
         :row-gap "Row Gap"}})

(defn all-or-sepearate-actions [attribute-labels on-update-shape {:keys [token selected-shapes]}]
  (let [attributes (set (keys attribute-labels))
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)
        all-action (let [props {:attributes attributes
                                :token token
                                :shape-ids shape-ids}]
                     {:title "All"
                      :selected? all-selected?
                      :action #(if all-selected?
                                 (st/emit! (wtc/unapply-token props))
                                 (st/emit! (wtc/apply-token (assoc props :on-update-shape on-update-shape))))})
        single-actions (map (fn [[attr title]]
                              (let [selected? (selected-pred attr)]
                                {:title title
                                 :selected? (and (not all-selected?) selected?)
                                 :action #(let [props {:attributes #{attr}
                                                       :token token
                                                       :shape-ids shape-ids}
                                                event (cond
                                                        all-selected? (-> (assoc props :attributes-to-remove attributes)
                                                                          (wtc/apply-token))
                                                        selected? (wtc/unapply-token props)
                                                        :else (-> (assoc props :on-update-shape on-update-shape)
                                                                  (wtc/apply-token)))]
                                            (st/emit! event))}))
                            attribute-labels)]
    (concat [all-action] single-actions)))

(defn spacing-attribute-actions [{:keys [token selected-shapes] :as context-data}]
  (let [on-update-shape (fn [resolved-value shape-ids attrs]
                          (dwsl/update-layout shape-ids {:layout-padding (zipmap attrs (repeat resolved-value))}))
        padding-attrs (:padding spacing)
        all-padding-attrs (into #{} (keys padding-attrs))
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes all-padding-attrs)
        horizontal-attributes #{:p1 :p3}
        horizontal-padding-selected? (and
                                      (not all-selected?)
                                      (every? selected-pred horizontal-attributes))
        vertical-attributes #{:p2 :p4}
        vertical-padding-selected? (and
                                    (not all-selected?)
                                    (every? selected-pred vertical-attributes))
        padding-items [{:title "All"
                        :selected? all-selected?
                        :action (fn []
                                  (let [props {:attributes all-padding-attrs
                                               :token token
                                               :shape-ids shape-ids}]
                                    (if all-selected?
                                      (st/emit! (wtc/unapply-token props))
                                      (st/emit! (wtc/apply-token (assoc props :on-update-shape on-update-shape))))))}
                       {:title "Horizontal"
                        :selected? horizontal-padding-selected?
                        :action (fn []
                                  (let [props {:token token
                                               :shape-ids shape-ids}
                                        event (cond
                                                all-selected? (wtc/apply-token (assoc props :attributes-to-remove vertical-attributes))
                                                horizontal-padding-selected? (wtc/apply-token (assoc props :attributes-to-remove horizontal-attributes))
                                                :else (wtc/apply-token (assoc props
                                                                              :attributes horizontal-attributes
                                                                              :on-update-shape on-update-shape)))]
                                    (st/emit! event)))}
                       {:title "Vertical"
                        :selected? vertical-padding-selected?
                        :action (fn []
                                  (let [props {:token token
                                               :shape-ids shape-ids}
                                        event (cond
                                                all-selected? (wtc/apply-token (assoc props :attributes-to-remove vertical-attributes))
                                                vertical-padding-selected? (wtc/apply-token (assoc props :attributes-to-remove vertical-attributes))
                                                :else (wtc/apply-token (assoc props
                                                                              :attributes vertical-attributes
                                                                              :on-update-shape on-update-shape)))]
                                    (st/emit! event)))}]
        single-padding-items (->> padding-attrs
                                  (map (fn [[attr title]]
                                         (let [same-axis-selected? (cond
                                                                     (get horizontal-attributes attr) horizontal-padding-selected?
                                                                     (get vertical-attributes attr) vertical-padding-selected?
                                                                     :else true)
                                               selected? (and
                                                          (not all-selected?)
                                                          (not same-axis-selected?)
                                                          (selected-pred attr))]
                                           {:title title
                                            :selected? selected?
                                            :action #(let [props {:attributes #{attr}
                                                                  :token token
                                                                  :shape-ids shape-ids}
                                                           event (cond
                                                                   all-selected? (-> (assoc props :attributes-to-remove all-padding-attrs)
                                                                                     (wtc/apply-token))
                                                                   selected? (wtc/unapply-token props)
                                                                   :else (-> (assoc props :on-update-shape on-update-shape)
                                                                             (wtc/apply-token)))]
                                                       (st/emit! event))}))))
        gap-items (all-or-sepearate-actions {:column-gap "Column Gap"
                                             :row-gap "Row Gap"}
                                            update-layout-spacing context-data)]
    (concat padding-items
            single-padding-items
            [:separator]
            gap-items)))

(defn sizing-attribute-actions [context-data]
  (concat
   (all-or-sepearate-actions {:width "Width"
                              :height "Height"}
                             update-shape-dimensions context-data)
   [:separator]
   (all-or-sepearate-actions {:layout-item-min-w "Min Width"
                              :layout-item-min-h "Min Height"}
                             update-layout-sizing-limits context-data)
   [:separator]
   (all-or-sepearate-actions {:layout-item-max-w "Max Width"
                              :layout-item-max-h "Max Height"}
                             update-layout-sizing-limits context-data)))

(defn generic-attribute-actions [attributes title {:keys [token selected-shapes]}]
  (let [{:keys [on-update-shape] :as p} (get wtc/token-types (:type token))
        {:keys [selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)]
    (map (fn [attribute]
           (let [selected? (selected-pred attribute)
                 props {:attributes #{attribute}
                        :token token
                        :shape-ids shape-ids}]

             {:title title
              :selected? selected?
              :action #(if selected?
                         (st/emit! (wtc/unapply-token props))
                         (st/emit! (wtc/apply-token (assoc props :on-update-shape on-update-shape))))}))
         attributes)))

(def shape-attribute-actions-map
  (let [stroke-width (partial generic-attribute-actions #{:stroke-width} "Stroke Width")]
    {:border-radius border-radius-attribute-actions
     :spacing spacing-attribute-actions
     :sizing sizing-attribute-actions
     :rotation (partial generic-attribute-actions #{:rotation} "Rotation")
     :opacity (partial generic-attribute-actions #{:opacity} "Opacity")
     :stroke-width stroke-width
     :dimensions (fn [context-data]
                   (concat
                    [{:title "Spacing" :submenu :spacing}
                     {:title "Sizing" :submenu :sizing}
                     :separator
                     {:title "Border Radius" :submenu :border-radius}]
                    (stroke-width context-data)
                    [:separator]
                    (generic-attribute-actions #{:x} "X" context-data)
                    (generic-attribute-actions #{:y} "Y" context-data)))}))

(defn generate-menu-entries [{:keys [type token] :as context-data}]
  (let [{:keys [modal]} (get wtc/token-types (:type token))
        with-actions (get shape-attribute-actions-map (or type (:type token)))
        attribute-actions (when with-actions
                            (with-actions context-data))
        default-actions [{:title "Delete Token"
                          :action #(st/emit! (dt/delete-token (:id token)))}
                         {:title "Duplicate Token"
                          :action #(st/emit! (dt/duplicate-token (:id token)))}
                         {:title "Edit Token"
                          :action (fn [event]
                                    (let [{:keys [key fields]} modal
                                          token (dt/get-token-data-from-token-id (:id token))]
                                      (st/emit! dt/hide-token-context-menu)
                                      (dom/stop-propagation event)
                                      (modal/show! key {:x (.-clientX ^js event)
                                                        :y (.-clientY ^js event)
                                                        :position :right
                                                        :fields fields
                                                        :token token})))}]]
    (concat
     attribute-actions
     (when attribute-actions [:separator])
     default-actions)))

;; Components ------------------------------------------------------------------

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title value on-click selected? children]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
        on-pointer-enter
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? true)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (dom/set-css-property! submenu-node "display" "block"))))
        on-pointer-leave
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? false)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (timers/schedule 50 #(when-not (mf/ref-val hovering?)
                                    (dom/set-css-property! submenu-node "display" "none"))))))
        set-dom-node
        (mf/use-callback
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node))
               (dom/set-css-property! submenu-node "top" (str (.-offsetTop dom) "px"))))))]
    [:li
     {:class (stl/css :context-menu-item)
      :ref set-dom-node
      :data-value value
      :on-click on-click
      :on-pointer-enter on-pointer-enter
      :on-pointer-leave on-pointer-leave}
     (when selected?
       [:span {:class (stl/css :icon-wrapper)}
        [:span {:class (stl/css :selected-icon)} i/tick]])
     [:span {:class (stl/css :title)} title]
     (when children
       [:*
        [:span {:class (stl/css :submenu-icon)} i/arrow]
        [:ul {:class (stl/css :token-context-submenu)
              :ref submenu-ref
              :style {:display "none" :left 235}
              :on-context-menu prevent-default}
         children]])]))

(mf/defc context-menu-tree
  [context-data]
  (let [entries (generate-menu-entries context-data)]
    (for [[index {:keys [title action selected? submenu] :as entry}] (d/enumerate entries)]
      [:* {:key (str title " " index)}
       (cond
         (= :separator entry) [:li {:class (stl/css :separator)}]
         submenu [:& menu-entry {:title title}
                  [:& context-menu-tree (assoc context-data :type submenu)]]
         :else [:& menu-entry
                {:title title
                 :on-click action
                 :selected? selected?}])])))

(mf/defc token-context-menu
  []
  (let [mdata          (mf/deref tokens-menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)
        objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)
        token-id (:token-id mdata)
        token (get (mf/deref refs/workspace-tokens) token-id)]
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
         [:& context-menu-tree {:token token
                                :selected-shapes selected-shapes}]])]]))

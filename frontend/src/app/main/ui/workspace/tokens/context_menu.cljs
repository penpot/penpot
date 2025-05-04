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
   [app.common.files.tokens :as cft]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as timers]
   [clojure.set :as set]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; Actions ---------------------------------------------------------------------

(defn attribute-actions [token selected-shapes attributes]
  (let [ids-by-attributes (cft/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids (into #{} (map :id selected-shapes))]
    {:all-selected? (cft/shapes-applied-all? ids-by-attributes shape-ids attributes)
     :shape-ids shape-ids
     :selected-pred #(seq (% ids-by-attributes))}))

(defn generic-attribute-actions [attributes title {:keys [token selected-shapes on-update-shape hint]}]
  (let [on-update-shape-fn (or on-update-shape
                               (-> (dwta/get-token-properties token)
                                   (:on-update-shape)))
        {:keys [selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)]
    (map (fn [attribute]
           (let [selected? (selected-pred attribute)
                 props {:attributes #{attribute}
                        :token token
                        :shape-ids shape-ids}]

             {:title title
              :hint hint
              :selected? selected?
              :action (fn []
                        (if selected?
                          (st/emit! (dwta/unapply-token props))
                          (st/emit! (dwta/apply-token (assoc props :on-update-shape on-update-shape-fn)))))}))
         attributes)))

(defn all-or-separate-actions [{:keys [attribute-labels on-update-shape-all on-update-shape hint]}
                               {:keys [token selected-shapes]}]
  (let [attributes (set (keys attribute-labels))
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)
        all-action (let [props {:attributes attributes
                                :token token
                                :shape-ids shape-ids}]
                     {:title (tr "labels.all")
                      :selected? all-selected?
                      :hint hint
                      :action #(if all-selected?
                                 (st/emit! (dwta/unapply-token props))
                                 (st/emit! (dwta/apply-token (assoc props :on-update-shape (or on-update-shape-all on-update-shape)))))})
        single-actions (map (fn [[attr title]]
                              (let [selected? (selected-pred attr)]
                                {:title title
                                 :selected? (and (not all-selected?) selected?)
                                 :action #(let [props {:attributes #{attr}
                                                       :token token
                                                       :shape-ids shape-ids}
                                                event (cond
                                                        all-selected? (-> (assoc props :attributes-to-remove attributes)
                                                                          (dwta/apply-token))
                                                        selected? (dwta/unapply-token props)
                                                        :else (-> (assoc props :on-update-shape on-update-shape)
                                                                  (dwta/apply-token)))]
                                            (st/emit! event))}))
                            attribute-labels)]
    (concat [all-action] single-actions)))

(defn layout-spacing-items [{:keys [token selected-shapes all-attr-labels horizontal-attr-labels vertical-attr-labels on-update-shape hint]}]
  (let [horizontal-attrs (into #{} (keys horizontal-attr-labels))
        vertical-attrs (into #{} (keys vertical-attr-labels))
        attrs (set/union horizontal-attrs vertical-attrs)
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes attrs)
        horizontal-selected? (and
                              (not all-selected?)
                              (every? selected-pred horizontal-attrs))
        vertical-selected? (and
                            (not all-selected?)
                            (every? selected-pred vertical-attrs))
        multi-items [{:title (tr "labels.all")
                      :selected? all-selected?
                      :hint hint
                      :action (fn []
                                (let [props {:attributes attrs
                                             :token token
                                             :shape-ids shape-ids}]
                                  (if all-selected?
                                    (st/emit! (dwta/unapply-token props))
                                    (st/emit! (dwta/apply-token (assoc props :on-update-shape on-update-shape))))))}
                     {:title "Horizontal"
                      :selected? horizontal-selected?
                      :action (fn []
                                (let [props {:token token
                                             :shape-ids shape-ids}
                                      event (cond
                                              all-selected? (dwta/apply-token (assoc props :attributes-to-remove vertical-attrs))
                                              horizontal-selected? (dwta/apply-token (assoc props :attributes-to-remove horizontal-attrs))
                                              :else (dwta/apply-token (assoc props
                                                                             :attributes horizontal-attrs
                                                                             :on-update-shape on-update-shape)))]
                                  (st/emit! event)))}
                     {:title "Vertical"
                      :selected? vertical-selected?
                      :action (fn []
                                (let [props {:token token
                                             :shape-ids shape-ids}
                                      event (cond
                                              all-selected? (dwta/apply-token (assoc props :attributes-to-remove horizontal-attrs))
                                              vertical-selected? (dwta/apply-token (assoc props :attributes-to-remove vertical-attrs))
                                              :else (dwta/apply-token (assoc props
                                                                             :attributes vertical-attrs
                                                                             :on-update-shape on-update-shape)))]
                                  (st/emit! event)))}]
        single-items (map (fn [[attr title]]
                            (let [same-axis-selected? (cond
                                                        (get horizontal-attrs attr) horizontal-selected?
                                                        (get vertical-attrs attr) vertical-selected?
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
                                                      all-selected? (-> (assoc props :attributes-to-remove attrs)
                                                                        (dwta/apply-token))
                                                      selected? (dwta/unapply-token props)
                                                      :else (-> (assoc props :on-update-shape on-update-shape)
                                                                (dwta/apply-token)))]
                                          (st/emit! event))}))
                          all-attr-labels)]
    (concat multi-items single-items)))

(defn update-shape-layout-padding [value shape-ids attributes]
  (st/emit!
   (when (= (count attributes) 1)
     (dwsl/update-layout shape-ids {:layout-padding-type :multiple}))
   (dwta/update-layout-padding value shape-ids attributes)))

(defn update-shape-layout-margin [value shape-ids attributes]
  (st/emit!
   (when (= (count attributes) 1)
     (dwsl/update-layout shape-ids {:layout-item-margin-type :multiple}))
   (dwta/update-layout-item-margin value shape-ids attributes)))

(defn spacing-attribute-actions [{:keys [token selected-shapes] :as context-data}]
  (let [padding-items (layout-spacing-items {:token token
                                             :selected-shapes selected-shapes
                                             :all-attr-labels {:p1 "Padding top"
                                                               :p2 "Padding right"
                                                               :p3 "Padding bottom"
                                                               :p4 "Padding left"}
                                             :hint (tr "workspace.token.paddings")
                                             :horizontal-attr-labels {:p2 "Padding right"
                                                                      :p4 "Padding left"}
                                             :vertical-attr-labels {:p1 "Padding top"
                                                                    :p3 "Padding bottom"}
                                             :on-update-shape update-shape-layout-padding})
        margin-items (layout-spacing-items {:token token
                                            :selected-shapes selected-shapes
                                            :all-attr-labels {:m1 "Margin top"
                                                              :m2 "Margin right"
                                                              :m3 "Margin bottom"
                                                              :m4 "Margin left"}
                                            :hint (tr "workspace.token.margins")
                                            :horizontal-attr-labels {:m2 "Margin right"
                                                                     :m4 "Margin left"}
                                            :vertical-attr-labels {:m1 "Margin top"
                                                                   :m3 "Margin bottom"}
                                            :on-update-shape update-shape-layout-margin})
        gap-items (all-or-separate-actions {:attribute-labels {:column-gap "Column Gap"
                                                               :row-gap "Row Gap"}
                                            :hint (tr "workspace.token.gaps")
                                            :on-update-shape dwta/update-layout-spacing}
                                           context-data)]
    (concat gap-items
            [:separator]
            padding-items
            [:separator]
            margin-items)))

(defn sizing-attribute-actions [context-data]
  (concat
   (all-or-separate-actions {:attribute-labels {:width "Width"
                                                :height "Height"}
                             :hint (tr "workspace.token.size")
                             :on-update-shape dwta/update-shape-dimensions}
                            context-data)
   [:separator]
   (all-or-separate-actions {:attribute-labels {:layout-item-min-w "Min Width"
                                                :layout-item-min-h "Min Height"}
                             :hint (tr "workspace.token.min-size")
                             :on-update-shape dwta/update-layout-sizing-limits}
                            context-data)
   [:separator]
   (all-or-separate-actions {:attribute-labels {:layout-item-max-w "Max Width"
                                                :layout-item-max-h "Max Height"}
                             :hint (tr "workspace.token.max-size")
                             :on-update-shape dwta/update-layout-sizing-limits}
                            context-data)))

(defn update-shape-radius-for-corners [value shape-ids attributes]
  (st/emit!
   (ptk/data-event :expand-border-radius)
   (dwta/update-shape-radius-for-corners value shape-ids attributes)))

(def shape-attribute-actions-map
  (let [stroke-width (partial generic-attribute-actions #{:stroke-width} "Stroke Width")]
    {:border-radius (partial all-or-separate-actions {:attribute-labels {:r1 "Top Left"
                                                                         :r2 "Top Right"
                                                                         :r4 "Bottom Left"
                                                                         :r3 "Bottom Right"}
                                                      :hint (tr "workspace.token.radius")
                                                      :on-update-shape-all dwta/update-shape-radius-all
                                                      :on-update-shape update-shape-radius-for-corners})
     :color (fn [context-data]
              [(generic-attribute-actions #{:fill} "Fill" (assoc context-data :on-update-shape dwta/update-fill :hint (tr "workspace.token.color")))
               (generic-attribute-actions #{:stroke-color} "Stroke" (assoc context-data :on-update-shape dwta/update-stroke-color))])
     :spacing spacing-attribute-actions
     :sizing sizing-attribute-actions
     :rotation (partial generic-attribute-actions #{:rotation} "Rotation")
     :opacity (partial generic-attribute-actions #{:opacity} "Opacity")
     :stroke-width stroke-width
     :dimensions (fn [context-data]
                   (concat
                    [{:title "Sizing" :submenu :sizing}
                     {:title "Spacing" :submenu :spacing}
                     :separator
                     {:title "Border Radius" :submenu :border-radius}]
                    [:separator]
                    (stroke-width (assoc context-data :on-update-shape dwta/update-stroke-width))
                    [:separator]
                    (generic-attribute-actions #{:x} "X" (assoc context-data :on-update-shape dwta/update-shape-position :hint (tr "workspace.token.axis")))
                    (generic-attribute-actions #{:y} "Y" (assoc context-data :on-update-shape dwta/update-shape-position))))}))

(defn default-actions [{:keys [token selected-token-set-name]}]
  (let [{:keys [modal]} (dwta/get-token-properties token)]
    [{:title (tr "workspace.token.edit")
      :no-selectable true
      :action (fn [event]
                (let [{:keys [key fields]} modal]
                  (dom/stop-propagation event)
                  (st/emit! (dwtl/assign-token-context-menu nil)
                            (modal/show key {:x (.-clientX ^js event)
                                             :y (.-clientY ^js event)
                                             :position :right
                                             :fields fields
                                             :action "edit"
                                             :selected-token-set-name selected-token-set-name
                                             :token token}))))}
     {:title (tr "workspace.token.duplicate")
      :no-selectable true
      :action #(st/emit! (dwtl/duplicate-token (:name token)))}
     {:title (tr "workspace.token.delete")
      :no-selectable true
      :action #(st/emit! (dwtl/delete-token
                          (ctob/prefixed-set-path-string->set-name-string selected-token-set-name)
                          (:name token)))}]))

(defn selection-actions [{:keys [type token] :as context-data}]
  (let [with-actions (get shape-attribute-actions-map (or type (:type token)))
        attribute-actions (if with-actions (with-actions context-data) [])]
    (concat
     attribute-actions
     (when (seq attribute-actions) [:separator])
     (default-actions context-data))))

(defn submenu-actions-selection-actions [{:keys [type token] :as context-data}]
  (let [with-actions (get shape-attribute-actions-map (or type (:type token)))
        attribute-actions (if with-actions (with-actions context-data) [])]
    (concat
     attribute-actions)))

;; Components ------------------------------------------------------------------

(def ^:private tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-tokens))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title value hint on-click selected? children submenu-offset no-selectable]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
        parent-menu-dom-element-pos* (mf/use-state nil)
        parent-menu-dom-element-pos (deref parent-menu-dom-element-pos*)
        is-submenu-outside* (mf/use-state false)
        is-submenu-outside? (deref is-submenu-outside*)
        hint? (and hint (seq hint))
        on-pointer-enter
        (mf/use-fn
         (mf/deps is-submenu-outside?)
         (fn []
           (mf/set-ref-val! hovering? true)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (dom/set-css-property! submenu-node "display" "block")
             (reset! is-submenu-outside* (dom/is-element-outside? submenu-node)))))

        on-pointer-leave
        (mf/use-fn
         (mf/deps is-submenu-outside?)
         (fn []
           (mf/set-ref-val! hovering? false)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (timers/schedule 50 #(when-not (mf/ref-val hovering?)
                                    (dom/set-css-property! submenu-node "display" "none")
                                    (reset! is-submenu-outside* false))))))

        get-parent-menu-entry-position
        (mf/use-fn
         (fn [parent-menu-dom-element]

           (when (some? parent-menu-dom-element)
             (reset! parent-menu-dom-element-pos* (dm/str (.-offsetTop parent-menu-dom-element) "px")))))]

    [:li {:class (stl/css-case
                  :context-menu-item true
                  :context-menu-item-selected (and (not no-selectable) selected?)
                  :context-menu-item-unselected (and (not no-selectable) (not selected?))
                  :context-menu-item-hint-wrapper hint?)
          :ref get-parent-menu-entry-position
          :data-value value
          :on-click on-click
          :on-pointer-enter on-pointer-enter
          :on-pointer-leave on-pointer-leave}
     (when hint
       [:span {:class (stl/css :context-menu-item-hint)} hint])
     (when (not no-selectable)
       [:> icon* {:icon-id "tick" :size "s" :class (stl/css :icon-wrapper)}])
     [:span {:class (stl/css :item-text)}
      title]
     (when children
       [:*
        [:> icon* {:icon-id "arrow" :size "s"}]
        [:ul {:ref submenu-ref
              :class (stl/css-case
                      :token-context-submenu true
                      :token-context-submenu-top is-submenu-outside?)
              :style {:left (dm/str submenu-offset "px")
                      :top (if is-submenu-outside? "unset" parent-menu-dom-element-pos)}
              :on-context-menu prevent-default}
         children]])]))

(mf/defc menu-tree
  [{:keys [selected-shapes submenu-offset type errors] :as context-data}]
  (let [shape-types (into #{} (map :type selected-shapes))
        entries (if (and (not (some? errors))
                         (seq selected-shapes)
                         (not= shape-types #{:group}))
                  (if (some? type)
                    (submenu-actions-selection-actions context-data)
                    (selection-actions context-data))
                  (default-actions context-data))]
    (for [[index {:keys [title action selected? hint submenu no-selectable] :as entry}] (d/enumerate entries)]
      [:* {:key (dm/str title " " index)}
       (cond
         (= :separator entry) [:li {:class (stl/css :separator)}]
         submenu [:& menu-entry {:title title
                                 :hint hint
                                 :no-selectable true
                                 :submenu-offset submenu-offset}
                  [:& menu-tree (assoc context-data :type submenu)]]
         :else [:& menu-entry
                {:title title
                 :on-click action
                 :hint hint
                 :no-selectable no-selectable
                 :selected? selected?}])])))

(mf/defc token-context-menu-tree
  [{:keys [width errors] :as mdata}]
  (let [objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)
        token-name (:token-name mdata)
        token (mf/deref (refs/workspace-selected-token-set-token token-name))
        selected-token-set-name (mf/deref refs/selected-token-set-name)]
    [:ul {:class (stl/css :context-list)}
     [:& menu-tree {:submenu-offset width
                    :token token
                    :errors errors
                    :selected-token-set-name selected-token-set-name
                    :selected-shapes selected-shapes}]]))

(mf/defc token-context-menu
  []
  (let [mdata               (mf/deref tokens-menu-ref)
        is-open?            (boolean mdata)
        width               (mf/use-state 0)
        dropdown-ref        (mf/use-ref)
        dropdown-direction* (mf/use-state "down")
        dropdown-direction  (deref dropdown-direction*)
        dropdown-direction-change* (mf/use-ref 0)
        top                 (+ (get-in mdata [:position :y]) 5)
        left                (+ (get-in mdata [:position :x]) 5)]

    (mf/use-effect
     (mf/deps is-open?)
     (fn []
       (when-let [node (mf/ref-val dropdown-ref)]
         (reset! width (.-offsetWidth node)))))

    (mf/with-effect [is-open?]
      (when (and (not= 0 (mf/ref-val dropdown-direction-change*)) (= false is-open?))
        (reset! dropdown-direction* "down")
        (mf/set-ref-val! dropdown-direction-change* 0)))

    (mf/with-effect [is-open? dropdown-ref]
      (let [dropdown-element (mf/ref-val dropdown-ref)]
        (when (and (= 0 (mf/ref-val dropdown-direction-change*)) dropdown-element)
          (let [is-outside? (dom/is-element-outside? dropdown-element)]
            (reset! dropdown-direction* (if is-outside? "up" "down"))
            (mf/set-ref-val! dropdown-direction-change* (inc (mf/ref-val dropdown-direction-change*)))))))

    ;; FIXME: perf optimization

    (when is-open?
      (mf/portal
       (mf/html
        [:& dropdown {:show is-open?
                      :on-close #(st/emit! (dwtl/assign-token-context-menu nil))}
         [:div {:class (stl/css :token-context-menu)
                :data-testid "tokens-context-menu-for-token"
                :ref dropdown-ref
                :data-direction dropdown-direction
                :style {:--bottom (if (= dropdown-direction "up")
                                    "40px"
                                    "unset")
                        :--top (dm/str top "px")
                        :left (dm/str left "px")}
                :on-context-menu prevent-default}
          (when mdata
            [:& token-context-menu-tree (assoc mdata :width @width)])]])
       (dom/get-body)))))

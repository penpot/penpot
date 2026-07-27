;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.components-debugger
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.thumbnails :as thc]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.floating-drag :as fd]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.shape-icon :as usi]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private panel-width 1024)
(def ^:private panel-height 768)
(def ^:private sidebar-offset 290)
(def ^:private min-z-index 300)

(def ^:private preview-x 16)
(def ^:private preview-y 16)
(def ^:private preview-width 400)
(def ^:private panels-gap 56)
(def ^:private marker-radius 11)
(def ^:private marker-dot-radius 4)
(def ^:private text-padding 8)
(def ^:private text-line-height 16)
(def ^:private icon-size 16)
(def ^:private labels-title-gap 12)
(def ^:private thumbnail-height 80)
(def ^:private thumbnail-margin 8)
(def ^:private inner-padding 8)
(def ^:private child-gap 20)
(def ^:private box-min-height 40)
(def ^:private legend-gap 20)
(def ^:private legend-row-gap 24)
(def ^:private legend-line-width 36)
(def ^:private legend-width 220)
(def ^:private legend-padding-x 12)
(def ^:private legend-padding-y 10)
(def ^:private deleted-banner-height (+ text-padding text-line-height text-padding))

(defn- canvas-content-width
  "Total SVG canvas width for the given number of panel columns."
  [column-count]
  (let [column-count (max 1 column-count)
        column-step  (+ preview-x preview-width panels-gap)]
    (+ (* (dec column-count) column-step)
       preview-width
       (* 2 preview-x))))

(defn- column-x
  "Left x coordinate of the panel column at `column-idx`."
  [column-idx]
  (+ preview-x (* column-idx (+ preview-width panels-gap))))

(defn- legend-total-height
  "Height of the arrows legend (fixed two-row layout)."
  []
  (+ (* 2 legend-padding-y) legend-row-gap (* 2 marker-radius)))

(defn- initial-position
  "Default fixed position for the floating debugger panel."
  []
  {:top 40
   :left (- (:width (dom/get-window-size)) panel-width sidebar-offset)})

(defn- shape-box-labels
  "Build footer info labels for a panel root (file/page name and ids)."
  [_shape & {:keys [file-id page-id file-name page-name]}]
  (cond-> []
    file-name
    (conj (str "File: " file-name))

    file-id
    (conj (str "File Id: " file-id))

    page-name
    (conj (str "Page: " page-name))

    page-id
    (conj (str "Page Id: " page-id))))

(defn- thumbnail-slot-height
  "Vertical space reserved for a shape thumbnail, including margins."
  []
  (+ thumbnail-margin thumbnail-height thumbnail-margin))

(defn- shape-thumbnail-slot?
  "Whether `shape` can show a thumbnail given file/page context."
  [shape {:keys [file-id page-id]}]
  (and file-id page-id
       (or (cfh/frame-shape? shape)
           (ctk/instance-root? shape)
           (ctk/main-instance? shape)
           ;; Swapped nested heads are not instance-roots, but still need a slot.
           (some? (ctk/get-swap-slot shape)))))

(defn- box-thumbnail-slot?
  "A box reserves a thumbnail slot when it is the root of a panel or the
   highlighted shape, and the shape itself qualifies for a thumbnail."
  [shape root? highlight-id thumbnail-context]
  (and (some? thumbnail-context)
       (or root? (= (:id shape) highlight-id))
       (shape-thumbnail-slot? shape thumbnail-context)))

(defn- labels-bg-height
  "Height of the labels footer background, or nil when there are no labels."
  [label-count]
  (when (pos? label-count)
    (+ text-padding
       (* label-count text-line-height)
       text-padding)))

(defn- context-deleted-banner-height
  "Extra top banner height when the panel marks a deleted component."
  [thumbnail-context]
  (if (:deleted-banner thumbnail-context)
    deleted-banner-height
    0))

(defn- child-thumbnail-context
  "Thumbnail context for nested boxes; deleted banner applies only to the root."
  [thumbnail-context]
  (dissoc thumbnail-context :deleted-banner))

(defn- box-header-height
  "Height of the box header (optional deleted banner, thumbnail, title row)."
  [_label-count thumbnail-slot? thumbnail-context]
  (+ (context-deleted-banner-height thumbnail-context)
     (if thumbnail-slot?
       (+ thumbnail-margin
          (thumbnail-slot-height)
          labels-title-gap)
       text-padding)
     icon-size
     text-padding))

(defn- labels-footer-height
  "Height of the labels footer including the gap above it."
  [label-count]
  (if (pos? label-count)
    (+ labels-title-gap
       (labels-bg-height label-count))
    0))

(defn- header-thumbnail-y
  "Y coordinate of the thumbnail slot within a box header."
  [y _label-count thumbnail-context]
  (+ y
     (context-deleted-banner-height thumbnail-context)
     thumbnail-margin))

(defn- header-title-y
  "Y coordinate of the title/icon row within a box header."
  [y _label-count thumbnail-slot? thumbnail-context]
  (+ y
     (context-deleted-banner-height thumbnail-context)
     (if thumbnail-slot?
       (+ thumbnail-margin
          (thumbnail-slot-height)
          labels-title-gap)
       text-padding)))

(defn- shape-children
  "Child shapes of `shape` looked up in `objects`, skipping missing ids."
  [objects shape]
  (->> (:shapes shape)
       (map #(get objects %))
       (remove nil?)))

(defn- shape-display-label
  "Short label combining truncated shape name and trailing id digits."
  [shape]
  (let [name     (:name shape)
        short-name (if (< (count name) 24)
                     name
                     (str (str/slice name 0 22) "..."))
        short-id (str/slice (str (:id shape)) 24)]
    (str short-name " | " short-id)))

(defn- near-match-source-shape
  "Shape used to resolve the near component match."
  [objects shape]
  (if (and (not (ctk/in-component-copy? shape))
           (ctn/has-any-copy-parent? objects shape))
    (last (ctn/get-parent-copy-heads objects shape))
    shape))

(defn- ref-shape->component-head
  "Walk from a ref shape to its component head, preserving context metadata."
  [ref-shape]
  (when ref-shape
    (let [meta'     (meta ref-shape)
          container (:container meta')
          objects   (:objects container)
          head      (or (last (ctn/get-parent-heads objects ref-shape))
                        ref-shape)]
      (cond-> head
        meta' (with-meta meta')))))

(defn- near-component-render-data
  "Panel render data for a near-component head and its highlighted ref."
  [near-head ref-shape]
  (when near-head
    (let [container (-> near-head meta :container)]
      {:shape near-head
       :objects (:objects container)
       :highlight-id (:id ref-shape)})))


(defn- resolve-near-component-shape
  "Resolve near-match (or ref-shape fallback) render data for `shape`.
   Includes deleted components so the chain can surface them."
  [file page libraries objects shape]
  (when-let [source (near-match-source-shape objects shape)]
    (when-let [ref-shape (or (ctf/find-near-match file page libraries source
                                                  :with-context? true
                                                  :include-deleted? true)
                             (ctf/find-ref-shape file page libraries source
                                                 :with-context? true
                                                 :include-deleted? true))]
      (near-component-render-data (ref-shape->component-head ref-shape)
                                  ref-shape))))

(defn- resolve-file-name
  "File objects from with-context metadata often lack :name; look it up."
  [file libraries]
  (or (:name file)
      (when-let [file-id (:id file)]
        (get-in libraries [file-id :name]))))

(defn- deleted-component-container?
  "True when the container is a deleted component (not a live page)."
  [container]
  (boolean (or (:deleted container)
               (ctn/component? container))))

(defn- enrich-near-component-data
  "Attach root shape, file/page ids and display names to near-match data."
  [near-data {:keys [file page]} libraries]
  (when near-data
    (let [shape      (:shape near-data)
          objects    (:objects near-data)
          meta'      (meta shape)
          ;; Prefer the matched shape's file/page (where the tree lives).
          match-file (or (:file meta') file)
          match-page (or (:container meta') page)
          deleted?   (deleted-component-container? match-page)
          file-id    (:id match-file)
          root-shape (or (ctn/get-instance-root objects shape)
                         (when deleted?
                           (ctk/get-deleted-component-root match-page))
                         shape)]
      (assoc near-data
             :root-shape root-shape
             :file       match-file
             :page       match-page
             :deleted    deleted?
             :file-name  (resolve-file-name match-file libraries)
             :file-id    file-id
             :page-name  (:name match-page)
             :page-id    (:id match-page)))))

(defn- resolve-swap-slot-main-data
  "When the highlighted shape has a swap-slot, return its component main tree."
  [file page libraries objects highlight-id]
  (when-let [shape (get objects highlight-id)]
    (when (ctk/get-swap-slot shape)
      (when-let [component (ctf/find-ref-component file page libraries shape
                                                   :include-deleted? true)]
        (let [head           (ctn/get-head-shape objects shape)
              component-file (ctf/find-component-file file libraries (:component-file head))
              file-data      (:data component-file)
              container      (ctf/get-component-container file-data component)
              root-shape     (ctf/get-component-root file-data component)
              component-page (ctf/get-component-page file-data component)
              highlight-main-id
              (or (ctf/find-ref-id-for-swapped shape page libraries)
                  (some-> (ctf/get-ref-shape file-data component shape) :id)
                  (ctk/get-swap-slot shape))]
          (when root-shape
            {:root-shape root-shape
             :objects (:objects container)
             :highlight-id highlight-main-id
             :file component-file
             :deleted (boolean (:deleted component))
             :file-name (resolve-file-name component-file libraries)
             :file-id (:id component-file)
             :page-name (:name component-page)
             :page-id (:id component-page)}))))))

(defn- near-match-context
  "Resolve file/page/objects for near-match lookup from shape metadata,
   falling back to the workspace context."
  [shape workspace-file workspace-page workspace-objects]
  (let [meta' (meta shape)]
    (if-let [container (:container meta')]
      {:file    (or (:file meta') workspace-file)
       :page    container
       :objects (:objects container)}
      {:file    workspace-file
       :page    workspace-page
       :objects workspace-objects})))

(defn- near-component-data-chain
  "Resolve near-component matches recursively until no more matches.
   Stops if a shape id repeats to avoid cycles, and does not walk past
   a deleted component."
  [workspace-file workspace-page libraries workspace-objects initial-shape]
  (loop [shape  initial-shape
         result []
         seen   #{}]
    (if (or (nil? shape) (contains? seen (:id shape)))
      result
      (let [seen (conj seen (:id shape))
            {:keys [file page objects]}
            (near-match-context shape workspace-file workspace-page workspace-objects)]
        (if-let [near-data (when (and file page)
                             (enrich-near-component-data
                              (resolve-near-component-shape file page libraries objects shape)
                              {:file file :page page}
                              libraries))]
          (if (:deleted near-data)
            (conj result near-data)
            (recur (:shape near-data) (conj result near-data) seen))
          result)))))

(defn- child-insets
  "Left/right padding applied around nested child boxes."
  []
  {:left (* 2 inner-padding)
   :right inner-padding})

(defn- child-content-width
  "Inner width available for child boxes inside a parent of `width`."
  [width]
  (let [{:keys [left right]} (child-insets)]
    (- width left right)))

(defn- shape-box-height
  "Computed height of a shape box including nested children."
  ([objects shape width]
   (shape-box-height objects shape width nil nil nil false))
  ([objects shape width labels thumbnail-context highlight-id root?]
   (let [thumbnail-slot? (box-thumbnail-slot? shape root? highlight-id thumbnail-context)
         label-count (count labels)
         header      (box-header-height label-count thumbnail-slot? thumbnail-context)
         footer      (labels-footer-height label-count)
         children (shape-children objects shape)]
     (if (empty? children)
       (max box-min-height (+ header footer))
       (let [child-width (child-content-width width)
             children-height
             (->> children
                  (map #(shape-box-height objects % child-width nil
                                          (child-thumbnail-context thumbnail-context)
                                          highlight-id false))
                  (interpose child-gap)
                  (reduce + 0))]
         (+ header (* 2 inner-padding) children-height footer))))))

(defn- shape-child-layouts
  "Layout descriptors for each child box under `shape`."
  [objects shape x y width labels thumbnail-context highlight-id root?]
  (let [thumbnail-slot? (box-thumbnail-slot? shape root? highlight-id thumbnail-context)
        header    (box-header-height (count labels) thumbnail-slot? thumbnail-context)
        children  (reverse (shape-children objects shape))
        {:keys [left]} (child-insets)
        inner-x   (+ x left)
        inner-w   (child-content-width width)
        start-y   (+ y header inner-padding)
        child-ctx (child-thumbnail-context thumbnail-context)]
    (loop [cy start-y
           [child & rest] children
           result []]
      (if child
        (let [child-height (shape-box-height objects child inner-w nil child-ctx highlight-id false)]
          (recur (+ cy child-height child-gap)
                 rest
                 (conj result {:shape child :x inner-x :y cy :width inner-w})))
        result))))

(defn- root-panel-height
  "Height of a top-level panel rooted at `root-shape`."
  [objects root-shape labels thumbnail-context highlight-id]
  (shape-box-height objects root-shape preview-width labels thumbnail-context highlight-id true))

(defn- swap-slot-panel
  "Build a swap-main panel for the highlight at `column-idx`.
   The panel is drawn one column to the right (`(inc column-idx)`), so
   arrows pair it with `highlight-panels` via `(dec column-idx)`."
  [column-idx swap-row-y file page libraries objects highlight-id]
  (when-let [{:keys [root-shape objects highlight-id file deleted
                     file-id page-id file-name page-name]}
             (resolve-swap-slot-main-data file page libraries objects highlight-id)]
    {:column-idx (inc column-idx)
     :y swap-row-y
     :root-shape root-shape
     :objects objects
     :highlight-id highlight-id
     :file file
     :deleted deleted
     :file-name file-name
     :file-id file-id
     :page-name page-name
     :page-id page-id
     :labels (shape-box-labels root-shape
                               :file-name file-name
                               :file-id file-id
                               :page-name page-name
                               :page-id page-id)}))

(defn- shape-highlight-anchor
  "Return {:left :right :y} for the highlighted shape box."
  [objects shape x y width labels thumbnail-context highlight-id root?]
  (if (= (:id shape) highlight-id)
    (let [height (shape-box-height objects shape width labels thumbnail-context highlight-id root?)]
      {:left x
       :right (+ x width)
       :y (+ y (/ height 2))})
    (some (fn [{:keys [shape x y width]}]
            (shape-highlight-anchor objects shape x y width nil thumbnail-context highlight-id false))
          (shape-child-layouts objects shape x y width labels thumbnail-context highlight-id root?))))

(defn- panel-highlight-anchor
  "Highlight anchor for a panel map used when drawing connection arrows."
  [{:keys [objects root-shape highlight-id labels thumbnail-context panel-x panel-y]}]
  (when highlight-id
    (shape-highlight-anchor objects root-shape panel-x panel-y preview-width
                            labels thumbnail-context highlight-id true)))

(defn- navigate-arrow-path
  "SVG path data for the arrowhead drawn at connection endpoints."
  []
  "M -6.5 0 L 5.5 0 M 6.715 0.715 L -0.5 -6.5 M 6.715 -0.715 L -0.365 6.635")

(defn- highlight-arrow-path
  "Cubic SVG path from `from-anchor` to `to-anchor` for a connection arrow."
  [from-anchor to-anchor]
  (let [x1 (+ (:right from-anchor) marker-dot-radius)
        y1 (:y from-anchor)
        x2 (- (:left to-anchor) marker-radius)
        y2 (:y to-anchor)
        mid-x (/ (+ x1 x2) 2)]
    (dm/str "M " x1 " " y1
            " C " mid-x " " y1 ", "
            mid-x " " y2 ", "
            x2 " " y2)))

(defn- arrow-marker-styles
  "CSS module classes for arrow markers of the given variant (:swap or default)."
  [variant]
  (case variant
    :swap {:circle (stl/css :swap-arrow-marker-circle)
           :icon (stl/css :swap-arrow-marker-icon)
           :path (stl/css :swap-arrow)}
    {:circle (stl/css :highlight-arrow-marker-circle)
     :icon (stl/css :highlight-arrow-marker-icon)
     :path (stl/css :highlight-arrow)}))

(defn- swap-panel-as-highlight-panel
  "Adapt a swap panel map into the highlight-panel shape used for anchors."
  [{:keys [column-idx y objects root-shape labels file-id page-id]}]
  {:objects objects
   :root-shape root-shape
   :highlight-id (:id root-shape)
   :labels labels
   :thumbnail-context {:file-id file-id :page-id page-id}
   :panel-x (column-x column-idx)
   :panel-y y})

(defn- build-swap-panel-pairs
  "Pair each swap panel with its source highlight panel for arrow drawing."
  [highlight-panels swap-panels]
  (keep (fn [swap]
          (when-let [from (nth highlight-panels (dec (:column-idx swap)) nil)]
            [from (swap-panel-as-highlight-panel swap)]))
        swap-panels))

(mf/defc highlight-arrow-marker*
  "Endpoint marker for a connection arrow (dot or arrowhead)."
  {::mf/private true}
  [{:keys [x y show-arrow circle-class icon-class]}]
  [:g {:transform (dm/str "translate(" x "," y ")")}
   [:circle {:cx 0
             :cy 0
             :r (if show-arrow marker-radius marker-dot-radius)
             :class circle-class}]
   (when show-arrow
     [:path {:d (navigate-arrow-path)
             :class icon-class}])])

(mf/defc connection-arrows*
  "Draw curved arrows between consecutive panel pairs for a given variant."
  {::mf/private true}
  [{:keys [panel-pairs variant]}]
  (let [{:keys [circle icon path]} (arrow-marker-styles variant)]
    (for [[idx [from-panel to-panel]] (map-indexed vector panel-pairs)
          :let [from (panel-highlight-anchor from-panel)
                to (panel-highlight-anchor to-panel)]
          :when (and from to)]
      ^{:key (dm/str (name variant) "-arrow-" idx)}
      [:g
       [:path {:d (highlight-arrow-path from to)
               :class path
               :fill "none"}]
       [:> highlight-arrow-marker*
        {:x (+ (:right from) marker-dot-radius)
         :y (:y from)
         :circle-class circle}]
       [:> highlight-arrow-marker*
        {:x (- (:left to) marker-radius 2)
         :y (:y to)
         :show-arrow true
         :circle-class circle
         :icon-class icon}]])))

(mf/defc highlight-arrows*
  "Near-match and swap connection arrows overlaid on the canvas."
  {::mf/private true}
  [{:keys [highlight-pairs swap-pairs]}]
  [:g {:class (stl/css :highlight-arrows)}
   [:> connection-arrows* {:panel-pairs highlight-pairs :variant :highlight}]
   [:> connection-arrows* {:panel-pairs swap-pairs :variant :swap}]])

(mf/defc arrows-legend*
  "Legend explaining near-match vs swap arrow colors."
  {::mf/private true}
  [{:keys [x y]}]
  (let [rows [{:variant :highlight :label "Near shape ref"}
              {:variant :swap :label "Swapped by"}]
        row-count (count rows)
        row-start-y (+ y legend-padding-y marker-radius)
        marker-start-x (+ x legend-padding-x)
        marker-end-x (+ marker-start-x legend-line-width)
        legend-height (+ (* 2 legend-padding-y)
                         (* (dec row-count) legend-row-gap)
                         (* 2 marker-radius))]
    [:g {:class (stl/css :highlight-arrows)}
     [:rect {:x x
             :y y
             :width legend-width
             :height legend-height
             :rx 6
             :ry 6
             :class (stl/css :legend-bg)}]
     (for [[idx {:keys [variant label]}] (map-indexed vector rows)
           :let [row-y (+ row-start-y (* idx legend-row-gap))
                 {:keys [circle icon path]} (arrow-marker-styles variant)
                 marker-x marker-end-x]]
       ^{:key (dm/str "legend-" (name variant))}
       [:g
        [:path {:d (dm/str "M " marker-start-x " " row-y " L " marker-x " " row-y)
                :class path
                :fill "none"}]
        [:> highlight-arrow-marker*
         {:x marker-start-x
          :y row-y
          :circle-class circle}]
        [:> highlight-arrow-marker*
         {:x marker-x
          :y row-y
          :show-arrow true
          :circle-class circle
          :icon-class icon}]
        [:text {:x (+ marker-x 18)
                :y row-y
                :dominant-baseline "middle"
                :class (stl/css :legend-text)}
         label]])]))

(mf/defc labeled-rect*
  "Single shape box: border, optional thumbnail, title, and footer labels."
  {::mf/private true}
  [{:keys [x y width height name labels icon-id icon-component? selected?
           thumbnail-uri thumbnail-slot? thumbnail-unavailable
           swap-variant deleted-variant root?]}]
  (let [has-icon?      (some? icon-id)
        label-count    (count labels)
        labels-height  (labels-bg-height label-count)
        labels-y       (when labels-height
                         (- (+ y height) labels-height))
        text-x         (+ x text-padding)
        deleted-root?  (and deleted-variant root?)
        banner-context (when deleted-root? {:deleted-banner true})
        thumbnail-slot-y (header-thumbnail-y y label-count banner-context)
        thumbnail-y      (+ thumbnail-slot-y thumbnail-margin)
        thumbnail-w      (- width (* 2 text-padding))
        title-y        (header-title-y y label-count thumbnail-slot? banner-context)]
    [:g
     [:rect {:x x
             :y y
             :width width
             :height height
             :class (stl/css-case :selection-preview-box true
                                  :selected selected?
                                  :swap (and swap-variant (or root? selected?))
                                  :deleted (and deleted-variant (or root? selected?)))}]

     (when deleted-root?
       [:g
        [:rect {:x (+ x 1)
                :y (+ y 1)
                :width (- width 2)
                :height (- deleted-banner-height 1)
                :class (stl/css :deleted-header-bg)}]
        [:text {:x text-x
                :y (+ y text-padding)
                :dominant-baseline "hanging"
                :class (stl/css :deleted-header-text)}
         "Deleted component"]])

     (when labels-y
       ;; Inset 1px so the box stroke isn't covered by the labels fill.
       [:rect {:x (+ x 1)
               :y labels-y
               :width (- width 2)
               :height (- labels-height 1)
               :class (stl/css :info-labels-bg)}])

     (for [[idx label] (map-indexed vector labels)
           :let [text (if (string? label) label (str label))]]
       ^{:key idx}
       [:text {:x text-x
               :y (+ labels-y text-padding (* idx text-line-height))
               :text-anchor "start"
               :dominant-baseline "hanging"
               :class (stl/css :info-text)}
        text])

     (when thumbnail-slot?
       [:g
        [:rect {:x (+ x text-padding)
                :y thumbnail-y
                :width thumbnail-w
                :height thumbnail-height
                :class (stl/css-case :shape-thumbnail-bg true
                                     :is-loading (and (nil? thumbnail-uri)
                                                      (not thumbnail-unavailable))
                                     :unavailable thumbnail-unavailable)}]
        (cond
          thumbnail-uri
          [:image {:x (+ x text-padding)
                   :y thumbnail-y
                   :width thumbnail-w
                   :height thumbnail-height
                   :href thumbnail-uri
                   :preserveAspectRatio "xMidYMid meet"
                   :class (stl/css :shape-thumbnail-image)}]

          thumbnail-unavailable
          [:text {:x (+ x text-padding (/ thumbnail-w 2))
                  :y (+ thumbnail-y (/ thumbnail-height 2))
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :class (stl/css :shape-thumbnail-unavailable)}
           "No thumbnail available"]

          :else
          [:text {:x (+ x text-padding (/ thumbnail-w 2))
                  :y (+ thumbnail-y (/ thumbnail-height 2))
                  :text-anchor "middle"
                  :dominant-baseline "middle"
                  :class (stl/css :shape-thumbnail-loading)}
           "Loading…"])])

     (when has-icon?
       [:g {:transform (dm/str "translate(" text-x "," title-y ")")}
        [:> icon* {:icon-id icon-id
                   :size "m"
                   :class (stl/css-case :selection-preview-icon true
                                        :component icon-component?)}]])
     [:text {:x (+ text-x 20)
             :y title-y
             :dominant-baseline "hanging"
             :class (stl/css :selection-preview-text)}
      name]]))

(defn- shape-thumbnail-tag
  "Object-id tag used for a shape's own thumbnail: root frames render under
   \"frame\", every other shape under \"component\"."
  [shape]
  (if (cfh/frame-shape? shape) "frame" "component"))

;; Debugger-owned thumbnail URIs (object-id -> blob uri). After generation we
;; copy the workspace thumbnail here and clear it from workspace state so the
;; canvas is not left with a debugger-only render.
(defonce ^:private local-thumbnails (atom {}))

;; Object-ids we have already asked to generate in this session (once each).
(defonce ^:private thumbnail-request-cache (atom #{}))

;; Workspace uri present when a generation was requested; ignored on capture so
;; a swapped shape's stale thumbnail is never stored as the local result.
(defonce ^:private thumbnail-request-baseline (atom {}))

(when-not (map? @local-thumbnails)
  (reset! local-thumbnails {}))

(when-not (set? @thumbnail-request-cache)
  (reset! thumbnail-request-cache #{}))

(when-not (map? @thumbnail-request-baseline)
  (reset! thumbnail-request-baseline {}))

(defn- clear-local-thumbnails!
  "Revoke and drop all debugger-owned thumbnails (call when the debugger closes)."
  []
  (doseq [uri (vals @local-thumbnails)]
    (when (string? uri)
      (wapi/revoke-uri uri)))
  (reset! local-thumbnails {})
  (reset! thumbnail-request-cache #{})
  (reset! thumbnail-request-baseline {}))

(defn- mark-thumbnail-requested!
  "Mark `object-id` as requested with the current workspace `baseline-uri`.
   Returns true on the first mark."
  [object-id baseline-uri]
  (let [first?* (atom false)]
    (swap! thumbnail-request-cache
           (fn [ids]
             (let [ids (if (set? ids) ids #{})]
               (if (contains? ids object-id)
                 ids
                 (do
                   (reset! first?* true)
                   (conj ids object-id))))))
    (when @first?*
      (swap! thumbnail-request-baseline assoc object-id baseline-uri))
    @first?*))

(defn- store-local-thumbnail!
  "Keep a private copy of `uri` for `object-id`, then clear the workspace
   thumbnail (which would revoke the original blob URL)."
  [file-id page-id shape-id tag object-id uri]
  (when (and uri (nil? (get @local-thumbnails object-id)))
    ;; Reserve the slot so concurrent captures do not race.
    (swap! local-thumbnails assoc object-id :pending)
    (-> (js/fetch uri)
        (.then (fn [response] (.blob response)))
        (.then (fn [blob]
                 (let [local-uri (wapi/create-uri blob)]
                   (swap! local-thumbnails assoc object-id local-uri)
                   (swap! thumbnail-request-baseline dissoc object-id)
                   (st/emit! (dwt/clear-thumbnail file-id page-id shape-id tag)))))
        (.catch (fn [_]
                  (swap! local-thumbnails dissoc object-id))))))

(defn- use-shape-thumbnail
  "URI of `shape`'s thumbnail for the debugger.

   Prefers a locally stored URI. Missing thumbnails and swapped shapes are
   generated once into workspace state, then copied into `local-thumbnails`
   and cleared from the workspace. Non-swapped shapes that already have a
   workspace thumbnail keep using it. When `enabled?` is false (e.g. deleted
   shapes) nothing is generated and nil is returned."
  [shape file-id page-id enabled?]
  (let [shape-id       (:id shape)
        tag            (shape-thumbnail-tag shape)
        swapped?       (some? (ctk/get-swap-slot shape))
        object-id      (mf/with-memo [file-id page-id shape-id tag]
                         (thc/fmt-object-id file-id page-id shape-id tag))
        thumb-ref      (mf/with-memo [object-id]
                         (refs/workspace-thumbnail-by-id object-id))
        workspace-uri  (:uri (mf/deref thumb-ref))
        local-entry    (get @local-thumbnails object-id)
        local-uri      (when (string? local-entry) local-entry)
        missing?       (nil? workspace-uri)]

    ;; Request generation once for missing or swapped shapes with no local uri.
    (mf/with-effect [object-id enabled? swapped? local-entry missing? workspace-uri]
      (when (and enabled? file-id page-id shape-id (nil? local-entry))
        (cond
          swapped?
          (when (mark-thumbnail-requested! object-id workspace-uri)
            (st/emit! (dwt/clear-thumbnail file-id page-id shape-id tag))
            (tm/schedule-on-idle
             #(st/emit! (dwt/update-thumbnail file-id page-id shape-id tag
                                              "components-debugger"))))

          missing?
          (when (mark-thumbnail-requested! object-id nil)
            (tm/schedule-on-idle
             #(st/emit! (dwt/update-thumbnail file-id page-id shape-id tag
                                              "components-debugger")))))))

    ;; Once workspace has a freshly generated uri, keep it locally and clear.
    (mf/with-effect [object-id enabled? workspace-uri]
      (when (and enabled? workspace-uri (nil? local-entry)
                 (contains? @thumbnail-request-cache object-id)
                 (not= workspace-uri (get @thumbnail-request-baseline object-id)))
        (store-local-thumbnail! file-id page-id shape-id tag object-id workspace-uri)))

    (when enabled?
      (or local-uri
          ;; Swapped workspace uris are stale until regenerated into local.
          (when-not swapped?
            workspace-uri)))))

(mf/defc shape-box*
  "Recursive shape tree box with thumbnails and nested children."
  {::mf/private true}
  [{:keys [shape objects x y width selected-id labels thumbnail-context root?
           swap-variant deleted-variant]}]
  (let [labels                 (or labels (shape-box-labels shape))
        thumbnail-slot?        (box-thumbnail-slot? shape root? selected-id thumbnail-context)
        file-id                (:file-id thumbnail-context)
        page-id                (:page-id thumbnail-context)
        thumbnail-unavailable? deleted-variant
        thumbnail-uri          (use-shape-thumbnail shape file-id page-id
                                                    (and thumbnail-slot?
                                                         (not thumbnail-unavailable?)))
        height                 (shape-box-height objects shape width labels thumbnail-context selected-id root?)
        child-layouts          (shape-child-layouts objects shape x y width labels thumbnail-context selected-id root?)
        selected?              (= (:id shape) selected-id)
        icon-component?        (or (ctk/instance-head? shape)
                                   (ctk/is-variant-container? shape))]
    [:g
     [:> labeled-rect*
      {:x x
       :y y
       :width width
       :height height
       :name (shape-display-label shape)
       :labels labels
       :icon-id (usi/get-shape-icon shape)
       :icon-component? icon-component?
       :selected? selected?
       :swap-variant swap-variant
       :deleted-variant deleted-variant
       :root? root?
       :thumbnail-uri thumbnail-uri
       :thumbnail-unavailable thumbnail-unavailable?
       :thumbnail-slot? thumbnail-slot?}]
     (for [{:keys [shape x y width]} child-layouts]
       ^{:key (dm/str (:id shape))}
       [:> shape-box*
        {:shape shape
         :objects objects
         :x x
         :y y
         :width width
         :selected-id selected-id
         :swap-variant swap-variant
         :deleted-variant deleted-variant
         :thumbnail-context (child-thumbnail-context thumbnail-context)}])]))

(mf/defc components-debugger-content*
  "Floating debugger UI: selection, near-match chain, and swap panels."
  {::mf/private true}
  []
  (let [container (hooks/use-portal-container :popup)

        wrapper-ref (mf/use-ref nil)
        z-index-ref (mf/use-ref min-z-index)

        selected  (mf/deref refs/selected-shapes)
        objects   (mf/deref refs/workspace-page-objects)
        file      (mf/deref refs/file)
        page      (mf/deref refs/workspace-page)
        libraries (mf/deref refs/files)

        selected-id
        (when (= 1 (count selected))
          (first selected))

        selected-shape
        (when selected-id
          (get objects selected-id))

        root-shape
        (when selected-shape
          (ctn/get-instance-root objects selected-shape))

        near-component-chain
        (when (and selected-shape file page)
          (near-component-data-chain file page libraries objects selected-shape))

        selection-labels
        (when root-shape
          (shape-box-labels root-shape
                            :file-name (:name file)
                            :file-id (:id file)
                            :page-name (:name page)
                            :page-id (:id page)))

        selection-panel-height
        (when root-shape
          (root-panel-height objects root-shape selection-labels
                             {:file-id (:id file) :page-id (:id page)}
                             selected-id))

        legend-x
        (+ preview-x (/ (- preview-width legend-width) 2))

        ;; When any near panel is deleted, shift non-deleted first-row panels
        ;; down so their content lines up with the area below the deleted banner.
        first-row-align-offset
        (if (some :deleted near-component-chain)
          deleted-banner-height
          0)

        selection-panel-y
        (+ preview-y first-row-align-offset)

        legend-y
        (when selection-panel-height
          (+ selection-panel-y selection-panel-height legend-gap))

        first-row-heights
        (cond-> []
          selection-panel-height
          (conj (+ selection-panel-height first-row-align-offset))

          near-component-chain
          (into (map (fn [{:keys [root-shape objects highlight-id deleted
                                  file-name file-id page-name page-id]}]
                       (let [height (root-panel-height objects root-shape
                                                       (shape-box-labels root-shape
                                                                         :file-name file-name
                                                                         :file-id file-id
                                                                         :page-name page-name
                                                                         :page-id page-id)
                                                       {:file-id file-id
                                                        :page-id page-id
                                                        :deleted-banner deleted}
                                                       highlight-id)]
                         (if deleted
                           height
                           (+ height first-row-align-offset))))
                     near-component-chain)))

        swap-row-y
        (when (seq first-row-heights)
          (+ preview-y (apply max first-row-heights) panels-gap))

        selection-swap-panel
        (when (and root-shape selected-id swap-row-y)
          (swap-slot-panel 0 swap-row-y
                           file page libraries objects selected-id))

        near-swap-panels
        (when (and near-component-chain swap-row-y)
          (keep-indexed
           ;; Near columns are 1-based (selection is column 0), matching
           ;; swap-slot-panel's (inc column-idx) placement and arrow pairing.
           (fn [idx {:keys [highlight-id file page objects]}]
             (swap-slot-panel (inc idx) swap-row-y
                              file page libraries objects highlight-id))
           near-component-chain))

        swap-panels*
        (cond-> []
          selection-swap-panel (conj selection-swap-panel)
          (seq near-swap-panels) (into near-swap-panels))

        swap-row-align-offset
        (if (some :deleted swap-panels*)
          deleted-banner-height
          0)

        swap-panels
        (mapv (fn [panel]
                (cond-> panel
                  (not (:deleted panel))
                  (update :y + swap-row-align-offset)))
              swap-panels*)

        primary-column-count
        (if near-component-chain
          (inc (count near-component-chain))
          (if root-shape 1 0))

        max-column-idx
        (reduce max (dec primary-column-count)
                (map :column-idx swap-panels))

        canvas-width
        (canvas-content-width (inc max-column-idx))

        swap-panel-heights
        (map (fn [{:keys [objects root-shape labels highlight-id file-id page-id deleted]}]
               (let [height (root-panel-height objects root-shape labels
                                               {:file-id file-id
                                                :page-id page-id
                                                :deleted-banner deleted}
                                               highlight-id)]
                 (if deleted
                   height
                   (+ height swap-row-align-offset))))
             swap-panels)

        canvas-height
        (let [first-row-bottom (when (seq first-row-heights)
                                 (+ preview-y (apply max first-row-heights)))
              legend-bottom    (when legend-y
                                 (+ legend-y (legend-total-height)))
              swap-bottom      (when (and swap-row-y (seq swap-panel-heights))
                                 (+ swap-row-y (apply max swap-panel-heights)))
              bottoms          (remove nil? [first-row-bottom legend-bottom swap-bottom])]
          (when (seq bottoms)
            (+ (apply max bottoms) preview-y)))

        highlight-panels
        (cond-> []
          (and root-shape selected-id)
          (conj {:objects objects
                 :root-shape root-shape
                 :highlight-id selected-id
                 :labels selection-labels
                 :thumbnail-context {:file-id (:id file) :page-id (:id page)}
                 :panel-x preview-x
                 :panel-y selection-panel-y})

          near-component-chain
          (into (map-indexed
                 (fn [idx {:keys [root-shape objects highlight-id deleted
                                  file-name file-id page-name page-id]}]
                   {:objects objects
                    :root-shape root-shape
                    :highlight-id highlight-id
                    :labels (shape-box-labels root-shape
                                              :file-name file-name
                                              :file-id file-id
                                              :page-name page-name
                                              :page-id page-id)
                    :thumbnail-context {:file-id file-id
                                        :page-id page-id
                                        :deleted-banner deleted}
                    :panel-x (column-x (inc idx))
                    :panel-y (if deleted
                               preview-y
                               (+ preview-y first-row-align-offset))})
                 near-component-chain)))

        bring-to-front
        (mf/use-fn
         (fn []
           (let [target (mf/ref-val wrapper-ref)]
             (when target
               (mf/set-ref-val! z-index-ref (inc (mf/ref-val z-index-ref)))
               (dom/set-css-property! target "z-index"
                                      (dm/str (mf/ref-val z-index-ref)))))))

        {:keys [on-pointer-down on-pointer-move on-pointer-up on-lost-pointer-capture]}
        (fd/use-floating-drag wrapper-ref
                              bring-to-front
                              (stl/css :is-dragging))

        {:keys [top left]}
        (initial-position)

        handle-close
        (mf/use-fn
         (fn []
           (clear-local-thumbnails!)
           (dbg/disable! :components-debugger)))]

    ;; Also clear if the debugger unmounts without using the close button
    ;; (e.g. debug flag toggled elsewhere).
    (mf/with-effect []
      (fn []
        (clear-local-thumbnails!)))

    (mf/portal
     (mf/html
      [:div {:ref wrapper-ref
             :class (stl/css :wrapper)
             :style {:position "fixed"
                     :top (dm/str top "px")
                     :left (dm/str left "px")
                     :width (dm/str panel-width "px")
                     :height (dm/str panel-height "px")
                     :z-index min-z-index}}
       [:div {:class (stl/css :inner)}
        [:div {:class (stl/css :header)
               :on-pointer-down on-pointer-down
               :on-pointer-move on-pointer-move
               :on-pointer-up on-pointer-up
               :on-lost-pointer-capture on-lost-pointer-capture}
         [:h1 {:class (stl/css :title)} "COMPONENTS DEBUGGER (BETA)"]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.close")
                           :on-click handle-close
                           :icon i/close}]]

        [:div {:class (stl/css :canvas)}
         [:svg {:class (stl/css :canvas-svg)
                :width canvas-width
                :height (if canvas-height (dm/str canvas-height "px") "100%")}
          (when (some? root-shape)
            [:g
             [:> shape-box*
              {:shape root-shape
               :objects objects
               :selected-id selected-id
               :root? true
               :thumbnail-context {:file-id (:id file)
                                   :page-id (:id page)
                                   :file file
                                   :libraries libraries}
               :labels selection-labels
               :x preview-x
               :y selection-panel-y
               :width preview-width}]
             (for [[idx {:keys [root-shape objects highlight-id file deleted
                                file-name file-id page-name page-id]}]
                   (map-indexed vector near-component-chain)
                   :let [column-x (column-x (inc idx))
                         panel-y (if deleted
                                   preview-y
                                   (+ preview-y first-row-align-offset))]]
               ^{:key (dm/str "near-" (inc idx) "-" (:id root-shape))}
               [:> shape-box*
                {:shape root-shape
                 :objects objects
                 :selected-id highlight-id
                 :root? true
                 :deleted-variant deleted
                 :thumbnail-context {:file-id file-id
                                     :page-id page-id
                                     :file file
                                     :libraries libraries
                                     :deleted-banner deleted}
                 :labels (shape-box-labels root-shape
                                           :file-name file-name
                                           :file-id file-id
                                           :page-name page-name
                                           :page-id page-id)
                 :x column-x
                 :y panel-y
                 :width preview-width}])
             (for [{:keys [column-idx y root-shape objects highlight-id
                           file file-id page-id labels deleted]}
                   swap-panels
                   :let [swap-x (column-x column-idx)]]
               ^{:key (dm/str "swap-" column-idx "-" (:id root-shape))}
               [:> shape-box*
                {:shape root-shape
                 :objects objects
                 :selected-id highlight-id
                 :root? true
                 :swap-variant true
                 :deleted-variant deleted
                 :thumbnail-context {:file-id file-id
                                     :page-id page-id
                                     :file file
                                     :libraries libraries
                                     :deleted-banner deleted}
                 :labels labels
                 :x swap-x
                 :y y
                 :width preview-width}])
             (when legend-y
               [:> arrows-legend* {:x legend-x
                                   :y legend-y}])
             [:> highlight-arrows*
              {:highlight-pairs (map vector highlight-panels (rest highlight-panels))
               :swap-pairs (build-swap-panel-pairs highlight-panels swap-panels)}]])]]]])
     container)))

(mf/defc components-debugger*
  "Mounts the components debugger when the debug flag is enabled."
  []
  (let [;; Subscribe to dbg/state so the component re-renders when
        ;; debug options are toggled without a page reload.
        _dbg     (mf/deref dbg/state)
        visible? (dbg/enabled? :components-debugger)]
    (when visible?
      [:> components-debugger-content*])))

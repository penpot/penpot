;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.render
  "Rendering utilities and components for penpot SVG.

  NOTE: This namespace is used from worker and from many parts of the
  workspace; we need to be careful when adding new requires because
  this can cause to import too many deps on worker bundle."

  (:require
   ["react-dom/server" :as rds]
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cfg]
   [app.main.fonts :as fonts]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.export :as export]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.grid-layout-viewer :refer [grid-layout-viewer]]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.strings :as ust]
   [app.util.thumbnails :as th]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:const viewbox-decimal-precision 3)
(def ^:private default-color clr/canvas)

(mf/defc background
  [{:keys [vbox color]}]
  [:rect
   {:x (:x vbox)
    :y (:y vbox)
    :width (:width vbox)
    :height (:height vbox)
    :fill color}])

(defn- calculate-dimensions
  [objects aspect-ratio]
  (let [root-objects (ctst/get-root-objects objects)]
    (if (empty? root-objects)
      ;; Empty page, we create an arbitrary rect for the thumbnail
      (-> (grc/make-rect {:x 0 :y 0 :width 100 :height 100})
          (grc/update-rect :position)
          (grc/fix-aspect-ratio aspect-ratio))

      (let [bounds
            (->> root-objects
                 (map (partial gsb/get-object-bounds objects))
                 (grc/join-rects))]
        (-> bounds
            (update :x mth/finite 0)
            (update :y mth/finite 0)
            (update :width mth/finite 100000)
            (update :height mth/finite 100000)
            (grc/update-rect :position)
            (grc/fix-aspect-ratio aspect-ratio))))))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap-props false}
      [{:keys [shape]}]
      (let [thumbnails? (mf/use-ctx muc/render-thumbnails)
            childs      (mapv (d/getf objects) (:shapes shape))]
        (if (and thumbnails? (some? (:thumbnail-id shape)))
          [:& frame/frame-thumbnail {:shape shape :bounds (:children-bounds shape)}]
          [:& frame-shape {:shape shape :childs childs}])))))

(defn group-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        group-shape   (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        [:& group-shape {:shape shape
                         :is-child-selected? true
                         :childs childs}]))))

(defn bool-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        bool-shape   (bool/bool-shape shape-wrapper)]
    (mf/fnc bool-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mf/with-memo [(:id shape) objects]
                     (->> (cfh/get-children-ids objects (:id shape))
                          (select-keys objects)))]
        [:& bool-shape {:shape shape :childs childs}]))))

(defn svg-raw-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        svg-raw-shape (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        (if (and (map? (:content shape))
                ;;  tspan shouldn't be contained in a group or have svg defs
                 (not= :tspan (get-in shape [:content :tag]))
                 (or (= :svg (get-in shape [:content :tag]))
                     (contains? shape :svg-attrs)))
          [:> shape-container {:shape shape}
           [:& svg-raw-shape {:shape shape
                              :childs childs}]]

          [:& svg-raw-shape {:shape shape
                             :childs childs}])))))

(defn shape-wrapper-factory
  [objects]
  (mf/fnc shape-wrapper
    [{:keys [frame shape] :as props}]
    (let [group-wrapper   (mf/use-memo (mf/deps objects) #(group-wrapper-factory objects))
          svg-raw-wrapper (mf/use-memo (mf/deps objects) #(svg-raw-wrapper-factory objects))
          bool-wrapper    (mf/use-memo (mf/deps objects) #(bool-wrapper-factory objects))
          frame-wrapper   (mf/use-memo (mf/deps objects) #(frame-wrapper-factory objects))]
      (when shape
        (let [opts #js {:shape shape}
              svg-raw? (= :svg-raw (:type shape))]
          (if-not svg-raw?
            [:> shape-container {:shape shape}
             (case (:type shape)
               :text    [:> text/text-shape opts]
               :rect    [:> rect/rect-shape opts]
               :path    [:> path/path-shape opts]
               :image   [:> image/image-shape opts]
               :circle  [:> circle/circle-shape opts]
               :frame   [:> frame-wrapper {:shape shape}]
               :group   [:> group-wrapper {:shape shape :frame frame}]
               :bool    [:> bool-wrapper  {:shape shape :frame frame}]
               nil)]

            ;; Don't wrap svg elements inside a <g> otherwise some can break
            [:> svg-raw-wrapper {:shape shape :frame frame}]))))))

(defn format-viewbox
  "Format a viewbox given a rectangle"
  [{:keys [x y width height] :or {x 0 y 0 width 100 height 100}}]
  (str/join
   " "
   (->> [x y width height]
        (map #(ust/format-precision % viewbox-decimal-precision)))))

(defn adapt-root-frame
  [objects object]
  (let [shapes   (cfh/get-immediate-children objects)
        srect    (gsh/shapes->rect shapes)
        object   (merge object (select-keys srect [:x :y :width :height]))]
    (assoc object :fill-color "#f0f0f0")))

(defn adapt-objects-for-shape
  [objects object-id]
  (let [object   (get objects object-id)
        object   (cond->> object
                   (cfh/root? object)
                   (adapt-root-frame objects))

        ;; Replace the previous object with the new one
        objects  (assoc objects object-id object)

        vector (-> (gpt/point (:x object) (:y object))
                   (gpt/negate))

        mod-ids  (cons object-id (cfh/get-children-ids objects object-id))

        updt-fn  #(update %1 %2 gsh/transform-shape (ctm/move-modifiers vector))]

    (reduce updt-fn objects mod-ids)))

(mf/defc page-svg
  {::mf/wrap [mf/memo]}
  [{:keys [data use-thumbnails embed include-metadata aspect-ratio] :as props
    :or {embed false include-metadata false}}]
  (let [objects (:objects data)
        shapes  (cfh/get-immediate-children objects)
        dim     (calculate-dimensions objects aspect-ratio)
        vbox    (format-viewbox dim)
        bgcolor (get data :background default-color)

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(shape-wrapper-factory objects))]

    [:& (mf/provider muc/render-thumbnails) {:value use-thumbnails}
     [:& (mf/provider embed/context) {:value embed}
      [:& (mf/provider export/include-metadata-ctx) {:value include-metadata}
       [:svg {:view-box vbox
              :version "1.1"
              :xmlns "http://www.w3.org/2000/svg"
              :xmlnsXlink "http://www.w3.org/1999/xlink"
              :xmlns:penpot (when include-metadata "https://penpot.app/xmlns")
              :style {:width "100%"
                      :height "100%"
                      :background bgcolor}
              :fill "none"}

        (when include-metadata
          [:& export/export-page {:page data}])

        (let [shapes (->> shapes
                          (remove cfh/frame-shape?)
                          (mapcat #(cfh/get-children-with-self objects (:id %))))
              fonts (ff/shapes->fonts shapes)]
          [:& ff/fontfaces-style {:fonts fonts}])

        (for [item shapes]
          [:& shape-wrapper {:shape item
                             :key (:id item)}])]]]]))

(mf/defc frame-imposter
  {::mf/wrap-props false}
  [{:keys [objects frame vbox x y width height background]}]
  (let [shape-wrapper (shape-wrapper-factory objects)]
    [:& (mf/provider muc/render-thumbnails) {:value false}
     [:svg {:view-box vbox
            :width (ust/format-precision width viewbox-decimal-precision)
            :height (ust/format-precision height viewbox-decimal-precision)
            :version "1.1"
            :xmlns "http://www.w3.org/2000/svg"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :fill "none"}
      (when (some? background)
        [:rect {:x x :y y :width width :height height :fill background}])
      [:& shape-wrapper {:shape frame}]]]))

;; Component that serves for render frame thumbnails, mainly used in
;; the viewer and inspector
(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom use-thumbnails aspect-ratio background-color] :or {zoom 1} :as props}]
  (let [frame-id         (:id frame)

        bgcolor (d/nilv background-color default-color)
        include-metadata (mf/use-ctx export/include-metadata-ctx)

        bounds (-> (gsb/get-object-bounds objects frame)
                   (grc/fix-aspect-ratio aspect-ratio))

        ;; Bounds without shadows/blur will be the bounds of the thumbnail
        bounds2 (gsb/get-object-bounds objects (dissoc frame :shadow :blur))

        delta-bounds (gpt/point (:x bounds) (:y bounds))
        vector (gpt/negate delta-bounds)

        children-ids
        (cfh/get-children-ids objects frame-id)

        objects
        (mf/with-memo [frame-id objects vector]
          (let [update-fn #(update %1 %2 gsh/transform-shape (ctm/move-modifiers vector))]
            (->> children-ids
                 (into [frame-id])
                 (reduce update-fn objects))))

        frame
        (mf/with-memo [vector]
          (gsh/transform-shape frame (ctm/move-modifiers vector)))

        frame
        (cond-> frame
          (and (some? bounds) (nil? (:children-bounds bounds)))
          (assoc :children-bounds bounds2))

        frame (-> frame
                  (update-in [:children-bounds :x] - (:x delta-bounds))
                  (update-in [:children-bounds :y] - (:y delta-bounds)))

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(shape-wrapper-factory objects))

        width  (* (:width bounds) zoom)
        height (* (:height bounds) zoom)
        vbox   (format-viewbox {:width (:width bounds 0) :height (:height bounds 0)})]

    [:& (mf/provider muc/render-thumbnails) {:value use-thumbnails}
     [:svg {:view-box vbox
            :width (ust/format-precision width viewbox-decimal-precision)
            :height (ust/format-precision height viewbox-decimal-precision)
            :version "1.1"
            :xmlns "http://www.w3.org/2000/svg"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :xmlns:penpot (when include-metadata "https://penpot.app/xmlns")
            :style {:background bgcolor}
            :fill "none"}
      [:& shape-wrapper {:shape frame}]]]))

(mf/defc empty-grids
  {::mf/wrap-props false}
  [{:keys [root-shape-id objects]}]
  (let [empty-grids
        (->> (cons root-shape-id (cfh/get-children-ids objects root-shape-id))
             (filter #(ctl/grid-layout? objects %))
             (map #(get objects %))
             (filter #(empty? (:shapes %))))]
    (for [grid empty-grids]
      [:& grid-layout-viewer {:shape grid :objects objects}])))

;; Component for rendering a thumbnail of a single componenent. Mainly
;; used to render thumbnails on assets panel.
(mf/defc component-svg
  {::mf/wrap [mf/memo #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [objects root-shape show-grids? is-hidden zoom class] :or {zoom 1} :as props}]
  (when root-shape
    (let [root-shape-id (:id root-shape)
          include-metadata (mf/use-ctx export/include-metadata-ctx)

          vector
          (mf/use-memo
           (mf/deps (:x root-shape) (:y root-shape))
           (fn []
             (-> (gpt/point (:x root-shape) (:y root-shape))
                 (gpt/negate))))

          objects
          (mf/use-memo
           (mf/deps vector objects root-shape-id)
           (fn []
             (let [children-ids (cons root-shape-id (cfh/get-children-ids objects root-shape-id))
                   update-fn    #(update %1 %2 gsh/transform-shape (ctm/move-modifiers vector))]
               (reduce update-fn objects children-ids))))

          root-shape' (get objects root-shape-id)
          width       (* (:width root-shape') zoom)
          height      (* (:height root-shape') zoom)
          vbox        (format-viewbox {:width (:width root-shape' 0)
                                       :height (:height root-shape' 0)})
          root-shape-wrapper
          (mf/use-memo
           (mf/deps objects root-shape')
           (fn []
             (case (:type root-shape')
               :group (group-wrapper-factory objects)
               :frame (frame-wrapper-factory objects))))]

      [:svg {:view-box vbox
             :width (ust/format-precision width viewbox-decimal-precision)
             :height (ust/format-precision height viewbox-decimal-precision)
             :version "1.1"
             :class class
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns:penpot (when include-metadata "https://penpot.app/xmlns")
             :fill "none"}

       (when-not is-hidden
         [:*
          [:> shape-container {:shape root-shape'}
           [:& (mf/provider muc/is-component?) {:value true}
            [:& root-shape-wrapper {:shape root-shape' :view-box vbox}]]]

          (when show-grids?
            [:& empty-grids {:root-shape-id root-shape-id :objects objects}])])])))

(mf/defc component-svg-thumbnail
  {::mf/wrap [mf/memo #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [thumbnail-uri on-error show-grids? class
           objects root-shape zoom] :or {zoom 1} :as props}]

  (when root-shape
    (let [root-shape-id (:id root-shape)

          vector
          (mf/use-memo
           (mf/deps (:x root-shape) (:y root-shape))
           (fn []
             (-> (gpt/point (:x root-shape) (:y root-shape))
                 (gpt/negate))))

          objects
          (mf/use-memo
           (mf/deps vector objects root-shape-id)
           (fn []
             (let [children-ids (cons root-shape-id (cfh/get-children-ids objects root-shape-id))
                   update-fn    #(update %1 %2 gsh/transform-shape (ctm/move-modifiers vector))]
               (reduce update-fn objects children-ids))))

          root-shape' (get objects root-shape-id)

          width       (:width root-shape' 0)
          height      (:height root-shape' 0)
          width-zoom  (* (:width root-shape') zoom)
          height-zoom (* (:height root-shape') zoom)
          vbox        (format-viewbox {:width width :height height})]

      [:svg {:view-box vbox
             :width (ust/format-precision width-zoom viewbox-decimal-precision)
             :height (ust/format-precision height-zoom viewbox-decimal-precision)
             :version "1.1"
             :class class
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :fill "none"}
       [:image {:x 0
                :y 0
                :width width
                :height height
                :href thumbnail-uri
                :on-error on-error
                :loading "lazy"
                :decoding "async"}]
       (when show-grids?
         [:& empty-grids {:root-shape-id root-shape-id :objects objects}])])))

(mf/defc object-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects object-id embed]
    :or {embed false}
    :as props}]
  (let [object  (get objects object-id)
        object (cond-> object
                 (:hide-fill-on-export object)
                 (assoc :fills []))


        {:keys [width height] :as bounds} (gsb/get-object-bounds objects object {:ignore-margin? false})
        vbox (format-viewbox bounds)
        fonts (ff/shape->fonts object objects)

        shape-wrapper
        (mf/with-memo [objects]
          (shape-wrapper-factory objects))]

    [:& (mf/provider export/include-metadata-ctx) {:value false}
     [:& (mf/provider embed/context) {:value embed}
      [:svg {:id (dm/str "screenshot-" object-id)
             :view-box vbox
             :width (ust/format-precision width viewbox-decimal-precision)
             :height (ust/format-precision height viewbox-decimal-precision)
             :version "1.1"
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             ;; Fix Chromium bug about color of html texts
             ;; https://bugs.chromium.org/p/chromium/issues/detail?id=1244560#c5
             :style {:-webkit-print-color-adjust :exact}
             :fill "none"}

       [:& ff/fontfaces-style {:fonts fonts}]
       [:& shape-wrapper {:shape object}]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SPRITES (DEBUG)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc component-symbol
  [{:keys [component] :as props}]
  (let [name       (:name component)
        path       (:path component)
        root-id    (or (:main-instance-id component)
                       (:id component))
        orig-root  (get (:objects component) root-id)
        objects    (adapt-objects-for-shape (:objects component)
                                            root-id)
        root-shape (get objects root-id)
        selrect    (:selrect root-shape)

        main-instance-id     (:main-instance-id component)
        main-instance-page   (:main-instance-page component)
        main-instance-x      (when (:deleted component) (:x orig-root))
        main-instance-y      (when (:deleted component) (:y orig-root))
        main-instance-parent (when (:deleted component) (:parent-id orig-root))
        main-instance-frame  (when (:deleted component) (:frame-id orig-root))

        vbox
        (format-viewbox
         {:width (:width selrect)
          :height (:height selrect)})

        group-wrapper
        (mf/use-memo
         (mf/deps objects)
         (fn [] (group-wrapper-factory objects)))

        frame-wrapper
        (mf/use-memo
         (mf/deps objects)
         (fn [] (frame-wrapper-factory objects)))]

    (when root-shape
      [:> "symbol" #js {:id (str (:id component))
                        :viewBox vbox
                        "penpot:path" path
                        "penpot:main-instance-id" main-instance-id
                        "penpot:main-instance-page" main-instance-page
                        "penpot:main-instance-x" main-instance-x
                        "penpot:main-instance-y" main-instance-y
                        "penpot:main-instance-parent" main-instance-parent
                        "penpot:main-instance-frame" main-instance-frame}
       [:title name]
       [:> shape-container {:shape root-shape}
        (case (:type root-shape)
          :group [:& group-wrapper {:shape root-shape :view-box vbox}]
          :frame [:& frame-wrapper {:shape root-shape :view-box vbox}])]])))

(mf/defc components-svg
  {::mf/wrap-props false}
  [{:keys [data children embed include-metadata deleted?]}]
  (let [components (if (not deleted?)
                     (ctkl/components-seq data)
                     (ctkl/deleted-components-seq data))]
    [:& (mf/provider embed/context) {:value embed}
     [:& (mf/provider export/include-metadata-ctx) {:value include-metadata}
      [:svg {:version "1.1"
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns:penpot (when include-metadata "https://penpot.app/xmlns")
             :style {:display (when-not (some? children) "none")}
             :fill "none"}
       [:defs
        (for [component components]
          (let [component (ctf/load-component-objects data component)]
            [:& component-symbol {:key (dm/str (:id component)) :component component}]))]

       children]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDER FOR DOWNLOAD (wrongly called exportation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-image-data [shape]
  (cond
    (= :image (:type shape))
    [(:metadata shape)]

    (some? (:fill-image shape))
    [(:fill-image shape)]

    :else
    []))

(defn- populate-images-cache
  [objects]
  (let [images (->> objects
                    (vals)
                    (mapcat get-image-data))]
    (->> (rx/from images)
         (rx/map #(cfg/resolve-file-media %))
         (rx/merge-map http/fetch-data-uri))))

(defn populate-fonts-cache [objects]
  (let [texts (->> objects
                   (vals)
                   (filterv #(= (:type %) :text))
                   (mapv :content))]

    (->> (rx/from texts)
         (rx/map fonts/get-content-fonts)
         (rx/reduce set/union #{})
         (rx/merge-map identity)
         (rx/merge-map fonts/fetch-font-css)
         (rx/merge-map fonts/extract-fontface-urls)
         (rx/merge-map http/fetch-data-uri))))

(defn render-page
  [data]
  (rx/concat
   (->> (rx/merge
         (populate-images-cache (:objects data))
         (populate-fonts-cache (:objects data)))
        (rx/ignore))

   (->> (rx/of data)
        (rx/map
         (fn [data]
           (let [elem (mf/element page-svg #js {:data data :embed true :include-metadata true})]
             (rds/renderToStaticMarkup elem)))))))

(defn render-components
  [data deleted?]
  (let [;; Join all components objects into a single map
        components  (if (not deleted?)
                      (ctkl/components-seq data)
                      (ctkl/deleted-components-seq data))
        objects (->> components
                     (map (partial ctf/load-component-objects data))
                     (map :objects)
                     (reduce conj))]
    (rx/concat
     (->> (rx/merge
           (populate-images-cache objects)
           (populate-fonts-cache objects))
          (rx/ignore))

     (->> (rx/of data)
          (rx/map
           (fn [data]
             (let [elem (mf/element components-svg
                                    #js {:data data
                                         :embed true
                                         :include-metadata true
                                         :deleted? deleted?})]
               (rds/renderToStaticMarkup elem))))))))

(defn render-frame
  ([objects shape object-id]
   (render-frame objects shape object-id nil))
  ([objects shape object-id options]
   (if (some? shape)
     (let [fonts          (ff/shape->fonts shape objects)

           bounds         (gsb/get-object-bounds objects shape {:ignore-margin? false})

           background     (when (str/ends-with? object-id "component")
                            (or (:background options) (dom/get-css-variable "--assets-component-background-color") "#fff"))

           x              (dm/get-prop bounds :x)
           y              (dm/get-prop bounds :y)
           width          (dm/get-prop bounds :width)
           height         (dm/get-prop bounds :height)

           viewbox        (str/ffmt "% % % %" x y width height)

           [fixed-width fixed-height] (th/get-relative-size width height)
           [component-width component-height] (th/get-proportional-size width height 140 140)

           data           (with-redefs [cfg/public-uri cfg/rasterizer-uri]
                            (rds/renderToStaticMarkup
                             (mf/element frame-imposter
                                         #js {:objects objects
                                              :frame shape
                                              :vbox viewbox
                                              :background background
                                              :x x
                                              :y y
                                              :width width
                                              :height height})))
           component?    (str/ends-with? object-id "/component")]

       (->> (fonts/render-font-styles-cached fonts)
            (rx/catch (fn [cause]
                        (l/err :hint "unexpected error on rendering imposter"
                               :cause cause)
                        (rx/empty)))
            (rx/map (fn [styles]
                      {:id object-id
                       :data data
                       :width (if component? component-width fixed-width)
                       :height (if component? component-height fixed-height)
                       :styles styles}))))

     (do
       (l/warn :msg "imposter shape is nil")
       (rx/empty)))))

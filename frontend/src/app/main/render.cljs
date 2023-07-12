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
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.config :as cfg]
   [app.main.fonts :as fonts]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.export :as export]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.strings :as ust]
   [app.util.timers :as ts]
   [beicon.core :as rx]
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
  [objects]
  (let [bounds (->> (ctst/get-root-objects objects)
                    (map (partial gsb/get-object-bounds objects))
                    (grc/join-rects))]
    (-> bounds
        (update :x mth/finite 0)
        (update :y mth/finite 0)
        (update :width mth/finite 100000)
        (update :height mth/finite 100000)
        (grc/update-rect :position))))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      [{:keys [shape] :as props}]

      (let [render-thumbnails? (mf/use-ctx muc/render-thumbnails)
            childs (mapv #(get objects %) (:shapes shape))]
        (if (and render-thumbnails? (some? (:thumbnail shape)))
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
                     (->> (cph/get-children-ids objects (:id shape))
                          (select-keys objects)))]
        [:& bool-shape {:shape shape :childs childs}]))))

(defn svg-raw-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        svg-raw-shape   (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))]
        (if (and (map? (:content shape))
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
      (when (and shape (not (:hidden shape)))
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
  (let [shapes   (cph/get-immediate-children objects)
        srect    (gsh/shapes->rect shapes)
        object   (merge object (select-keys srect [:x :y :width :height]))]
    (assoc object :fill-color "#f0f0f0")))

(defn adapt-objects-for-shape
  [objects object-id]
  (let [object   (get objects object-id)
        object   (cond->> object
                   (cph/root? object)
                   (adapt-root-frame objects))

        ;; Replace the previous object with the new one
        objects  (assoc objects object-id object)

        vector (-> (gpt/point (:x object) (:y object))
                   (gpt/negate))

        mod-ids  (cons object-id (cph/get-children-ids objects object-id))

        updt-fn  #(update %1 %2 gsh/transform-shape (ctm/move-modifiers vector))]

    (reduce updt-fn objects mod-ids)))

(mf/defc page-svg
  {::mf/wrap [mf/memo]}
  [{:keys [data thumbnails? render-embed? include-metadata?] :as props
    :or {render-embed? false include-metadata? false}}]
  (let [objects (:objects data)
        shapes  (cph/get-immediate-children objects)
        dim     (calculate-dimensions objects)
        vbox    (format-viewbox dim)
        bgcolor (dm/get-in data [:options :background] default-color)

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(shape-wrapper-factory objects))]

    [:& (mf/provider muc/render-thumbnails) {:value thumbnails?}
     [:& (mf/provider embed/context) {:value render-embed?}
      [:& (mf/provider export/include-metadata-ctx) {:value include-metadata?}
       [:svg {:view-box vbox
              :version "1.1"
              :xmlns "http://www.w3.org/2000/svg"
              :xmlnsXlink "http://www.w3.org/1999/xlink"
              :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
              :style {:width "100%"
                      :height "100%"
                      :background bgcolor}
              :fill "none"}

        (when include-metadata?
          [:& export/export-page {:id (:id data) :options (:options data)}])

        (let [shapes (->> shapes
                          (remove cph/frame-shape?)
                          (mapcat #(cph/get-children-with-self objects (:id %))))
              fonts (ff/shapes->fonts shapes)]
          [:& ff/fontfaces-style {:fonts fonts}])

        (for [item shapes]
          [:& shape-wrapper {:shape item
                             :key (:id item)}])]]]]))


;; Component that serves for render frame thumbnails, mainly used in
;; the viewer and inspector
(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom show-thumbnails?] :or {zoom 1} :as props}]
  (let [frame-id          (:id frame)
        include-metadata? (mf/use-ctx export/include-metadata-ctx)

        bounds (gsb/get-object-bounds objects frame)

        ;; Bounds without shadows/blur will be the bounds of the thumbnail
        bounds2 (gsb/get-object-bounds objects (dissoc frame :shadow :blur))

        delta-bounds (gpt/point (:x bounds) (:y bounds))
        vector (gpt/negate delta-bounds)

        children-ids
        (cph/get-children-ids objects frame-id)

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

    [:& (mf/provider muc/render-thumbnails) {:value show-thumbnails?}
     [:svg {:view-box vbox
            :width (ust/format-precision width viewbox-decimal-precision)
            :height (ust/format-precision height viewbox-decimal-precision)
            :version "1.1"
            :xmlns "http://www.w3.org/2000/svg"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
            :fill "none"}
      [:& shape-wrapper {:shape frame}]]]))

;; Component for rendering a thumbnail of a single componenent. Mainly
;; used to render thumbnails on assets panel.
(mf/defc component-svg
  {::mf/wrap [mf/memo #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [objects root-shape zoom] :or {zoom 1} :as props}]
  (when root-shape
  (let [root-shape-id (:id root-shape)
        include-metadata? (mf/use-ctx export/include-metadata-ctx)

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
           (let [children-ids (cons root-shape-id (cph/get-children-ids objects root-shape-id))
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
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
           :fill "none"}

     [:> shape-container {:shape root-shape'}
      [:& (mf/provider muc/is-component?) {:value true}
       [:& root-shape-wrapper {:shape root-shape' :view-box vbox}]]]])))

(mf/defc object-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects object-id render-embed?]
    :or {render-embed? false}
    :as props}]
  (let [object  (get objects object-id)
        object (cond-> object
                 (:hide-fill-on-export object)
                 (assoc :fills []))


        {:keys [width height] :as bounds} (gsb/get-object-bounds objects object)
        vbox (format-viewbox bounds)
        fonts (ff/shape->fonts object objects)

        shape-wrapper
        (mf/with-memo [objects]
          (shape-wrapper-factory objects))]

    [:& (mf/provider export/include-metadata-ctx) {:value false}
     [:& (mf/provider embed/context) {:value render-embed?}
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
        objects    (adapt-objects-for-shape (:objects component)
                                            root-id)
        root-shape (get objects root-id)
        selrect    (:selrect root-shape)

        main-instance-id   (:main-instance-id component)
        main-instance-page (:main-instance-page component)
        main-instance-x    (:main-instance-x component)
        main-instance-y    (:main-instance-y component)

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

    [:> "symbol" #js {:id (str root-id)
                      :viewBox vbox
                      "penpot:path" path
                      "penpot:main-instance-id" main-instance-id
                      "penpot:main-instance-page" main-instance-page
                      "penpot:main-instance-x" main-instance-x
                      "penpot:main-instance-y" main-instance-y}
     [:title name]
     [:> shape-container {:shape root-shape}
      (case (:type root-shape)
        :group [:& group-wrapper {:shape root-shape :view-box vbox}]
        :frame [:& frame-wrapper {:shape root-shape :view-box vbox}])]]))

(mf/defc components-sprite-svg
  {::mf/wrap-props false}
  [props]
  (let [data              (obj/get props "data")
        children          (obj/get props "children")
        render-embed?     (obj/get props "render-embed?")
        include-metadata? (obj/get props "include-metadata?")
        source            (keyword (obj/get props "source" "components"))]
    [:& (mf/provider embed/context) {:value render-embed?}
     [:& (mf/provider export/include-metadata-ctx) {:value include-metadata?}
      [:svg {:version "1.1"
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
             :style {:display (when-not (some? children) "none")}
             :fill "none"}
       [:defs
        (for [[id component] (source data)]
          (let [component (ctf/load-component-objects data component)]
            [:& component-symbol {:key (dm/str id) :component component}]))]
       
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
         (rx/flat-map http/fetch-data-uri))))

(defn populate-fonts-cache [objects]
  (let [texts (->> objects
                   (vals)
                   (filterv #(= (:type %) :text))
                   (mapv :content))]

    (->> (rx/from texts)
         (rx/map fonts/get-content-fonts)
         (rx/reduce set/union #{})
         (rx/flat-map identity)
         (rx/flat-map fonts/fetch-font-css)
         (rx/flat-map fonts/extract-fontface-urls)
         (rx/flat-map http/fetch-data-uri))))

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
           (let [elem (mf/element page-svg #js {:data data :render-embed? true :include-metadata? true})]
             (rds/renderToStaticMarkup elem)))))))

(defn render-components
  [data source]
  (let [;; Join all components objects into a single map
        objects (->> (source data)
                     (vals)
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
             (let [elem (mf/element components-sprite-svg
                                    #js {:data data :render-embed? true :include-metadata? true
                                         :source (name source)})]
               (rds/renderToStaticMarkup elem))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.exports
  "The main logic for SVG export functionality."
  (:require
   [app.common.colors :as clr]
   [app.common.geom.align :as gal]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.export :as use]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.shapes.text.fontfaces :as ff]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [debug :refer [debug?]]
   [rumext.alpha :as mf]))

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
  [{:keys [objects] :as data} vport]
  (let [shapes (cp/select-toplevel-shapes objects {:include-frames? true
                                                   :include-frame-children? false})
        to-finite (fn [val fallback] (if (not (mth/finite? val)) fallback val))
        rect (cond->> (gsh/selection-rect shapes)
               (some? vport)
               (gal/adjust-to-viewport vport))]
    (-> rect
        (update :x to-finite 0)
        (update :y to-finite 0)
        (update :width to-finite 100000)
        (update :height to-finite 100000))))

(declare shape-wrapper-factory)

(defn frame-wrapper-factory
  [objects]
  (let [shape-wrapper (shape-wrapper-factory objects)
        frame-shape   (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      [{:keys [shape] :as props}]
      (let [childs (mapv #(get objects %) (:shapes shape))
            shape  (gsh/transform-shape shape)]
        [:> shape-container {:shape shape}
         [:& frame-shape {:shape shape :childs childs}]]))))

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
      (let [childs (->> (cp/get-children (:id shape) objects)
                        (select-keys objects))]
        [:& bool-shape {:shape shape
                        :childs childs}]))))

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
        (let [shape (gsh/transform-shape shape)
              opts #js {:shape shape}
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

(defn get-viewbox [{:keys [x y width height] :or {x 0 y 0 width 100 height 100}}]
  (str/fmt "%s %s %s %s" x y width height))

(mf/defc page-svg
  {::mf/wrap [mf/memo]}
  [{:keys [data width height thumbnails? embed? include-metadata?] :as props
    :or {embed? false include-metadata? false}}]
  (let [objects (:objects data)
        root    (get objects uuid/zero)
        shapes
        (->> (:shapes root)
             (map #(get objects %)))

        root-children
        (->> shapes
             (filter #(not= :frame (:type %)))
             (mapcat #(cp/get-object-with-children (:id %) objects)))

        vport   (when (and (some? width) (some? height))
                  {:width width :height height})
        dim     (calculate-dimensions data vport)
        vbox    (get-viewbox dim)
        background-color (get-in data [:options :background] default-color)
        frame-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(frame-wrapper-factory objects))

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(shape-wrapper-factory objects))]
    [:& (mf/provider embed/context) {:value embed?}
     [:& (mf/provider use/include-metadata-ctx) {:value include-metadata?}
      [:svg {:view-box vbox
             :version "1.1"
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
             :style {:width "100%"
                     :height "100%"
                     :background background-color}}

        [:& use/export-page {:options (:options data)}]
        [:& ff/fontfaces-style {:shapes root-children}]
        (for [item shapes]
          (let [frame? (= (:type item) :frame)]
            (cond
              (and frame? thumbnails? (some? (:thumbnail item)))
              [:image {:xlinkHref (:thumbnail item)
                       :x (:x item)
                       :y (:y item)
                       :width (:width item)
                       :height (:height item)
                       ;; DEBUG
                       :style {:filter (when (debug? :thumbnails) "sepia(1)")}
                       }]
              frame?
              [:& frame-wrapper {:shape item
                                 :key (:id item)}]
              :else
              [:& shape-wrapper {:shape item
                                 :key (:id item)}])))]]]))

(mf/defc frame-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects frame zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x frame) (:y frame))
                     (gpt/negate)
                     (gmt/translate-matrix))

        frame-id (:id frame)

        include-metadata? (mf/use-ctx use/include-metadata-ctx)

        modifier-ids (concat [frame-id] (cp/get-children frame-id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        frame (assoc-in frame [:modifiers :displacement] modifier)

        width  (* (:width frame) zoom)
        height (* (:height frame) zoom)
        vbox   (str "0 0 " (:width frame 0)
                    " "    (:height frame 0))
        wrapper (mf/use-memo
                 (mf/deps objects)
                 #(frame-wrapper-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")}
     [:& wrapper {:shape frame :view-box vbox}]]))

(mf/defc component-svg
  {::mf/wrap [mf/memo
              #(mf/deferred % ts/idle-then-raf)]}
  [{:keys [objects group zoom] :or {zoom 1} :as props}]
  (let [modifier (-> (gpt/point (:x group) (:y group))
                     (gpt/negate)
                     (gmt/translate-matrix))

        group-id (:id group)

        include-metadata? (mf/use-ctx use/include-metadata-ctx)

        modifier-ids (concat [group-id] (cp/get-children group-id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        group (assoc-in group [:modifiers :displacement] modifier)

        width  (* (:width group) zoom)
        height (* (:height group) zoom)
        vbox   (str "0 0 " (:width group 0)
                    " "    (:height group 0))
        wrapper (mf/use-memo
                  (mf/deps objects)
                  #(group-wrapper-factory objects))]

    [:svg {:view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlns "http://www.w3.org/2000/svg"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")}
     [:> shape-container {:shape group}
      [:& wrapper {:shape group :view-box vbox}]]]))

(mf/defc component-symbol
  [{:keys [id data] :as props}]

  (let [{:keys [name path objects]} data
        root (get objects id)

        {:keys [width height]} (:selrect root)
        vbox   (str "0 0 " width " " height)

        modifier (-> (gpt/point (:x root) (:y root))
                     (gpt/negate)
                     (gmt/translate-matrix))

        modifier-ids (concat [id] (cp/get-children id objects))
        update-fn #(assoc-in %1 [%2 :modifiers :displacement] modifier)
        objects (reduce update-fn objects modifier-ids)
        root (assoc-in root [:modifiers :displacement] modifier)

        group-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(group-wrapper-factory objects))]

    [:> "symbol" #js {:id (str id)
                      :viewBox vbox
                      "penpot:path" path}
     [:title name]
     [:> shape-container {:shape root}
      [:& group-wrapper {:shape root :view-box vbox}]]]))

(mf/defc components-sprite-svg
  {::mf/wrap-props false}
  [props]

  (let [data (obj/get props "data")
        children (obj/get props "children")
        embed? (obj/get props "embed?")
        include-metadata? (obj/get props "include-metadata?")]
    [:& (mf/provider embed/context) {:value embed?}
     [:& (mf/provider use/include-metadata-ctx) {:value include-metadata?}
      [:svg {:version "1.1"
             :xmlns "http://www.w3.org/2000/svg"
             :xmlnsXlink "http://www.w3.org/1999/xlink"
             :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
             :style {:width "100vw"
                     :height "100vh"
                     :display (when-not (some? children) "none")}}
       [:defs
        (for [[component-id component-data] (:components data)]
          [:& component-symbol {:id component-id
                                :key (str component-id)
                                :data component-data}])]

       children]]]))

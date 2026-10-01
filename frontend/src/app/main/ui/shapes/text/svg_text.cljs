;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.types.text.japanese-layout :as jl]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def fill-attrs [:fill-color :fill-color-gradient :fill-opacity])

(defn- px
  "CSS pixel size, or nil for a non-positive size."
  [size]
  (when (pos? size) (dm/str size "px")))

(defn- with-font-features
  [style data]
  (let [font-features (sts/font-feature-settings (:font-features data))]
    (cond-> style
      (some? font-features) (obj/set! "fontFeatureSettings" font-features))))

(defn- annotation-style
  "Style of a ruby, warichu or emphasis line drawn at `font-size` along a
  position-data strip."
  [data font-size vertical? text-orientation fill]
  (with-font-features
    #js {:fontFamily (:font-family data)
         :fontSize (px font-size)
         :fontWeight (:font-weight data)
         :fontStyle (:font-style data)
         :direction (:direction data)
         :writingMode (if vertical? "vertical-rl" "horizontal-tb")
         :textSpacingTrim "normal"
         :textOrientation text-orientation
         :textAutospace "normal"
         :whiteSpace "pre"
         :fill fill}
    data))

(defn- strip-text-props
  "Props of the `text` element drawing a position-data strip's base text."
  [shape data index fill browser-props]
  (let [style (with-font-features
                #js {:fontFamily (:font-family data)
                     :fontSize (:font-size data)
                     :fontWeight (:font-weight data)
                     :textTransform (:text-transform data)
                     :textDecoration (:text-decoration data)
                     :textCombineUpright (sts/css-text-combine-upright
                                          (:text-combine-upright data))
                     :letterSpacing (:letter-spacing data)
                     :fontStyle (:font-style data)
                     :direction (:direction data)
                     :textSpacingTrim "normal"
                     :whiteSpace "pre"}
                data)
        key   (dm/str "text-" (:id shape) "-" index)]
    (if (= "vertical-rl" (:writing-mode data))
      ;; Vertical strip: glyphs run down the column; x is the column's
      ;; central axis and y the strip top (stored y is the strip bottom).
      #js {:key key
           :x (+ (:x data) (/ (:width data) 2))
           :y (- (:y data) (:height data))
           :textLength (:height data)
           :lengthAdjust "spacingAndGlyphs"
           :style (-> style
                      (obj/set! "writingMode" "vertical-rl")
                      (obj/set! "textOrientation" (or (:text-orientation data) "mixed"))
                      (obj/set! "textAutospace" "normal")
                      (obj/set! "fill" fill))}
      (cond-> #js {:key key
                   :x (if (= "rtl" (:direction data)) (+ (:x data) (:width data)) (:x data))
                   :y (:y data)
                   :dominantBaseline "ideographic"
                   :textLength (:width data)
                   :lengthAdjust "spacingAndGlyphs"
                   :style (obj/set! style "fill" fill)}
        (some? browser-props)
        (obj/merge! browser-props)))))

(defn- warichu-line-props
  "Props of the `text` element drawing one warichu sub-line at half size in
  its own strip: the renderer splits the span and places each sub-line."
  [data fill]
  (let [vertical?  (= "vertical-rl" (:writing-mode data))
        font-size  (* (js/parseFloat (:font-size data)) jl/warichu-font-scale)
        font-size  (if (js/isNaN font-size) 0 font-size)
        style      (annotation-style data font-size vertical? (when vertical? "upright") fill)
        top        (- (:y data) (:height data))]
    (if vertical?
      #js {:x (+ (:x data) (/ (:width data) 2))
           :y top
           :style style}
      #js {:x (:x data)
           :y top
           :dominantBaseline "hanging"
           :textLength (:width data)
           :lengthAdjust "spacingAndGlyphs"
           :style style})))

(defn- ruby-text-props
  "Props of the `text` element drawing a strip's ruby annotation beside it
  (vertical) or above/below it (horizontal), per its side and alignment."
  [shape data index ruby-font-size style]
  (let [key       (dm/str "ruby-" (:id shape) "-" index)
        ruby-side (:ruby-side data "over")]
    (if (= "vertical-rl" (:writing-mode data))
      #js {:key key
           :x (if (= "under" ruby-side)
                (- (:x data) (/ ruby-font-size 2))
                (+ (:x data) (:width data) (/ ruby-font-size 2)))
           :y (- (:y data) (:height data))
           :textLength (:height data)
           :lengthAdjust "spacingAndGlyphs"
           :style style}
      (let [rtl?       (= "rtl" (:direction data))
            ruby-align (:ruby-align data "space-around")
            fit?       (or (= "none" (:ruby-overhang data "auto"))
                           (= "space-around" ruby-align))]
        (cond-> #js {:key key
                     :x (if (= "center" ruby-align)
                          (+ (:x data) (/ (:width data) 2))
                          (if rtl? (+ (:x data) (:width data)) (:x data)))
                     :y (if (= "under" ruby-side)
                          (:y data)
                          (- (:y data) (:height data)))
                     :dominantBaseline (if (= "under" ruby-side) "hanging" "text-after-edge")
                     :style style}
          (= "center" ruby-align)
          (obj/set! "textAnchor" "middle")

          (= "start" ruby-align)
          (obj/set! "textAnchor" (if rtl? "end" "start"))

          fit?
          (-> (obj/set! "textLength" (:width data))
              (obj/set! "lengthAdjust" "spacingAndGlyphs"))

          (= "space-between" ruby-align)
          (-> (obj/set! "textLength" (:width data))
              (obj/set! "lengthAdjust" "spacing")))))))

(defn- emphasis-mark-props
  "Props of the `text` element drawing one emphasis mark centred in its em
  box, as placed by the renderer."
  [data fill]
  (let [size (:width data)]
    #js {:x (+ (:x data) (/ size 2))
         :y (- (:y data) (/ (:height data) 2))
         :textAnchor "middle"
         :dominantBaseline "central"
         :style (annotation-style data size false nil fill)}))

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]

  (let [render-id (mf/use-ctx muc/render-id)
        shape (obj/get props "shape")
        shape (cond-> shape (:is-mask? shape) set-white-fill)

        {:keys [x y width height position-data]} shape

        transform (gsh/transform-str shape)

        ;; These position attributes are not really necessary but they are convenient for for the export
        group-props (-> #js {:transform transform
                             :className "text-container"
                             :x x
                             :y y
                             :width width
                             :height height}
                        (attrs/add-fill-props! shape render-id)
                        (attrs/add-border-props! shape))
        get-gradient-id
        (fn [index]
          (str render-id "-" (:id shape) "-" index))]

    [:*
     ;; Definition of gradients for partial elements
     (when (d/seek :fill-color-gradient position-data)
       [:defs
        (for [[index data] (d/enumerate position-data)]
          (when (some? (:fill-color-gradient data))
            (let [id (dm/str "fill-color-gradient-" (get-gradient-id index))]
              [:& grad/gradient {:id id
                                 :key id
                                 :attr :fill-color-gradient
                                 :shape data}])))])

     [:> :g group-props
      (for [[index data] (d/enumerate position-data)]
        (let [vertical?        (= "vertical-rl" (:writing-mode data))
              ruby             (jl/visible-ruby data)
              font-size        (js/parseFloat (:font-size data))
              ruby-font-size   (if (js/isNaN font-size)
                                 0
                                 (* font-size (jl/ruby-font-scale (:ruby-size data))))
              ;; Annotations take the fills of this strip's own render id.
              annotation-fill  (str "url(#fill-" index "-" render-id "-" index ")")

              browser-props
              (when (and (not vertical?) (cf/check-browser? :safari))
                #js {:dominantBaseline "hanging"
                     :dy "0.2em"
                     :y (- (:y data) (:height data))})

              text-props (strip-text-props shape data index
                                           (str "url(#fill-" index "-" render-id ")")
                                           browser-props)
              shape (-> shape
                        (assoc :fills (:fills data))
                        ;; The text elements have the shadow and blur already applied in the
                        ;; group parent.
                        (dissoc :shadow :blur))

              ;; Need to create new render-id per text-block
              render-id (dm/str render-id "-" index)]

          [:& (mf/provider muc/render-id) {:key index :value render-id}
           ;; Text fills definition. Need to be defined per-text block
           [:defs
            [:& fills/fills          {:shape shape :render-id render-id}]]

           (if (:emphasis-mark data)
             ;; Emphasis marks (圏点) are not stroked.
             [:> :text (emphasis-mark-props data annotation-fill) (:text data)]
             [:*
              [:& shape-custom-strokes {:shape shape :position index :render-id render-id}
               (if (= "warichu" (:warichu data))
                 [:> :text (warichu-line-props data annotation-fill) (:text data)]
                 [:> :text text-props (:text data)])]
              (when (some? ruby)
                [:> :text (ruby-text-props shape data index ruby-font-size
                                           (annotation-style data ruby-font-size vertical?
                                                             "upright" annotation-fill))
                 ruby])])]))]]))

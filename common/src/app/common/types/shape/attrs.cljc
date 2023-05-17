;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.attrs
  (:require
   [app.common.colors :as clr]))

(def default-color clr/gray-20)

;; Attributes that may directly be edited by the user with forms
(def editable-attrs
  {:frame #{:proportion-lock
            :width :height
            :x :y
            :rx :ry
            :r1 :r2 :r3 :r4
            :rotation
            :selrect
            :points
            :show-content
            :hide-in-viewer

            :opacity
            :blend-mode
            :blocked
            :hidden

            :shadow

            :blur

            :fills
            :fill-color
            :fill-opacity
            :fill-color-ref-id
            :fill-color-ref-file
            :fill-color-gradient
            :hide-fill-on-export

            :strokes
            :stroke-style
            :stroke-alignment
            :stroke-width
            :stroke-color
            :stroke-color-ref-id
            :stroke-color-ref-file
            :stroke-opacity
            :stroke-color-gradient
            :stroke-cap-start
            :stroke-cap-end

            :exports

            :layout
            :layout-flex-dir
            :layout-gap
            :layout-gap-type
            :layout-align-items
            :layout-justify-content
            :layout-align-content
            :layout-wrap-type
            :layout-padding-type
            :layout-padding

            :layout-item-margin
            :layout-item-margin-type
            :layout-item-h-sizing
            :layout-item-v-sizing
            :layout-item-max-h
            :layout-item-min-h
            :layout-item-max-w
            :layout-item-min-w
            :layout-item-align-self}

   :group #{:proportion-lock
            :width :height
            :x :y
            :rotation
            :selrect
            :points

            :constraints-h
            :constraints-v
            :fixed-scroll
            :parent-id
            :frame-id

            :opacity
            :blend-mode
            :blocked
            :hidden

            :shadow

            :blur

            :exports

            :layout-item-margin
            :layout-item-margin-type
            :layout-item-h-sizing
            :layout-item-v-sizing
            :layout-item-max-h
            :layout-item-min-h
            :layout-item-max-w
            :layout-item-min-w
            :layout-item-align-self
            :layout-item-absolute
            :layout-item-z-index}

   :rect #{:proportion-lock
           :width :height
           :x :y
           :rotation
           :rx :ry
           :r1 :r2 :r3 :r4
           :selrect
           :points

           :constraints-h
           :constraints-v
           :fixed-scroll
           :parent-id
           :frame-id

           :opacity
           :blend-mode
           :blocked
           :hidden

           :fills
           :fill-color
           :fill-opacity
           :fill-color-ref-id
           :fill-color-ref-file
           :fill-color-gradient

           :strokes
           :stroke-style
           :stroke-alignment
           :stroke-width
           :stroke-color
           :stroke-color-ref-id
           :stroke-color-ref-file
           :stroke-opacity
           :stroke-color-gradient
           :stroke-cap-start
           :stroke-cap-end

           :shadow

           :blur

           :exports

           :layout-item-margin
           :layout-item-margin-type
           :layout-item-h-sizing
           :layout-item-v-sizing
           :layout-item-max-h
           :layout-item-min-h
           :layout-item-max-w
           :layout-item-min-w
           :layout-item-align-self}

   :circle #{:proportion-lock
             :width :height
             :x :y
             :rotation
             :selrect
             :points

             :constraints-h
             :constraints-v
             :fixed-scroll
             :parent-id
             :frame-id

             :opacity
             :blend-mode
             :blocked
             :hidden

             :fills
             :fill-color
             :fill-opacity
             :fill-color-ref-id
             :fill-color-ref-file
             :fill-color-gradient

             :strokes
             :stroke-style
             :stroke-alignment
             :stroke-width
             :stroke-color
             :stroke-color-ref-id
             :stroke-color-ref-file
             :stroke-opacity
             :stroke-color-gradient
             :stroke-cap-start
             :stroke-cap-end

             :shadow

             :blur

             :exports

             :layout-item-margin
             :layout-item-margin-type
             :layout-item-h-sizing
             :layout-item-v-sizing
             :layout-item-max-h
             :layout-item-min-h
             :layout-item-max-w
             :layout-item-min-w
             :layout-item-align-self}

   :path #{:proportion-lock
           :width :height
           :x :y
           :rotation
           :selrect
           :points

           :constraints-h
           :constraints-v
           :fixed-scroll
           :parent-id
           :frame-id

           :opacity
           :blend-mode
           :blocked
           :hidden

           :fills
           :fill-color
           :fill-opacity
           :fill-color-ref-id
           :fill-color-ref-file
           :fill-color-gradient

           :strokes
           :stroke-style
           :stroke-alignment
           :stroke-width
           :stroke-color
           :stroke-color-ref-id
           :stroke-color-ref-file
           :stroke-opacity
           :stroke-color-gradient
           :stroke-cap-start
           :stroke-cap-end

           :shadow

           :blur

           :exports

           :layout-item-margin
           :layout-item-margin-type
           :layout-item-h-sizing
           :layout-item-v-sizing
           :layout-item-max-h
           :layout-item-min-h
           :layout-item-max-w
           :layout-item-min-w
           :layout-item-align-self}

   :text #{:proportion-lock
           :width :height
           :x :y
           :rotation
           :selrect
           :points

           :constraints-h
           :constraints-v
           :fixed-scroll
           :parent-id
           :frame-id

           :opacity
           :blend-mode
           :blocked
           :hidden

           :fill-color
           :fill-opacity
           :fill-color-ref-id
           :fill-color-ref-file
           :fill-color-gradient

           :strokes
           :stroke-style
           :stroke-alignment
           :stroke-width
           :stroke-color
           :stroke-color-ref-id
           :stroke-color-ref-file
           :stroke-opacity
           :stroke-color-gradient
           :stroke-cap-start
           :stroke-cap-end

           :shadow

           :blur

           :typography-ref-id
           :typography-ref-file

           :font-id
           :font-family
           :font-variant-id
           :font-size
           :font-weight
           :font-style

           :text-align

           :text-direction

           :line-height
           :letter-spacing

           :vertical-align

           :text-decoration

           :text-transform

           :grow-type

           :exports

           :layout-item-margin
           :layout-item-margin-type
           :layout-item-h-sizing
           :layout-item-v-sizing
           :layout-item-max-h
           :layout-item-min-h
           :layout-item-max-w
           :layout-item-min-w
           :layout-item-align-self}

   :image #{:proportion-lock
            :width :height
            :x :y
            :rotation
            :rx :ry
            :r1 :r2 :r3 :r4
            :selrect
            :points

            :constraints-h
            :constraints-v
            :fixed-scroll
            :parent-id
            :frame-id

            :opacity
            :blend-mode
            :blocked
            :hidden

            :shadow

            :blur

            :exports

            :layout-item-margin
            :layout-item-margin-type
            :layout-item-h-sizing
            :layout-item-v-sizing
            :layout-item-max-h
            :layout-item-min-h
            :layout-item-max-w
            :layout-item-min-w
            :layout-item-align-self}

   :svg-raw #{:proportion-lock
              :width :height
              :x :y
              :rotation
              :rx :ry
              :r1 :r2 :r3 :r4
              :selrect
              :points

              :constraints-h
              :constraints-v
              :fixed-scroll
              :parent-id
              :frame-id

              :opacity
              :blend-mode
              :blocked
              :hidden

              :fills
              :fill-color
              :fill-opacity
              :fill-color-ref-id
              :fill-color-ref-file
              :fill-color-gradient

              :strokes
              :stroke-style
              :stroke-alignment
              :stroke-width
              :stroke-color
              :stroke-color-ref-id
              :stroke-color-ref-file
              :stroke-opacity
              :stroke-color-gradient
              :stroke-cap-start
              :stroke-cap-end

              :shadow

              :blur

              :exports

              :layout-item-margin
              :layout-item-margin-type
              :layout-item-h-sizing
              :layout-item-v-sizing
              :layout-item-max-h
              :layout-item-min-h
              :layout-item-max-w
              :layout-item-min-w
              :layout-item-align-self}

   :bool #{:proportion-lock
           :width :height
           :x :y
           :rotation
           :rx :ry
           :r1 :r2 :r3 :r4
           :selrect
           :points

           :constraints-h
           :constraints-v
           :fixed-scroll
           :parent-id
           :frame-id

           :opacity
           :blend-mode
           :blocked
           :hidden

           :fills
           :fill-color
           :fill-opacity
           :fill-color-ref-id
           :fill-color-ref-file
           :fill-color-gradient

           :stroke-style
           :stroke-alignment
           :stroke-width
           :stroke-color
           :stroke-color-ref-id
           :stroke-color-ref-file
           :stroke-opacity
           :stroke-color-gradient
           :stroke-cap-start
           :stroke-cap-end

           :shadow

           :blur

           :exports

           :layout-item-margin
           :layout-item-margin-type
           :layout-item-h-sizing
           :layout-item-v-sizing
           :layout-item-max-h
           :layout-item-min-h
           :layout-item-max-w
           :layout-item-min-w
           :layout-item-align-self}})



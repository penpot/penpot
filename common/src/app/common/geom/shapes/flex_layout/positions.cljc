;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.positions
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]))

(defn get-base-line
  [parent layout-bounds total-width total-height num-lines]

  (let [layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)
        row?          (ctl/row? parent)
        col?          (ctl/col? parent)
        hv            (partial gpo/start-hv layout-bounds)
        vv            (partial gpo/start-vv layout-bounds)

        wrap?    (ctl/wrap? parent)

        end?     (or (and wrap? (ctl/content-end? parent))
                     (and (not wrap?) (ctl/align-items-end? parent)))
        center?  (or (and wrap? (ctl/content-center? parent))
                     (and (not wrap?) (ctl/align-items-center? parent)))
        around?  (and wrap? (ctl/content-around? parent))
        evenly?  (and wrap? (ctl/content-evenly? parent))

        ;; Adjust the totals so it takes into account the gaps
        [layout-gap-row layout-gap-col] (ctl/gaps parent)
        lines-gap-row (* (dec num-lines) layout-gap-row)
        lines-gap-col (* (dec num-lines) layout-gap-col)

        free-width-gap (- layout-width total-width lines-gap-col)
        free-height-gap (- layout-height total-height lines-gap-row)
        free-width (- layout-width total-width)
        free-height (- layout-height total-height)]

    (cond-> (gpo/origin layout-bounds)
      row?
      (cond-> center?
        (gpt/add (vv (/ free-height-gap 2)))

        end?
        (gpt/add (vv free-height-gap))

        around?
        (gpt/add (vv (max lines-gap-row (/ free-height num-lines 2))))

        evenly?
        (gpt/add (vv (max lines-gap-row (/ free-height (inc num-lines))))))

      col?
      (cond-> center?
        (gpt/add (hv (/ free-width-gap 2)))

        end?
        (gpt/add (hv free-width-gap))

        around?
        (gpt/add (hv (max lines-gap-col (/ free-width num-lines) 2)))

        evenly?
        (gpt/add (hv (max lines-gap-col (/ free-width (inc num-lines)))))))))

(defn get-next-line
  [parent layout-bounds {:keys [line-width line-height]} base-p total-width total-height num-lines]

  (let [layout-width  (gpo/width-points layout-bounds)
        layout-height (gpo/height-points layout-bounds)
        row? (ctl/row? parent)
        col? (ctl/col? parent)

        auto-width? (ctl/auto-width? parent)
        auto-height? (ctl/auto-height? parent)

        [layout-gap-row layout-gap-col] (ctl/gaps parent)

        hv   #(gpo/start-hv layout-bounds %)
        vv   #(gpo/start-vv layout-bounds %)

        stretch? (ctl/content-stretch? parent)
        between? (ctl/content-between? parent)
        around?  (ctl/content-around? parent)
        evenly?  (ctl/content-evenly? parent)

        free-width  (- layout-width total-width)
        free-height (- layout-height total-height)

        line-gap-col
        (cond
          auto-width?
          layout-gap-col

          stretch?
          (/ free-width num-lines)

          between?
          (/ free-width (dec num-lines))

          around?
          (/ free-width num-lines)

          evenly?
          (/ free-width (inc num-lines))

          :else
          layout-gap-col)

        line-gap-row
        (cond
          auto-height?
          layout-gap-row

          stretch?
          (/ free-height num-lines)

          between?
          (/ free-height (dec num-lines))

          around?
          (/ free-height num-lines)

          evenly?
          (/ free-height (inc num-lines))

          :else
          layout-gap-row)]

    (cond-> base-p
      row?
      (gpt/add (vv (+ line-height (max layout-gap-row line-gap-row))))

      col?
      (gpt/add (hv (+ line-width (max layout-gap-col line-gap-col)))))))

(defn get-start-line
  "Cross axis line. It's position is fixed along the different lines"
  [parent layout-bounds {:keys [line-width line-height num-children]} base-p total-width total-height num-lines]

  (let [layout-width        (gpo/width-points layout-bounds)
        layout-height       (gpo/height-points layout-bounds)
        [layout-gap-row layout-gap-col] (ctl/gaps parent)
        row?                (ctl/row? parent)
        col?                (ctl/col? parent)
        space-between?      (ctl/space-between? parent)
        space-around?       (ctl/space-around? parent)
        space-evenly?       (ctl/space-evenly? parent)
        h-center?           (ctl/h-center? parent)
        h-end?              (ctl/h-end? parent)
        v-center?           (ctl/v-center? parent)
        v-end?              (ctl/v-end? parent)
        content-stretch?    (ctl/content-stretch? parent)
        auto-width?         (ctl/auto-width? parent)
        auto-height?        (ctl/auto-height? parent)
        hv                  (partial gpo/start-hv layout-bounds)
        vv                  (partial gpo/start-vv layout-bounds)
        children-gap-width  (* layout-gap-col (dec num-children))
        children-gap-height (* layout-gap-row (dec num-children))

        line-height
        (if (and row? content-stretch? (not auto-height?))
          (+ line-height (/ (- layout-height total-height) num-lines))
          line-height)

        line-width
        (if (and col? content-stretch? (not auto-width?))
          (+ line-width (/ (- layout-width total-width) num-lines))
          line-width)

        start-p
        (cond-> base-p
          ;; X AXIS
          (and row? h-center? (not space-around?) (not space-evenly?) (not space-between?))
          (-> (gpt/add (hv (/ layout-width 2)))
              (gpt/subtract (hv (/ (+ line-width children-gap-width) 2))))

          (and row? h-end? (not space-around?) (not space-evenly?) (not space-between?))
          (-> (gpt/add (hv layout-width))
              (gpt/subtract (hv (+ line-width children-gap-width))))

          ;; Y AXIS
          (and col? v-center? (not space-around?) (not space-evenly?) (not space-between?))
          (-> (gpt/add (vv (/ layout-height 2)))
              (gpt/subtract (vv (/ (+ line-height children-gap-height) 2))))

          (and col? v-end? (not space-around?) (not space-evenly?) (not space-between?))
          (-> (gpt/add (vv layout-height))
              (gpt/subtract (vv (+ line-height children-gap-height)))))]

    start-p))

(defn get-child-position
  "Calculates the position for the current shape given the layout-data context"
  [parent child
   child-width child-height
   {:keys [start-p layout-gap-row layout-gap-col margin-x margin-y line-height line-width layout-bounds] :as layout-data}]

  (let [row?         (ctl/row? parent)
        col?         (ctl/col? parent)
        h-start?     (ctl/h-start? parent)
        h-center?    (ctl/h-center? parent)
        h-end?       (ctl/h-end? parent)
        v-start?     (ctl/v-start? parent)
        v-center?    (ctl/v-center? parent)
        v-end?       (ctl/v-end? parent)

        self-start?  (ctl/align-self-start? child)
        self-end?    (ctl/align-self-end? child)
        self-center? (ctl/align-self-center? child)
        align-self?  (or self-start? self-end? self-center?)

        v-start?     (if (or col? (not align-self?)) v-start? self-start?)
        v-center?    (if (or col? (not align-self?)) v-center? self-center?)
        v-end?       (if (or col? (not align-self?)) v-end? self-end?)

        h-start?     (if (or row? (not align-self?)) h-start? self-start?)
        h-center?    (if (or row? (not align-self?)) h-center? self-center?)
        h-end?       (if (or row? (not align-self?)) h-end? self-end?)

        [margin-top margin-right margin-bottom margin-left] (ctl/child-margins child)

        hv     (partial gpo/start-hv layout-bounds)
        vv     (partial gpo/start-vv layout-bounds)

        corner-p
        (cond-> start-p
          ;; COLUMN DIRECTION
          col?
          (cond-> (some? margin-top)
            (gpt/add (vv margin-top))

            h-center?
            (gpt/add (hv (- (/ child-width 2))))

            h-end?
            (gpt/add (hv (- child-width)))

            h-start?
            (gpt/add (hv margin-left))

            h-center?
            (gpt/add (hv (+ (/ line-width 2) (/ (- margin-left margin-right) 2))))

            h-end?
            (gpt/add (hv (+ line-width (- margin-right)))))

          ;; ROW DIRECTION
          row?
          (cond-> v-center?
            (gpt/add (vv (- (/ child-height 2))))

            v-end?
            (gpt/add (vv (- child-height)))

            (some? margin-left)
            (gpt/add (hv margin-left))

            v-start?
            (gpt/add (vv margin-top))

            v-center?
            (gpt/add (vv (+ (/ line-height 2) (/ (- margin-top margin-bottom) 2))))

            v-end?
            (gpt/add (vv (+ line-height (- margin-bottom)))))

          ;; Margins
          (some? margin-x)
          (gpt/add (hv margin-x))

          (some? margin-y)
          (gpt/add (vv margin-y)))

        ;; Fix position when layout is flipped
        ;;corner-p
        ;;(cond-> corner-p
        ;;  (:flip-x parent)
        ;;  (gpt/add (hv child-width))
        ;;
        ;;  (:flip-y parent)
        ;;  (gpt/add (vv child-height)))

        next-p
        (cond-> start-p
          row?
          (-> (gpt/add (hv (+ child-width layout-gap-col)))
              (gpt/add (hv (+ margin-left margin-right))))

          col?
          (-> (gpt/add (vv (+ margin-top margin-bottom)))
              (gpt/add (vv (+ child-height layout-gap-row))))

          (some? margin-x)
          (gpt/add (hv margin-x))

          (some? margin-y)
          (gpt/add (vv margin-y)))

        layout-data
        (assoc layout-data :start-p next-p)]

    [corner-p layout-data]))

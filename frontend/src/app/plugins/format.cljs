;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.format
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.util.object :as obj]))

(defn format-id
  [id]
  (when id (dm/str id)))

(defn format-key
  [kw]
  (when kw (d/name kw)))

(defn format-array
  [format-fn coll]
  (when (some? coll)
    (apply array (keep format-fn coll))))

(defn format-mixed
  [value]
  (if (= value :multiple)
    "mixed"
    value))

;; export type PenpotPoint = { x: number; y: number };
(defn format-point
  [{:keys [x y] :as point}]
  (when (some? point)
    (obj/clear-empty
     #js {:x x :y y})))

;;export type PenpotBounds = {
;;  x: number;
;;  y: number;
;;  width: number;
;;  height: number;
;;};
(defn format-bounds
  [{:keys [x y width height] :as bounds}]
  (when (some? bounds)
    (obj/clear-empty
     #js {:x x :y y :width width :height height})))

;; export interface PenpotColorShapeInfoEntry {
;;   readonly property: string;
;;   readonly index?: number;
;;   readonly shapeId: string;
;; }
(defn format-shape-info
  [{:keys [prop shape-id index] :as info}]
  (when (some? info)
    (obj/clear-empty
     #js {:property (d/name prop)
          :index index
          :shapeId (dm/str shape-id)})))

;; export type PenpotGradient = {
;;   type: 'linear' | 'radial';
;;   startX: number;
;;   startY: number;
;;   endX: number;
;;   endY: number;
;;   width: number;
;;   stops: Array<{ color: string; opacity?: number; offset: number }>;
;; };
(defn format-stop
  [{:keys [color opacity offset] :as stop}]
  (when (some? stop)
    (obj/clear-empty #js {:color color :opacity opacity :offset offset})))

(defn format-gradient
  [{:keys [type start-x start-y end-x end-y width stops] :as gradient}]
  (when (some? gradient)
    (obj/clear-empty
     #js {:type (format-key type)
          :startX start-x
          :startY start-y
          :endX end-x
          :endY end-y
          :width width
          :stops (format-array format-stop stops)})))

;; export type PenpotImageData = {
;;   name?: string;
;;   width: number;
;;   height: number;
;;   mtype?: string;
;;   id: string;
;;   keepApectRatio?: boolean;
;; };
(defn format-image
  [{:keys [name width height mtype id keep-aspect-ratio] :as image}]
  (when (some? image)
    (obj/clear-empty
     #js {:name name
          :width width
          :height height
          :mtype mtype
          :id (format-id id)
          :keepAspectRatio keep-aspect-ratio})))

;; export interface PenpotColor {
;;   id?: string;
;;   name?: string;
;;   path?: string;
;;   color?: string;
;;   opacity?: number;
;;   refId?: string;
;;   refFile?: string;
;;   gradient?: PenpotGradient;
;;   image?: PenpotImageData;
;; }
(defn format-color
  [{:keys [id name path color opacity ref-id ref-file gradient image] :as color-data}]
  (when (some? color-data)
    (obj/clear-empty
     #js {:id (format-id id)
          :name name
          :path path
          :color color
          :opacity opacity
          :refId (format-id ref-id)
          :refFile (format-id ref-file)
          :gradient (format-gradient gradient)
          :image (format-image image)})))

;; PenpotColor & PenpotColorShapeInfo
(defn format-color-result
  [[color attrs]]
  (let [shapes-info (apply array (map format-shape-info attrs))
        color (format-color color)]
    (obj/set! color "shapeInfo" shapes-info)
    color))


;; export interface PenpotShadow {
;;   id?: string;
;;   style?: 'drop-shadow' | 'inner-shadow';
;;   offsetX?: number;
;;   offsetY?: number;
;;   blur?: number;
;;   spread?: number;
;;   hidden?: boolean;
;;   color?: PenpotColor;
;; }
(defn format-shadow
  [{:keys [id style offset-x offset-y blur spread hidden color] :as shadow}]
  (when (some? shadow)
    (obj/clear-empty
     #js {:id (-> id format-id)
          :style (-> style format-key)
          :offsetX offset-x
          :offsetY offset-y
          :blur blur
          :spread spread
          :hidden hidden
          :color (format-color color)})))

(defn format-shadows
  [shadows]
  (when (some? shadows)
    (format-array format-shadow shadows)))

;;export interface PenpotFill {
;;  fillColor?: string;
;;  fillOpacity?: number;
;;  fillColorGradient?: PenpotGradient;
;;  fillColorRefFile?: string;
;;  fillColorRefId?: string;
;;  fillImage?: PenpotImageData;
;;}
(defn format-fill
  [{:keys [fill-color fill-opacity fill-color-gradient fill-color-ref-file fill-color-ref-id fill-image] :as fill}]
  (when (some? fill)
    (obj/clear-empty
     #js {:fillColor fill-color
          :fillOpacity fill-opacity
          :fillColorGradient (format-gradient fill-color-gradient)
          :fillColorRefFile (format-id fill-color-ref-file)
          :fillColorRefId (format-id fill-color-ref-id)
          :fillImage (format-image fill-image)})))

(defn format-fills
  [fills]
  (cond
    (= fills :multiple)
    "mixed"

    (= fills "mixed")
    "mixed"

    (some? fills)
    (format-array format-fill fills)))

;; export interface PenpotStroke {
;;   strokeColor?: string;
;;   strokeColorRefFile?: string;
;;   strokeColorRefId?: string;
;;   strokeOpacity?: number;
;;   strokeStyle?: 'solid' | 'dotted' | 'dashed' | 'mixed' | 'none' | 'svg';
;;   strokeWidth?: number;
;;   strokeAlignment?: 'center' | 'inner' | 'outer';
;;   strokeCapStart?: PenpotStrokeCap;
;;   strokeCapEnd?: PenpotStrokeCap;
;;   strokeColorGradient?: PenpotGradient;
;; }
(defn format-stroke
  [{:keys [stroke-color stroke-color-ref-file stroke-color-ref-id
           stroke-opacity stroke-style stroke-width stroke-alignment
           stroke-cap-start stroke-cap-end stroke-color-gradient] :as stroke}]

  (when (some? stroke)
    (obj/clear-empty
     #js {:strokeColor stroke-color
          :strokeColorRefFile (format-id stroke-color-ref-file)
          :strokeColorRefId (format-id stroke-color-ref-id)
          :strokeOpacity stroke-opacity
          :strokeStyle (format-key stroke-style)
          :strokeWidth stroke-width
          :strokeAlignment (format-key stroke-alignment)
          :strokeCapStart (format-key stroke-cap-start)
          :strokeCapEnd (format-key stroke-cap-end)
          :strokeColorGradient (format-gradient stroke-color-gradient)})))

(defn format-strokes
  [strokes]
  (when (some? strokes)
    (format-array format-stroke strokes)))

;; export interface PenpotBlur {
;;   id?: string;
;;   type?: 'layer-blur';
;;   value?: number;
;;   hidden?: boolean;
;; }
(defn format-blur
  [{:keys [id type value hidden] :as blur}]
  (when (some? blur)
    (obj/clear-empty
     #js {:id (format-id id)
          :type (format-key type)
          :value value
          :hidden hidden})))

;; export interface PenpotExport {
;;   type: 'png' | 'jpeg' | 'svg' | 'pdf';
;;   scale: number;
;;   suffix: string;
;; }
(defn format-export
  [{:keys [type scale suffix] :as export}]
  (when (some? export)
    (obj/clear-empty
     #js {:type (format-key type)
          :scale scale
          :suffix suffix})))

(defn format-exports
  [exports]
  (when (some? exports)
    (format-array format-export exports)))

;; export interface PenpotFrameGuideColumnParams {
;;   color: { color: string; opacity: number };
;;   type?: 'stretch' | 'left' | 'center' | 'right';
;;   size?: number;
;;   margin?: number;
;;   itemLength?: number;
;;   gutter?: number;
;; }
(defn format-frame-guide-column-params
  [{:keys [color type size margin item-length gutter] :as params}]
  (when (some? params)
    (obj/clear-empty
     #js {:color (format-color color)
          :type (format-key type)
          :size size
          :margin margin
          :itemLength item-length
          :gutter gutter})))

;; export interface PenpotFrameGuideColumn {
;;   type: 'column';
;;   display: boolean;
;;   params: PenpotFrameGuideColumnParams;
;; }
(defn format-frame-guide-column
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/clear-empty
     #js {:type (format-key type)
          :display display
          :params (format-frame-guide-column-params params)})))

;; export interface PenpotFrameGuideRow {
;;   type: 'row';
;;   display: boolean;
;;   params: PenpotFrameGuideColumnParams;
;; }
(defn format-frame-guide-row
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/clear-empty
     #js {:type (format-key type)
          :display display
          :params (format-frame-guide-column-params params)})))

;;export interface PenpotFrameGuideSquareParams {
;;  color: { color: string; opacity: number };
;;  size?: number;
;;}
(defn format-frame-guide-square-params
  [{:keys [color size] :as params}]
  (when (some? params)
    (obj/clear-empty
     #js {:color (format-color color)
          :size size})))

;; export interface PenpotFrameGuideSquare {
;;   type: 'square';
;;   display: boolean;
;;   params: PenpotFrameGuideSquareParams;
;; }

(defn format-frame-guide-square
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/clear-empty
     #js {:type (format-key type)
          :display display
          :params (format-frame-guide-column-params params)})))

(defn format-frame-guide
  [{:keys [type] :as guide}]
  (when (some? guide)
    (case type
      :column (format-frame-guide-column guide)
      :row    (format-frame-guide-row guide)
      :square (format-frame-guide-square guide))))

(defn format-frame-guides
  [guides]
  (when (some? guides)
    (format-array format-frame-guide guides)))

;;interface PenpotPathCommand {
;;  command:
;;    | 'M' | 'move-to'
;;    | 'Z' | 'close-path'
;;    | 'L' | 'line-to'
;;    | 'H' | 'line-to-horizontal'
;;    | 'V' | 'line-to-vertical'
;;    | 'C' | 'curve-to'
;;    | 'S' | 'smooth-curve-to'
;;    | 'Q' | 'quadratic-bezier-curve-to'
;;    | 'T' | 'smooth-quadratic-bezier-curve-to'
;;    | 'A' | 'elliptical-arc';
;;
;;  params?: {
;;    x?: number;
;;    y?: number;
;;    c1x: number;
;;    c1y: number;
;;    c2x: number;
;;    c2y: number;
;;    rx?: number;
;;    ry?: number;
;;    xAxisRotation?: number;
;;    largeArcFlag?: boolean;
;;    sweepFlag?: boolean;
;;  };
;;}
(defn format-command-params
  [{:keys [x y c1x c1y c2x c2y rx ry x-axis-rotation large-arc-flag sweep-flag] :as props}]
  (when (some? props)
    (obj/clear-empty
     #js {:x x
          :y y
          :c1x c1x
          :c1y c1y
          :c2x c2x
          :c2y c2y
          :rx rx
          :ry ry
          :xAxisRotation x-axis-rotation
          :largeArcFlag large-arc-flag
          :sweepFlag sweep-flag})))

(defn format-command
  [{:keys [command params] :as props}]
  (when (some? props)
    (obj/clear-empty
     #js {:command (format-key command)
          :params (format-command-params params)})))

(defn format-path-content
  [content]
  (when (some? content)
    (format-array format-command content)))

;; export type PenpotTrackType = 'flex' | 'fixed' | 'percent' | 'auto';
;;
;; export interface PenpotTrack {
;;   type: PenpotTrackType;
;;   value: number | null;
;; }
(defn format-track
  [{:keys [type value] :as track}]
  (when (some? track)
    (obj/clear-empty
     #js {:type (-> type format-key)
          :value value})))

(defn format-tracks
  [tracks]
  (when (some? tracks)
    (format-array format-track tracks)))

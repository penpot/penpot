;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.parser
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn parse-id
  [id]
  (when id (uuid/uuid id)))

(defn parse-keyword
  [kw]
  (when kw (keyword kw)))

(defn parse-hex
  [color]
  (if (string? color) (-> color str/lower) color))

;; {
;;    name?: string;
;;    nameLike?: string;
;;    type?:
;;      | 'frame'
;;      | 'group'
;;      | 'bool'
;;      | 'rect'
;;      | 'path'
;;      | 'text'
;;      | 'circle'
;;      | 'svg-raw'
;;      | 'image';
;;  }
(defn parse-criteria
  [^js criteria]
  (when (some? criteria)
    (d/without-nils
     {:name (obj/get criteria "name")
      :name-like (obj/get criteria "nameLike")
      :type (-> (obj/get criteria "type") parse-keyword)})))

;;export type PenpotImageData = {
;;  name?: string;
;;  width: number;
;;  height: number;
;;  mtype?: string;
;;  id: string;
;;  keepApectRatio?: boolean;
;;}
(defn parse-image-data
  [^js image-data]
  (when (some? image-data)
    (d/without-nils
     {:id (-> (obj/get image-data "id") parse-id)
      :name (obj/get image-data "name")
      :width (obj/get image-data "width")
      :height (obj/get image-data "height")
      :mtype (obj/get image-data "mtype")
      :keep-aspect-ratio (obj/get image-data "keepApectRatio")})))

;; export type PenpotGradient = {
;;   type: 'linear' | 'radial';
;;   startX: number;
;;   startY: number;
;;   endX: number;
;;   endY: number;
;;   width: number;
;;   stops: Array<{ color: string; opacity?: number; offset: number }>;
;; }
(defn parse-gradient-stop
  [^js stop]
  (when (some? stop)
    (d/without-nils
     {:color (-> (obj/get stop "color") parse-hex)
      :opacity (obj/get stop "opacity")
      :offset (obj/get stop "offset")})))

(defn parse-gradient
  [^js gradient]
  (when (some? gradient)
    (d/without-nils
     {:type (-> (obj/get gradient "type") parse-keyword)
      :start-x (obj/get gradient "startX")
      :start-y (obj/get gradient "startY")
      :end-x (obj/get gradient "endX")
      :end-y (obj/get gradient "endY")
      :width (obj/get gradient "width")
      :stops (->> (obj/get gradient "stops")
                  (mapv parse-gradient-stop))})))

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
(defn parse-color
  [^js color]
  (when (some? color)
    (d/without-nils
     {:id (-> (obj/get color "id") parse-id)
      :name (obj/get color "name")
      :path (obj/get color "path")
      :color (-> (obj/get color "color") parse-hex)
      :opacity (obj/get color "opacity")
      :ref-id (-> (obj/get color "refId") parse-id)
      :ref-file (-> (obj/get color "refFile") parse-id)
      :gradient (-> (obj/get color "gradient") parse-gradient)
      :image (-> (obj/get color "image") parse-image-data)})))

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
(defn parse-shadow
  [^js shadow]
  (when (some? shadow)
    (d/without-nils
     {:id (-> (obj/get shadow "id") parse-id)
      :style (-> (obj/get shadow "style") parse-keyword)
      :offset-x (obj/get shadow "offsetX")
      :offset-y (obj/get shadow "offsetY")
      :blur (obj/get shadow "blur")
      :spread (obj/get shadow "spread")
      :hidden (obj/get shadow "hidden")
      :color (-> (obj/get shadow "color") parse-color)})))

(defn parse-shadows
  [^js shadows]
  (when (some? shadows)
    (into [] (map parse-shadow) shadows)))

;;export interface PenpotFill {
;;  fillColor?: string;
;;  fillOpacity?: number;
;;  fillColorGradient?: PenpotGradient;
;;  fillColorRefFile?: string;
;;  fillColorRefId?: string;
;;  fillImage?: PenpotImageData;
;;}
(defn parse-fill
  [^js fill]
  (when (some? fill)
    (d/without-nils
     {:fill-color (-> (obj/get fill "fillColor") parse-hex)
      :fill-opacity (obj/get fill "fillOpacity")
      :fill-color-gradient (-> (obj/get fill "fillColorGradient") parse-gradient)
      :fill-color-ref-file (-> (obj/get fill "fillColorRefFile") parse-id)
      :fill-color-ref-id (-> (obj/get fill "fillColorRefId") parse-id)
      :fill-image (-> (obj/get fill "fillImage") parse-image-data)})))

(defn parse-fills
  [^js fills]
  (when (some? fills)
    (into [] (map parse-fill) fills)))

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
(defn parse-stroke
  [^js stroke]
  (when (some? stroke)
    (d/without-nils
     {:stroke-color (-> (obj/get stroke "strokeColor") parse-hex)
      :stroke-color-ref-file (-> (obj/get stroke "strokeColorRefFile") parse-id)
      :stroke-color-ref-id (-> (obj/get stroke "strokeColorRefId") parse-id)
      :stroke-opacity (obj/get stroke "strokeOpacity")
      :stroke-style (-> (obj/get stroke "strokeStyle") parse-keyword)
      :stroke-width (obj/get stroke "strokeWidth")
      :stroke-alignment (-> (obj/get stroke "strokeAlignment") parse-keyword)
      :stroke-cap-start (-> (obj/get stroke "strokeCapStart") parse-keyword)
      :stroke-cap-end (-> (obj/get stroke "strokeCapEnd") parse-keyword)
      :stroke-color-gradient (-> (obj/get stroke "strokeColorGradient") parse-gradient)})))

(defn parse-strokes
  [^js strokes]
  (when (some? strokes)
    (into [] (map parse-stroke) strokes)))

;; export interface PenpotBlur {
;;   id?: string;
;;   type?: 'layer-blur';
;;   value?: number;
;;   hidden?: boolean;
;; }
(defn parse-blur
  [^js blur]
  (when (some? blur)
    (d/without-nils
     {:id (-> (obj/get blur "id") parse-id)
      :type (-> (obj/get blur "type") parse-keyword)
      :value (obj/get blur "value")
      :hidden (obj/get blur "hidden")})))


;; export interface PenpotExport {
;;   type: 'png' | 'jpeg' | 'svg' | 'pdf';
;;   scale: number;
;;   suffix: string;
;; }
(defn parse-export
  [^js export]
  (when (some? export)
    (d/without-nils
     {:type (-> (obj/get export "type") parse-keyword)
      :scale (obj/get export "scale")
      :suffix (obj/get export "suffix")})))

(defn parse-exports
  [^js exports]
  (when (some? exports)
    (into [] (map parse-export) exports)))

;; export interface PenpotFrameGuideColumnParams {
;;   color: { color: string; opacity: number };
;;   type?: 'stretch' | 'left' | 'center' | 'right';
;;   size?: number;
;;   margin?: number;
;;   itemLength?: number;
;;   gutter?: number;
;; }
(defn parse-frame-guide-column-params
  [^js params]
  (when params
    (d/without-nils
     {:color (-> (obj/get params "color") parse-color)
      :type (-> (obj/get params "type") parse-keyword)
      :size (obj/get params "size")
      :margin (obj/get params "margin")
      :item-length (obj/get params "itemLength")
      :gutter (obj/get params "gutter")})))

;; export interface PenpotFrameGuideColumn {
;;   type: 'column';
;;   display: boolean;
;;   params: PenpotFrameGuideColumnParams;
;; }
(defn parse-frame-guide-column
  [^js guide]
  (when guide
    (d/without-nils
     {:type (-> (obj/get guide "type") parse-keyword)
      :display (obj/get guide "display")
      :params (-> (obj/get guide "params") parse-frame-guide-column-params)})))

;; export interface PenpotFrameGuideRow {
;;   type: 'row';
;;   display: boolean;
;;   params: PenpotFrameGuideColumnParams;
;; }

(defn parse-frame-guide-row
  [^js guide]
  (when guide
    (d/without-nils
     {:type (-> (obj/get guide "type") parse-keyword)
      :display (obj/get guide "display")
      :params (-> (obj/get guide "params") parse-frame-guide-column-params)})))

;;export interface PenpotFrameGuideSquareParams {
;;  color: { color: string; opacity: number };
;;  size?: number;
;;}
(defn parse-frame-guide-square-params
  [^js params]
  (when (some? params)
    (d/without-nils
     {:color (-> (obj/get params "color") parse-color)
      :size (obj/get params "size")})))

;; export interface PenpotFrameGuideSquare {
;;   type: 'square';
;;   display: boolean;
;;   params: PenpotFrameGuideSquareParams;
;; }
(defn parse-frame-guide-square
  [^js guide]
  (when guide
    (d/without-nils
     {:type (-> (obj/get guide "type") parse-keyword)
      :display (obj/get guide "display")
      :params (-> (obj/get guide "params") parse-frame-guide-column-params)})))

(defn parse-frame-guide
  [^js guide]
  (when (some? guide)
    (case (obj/get guide "type")
      "column"
      parse-frame-guide-column

      "row"
      parse-frame-guide-row

      "square"
      (parse-frame-guide-square guide))))

(defn parse-frame-guides
  [^js guides]
  (when (some? guides)
    (into [] (map parse-frame-guide) guides)))

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
(defn parse-command-type
  [^string command-type]
  (case command-type
    "M" :move-to
    "Z" :close-path
    "L" :line-to
    "H" :line-to-horizontal
    "V" :line-to-vertical
    "C" :curve-to
    "S" :smooth-curve-to
    "Q" :quadratic-bezier-curve-to
    "T" :smooth-quadratic-bezier-curve-to
    "A" :elliptical-arc
    (parse-keyword command-type)))

(defn parse-command-params
  [^js params]
  (when (some? params)
    (d/without-nils
     {:x (obj/get params "x")
      :y (obj/get params "y")
      :c1x (obj/get params "c1x")
      :c1y (obj/get params "c1y")
      :c2x (obj/get params "c2x")
      :c2y (obj/get params "c2y")
      :rx (obj/get params "rx")
      :ry (obj/get params "ry")
      :x-axis-rotation (obj/get params "xAxisRotation")
      :large-arc-flag (obj/get params "largeArcFlag")
      :sweep-flag (obj/get params "sweepFlag")})))

(defn parse-command
  [^js command]
  (when (some? command)
    (d/without-nils
     {:command (-> (obj/get command "command") parse-command-type)
      :params (-> (obj/get command "paras") parse-command-params)})))

(defn parse-path-content
  [^js content]
  (when (some? content)
    (into [] (map parse-command) content)))

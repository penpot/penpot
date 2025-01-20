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

(def shape-proxy nil)

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

;; export type Point = { x: number; y: number };
(defn format-point
  [{:keys [x y] :as point}]
  (when (some? point)
    (obj/without-empty
     #js {:x x :y y})))

(defn shape-type
  [type]
  (case type
    :frame "board"
    :rect "rectangle"
    :circle "ellipse"
    (d/name type)))

;;export type Bounds = {
;;  x: number;
;;  y: number;
;;  width: number;
;;  height: number;
;;};
(defn format-bounds
  [{:keys [x y width height] :as bounds}]
  (when (some? bounds)
    (obj/without-empty
     #js {:x x :y y :width width :height height})))

;; export interface ColorShapeInfoEntry {
;;   readonly property: string;
;;   readonly index?: number;
;;   readonly shapeId: string;
;; }
(defn format-shape-info
  [{:keys [prop shape-id index] :as info}]
  (when (some? info)
    (obj/without-empty
     #js {:property (d/name prop)
          :index index
          :shapeId (dm/str shape-id)})))

;; export type Gradient = {
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
    (obj/without-empty #js {:color color :opacity opacity :offset offset})))

(defn format-gradient
  [{:keys [type start-x start-y end-x end-y width stops] :as gradient}]
  (when (some? gradient)
    (obj/without-empty
     #js {:type (format-key type)
          :startX start-x
          :startY start-y
          :endX end-x
          :endY end-y
          :width width
          :stops (format-array format-stop stops)})))

;; export type ImageData = {
;;   name?: string;
;;   width: number;
;;   height: number;
;;   mtype?: string;
;;   id: string;
;;   keepAspectRatio?: boolean;
;; };
(defn format-image
  [{:keys [name width height mtype id keep-aspect-ratio] :as image}]
  (when (some? image)
    (obj/without-empty
     #js {:name name
          :width width
          :height height
          :mtype mtype
          :id (format-id id)
          :keepAspectRatio keep-aspect-ratio})))

;; export interface Color {
;;   id?: string;
;;   name?: string;
;;   path?: string;
;;   color?: string;
;;   opacity?: number;
;;   refId?: string;
;;   refFile?: string;
;;   gradient?: Gradient;
;;   image?: ImageData;
;; }
(defn format-color
  [{:keys [id file-id name path color opacity ref-id ref-file gradient image] :as color-data}]
  (when (some? color-data)
    (let [id (or (format-id id) (format-id ref-id))
          file-id (or (format-id file-id) (format-id ref-file))]
      (obj/without-empty
       #js {:id (or (format-id id) (format-id ref-id))
            :fileId (or (format-id file-id) (format-id ref-file))
            :name name
            :path path
            :color color
            :opacity opacity
            :gradient (format-gradient gradient)
            :image (format-image image)}))))

;; Color & ColorShapeInfo
(defn format-color-result
  [[color attrs]]
  (let [shapes-info (apply array (map format-shape-info attrs))
        color (format-color color)]
    (obj/set! color "shapeInfo" shapes-info)
    color))


;; export interface Shadow {
;;   id?: string;
;;   style?: 'drop-shadow' | 'inner-shadow';
;;   offsetX?: number;
;;   offsetY?: number;
;;   blur?: number;
;;   spread?: number;
;;   hidden?: boolean;
;;   color?: Color;
;; }
(defn format-shadow
  [{:keys [id style offset-x offset-y blur spread hidden color] :as shadow}]
  (when (some? shadow)
    (obj/without-empty
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

;;export interface Fill {
;;  fillColor?: string;
;;  fillOpacity?: number;
;;  fillColorGradient?: Gradient;
;;  fillColorRefFile?: string;
;;  fillColorRefId?: string;
;;  fillImage?: ImageData;
;;}
(defn format-fill
  [{:keys [fill-color fill-opacity fill-color-gradient fill-color-ref-file fill-color-ref-id fill-image] :as fill}]
  (when (some? fill)
    (obj/without-empty
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

;; export interface Stroke {
;;   strokeColor?: string;
;;   strokeColorRefFile?: string;
;;   strokeColorRefId?: string;
;;   strokeOpacity?: number;
;;   strokeStyle?: 'solid' | 'dotted' | 'dashed' | 'mixed' | 'none' | 'svg';
;;   strokeWidth?: number;
;;   strokeAlignment?: 'center' | 'inner' | 'outer';
;;   strokeCapStart?: StrokeCap;
;;   strokeCapEnd?: StrokeCap;
;;   strokeColorGradient?: Gradient;
;; }
(defn format-stroke
  [{:keys [stroke-color stroke-color-ref-file stroke-color-ref-id
           stroke-opacity stroke-style stroke-width stroke-alignment
           stroke-cap-start stroke-cap-end stroke-color-gradient] :as stroke}]

  (when (some? stroke)
    (obj/without-empty
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

;; export interface Blur {
;;   id?: string;
;;   type?: 'layer-blur';
;;   value?: number;
;;   hidden?: boolean;
;; }
(defn format-blur
  [{:keys [id type value hidden] :as blur}]
  (when (some? blur)
    (obj/without-empty
     #js {:id (format-id id)
          :type (format-key type)
          :value value
          :hidden hidden})))

;; export interface Export {
;;   type: 'png' | 'jpeg' | 'svg' | 'pdf';
;;   scale: number;
;;   suffix: string;
;; }
(defn format-export
  [{:keys [type scale suffix] :as export}]
  (when (some? export)
    (obj/without-empty
     #js {:type (format-key type)
          :scale scale
          :suffix suffix})))

(defn format-exports
  [exports]
  (when (some? exports)
    (format-array format-export exports)))

;; export interface GuideColumnParams {
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
    (obj/without-empty
     #js {:color (format-color color)
          :type (format-key type)
          :size size
          :margin margin
          :itemLength item-length
          :gutter gutter})))

;; export interface GuideColumn {
;;   type: 'column';
;;   display: boolean;
;;   params: GuideColumnParams;
;; }
(defn format-frame-guide-column
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/without-empty
     #js {:type (format-key type)
          :display display
          :params (format-frame-guide-column-params params)})))

;; export interface GuideRow {
;;   type: 'row';
;;   display: boolean;
;;   params: GuideColumnParams;
;; }
(defn format-frame-guide-row
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/without-empty
     #js {:type (format-key type)
          :display display
          :params (format-frame-guide-column-params params)})))

;;export interface GuideSquareParams {
;;  color: { color: string; opacity: number };
;;  size?: number;
;;}
(defn format-frame-guide-square-params
  [{:keys [color size] :as params}]
  (when (some? params)
    (obj/without-empty
     #js {:color (format-color color)
          :size size})))

;; export interface GuideSquare {
;;   type: 'square';
;;   display: boolean;
;;   params: GuideSquareParams;
;; }

(defn format-frame-guide-square
  [{:keys [type display params] :as guide}]
  (when (some? guide)
    (obj/without-empty
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

;;interface PathCommand {
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
    (obj/without-empty
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
    (obj/without-empty
     #js {:command (format-key command)
          :params (format-command-params params)})))

(defn format-path-content
  [content]
  (when (some? content)
    (format-array format-command content)))

;; export type TrackType = 'flex' | 'fixed' | 'percent' | 'auto';
;;
;; export interface Track {
;;   type: TrackType;
;;   value: number | null;
;; }
(defn format-track
  [{:keys [type value] :as track}]
  (when (some? track)
    (obj/without-empty
     #js {:type (-> type format-key)
          :value value})))

(defn format-tracks
  [tracks]
  (when (some? tracks)
    (format-array format-track tracks)))


;; export interface Dissolve {
;;   type: 'dissolve';
;;   duration: number;
;;   easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
;; }
;;
;; export interface Slide {
;;   type: 'slide';
;;   way: 'in' | 'out';
;;   direction?:
;;     | 'right'
;;     | 'left'
;;     | 'up'
;;     | 'down';
;;   duration: number;
;;   offsetEffect?: boolean;
;;   easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
;; }
;;
;; export interface Push {
;;   type: 'push';
;;   direction?:
;;     | 'right'
;;     | 'left'
;;     | 'up'
;;     | 'down';
;;
;;   duration: number;
;;   easing?: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
;; }
;;
;; export type Animation = Dissolve | Slide | Push;

(defn format-animation
  [animation]
  (when animation
    (obj/without-empty
     (case (:animation-type animation)

       :dissolve
       #js {:type "dissolve"
            :duration (:duration animation)
            :easing (format-key (:easing animation))}

       :slide
       #js {:type "slide"
            :way (format-key (:way animation))
            :direction (format-key (:direction animation))
            :duration (:duration animation)
            :easing (format-key (:easing animation))
            :offsetEffect (:offset-effect animation)}

       :push
       #js {:type "push"
            :direction (format-key (:direction animation))
            :duration (:duration animation)
            :easing (format-key (:easing animation))}
       nil))))

;;export type Action =
;;  | NavigateTo
;;  | OpenOverlay
;;  | ToggleOverlay
;;  | CloseOverlay
;;  | PreviousScreen
;;  | OpenUrl;
;;
;;export interface NavigateTo {
;;  type: 'navigate-to';
;;  destination: Board;
;;  preserveScrollPosition?: boolean;
;;  animation: Animation;
;;}
;;
;;export interface OverlayAction {
;;  destination: Board;
;;  relativeTo?: Shape;
;;  position?:
;;    | 'manual'
;;    | 'center'
;;    | 'top-left'
;;    | 'top-right'
;;    | 'top-center'
;;    | 'bottom-left'
;;    | 'bottom-right'
;;    | 'bottom-center';
;;  manualPositionLocation?: Point;
;;  closeWhenClickOutside?: boolean;
;;  addBackgroundOverlay?: boolean;
;;  animation: Animation;
;;}
;;
;;export interface OpenOverlay extends OverlayAction {
;;  type: 'open-overlay';
;;}
;;
;;export interface ToggleOverlay extends OverlayAction {
;;  type: 'toggle-overlay';
;;}
;;
;;export interface CloseOverlay {
;;  type: 'close-overlay';
;;  destination?: Board;
;;  animation: Animation;
;;}
;;
;;export interface PreviousScreen {
;;  type: 'previous-screen';
;;}
;;
;;export interface OpenUrl {
;;  type: 'open-url';
;;  url: string;
;;}
(defn format-action
  [interaction plugin file-id page-id]
  (when interaction
    (obj/without-empty
     (case (:action-type interaction)
       :navigate
       #js {:type "navigate-to"
            :destination (when (:destination interaction) (shape-proxy plugin file-id page-id (:destination interaction)))
            :preserveScrollPosition (:preserve-scroll interaction false)
            :animation (format-animation (:animation interaction))}

       :open-overlay
       #js {:type "open-overlay"
            :destination (when (:destination interaction) (shape-proxy plugin file-id page-id (:destination interaction)))
            :relativeTo (when (:relative-to interaction) (shape-proxy plugin file-id page-id (:relative-to interaction)))
            :position (format-key (:overlay-pos-type interaction))
            :manualPositionLocation (format-point (:overlay-position interaction))
            :closeWhenClickOutside (:close-click-outside interaction)
            :addBackgroundOverlay (:background-overlay interaction)
            :animation (format-animation (:animation interaction))}

       :toggle-overlay
       #js {:type "toggle-overlay"
            :destination (when (:destination interaction) (shape-proxy plugin file-id page-id (:destination interaction)))
            :relativeTo (when (:relative-to interaction) (shape-proxy plugin file-id page-id (:relative-to interaction)))
            :position (format-key (:overlay-pos-type interaction))
            :manualPositionLocation (format-point (:overlay-position interaction))
            :closeWhenClickOutside (:close-click-outside interaction)
            :addBackgroundOverlay (:background-overlay interaction)
            :animation (format-animation (:animation interaction))}

       :close-overlay
       #js {:type "close-overlay"
            :destination (when (:destination interaction) (shape-proxy plugin file-id page-id (:destination interaction)))
            :animation (format-animation (:animation interaction))}

       :prev-screen
       #js {:type "previous-screen"}

       :open-url
       #js {:type "open-url"
            :url (:url interaction)}

       nil))))

(defn axis->orientation
  [axis]
  (case axis
    :y "horizontal"
    :x "vertical"))

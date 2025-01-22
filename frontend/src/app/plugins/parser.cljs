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

(defn parse-point
  [^js point]
  (when point
    {:x (obj/get point "x")
     :y (obj/get point "y")}))

(defn parse-shape-type
  [type]
  (case type
    "board"     :frame
    "boolean"   :bool
    "rectangle" :rect
    "ellipse"   :circle
    (parse-keyword type)))

;; {
;;    name?: string;
;;    nameLike?: string;
;;    type?:
;;      | 'board'
;;      | 'group'
;;      | 'boolean'
;;      | 'rectangle'
;;      | 'path'
;;      | 'text'
;;      | 'ellipse'
;;      | 'svg-raw'
;;      | 'image';
;;  }
(defn parse-criteria
  [^js criteria]
  (when (some? criteria)
    (d/without-nils
     {:name (obj/get criteria "name")
      :name-like (obj/get criteria "nameLike")
      :type (-> (obj/get criteria "type") parse-shape-type)})))

;;export type ImageData = {
;;  name?: string;
;;  width: number;
;;  height: number;
;;  mtype?: string;
;;  id: string;
;;  keepAspectRatio?: boolean;
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
      :keep-aspect-ratio (obj/get image-data "keepAspectRatio")})))

;; export type Gradient = {
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

;; export interface Color {
;;   id?: string;
;;   fileId?: string;
;;   refId?: string; // deprecated
;;   refFile?: string; // deprecated
;;   name?: string;
;;   path?: string;
;;   color?: string;
;;   opacity?: number;
;;   gradient?: Gradient;
;;   image?: ImageData;
;; }
(defn parse-color-data
  [^js color]
  (when (some? color)
    (let [id (or (obj/get color "id") (obj/get color "refId"))
          file-id (or (obj/get color "fileId") (obj/get color "refFile"))]
      (d/without-nils
       {:id (parse-id id)
        :file-id (parse-id file-id)
        :color (-> (obj/get color "color") parse-hex)
        :opacity (obj/get color "opacity")
        :gradient (-> (obj/get color "gradient") parse-gradient)
        :image (-> (obj/get color "image") parse-image-data)}))))

(defn parse-color
  [^js color]
  (when (some? color)
    (d/without-nils
     (-> (parse-color-data color)
         (assoc :name (obj/get color "name")
                :path (obj/get color "path"))))))

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

;;export interface Fill {
;;  fillColor?: string;
;;  fillOpacity?: number;
;;  fillColorGradient?: Gradient;
;;  fillColorRefFile?: string;
;;  fillColorRefId?: string;
;;  fillImage?: ImageData;
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

;; export interface Blur {
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


;; export interface Export {
;;   type: 'png' | 'jpeg' | 'svg' | 'pdf';
;;   scale: number;
;;   suffix: string;
;; }
(defn parse-export
  [^js export]
  (when (some? export)
    (d/without-nils
     {:type (-> (obj/get export "type") parse-keyword)
      :scale (obj/get export "scale" 1)
      :suffix (obj/get export "suffix" "")})))

(defn parse-exports
  [^js exports]
  (when (some? exports)
    (into [] (map parse-export) exports)))

;; export interface GuideColumnParams {
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

;; export interface GuideColumn {
;;   type: 'column';
;;   display: boolean;
;;   params: GuideColumnParams;
;; }
(defn parse-frame-guide-column
  [^js guide]
  (when guide
    (d/without-nils
     {:type (-> (obj/get guide "type") parse-keyword)
      :display (obj/get guide "display")
      :params (-> (obj/get guide "params") parse-frame-guide-column-params)})))

;; export interface GuideRow {
;;   type: 'row';
;;   display: boolean;
;;   params: GuideColumnParams;
;; }

(defn parse-frame-guide-row
  [^js guide]
  (when guide
    (d/without-nils
     {:type (-> (obj/get guide "type") parse-keyword)
      :display (obj/get guide "display")
      :params (-> (obj/get guide "params") parse-frame-guide-column-params)})))

;;export interface GuideSquareParams {
;;  color: { color: string; opacity: number };
;;  size?: number;
;;}
(defn parse-frame-guide-square-params
  [^js params]
  (when (some? params)
    (d/without-nils
     {:color (-> (obj/get params "color") parse-color)
      :size (obj/get params "size")})))

;; export interface GuideSquare {
;;   type: 'square';
;;   display: boolean;
;;   params: GuideSquareParams;
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

(defn parse-animation
  [^js animation]
  (when animation
    (let [animation-type (-> (obj/get animation "type") parse-keyword)]
      (d/without-nils
       (case animation-type
         :dissolve
         {:animation-type animation-type
          :duration (obj/get animation "duration")
          :easing (-> (obj/get animation "easing") parse-keyword)}

         :slide
         {:animation-type animation-type
          :way (-> (obj/get animation "way") parse-keyword)
          :direction (-> (obj/get animation "direction") parse-keyword)
          :duration (obj/get animation "duration")
          :easing (-> (obj/get animation "easing") parse-keyword)
          :offset-effect (boolean (obj/get animation "offsetEffect"))}

         :push
         {:animation-type animation-type
          :direction (-> (obj/get animation "direction") parse-keyword)
          :duration (obj/get animation "duration")
          :easing (-> (obj/get animation "easing") parse-keyword)}

         nil)))))

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
(defn parse-action
  [action]
  (when action
    (let [action-type (-> (obj/get action "type") parse-keyword)]
      (d/without-nils
       (case action-type
         :navigate-to
         {:action-type :navigate
          :destination (-> (obj/get action "destination") (obj/get "$id"))
          :preserve-scroll (obj/get action "preserveScrollPosition")
          :animation (-> (obj/get action "animation") parse-animation)}

         (:open-overlay
          :toggle-overlay)
         {:action-type action-type
          :destination (-> (obj/get action "destination") (obj/get "$id"))
          :relative-to (-> (obj/get action "relativeTo") (obj/get "$id"))
          :overlay-pos-type (-> (obj/get action "position") parse-keyword)
          :overlay-position (-> (obj/get action "manualPositionLocation") parse-point)
          :close-click-outside (obj/get action "closeWhenClickOutside")
          :background-overlay (obj/get action "addBackgroundOverlay")
          :animation (-> (obj/get action "animation") parse-animation)}

         :close-overlay
         {:action-type action-type
          :destination (-> (obj/get action "destination") (obj/get "$id"))
          :animation (-> (obj/get action "animation") parse-animation)}

         :previous-screen
         {:action-type :prev-screen}

         :open-url
         {:action-type action-type
          :url (obj/get action "url")}

         nil)))))

(defn parse-interaction
  [trigger ^js action delay]
  (when (and (string? trigger) (some? action))
    (let [trigger (parse-keyword trigger)
          action  (parse-action action)]
      (d/without-nils
       (d/patch-object {:event-type trigger :delay delay}  action)))))

(defn orientation->axis
  [axis]
  (case axis
    "horizontal" :y
    "vertical"   :x))

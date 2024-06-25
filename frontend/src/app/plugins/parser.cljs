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
  (when color (-> color str/lower)))

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
  (when image-data
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
  (when stop
    (d/without-nils
     {:color (-> (obj/get stop "color") parse-hex)
      :opacity (obj/get stop "opacity")
      :offset (obj/get stop "offset")})))

(defn parse-gradient
  [^js gradient]
  (when gradient
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
  (when color
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

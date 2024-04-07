;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-gen.common
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.types.shape.layout :as ctl]
   [cuerdas.core :as str]))

(defn shape->selector
  [shape]
  (if shape
    (let [name (-> (:name shape)
                   (subs 0 (min 10 (count (:name shape))))
                   (str/replace #"[^a-zA-Z0-9\s\:]+" ""))
          ;; selectors cannot start with numbers
          name (if (re-matches #"^\d.*" name) (dm/str "c-" name) name)
          id (-> (dm/str (:id shape))
                 (subs 24 36))
          selector (str/css-selector (dm/str name " " id))
          selector (if (str/starts-with? selector "-") (subs selector 1) selector)]
      selector)
    ""))

(defn svg-markup?
  "Function to determine whether a shape is rendered in HTML+CSS or is rendered
  through a SVG"
  [shape]
  (or
   ;; path and path-like shapes
   (cfh/path-shape? shape)
   (cfh/bool-shape? shape)

   ;; imported SVG images
   (cfh/svg-raw-shape? shape)
   (some? (:svg-attrs shape))

   ;; CSS masks are not enough we need to delegate to SVG
   (cfh/mask-shape? shape)

   ;; Texts with shadows or strokes we render in SVG
   (and (cfh/text-shape? shape)
        (or (d/not-empty? (:shadow shape))
            (d/not-empty? (:strokes shape))))

   ;; When a shape has several fills
   (> (count (:fills shape)) 1)

   ;; When a shape has several strokes or the stroke is not a "border"
   (or (> (count (:strokes shape)) 1)
       (and (= (count (:strokes shape)) 1)
            (not= (-> shape :strokes first :stroke-alignment) :inner)))))

(defn has-wrapper?
  [objects shape]
  ;; Layout children with a transform should be wrapped
  (and (ctl/any-layout-immediate-child? objects shape)
       (not (ctl/position-absolute? shape))
       (not (gmt/unit? (:transform shape)))))


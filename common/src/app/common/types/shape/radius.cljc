;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.types.shape.radius
  (:require
   [app.common.types.shape.attrs :refer [editable-attrs]]))

;; There are some shapes that admit border radius, as rectangles
;; frames components and images. 
;; Those shapes may define the radius of the corners with four values:
;; One for each corner (top-left, top-right, bottom-right, bottom-left)
;; has an independent value. SVG does not allow this directly, so we
;; emulate it with paths.

;; All operations take into account that the shape may not be a one of those
;; shapes that has border radius, and so it hasn't :r1.
;; In this case operations must leave shape untouched.

(defn can-get-border-radius?
  [shape]
  (contains? #{:rect :frame} (:type shape)))

(defn has-radius?
  [shape]
  (contains? (get editable-attrs (:type shape)) :r1))

(defn all-equal?
  [shape]
  (= (:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape)))

(defn radius-mode
  [shape]
  (if (all-equal? shape)
    :radius-1
    :radius-4))

(defn set-radius-to-all-corners
  [shape value]
  ;; Only Apply changes to shapes that support Border Radius
  (cond-> shape
    (can-get-border-radius? shape)
    (assoc :r1 value :r2 value :r3 value :r4 value)))

(defn mirror-corner
  "The corner `attr` mirrored along the axes `flip-x?` and `flip-y?` name."
  [attr flip-x? flip-y?]
  (cond->> attr
    flip-x?
    (get {:r1 :r2 :r2 :r1 :r3 :r4 :r4 :r3})

    flip-y?
    (get {:r1 :r4 :r2 :r3 :r3 :r2 :r4 :r1})))

(defn shown-corner
  "The corner `attr` of `shape` as the shape shows it, mirrored by its flips;
   the same mirroring takes a shown corner to the one it stores."
  [shape attr]
  (mirror-corner attr (:flip-x shape) (:flip-y shape)))

(def ^:private corners #{:r1 :r2 :r3 :r4})

(defn mirror-token-corners
  "The `applied-tokens` of a shape with the corners its radius tokens name
   mirrored along the axes `flip-x?` and `flip-y?` name. A radius token
   names a corner as the shape shows it, so a flip that mirrors the shape
   moves the token with the corner it applies to."
  [applied-tokens flip-x? flip-y?]
  (if (and (or flip-x? flip-y?) (some corners (keys applied-tokens)))
    (into {}
          (map (fn [[attr token]]
                 [(cond-> attr (corners attr) (mirror-corner flip-x? flip-y?)) token]))
          applied-tokens)
    applied-tokens))

(defn tokens-in-orientation
  "The `applied-tokens` of a shape oriented as `from`, with the corners its
   radius tokens name as a shape oriented as `to` shows them."
  [applied-tokens from to]
  (mirror-token-corners applied-tokens
                        (not= (boolean (:flip-x from)) (boolean (:flip-x to)))
                        (not= (boolean (:flip-y from)) (boolean (:flip-y to)))))

(defn set-radius-to-single-corner
  [shape attr value]
  (let [attr (shown-corner shape attr)]
    ;; Only Apply changes to shapes that support border Radius
    (cond-> shape
      (can-get-border-radius? shape)
      (assoc attr value))))

(defn set-radius-for-corners
  "Set border radius to `value` for each radius `attr`."
  [shape attrs value]
  (reduce
   (fn [shape' attr]
     (set-radius-to-single-corner shape' attr value))
   shape attrs))

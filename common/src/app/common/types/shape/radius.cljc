;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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

(defn set-radius-to-single-corner
  [shape attr value]
  (let [attr (cond->> attr
               (:flip-x shape)
               (get {:r1 :r2 :r2 :r1 :r3 :r4 :r4 :r3})

               (:flip-y shape)
               (get {:r1 :r4 :r2 :r3 :r3 :r2 :r4 :r1}))]
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

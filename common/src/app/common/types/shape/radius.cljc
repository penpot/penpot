;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.radius
  (:require
   [app.common.types.shape.attrs :refer [editable-attrs]]))

;; There are some shapes that admit border radius, as rectangles
;; frames and images. Those shapes may define the radius of the corners in two modes:
;; - radius-1 all corners have the same radius (although we store two
;;   values :rx and :ry because svg uses it this way).
;; - radius-4 each corner (top-left, top-right, bottom-right, bottom-left)
;;   has an independent value. SVG does not allow this directly, so we
;;   emulate it with paths.

;; A shape never will have both :rx and :r1 simultaneously

;; All operations take into account that the shape may not be a one of those
;; shapes that has border radius, and so it hasn't :rx nor :r1.
;; In this case operations must leave shape untouched.

(defn has-radius-old?
  [shape]
  (contains? (get editable-attrs (:type shape)) :rx))

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

(defn radius-4?
  [shape]
  (and (:r1 shape)
       (or (not= (:r1 shape) 0)
           (not= (:r2 shape) 0)
           (not= (:r3 shape) 0)
           (not= (:r4 shape) 0))))

(defn switch-to-radius-1
  [shape]
  (let [r (if (all-equal? shape) (:r1 shape) 0)]
    (assoc shape :r1 r :r2 r :r3 r  :r4 r)))

(defn switch-to-radius-4
  [shape]
  (let [rx (:rx shape 0)]
    (-> (assoc shape :r1 rx :r2 rx :r3 rx :r4 rx)
        (dissoc :rx))))


(defn set-radius-1
  [shape value]
  (-> shape
      (assoc :r1 value :r2 value :r3 value :r4 value)))

(defn set-radius-4
  [shape attr value]
  (let [attr (cond->> attr
               (:flip-x shape)
               (get {:r1 :r2 :r2 :r1 :r3 :r4 :r4 :r3})

               (:flip-y shape)
               (get {:r1 :r4 :r2 :r3 :r3 :r2 :r4 :r1}))]

    (assoc shape attr value)))

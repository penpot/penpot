;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.ruler-guides
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.spec :as us]
   [app.main.data.workspace.guides :as dwgu]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.register :as r]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(def shape-proxy identity)
(def shape-proxy? identity)

(defn ruler-guide-proxy? [p]
  (obj/type-of? p "RulerGuideProxy"))

(defn ruler-guide-proxy
  [plugin-id file-id page-id id]
  (obj/reify {:name "RuleGuideProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file {:enumerable false :get (constantly file-id)}
    :$page {:enumerable false :get (constantly page-id)}
    :$id {:enumerable false :get (constantly id)}

    :board
    {:this true
     :enumerable false
     :get
     (fn [self]
       (let [board-id (-> self u/proxy->ruler-guide :frame-id)]
         (when board-id
           (shape-proxy plugin-id file-id page-id board-id))))

     :set
     (fn [self value]
       (let [shape (u/locate-shape file-id page-id (obj/get value "$id"))]
         (cond
           (not (shape-proxy? value))
           (u/display-not-valid :board "The board is not a shape proxy")

           (not (cfh/frame-shape? shape))
           (u/display-not-valid :board "The shape is not a board")

           (not (r/check-permission plugin-id "content:write"))
           (u/display-not-valid :board "Plugin doesn't have 'content:write' permission")

           :else
           (let [board-id (when value (obj/get value "$id"))
                 guide    (-> self u/proxy->ruler-guide)]
             (st/emit! (dwgu/update-guides (assoc guide :frame-id board-id)))))))}

    :orientation
    {:this true
     :get #(-> % u/proxy->ruler-guide :axis format/axis->orientation)}

    :position
    {:this true
     :get
     (fn [self]
       (let [guide (u/proxy->ruler-guide self)]
         (if (:frame-id guide)
           (let [objects   (u/locate-objects file-id page-id)
                 board-pos (dm/get-in objects [(:frame-id guide) (:axis guide)])
                 position  (:position guide)]
             (- position board-pos))

           ;; No frame
           (:position guide))))
     :set
     (fn [self value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :position "Not valid position")

         (not (r/check-permission plugin-id "content:write"))
         (u/display-not-valid :position "Plugin doesn't have 'content:write' permission")

         :else
         (let [guide (u/proxy->ruler-guide self)
               position
               (if (:frame-id guide)
                 (let [objects   (u/locate-objects file-id page-id)
                       board-pos (dm/get-in objects [(:frame-id guide) (:axis guide)])]
                   (+ board-pos value))

                 value)]
           (st/emit! (dwgu/update-guides (assoc guide :position position))))))}

    :remove
    (fn []
      (let [guide (u/locate-ruler-guide file-id page-id id)]
        (st/emit! (dwgu/remove-guide guide))))))

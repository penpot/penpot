;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.shapes
  (:require [goog.events :as events]
            [lentes.core :as l]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.view.store :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.ui.shapes.rect :refer (rect-shape)]
            [uxbox.main.ui.shapes.icon :refer (icon-shape)]
            [uxbox.main.ui.shapes.text :refer (text-shape)]
            [uxbox.main.ui.shapes.group :refer (group-shape)]
            [uxbox.main.ui.shapes.path :refer (path-shape)]
            [uxbox.main.ui.shapes.circle :refer (circle-shape)]
            [uxbox.main.ui.shapes.image :refer (image-shape)]
            [uxbox.builtins.icons :as i]
            [uxbox.view.ui.viewer.interactions :as itx])
  (:import goog.events.EventType))

(def itx-flag-ref
  (-> (comp (l/key :flags) (l/lens :interactions))
      (l/derive st/state)))

;; --- Interactions Wrapper

(defn- interactions-wrapper-did-mount
  [own]
  (let [dom (mx/dom-node own)
        shape (first (:rum/args own))
        evnts (itx/build-events shape)
        keys (reduce (fn [acc [evt callback]]
                       (conj acc (events/listen dom evt callback)))
                     []
                     evnts)]
    (assoc own ::keys keys)))

(defn- interactions-wrapper-will-unmount
  [own]
  (let [keys (::keys own)]
    (run! #(events/unlistenByKey %) keys)
    (dissoc own ::keys)))

(mx/defc interactions-wrapper
  {:did-mount interactions-wrapper-did-mount
   :will-unmount interactions-wrapper-will-unmount
   :mixins [mx/reactive mx/static]}
  [shape factory]
  {:pre [(map? shape)]}
  (let [show-itx? (and (mx/react itx-flag-ref)
                       (not (empty? (:interactions shape))))
        rect (geom/shape->rect-shape shape)]
    [:g {:id (str "itx-" (:id shape))
         :style (when show-itx?
                  {:cursor "pointer"})}
     (factory shape)
     (when show-itx?
       [:circle {:fill "#78dbbe"
                 :cx (:x1 rect)
                 :cy (:y1 rect)
                 :r 5}])]))

;; --- Shapes

(declare shape)

(mx/defc shape*
  [{:keys [type] :as item}]
  (case type
    :group (group-shape item shape)
    :image (image-shape item)
    :text (text-shape item)
    :icon (icon-shape item)
    :rect (rect-shape item)
    :path (path-shape item)
    :circle (circle-shape item)))

(mx/defc shape
  [sid]
  {:pre [(uuid? sid)]}
  (let [item (get-in @st/state [:shapes sid])]
    (interactions-wrapper item shape*)))

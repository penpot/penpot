;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.shapes
  (:require [goog.events :as events]
            [lentes.core :as l]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.state :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.ui.shapes.rect :refer (rect-shape)]
            [uxbox.main.ui.shapes.icon :refer (icon-shape)]
            [uxbox.main.ui.shapes.text :refer (text-shape)]
            [uxbox.main.ui.shapes.group :refer (group-shape)]
            [uxbox.main.ui.shapes.path :refer (path-shape)]
            [uxbox.main.ui.shapes.circle :refer (circle-shape)]
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
  (let [show-itx? (mx/react itx-flag-ref)
        rect (geom/inner-rect shape)]
    [:g {:id (str "itx-" (:id shape))}
     (factory shape)
     (when show-itx?
       [:circle {:fill "red"
                 :cx (:x rect)
                 :cy (:y rect)
                 :r 10}])]))

;; --- Shapes

(mx/defc shape*
  [{:keys [type] :as shape}]
  (case type
    :group (group-shape shape #(interactions-wrapper % shape*))
    :text (text-shape shape)
    :icon (icon-shape shape)
    :rect (rect-shape shape)
    :path (path-shape shape)
    :circle (circle-shape shape)))

(mx/defc shape
  [sid]
  (let [item (get-in @st/state [:shapes-by-id sid])]
    (interactions-wrapper item shape*)))


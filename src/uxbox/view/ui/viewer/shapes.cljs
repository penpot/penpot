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
            [uxbox.main.ui.icons :as i]
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
       [:circle {:fill "#78dbbe"
                 :cx (:x rect)
                 :cy (:y rect)
                 :r 10}]
       [:path {:fill "#fff",
               :d "M325.238 570.484c-.254-.07-.48-.196-.67-.377-.29-.274-.434-.578-.485-1.023-.017-.15-.06-.326-.094-.395-.036-.07-.87-1.335-1.855-2.812-.985-1.477-1.826-2.765-1.87-2.862-.213-.485-.312-1.09-.236-1.454.147-.71.793-1.236 1.514-1.236.38 0 .935.18 1.283.418.085.057.392.338.682.625.29.285.538.51.55.498.01-.01.02-1.437.02-3.167 0-2.684.008-3.17.05-3.327.143-.553.566-.978 1.117-1.12.262-.07 1.485-.07 1.746-.002.507.133.873.463 1.086.982.065.16.068.226.083 1.602l.016 1.436.414.02c.437.02.603.062.885.23.19.112.414.353.54.58l.107.19.462.02c.486.02.637.056.93.23.19.112.416.354.542.58l.107.19.46.02c.487.02.635.056.932.23.316.185.614.613.693.996.024.112.03.918.025 2.49l-.01 2.325-.086.276c-.048.152-.12.353-.162.447-.09.205-1.66 2.574-1.842 2.78-.174.198-.53.43-.842.547l-.256.097-2.84.006c-2.372.006-2.865 0-3.004-.04zm5.86-1.075c.097-.048.225-.134.284-.192.135-.132 1.613-2.342 1.715-2.566.163-.355.168-.438.17-2.747 0-2.076-.002-2.15-.064-2.27-.034-.068-.11-.157-.167-.198-.093-.066-.14-.074-.44-.074h-.334l-.016.847-.016.848-.086.113c-.223.293-.61.284-.837-.017-.063-.085-.066-.14-.082-1.293l-.015-1.204-.086-.113c-.135-.177-.263-.22-.607-.21l-.296.008-.015.852c-.015.796-.02.856-.082.94-.125.166-.227.218-.428.218-.202 0-.304-.052-.43-.22-.062-.084-.065-.138-.08-1.292l-.017-1.204-.085-.112c-.135-.177-.263-.222-.608-.21l-.295.007-.016.847-.016.848-.086.112c-.116.153-.284.23-.46.21-.148-.017-.333-.135-.402-.257-.032-.055-.044-.683-.057-2.797l-.016-2.727-.074-.105c-.04-.056-.132-.132-.202-.168-.116-.06-.174-.064-.772-.055-.646.01-.646.01-.76.096-.233.178-.216-.168-.216 4.49 0 4.66.017 4.313-.217 4.49-.092.07-.147.087-.293.087-.15 0-.2-.015-.3-.092-.168-.127-.21-.25-.21-.6v-.297l-.917-.913c-.55-.548-.968-.94-1.045-.977-.18-.09-.453-.166-.588-.165-.26 0-.51.26-.512.528 0 .17.104.576.192.75.038.073.87 1.335 1.85 2.804.978 1.468 1.808 2.726 1.843 2.794.092.182.163.448.194.725.03.276.103.404.284.5.113.06.194.062 2.828.055l2.71-.007.177-.086z"}])]))

;; --- Shapes

(declare shape)

(mx/defc shape*
  [{:keys [type] :as item}]
  (case type
    :group (group-shape item shape)
    :text (text-shape item)
    :icon (icon-shape item)
    :rect (rect-shape item)
    :path (path-shape item)
    :circle (circle-shape item)))

(mx/defc shape
  [sid]
  (let [item (get-in @st/state [:shapes sid])]
    (interactions-wrapper item shape*)))

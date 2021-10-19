;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.interactions
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; WARNING: options are not deleted when changing event or action type, so it can be
;;          restored if the user changes it back later.
;;
;;          But that means that an interaction may have for example a delay or
;;          destination, even if its type does not require it (but a previous type did).
;;
;;          So make sure to use has-delay/has-destination... functions, or similar,
;;          before reading them.

;; -- Options depending on event type

(s/def ::event-type #{:click
                      :mouse-press
                      :mouse-over
                      :mouse-enter
                      :mouse-leave
                      :after-delay})

(s/def ::delay ::us/safe-integer)

(defmulti event-opts-spec :event-type)

(defmethod event-opts-spec :after-delay [_]
  (s/keys :req-un [::delay]))

(defmethod event-opts-spec :default [_]
  (s/keys :req-un []))

(s/def ::event-opts
  (s/multi-spec event-opts-spec ::event-type))

;; -- Options depending on action type

(s/def ::action-type #{:navigate
                       :open-overlay
                       :toggle-overlay
                       :close-overlay
                       :prev-screen
                       :open-url})

(s/def ::destination (s/nilable ::us/uuid))
(s/def ::overlay-pos-type #{:manual
                            :center
                            :top-left
                            :top-right
                            :top-center
                            :bottom-left
                            :bottom-right
                            :bottom-center})
(s/def ::overlay-position ::us/point)
(s/def ::url ::us/string)
(s/def ::close-click-outside ::us/boolean)
(s/def ::background-overlay ::us/boolean)

(defmulti action-opts-spec :action-type)

(defmethod action-opts-spec :navigate [_]
  (s/keys :req-un [::destination]))

(defmethod action-opts-spec :open-overlay [_]
  (s/keys :req-un [::destination
                   ::overlay-position
                   ::overlay-pos-type]
          :opt-un [::close-click-outside
                   ::background-overlay]))

(defmethod action-opts-spec :toggle-overlay [_]
  (s/keys :req-un [::destination
                   ::overlay-position
                   ::overlay-pos-type]
          :opt-un [::close-click-outside
                   ::background-overlay]))

(defmethod action-opts-spec :close-overlay [_]
  (s/keys :req-un [::destination]))

(defmethod action-opts-spec :prev-screen [_]
  (s/keys :req-un []))

(defmethod action-opts-spec :open-url [_]
  (s/keys :req-un [::url]))

(s/def ::action-opts
  (s/multi-spec action-opts-spec ::action-type))

;; -- Interaction

(s/def ::classifier
  (s/keys :req-un [::event-type
                   ::action-type]))

(s/def ::interaction
  (s/merge ::classifier
           ::event-opts
           ::action-opts))

(s/def ::interactions
  (s/coll-of ::interaction :kind vector?))

(def default-interaction
  {:event-type :click
   :action-type :navigate
   :destination nil})

(def default-delay 600)

;; -- Helpers for interaction

(declare calc-overlay-pos-initial)

(defn set-event-type
  [interaction event-type shape]
  (us/verify ::interaction interaction)
  (us/verify ::event-type event-type)
  (assert (or (not= event-type :after-delay)
              (= (:type shape) :frame)))
  (if (= (:event-type interaction) event-type)
    interaction
    (case event-type

      :after-delay
      (assoc interaction
             :event-type event-type
             :delay (get interaction :delay default-delay))

      (assoc interaction
             :event-type event-type))))


(defn set-action-type
  [interaction action-type]
  (us/verify ::interaction interaction)
  (us/verify ::action-type action-type)
  (if (= (:action-type interaction) action-type)
    interaction
    (case action-type

      :navigate
      (assoc interaction
             :action-type action-type
             :destination (get interaction :destination))

      (:open-overlay :toggle-overlay)
      (let [overlay-pos-type (get interaction :overlay-pos-type :center)
            overlay-position (get interaction :overlay-position (gpt/point 0 0))]
        (assoc interaction
               :action-type action-type
               :overlay-pos-type overlay-pos-type
               :overlay-position overlay-position))

      :close-overlay
      (assoc interaction
             :action-type action-type
             :destination (get interaction :destination))

      :prev-screen
      (assoc interaction
             :action-type action-type)

      :open-url
      (assoc interaction
             :action-type action-type
             :url (get interaction :url "")))))

(defn has-delay
  [interaction]
  (= (:event-type interaction) :after-delay))

(defn set-delay
  [interaction delay]
  (us/verify ::interaction interaction)
  (us/verify ::delay delay)
  (assert (has-delay interaction))
  (assoc interaction :delay delay))

(defn has-destination
  [interaction]
  (#{:navigate :open-overlay :toggle-overlay :close-overlay}
    (:action-type interaction)))

(defn destination?
  [interaction]
  (and (has-destination interaction)
       (some? (:destination interaction))))

(defn set-destination
  [interaction destination]
  (us/verify ::interaction interaction)
  (us/verify ::destination destination)
  (assert (has-destination interaction))
  (cond-> interaction
    :always
    (assoc :destination destination)

    (or (= (:action-type interaction) :open-overlay)
        (= (:action-type interaction) :toggle-overlay))
    (assoc :overlay-pos-type :center
           :overlay-position (gpt/point 0 0))))

(defn has-url
  [interaction]
  (= (:action-type interaction) :open-url))

(defn set-url
  [interaction url]
  (us/verify ::interaction interaction)
  (us/verify ::url url)
  (assert (has-url interaction))
  (assoc interaction :url url))

(defn has-overlay-opts
  [interaction]
  (#{:open-overlay :toggle-overlay} (:action-type interaction)))

(defn set-overlay-pos-type
  [interaction overlay-pos-type shape objects]
  (us/verify ::interaction interaction)
  (us/verify ::overlay-pos-type overlay-pos-type)
  (assert (has-overlay-opts interaction))
  (assoc interaction
         :overlay-pos-type overlay-pos-type
         :overlay-position (calc-overlay-pos-initial (:destination interaction)
                                                     shape
                                                     objects
                                                     overlay-pos-type)))
(defn toggle-overlay-pos-type
  [interaction overlay-pos-type shape objects]
  (us/verify ::interaction interaction)
  (us/verify ::overlay-pos-type overlay-pos-type)
  (assert (has-overlay-opts interaction))
  (let [new-pos-type (if (= (:overlay-pos-type interaction) overlay-pos-type)
                       :manual
                       overlay-pos-type)]
    (assoc interaction
           :overlay-pos-type new-pos-type
           :overlay-position (calc-overlay-pos-initial (:destination interaction)
                                                       shape
                                                       objects
                                                       new-pos-type))))
(defn set-overlay-position
  [interaction overlay-position]
  (us/verify ::interaction interaction)
  (us/verify ::overlay-position overlay-position)
  (assert (has-overlay-opts interaction))
  (assoc interaction
         :overlay-pos-type :manual
         :overlay-position overlay-position))

(defn set-close-click-outside
  [interaction close-click-outside]
  (us/verify ::interaction interaction)
  (us/verify ::us/boolean close-click-outside)
  (assert (has-overlay-opts interaction))
  (assoc interaction :close-click-outside close-click-outside))

(defn set-background-overlay
  [interaction background-overlay]
  (us/verify ::interaction interaction)
  (us/verify ::us/boolean background-overlay)
  (assert (has-overlay-opts interaction))
  (assoc interaction :background-overlay background-overlay))

(defn- calc-overlay-pos-initial
  [destination shape objects overlay-pos-type]
  (if (= overlay-pos-type :manual)
    (let [dest-frame   (get objects destination)
          overlay-size (:selrect dest-frame)
          orig-frame   (if (= (:type shape) :frame)
                         shape
                         (get objects (:frame-id shape)))
          frame-size   (:selrect orig-frame)]
      (gpt/point (/ (- (:width frame-size) (:width overlay-size)) 2)
                 (/ (- (:height frame-size) (:height overlay-size)) 2)))
    (gpt/point 0 0)))

(defn calc-overlay-position
  [interaction base-frame dest-frame frame-offset]
  (us/verify ::interaction interaction)
  (assert (has-overlay-opts interaction))
  (if (nil? dest-frame)
    (gpt/point 0 0)
    (let [overlay-size    (:selrect dest-frame)
          base-frame-size (:selrect base-frame)]
      (case (:overlay-pos-type interaction)
        :center
        (gpt/point (/ (- (:width base-frame-size) (:width overlay-size)) 2)
                   (/ (- (:height base-frame-size) (:height overlay-size)) 2))

        :top-left
        (gpt/point 0 0)

        :top-right
        (gpt/point (- (:width base-frame-size) (:width overlay-size))
                   0)

        :top-center
        (gpt/point (/ (- (:width base-frame-size) (:width overlay-size)) 2)
                   0)

        :bottom-left
        (gpt/point 0
                   (- (:height base-frame-size) (:height overlay-size)))

        :bottom-right
        (gpt/point (- (:width base-frame-size) (:width overlay-size))
                   (- (:height base-frame-size) (:height overlay-size)))

        :bottom-center
        (gpt/point (/ (- (:width base-frame-size) (:width overlay-size)) 2)
                   (- (:height base-frame-size) (:height overlay-size)))

        :manual
        (gpt/add (:overlay-position interaction) frame-offset)))))

;; -- Helpers for interactions

(defn add-interaction
  [interactions interaction]
  (conj (or interactions []) interaction))

(defn remove-interaction
  [interactions index]
  (let [interactions (or interactions [])]
    (into (subvec interactions 0 index)
          (subvec interactions (inc index)))))

(defn update-interaction
  [interactions index update-fn]
  (update interactions index update-fn))

(defn remap-interactions
  "Update all interactions whose destination points to a shape in the
  map to the new id. And remove the ones whose destination does not exist
  in the map nor in the objects tree."
  [interactions ids-map objects]
  (when (some? interactions)
    (let [xform (comp (filter (fn [interaction]
                                (let [destination (:destination interaction)]
                                  (or (nil? destination)
                                      (contains? ids-map destination)
                                      (contains? objects destination)))))
                      (map (fn [interaction]
                             (d/update-when interaction :destination #(get ids-map % %)))))]
      (into [] xform interactions))))

(defn actionable?
  "Check if there is any interaction that is clickable by the user"
  [interactions]
  (some #(= (:event-type %) :click) interactions))

(defn flow-origin?
  "Check if there is any interaction that is the start or the continuation of a flow"
  [interactions]
  (some #(and (#{:navigate :open-overlay :toggle-overlay :close-overlay} (:action-type %))
              (some? (:destination %)))
        interactions))

(defn flow-to?
  "Check if there is any interaction that flows into the given frame"
  [interactions frame-id]
  (some #(and (#{:navigate :open-overlay :toggle-overlay :close-overlay} (:action-type %))
              (= (:destination %) frame-id))
        interactions))

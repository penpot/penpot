;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.interactions
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.pages.helpers :as cph]
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

(s/def ::event-type
  #{:click
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
  (s/multi-spec event-opts-spec :event-type))

;; -- Animation options

(s/def ::animation-type #{:dissolve :slide :push})
(s/def ::duration ::us/safe-integer)
(s/def ::way #{:in :out})
(s/def ::direction #{:right :left :up :down})
(s/def ::offset-effect ::us/boolean)
(s/def ::easing
  #{:linear
    :ease
    :ease-in
    :ease-out
    :ease-in-out})

(defmulti animation-spec :animation-type)

(defmethod animation-spec :dissolve [_]
  (s/keys :req-un [::duration
                   ::easing]))

(defmethod animation-spec :slide [_]
  (s/keys :req-un [::duration
                   ::easing
                   ::way
                   ::direction
                   ::offset-effect]))

(defmethod animation-spec :push [_]
  (s/keys :req-un [::duration
                   ::easing
                   ::direction]))

(s/def ::animation
  (s/multi-spec animation-spec :animation-type))

;; -- Options depending on action type

(s/def ::action-type
  #{:navigate
    :open-overlay
    :toggle-overlay
    :close-overlay
    :prev-screen
    :open-url})
(s/def ::position-relative-to (s/nilable ::us/uuid))
(s/def ::overlay-pos-type
  #{:manual
    :center
    :top-left
    :top-right
    :top-center
    :bottom-left
    :bottom-right
    :bottom-center})

(s/def ::destination (s/nilable ::us/uuid))
(s/def ::overlay-position ::gpt/point)
(s/def ::url ::us/string)
(s/def ::close-click-outside ::us/boolean)
(s/def ::background-overlay ::us/boolean)
(s/def ::preserve-scroll ::us/boolean)

(defmulti action-opts-spec :action-type)

(defmethod action-opts-spec :navigate [_]
  (s/keys :opt-un [::destination
                   ::preserve-scroll
                   ::animation]))

(defmethod action-opts-spec :open-overlay [_]
  (s/keys :req-un [::overlay-position
                   ::overlay-pos-type]
          :opt-un [::destination
                   ::close-click-outside
                   ::background-overlay
                   ::animation
                   ::position-relative-to]))

(defmethod action-opts-spec :toggle-overlay [_]
  (s/keys :req-un [::overlay-position
                   ::overlay-pos-type]
          :opt-un [::destination
                   ::close-click-outside
                   ::background-overlay
                   ::animation
                   ::position-relative-to]))

(defmethod action-opts-spec :close-overlay [_]
  (s/keys :opt-un [::destination
                   ::animation
                   ::position-relative-to]))

(defmethod action-opts-spec :prev-screen [_]
  (s/keys :req-un []))

(defmethod action-opts-spec :open-url [_]
  (s/keys :req-un [::url]))

(s/def ::action-opts
  (s/multi-spec action-opts-spec :action-type))

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
   :destination nil
   :position-relative-to nil
   :preserve-scroll false})

(def default-delay 600)

;; -- Helpers for interaction

(declare calc-overlay-pos-initial)
(declare allowed-animation?)

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
  (let [new-interaction
        (if (= (:action-type interaction) action-type)
          interaction
          (case action-type
            :navigate
            (assoc interaction
                   :action-type action-type
                   :destination (get interaction :destination)
                   :preserve-scroll (get interaction :preserve-scroll false))

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
                   :url (get interaction :url ""))))]

    (cond-> new-interaction
      (not (allowed-animation? action-type
                               (-> new-interaction :animation :animation-type)))
      (dissoc :animation-type :animation))))

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

(defn has-preserve-scroll
  [interaction]
  (= (:action-type interaction) :navigate))

(defn set-preserve-scroll
  [interaction preserve-scroll]
  (us/verify ::interaction interaction)
  (us/verify ::us/boolean preserve-scroll)
  (assert (has-preserve-scroll interaction))
  (assoc interaction :preserve-scroll preserve-scroll))

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

(defn set-position-relative-to
  [interaction position-relative-to]
  (us/verify ::interaction interaction)
  (us/verify ::position-relative-to position-relative-to)
  (assert (has-overlay-opts interaction))
  (assoc interaction :position-relative-to position-relative-to))

(defn- calc-overlay-pos-initial
  [destination shape objects overlay-pos-type]
  (if (and (= overlay-pos-type :manual) (some? destination))
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
  [interaction                        ;; interaction data
   shape                              ;; Shape with the interaction
   objects                            ;; the objects tree
   relative-to-shape                  ;; the interaction position is realtive to this sape
   base-frame                         ;; the base frame of the current interaction
   dest-frame                         ;; the frame to display with this interaction
   frame-offset]                      ;; if this interaction starts in a frame opened on another interaction, this is the position of that frame

  (us/verify ::interaction interaction)
  (assert (has-overlay-opts interaction))
  (let [
        ;; When the interactive item is inside a nested frame we need to add to the offset the position
        ;; of the parent-frame otherwise the position won't match
        shape-frame (cph/get-frame objects shape)

        frame-offset (if (or (not= :manual (:overlay-pos-type interaction))
                             (nil? shape-frame)
                             (cph/root-frame? shape-frame)
                             (cph/root? shape-frame))
                       frame-offset
                       (gpt/add frame-offset (gpt/point shape-frame)))
        ]
    (if (nil? dest-frame)
      (gpt/point 0 0)
      (let [overlay-size           (gsb/get-object-bounds objects dest-frame)
            base-frame-size        (:selrect base-frame)
            relative-to-shape-size (:selrect relative-to-shape)
            relative-to-adjusted-to-base-frame {:x (- (:x relative-to-shape-size) (:x base-frame-size))
                                                :y (- (:y relative-to-shape-size) (:y base-frame-size))}
            relative-to-is-auto?   (and (nil? (:position-relative-to interaction)) (not= :manual (:overlay-pos-type interaction)))
            base-position          (if relative-to-is-auto?
                                     {:x 0 :y 0}
                                     {:x (+ (:x frame-offset)
                                            (:x relative-to-adjusted-to-base-frame))
                                      :y (+ (:y frame-offset)
                                            (:y relative-to-adjusted-to-base-frame))})
            overlay-position       (:overlay-position interaction)
            overlay-position       (if (= (:type relative-to-shape) :frame)
                                     overlay-position
                                     {:x (- (:x overlay-position) (:x relative-to-adjusted-to-base-frame))
                                      :y (- (:y overlay-position) (:y relative-to-adjusted-to-base-frame))})]
        (case (:overlay-pos-type interaction)
          :center
          (gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                     (+ (:y base-position) (/ (- (:height relative-to-shape-size) (:height overlay-size)) 2)))

          :top-left
          (gpt/point (:x base-position) (:y base-position))

          :top-right
          (gpt/point (+ (:x base-position) (- (:width relative-to-shape-size) (:width overlay-size)))
                     (:y base-position))

          :top-center
          (gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                     (:y base-position))

          :bottom-left
          (gpt/point (:x base-position)
                     (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))

          :bottom-right
          (gpt/point (+ (:x base-position) (- (:width relative-to-shape-size) (:width overlay-size)))
                     (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))

          :bottom-center
          (gpt/point (+ (:x base-position) (/ (- (:width relative-to-shape-size) (:width overlay-size)) 2))
                     (+ (:y base-position) (- (:height relative-to-shape-size) (:height overlay-size))))

          :manual
          (gpt/point (+ (:x base-position) (:x overlay-position))
                     (+ (:y base-position) (:y overlay-position))))))))

(defn has-animation?
  [interaction]
  (#{:navigate :open-overlay :close-overlay :toggle-overlay} (:action-type interaction)))

(defn allow-push?
  [action-type]
  ; Push animation is not allowed for overlay actions
  (= :navigate action-type))

(defn allowed-animation?
  [action-type animation-type]
  ; Some specific combinations are forbidden, but may occur if the action type
  ; is changed from a type that allows the animation to another one that doesn't.
  ; Currently the only case is an overlay action with push animation.
  (or (not= animation-type :push)
      (allow-push? action-type)))

(defn set-animation-type
  [interaction animation-type]
  (us/verify ::interaction interaction)
  (us/verify (s/nilable ::animation-type) animation-type)
  (assert (has-animation? interaction))
  (assert (allowed-animation? (:action-type interaction) animation-type))

  (if (= (-> interaction :animation :animation-type) animation-type)
    interaction
    (if (nil? animation-type)
      (dissoc interaction :animation)
      (cond-> interaction
        :always
        (update :animation assoc :animation-type animation-type)

        (= animation-type :dissolve)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear))

        (= animation-type :slide)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear)
                :way (get-in interaction [:animation :way] :in)
                :direction (get-in interaction [:animation :direction] :right)
                :offset-effect (get-in interaction [:animation :offset-effect] false))

        (= animation-type :push)
        (update :animation assoc
                :duration (get-in interaction [:animation :duration] 300)
                :easing (get-in interaction [:animation :easing] :linear)
                :direction (get-in interaction [:animation :direction] :right))))))

(defn has-duration?
  [interaction]
  (#{:dissolve :slide :push} (-> interaction :animation :animation-type)))

(defn set-duration
  [interaction duration]
  (us/verify ::interaction interaction)
  (us/verify ::duration duration)
  (assert (has-duration? interaction))
  (update interaction :animation assoc :duration duration))

(defn has-easing?
  [interaction]
  (#{:dissolve :slide :push} (-> interaction :animation :animation-type)))

(defn set-easing
  [interaction easing]
  (us/verify ::interaction interaction)
  (us/verify ::easing easing)
  (assert (has-easing? interaction))
  (update interaction :animation assoc :easing easing))

(defn has-way?
  [interaction]
  ; Way is ignored in slide animations of overlay actions
  (and (= (:action-type interaction) :navigate)
       (= (-> interaction :animation :animation-type) :slide)))

(defn set-way
  [interaction way]
  (us/verify ::interaction interaction)
  (us/verify ::way way)
  (assert (has-way? interaction))
  (update interaction :animation assoc :way way))

(defn has-direction?
  [interaction]
  (#{:slide :push} (-> interaction :animation :animation-type)))

(defn set-direction
  [interaction direction]
  (us/verify ::interaction interaction)
  (us/verify ::direction direction)
  (assert (has-direction? interaction))
  (update interaction :animation assoc :direction direction))

(defn invert-direction
  [animation]
  (us/verify (s/nilable ::animation) animation)
  (case (:direction animation)
    :right
    (assoc animation :direction :left)
    :left
    (assoc animation :direction :right)
    :up
    (assoc animation :direction :down)
    :down
    (assoc animation :direction :up)
    animation))

(defn has-offset-effect?
  [interaction]
  ; Offset-effect is ignored in slide animations of overlay actions
  (and (= (:action-type interaction) :navigate)
       (= (-> interaction :animation :animation-type) :slide)))

(defn set-offset-effect
  [interaction offset-effect]
  (us/verify ::interaction interaction)
  (us/verify ::offset-effect offset-effect)
  (assert (has-offset-effect? interaction))
  (update interaction :animation assoc :offset-effect offset-effect))

(defn dest-to?
  "Check if the interaction has the given frame as destination."
  [interaction frame-id]
  (and (has-destination interaction)
       (= (:destination interaction) frame-id)))

(defn navs-to?
  "Check if the interaction is a navigation to the given frame."
  [interaction frame-id]
  (and (= (:action-type interaction) :navigate)
       (= (:destination interaction) frame-id)))

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

(defn remove-interactions
  "Remove all interactions that the fn returns true."
  [f interactions]
  (-> (d/removev f interactions)
      not-empty))

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

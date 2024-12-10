;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.gradients
  "Gradients handlers and renders"
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.points :as gsp]
   [app.common.math :as mth]
   [app.main.data.workspace.colors :as dc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.viewport-ref :as uwvv]
   [app.util.dom :as dom]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def gradient-line-stroke-width 2)
(def gradient-line-stroke-color "var(--app-white)")
(def gradient-square-width 20.5)
(def gradient-square-radius 4)
(def gradient-square-stroke-width 2)
(def gradient-width-handler-radius 4)
(def gradient-width-handler-radius-selected 6)
(def gradient-width-handler-radius-handler 15)
(def gradient-width-handler-color "var(--app-white)")
(def gradient-square-stroke-color "var(--app-white)")
(def gradient-square-stroke-color-selected "var(--color-accent-tertiary)")

(def gradient-endpoint-radius 4)
(def gradient-endpoint-radius-selected 6)
(def gradient-endpoint-radius-handler 20)

(mf/defc shadow [{:keys [id offset]}]
  [:filter {:id id
            :x "-10%"
            :y "-10%"
            :width "120%"
            :height "120%"
            :filterUnits "objectBoundingBox"
            :color-interpolation-filters "sRGB"}
   [:feFlood {:flood-opacity "0" :result "BackgroundImageFix"}]
   [:feColorMatrix {:in "SourceAlpha" :type "matrix" :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
   [:feOffset {:dy offset}]
   [:feGaussianBlur {:stdDeviation "1"}]
   [:feColorMatrix {:type "matrix" :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0.15 0"}]
   [:feBlend {:mode "normal" :in2 "BackgroundImageFix" :result id}]
   [:feBlend {:mode "normal" :in "SourceGraphic" :in2 id :result "shape"}]])

(def checkerboard "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA8AAAAPCAIAAAC0tAIdAAACvUlEQVQoFQGyAk39AeLi4gAAAAAAAB0dHQAAAAAAAOPj4wAAAAAAAB0dHQAAAAAAAOPj4wAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB////AAAAAAAA4+PjAAAAAAAAHR0dAAAAAAAA4+PjAAAAAAAAHR0dAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATj4+MAAAAAAAAdHR0AAAAAAADj4+MAAAAAAAAdHR0AAAAAAADj4+MAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjScaa0cU7nIAAAAASUVORK5CYII=")

(mf/defc gradient-color-handler
  [{:keys [zoom point color angle selected index
           on-click on-pointer-down on-pointer-up on-pointer-move on-lost-pointer-capture]}]
  [:g {:filter "url(#gradient-drop-shadow)"
       :style {:cursor "pointer"}
       :transform (gmt/rotate-matrix angle point)}

   [:image {:href checkerboard
            :x (+ (- (:x point) (/ gradient-square-width 2 zoom)) (/ 12 zoom))
            :y (- (:y point) (/ gradient-square-width 2 zoom))
            :width (/ gradient-square-width zoom)
            :height (/ gradient-square-width zoom)}]

   [:rect {:x (+ (- (:x point) (/ gradient-square-width 2 zoom)) (/ 12 zoom))
           :y (- (:y point) (/ gradient-square-width 2 zoom))
           :rx (/ gradient-square-radius zoom)
           :width (/ gradient-square-width zoom 2)
           :height (/ gradient-square-width zoom)
           :fill (:value color)
           :on-click (partial on-click :to-p)
           :on-pointer-down (partial on-pointer-down :to-p)
           :on-pointer-up (partial on-pointer-up :to-p)}]

   (when selected
     [:rect {:pointer-events "none"
             :x (- (+ (- (:x point) (/ gradient-square-width 2 zoom)) (/ 12 zoom)) (/ 2 zoom))
             :y (- (- (:y point) (/ gradient-square-width 2 zoom)) (/ 2 zoom))
             :rx (/ (+ gradient-square-radius (/ 2 zoom)) zoom)
             :width (+ (/ gradient-square-width zoom) (/ 4 zoom))
             :height (+ (/ gradient-square-width zoom) (/ 4 zoom))
             :stroke "var(--color-accent-tertiary)"
             :stroke-width (/ gradient-square-stroke-width zoom)
             :fill "transparent"}])

   [:rect {:data-allow-click-modal "colorpicker"
           :data-index index
           :pointer-events "all"
           :x (+ (- (:x point) (/ gradient-square-width 2 zoom)) (/ 12 zoom))
           :y (- (:y point) (/ gradient-square-width 2 zoom))
           :rx (/ gradient-square-radius zoom)
           :width (/ gradient-square-width zoom)
           :height (/ gradient-square-width zoom)
           :stroke "var(--app-white)"
           :stroke-width (/ gradient-square-stroke-width zoom)
           :fill (:value color)
           :fill-opacity (:opacity color)
           :on-click on-click
           :on-pointer-down on-pointer-down
           :on-pointer-up on-pointer-up
           :on-pointer-move on-pointer-move
           :on-lost-pointer-capture on-lost-pointer-capture}]

   [:circle {:cx (:x point)
             :cy (:y point)
             :r (/ 2 zoom)
             :fill "var(--app-white)"}]])

(mf/defc gradient-handler-transformed
  [{:keys [from-p
           to-p
           width-p
           zoom
           editing
           stops
           on-change-start on-change-finish on-change-width]}]
  (let [moving-point (mf/use-var nil)
        angle        (+ 90 (gpt/angle from-p to-p))
        dragging-ref (mf/use-ref false)
        start-offset (mf/use-ref nil)

        handler-state (mf/use-state {:display? false :offset 0 :hover nil})

        endpoint-on-pointer-down
        (fn [position event]
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (dom/capture-pointer event)
          (reset! moving-point position)
          (when (#{:from-p :to-p} position)
            (st/emit! (dc/select-colorpicker-gradient-stop
                       (case position
                         :from-p 0
                         :to-p 1)))))

        endpoint-on-pointer-up
        (fn [_position event]
          (dom/release-pointer event)
          (dom/stop-propagation event)
          (dom/prevent-default event)
          (reset! moving-point nil)
          (swap! handler-state assoc :hover nil))

        endpoint-on-pointer-enter
        (mf/use-fn
         (fn [position]
           (swap! handler-state assoc :hover position)))

        endpoint-on-pointer-leave
        (mf/use-fn
         (fn [_]
           (swap! handler-state assoc :hover nil)))

        points-on-pointer-enter
        (mf/use-fn
         (fn []
           (swap! handler-state assoc :display? true)))

        points-on-pointer-leave
        (mf/use-fn
         (fn []
           (swap! handler-state assoc :display? false)))

        points-on-pointer-down
        (mf/use-fn
         (mf/deps stops)
         (fn [e]
           (dom/prevent-default e)
           (dom/stop-propagation e)

           (let [raw-pt (dom/get-client-position e)
                 position (uwvv/point->viewport raw-pt)
                 lv (-> (gpt/to-vec from-p to-p) (gpt/unit))
                 nv (gpt/normal-left lv)
                 offset (-> (gsp/project-t position [from-p to-p] nv)
                            (mth/precision 2))
                 new-stop (cc/interpolate-gradient stops offset)
                 stops (conj stops new-stop)
                 stops (->> stops (sort-by :offset) (into []))]
             (st/emit! (dc/update-colorpicker-stops stops)))))

        points-on-pointer-move
        (mf/use-fn
         (mf/deps from-p to-p)
         (fn [e]
           (let [raw-pt (dom/get-client-position e)
                 position (uwvv/point->viewport raw-pt)
                 lv (-> (gpt/to-vec from-p to-p) (gpt/unit))
                 nv (gpt/normal-left lv)
                 offset (gsp/project-t position [from-p to-p] nv)]
             (swap! handler-state assoc :offset offset))))

        handle-marker-pointer-down
        (mf/use-fn
         (mf/deps stops)
         (fn [event]

           (let [index (-> event dom/get-current-target (dom/get-data "index") d/read-string)
                 stop (get stops index)]
             (dom/capture-pointer event)
             (st/emit! (dc/select-colorpicker-gradient-stop index))
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-offset (:offset stop)))))

        handle-marker-pointer-move
        (mf/use-fn
         (mf/deps stops)
         (fn [event]
           (when-let [_ (mf/ref-val dragging-ref)]
             (let [index (-> event dom/get-target (dom/get-data "index") d/read-string)

                   raw-pt (dom/get-client-position event)
                   position (uwvv/point->viewport raw-pt)
                   lv (-> (gpt/to-vec from-p to-p) (gpt/unit))
                   nv (gpt/normal-left lv)
                   offset (gsp/project-t position [from-p to-p] nv)
                   offset (mth/precision (mth/clamp offset 0 1) 2)]

               (st/emit! (dc/update-colorpicker-stops (assoc-in stops [index :offset] offset)))))))

        handle-marker-lost-pointer-capture
        (mf/use-fn
         (mf/deps stops)
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-offset nil)
           (let [stops (->> stops
                            (sort-by :offset)
                            (into []))]
             (st/emit! (dc/update-colorpicker-stops stops)))))]

    (mf/use-effect
     (mf/deps @moving-point from-p to-p width-p)
     (fn []
       (let [subs (->> st/stream
                       (rx/filter mse/pointer-event?)
                       (rx/filter #(= :viewport (mse/get-pointer-source %)))
                       (rx/map mse/get-pointer-position)
                       (rx/subs!
                        (fn [pt]
                          (case @moving-point
                            :from-p  (when on-change-start (on-change-start pt))
                            :to-p    (when on-change-finish (on-change-finish pt))
                            :width-p (when on-change-width
                                       (let [width-v (gpt/unit (gpt/to-vec from-p width-p))
                                             distance (gpt/point-line-distance pt from-p to-p)
                                             new-width-p (gpt/add
                                                          from-p
                                                          (gpt/multiply width-v (gpt/point distance)))]
                                         (on-change-width new-width-p)))
                            nil))))]
         (fn [] (rx/dispose! subs)))))

    [:g.gradient-handlers {:pointer-events "none"}
     [:defs
      [:& shadow {:id "gradient-drop-shadow" :offset (/ 2 zoom)}]]

     (let [lv (-> (gpt/to-vec from-p to-p)
                  (gpt/unit))
           nv (gpt/normal-left lv)
           width (/ 40 zoom)
           points [(gpt/add from-p (gpt/scale nv (/ width -2)))
                   (gpt/add from-p (gpt/scale nv (/ width 2)))
                   (gpt/add to-p   (gpt/scale nv (/ width 2)))
                   (gpt/add to-p   (gpt/scale nv (/ width -2)))]
           points-str
           (->> points
                (map #(dm/str (:x %) "," (:y %)))
                (str/join ", "))]

       [:polygon {:points points-str
                  :data-allow-click-modal "colorpicker"
                  :fill "transparent"
                  :pointer-events "all"
                  :on-pointer-enter points-on-pointer-enter
                  :on-pointer-leave points-on-pointer-leave
                  :on-pointer-down points-on-pointer-down
                  :on-pointer-move points-on-pointer-move}])

     [:g {:filter "url(#gradient-drop-shadow)"}
      (let [pu
            (-> (gpt/to-vec from-p to-p)
                (gpt/normal-right)
                (gpt/unit))

            sc (/ gradient-line-stroke-width zoom 2)

            points
            [(gpt/add from-p (gpt/scale pu (- sc)))
             (gpt/add from-p (gpt/scale pu sc))
             (gpt/add to-p (gpt/scale pu sc))
             (gpt/add to-p (gpt/scale pu (- sc)))]]
        ;; Use the polygon shape instead of lines because horizontal/vertical lines won't work
        ;; with shadows
        [:polygon
         {:points
          (->> points
               (map #(dm/fmt "%, %" (:x %) (:y %)))
               (str/join " "))

          :fill gradient-line-stroke-color}])]

     (when width-p
       [:g {:filter "url(#gradient-drop-shadow)"}
        (let [pu
              (-> (gpt/to-vec from-p width-p)
                  (gpt/normal-right)
                  (gpt/unit))

              sc (/ gradient-line-stroke-width zoom 2)

              points
              [(gpt/add from-p (gpt/scale pu (- sc)))
               (gpt/add from-p (gpt/scale pu sc))
               (gpt/add width-p (gpt/scale pu sc))
               (gpt/add width-p (gpt/scale pu (- sc)))]]
          ;; Use the polygon shape instead of lines because horizontal/vertical lines won't work
          ;; with shadows
          [:polygon {:points
                     (->> points
                          (map #(dm/fmt "%, %" (:x %) (:y %)))
                          (str/join " "))

                     :fill gradient-line-stroke-color}])])

     (when width-p
       [:g {:filter "url(#gradient-drop-shadow)"}
        (when (= :width-p (:hover @handler-state))
          [:circle {:filter "url(#gradient-drop-shadow)"
                    :cx (:x width-p)
                    :cy (:y width-p)
                    :fill gradient-square-stroke-color-selected
                    :r (/ gradient-width-handler-radius-selected zoom)}])

        [:circle {:data-allow-click-modal "colorpicker"
                  :cx (:x width-p)
                  :cy (:y width-p)
                  :r (/ gradient-width-handler-radius zoom)
                  :fill gradient-width-handler-color}]

        [:circle {:data-allow-click-modal "colorpicker"
                  :pointer-events "all"
                  :cx (:x width-p)
                  :cy (:y width-p)
                  :r (/ gradient-width-handler-radius-handler zoom)
                  :fill "transpgarent"
                  :on-pointer-down (partial endpoint-on-pointer-down :width-p)
                  :on-pointer-enter (partial endpoint-on-pointer-enter :width-p)
                  :on-pointer-leave (partial endpoint-on-pointer-leave :width-p)
                  :on-pointer-up (partial endpoint-on-pointer-up :width-p)}]])

     [:g
      (when (= :from-p (:hover @handler-state))
        [:circle {:filter "url(#gradient-drop-shadow)"
                  :cx (:x from-p)
                  :cy (:y from-p)
                  :fill gradient-square-stroke-color-selected
                  :r (/ gradient-endpoint-radius-selected zoom)}])

      [:circle {:filter "url(#gradient-drop-shadow)"
                :cx (:x from-p)
                :cy (:y from-p)
                :fill "var(--app-white)"
                :r (/ gradient-endpoint-radius zoom)}]

      [:circle {:data-allow-click-modal "colorpicker"
                :pointer-events "all"
                :cx (:x from-p)
                :cy (:y from-p)
                :fill "transparent"
                :r (/ gradient-endpoint-radius-handler zoom)
                :on-pointer-down (partial endpoint-on-pointer-down :from-p)
                :on-pointer-up (partial endpoint-on-pointer-up :from-p)
                :on-pointer-enter (partial endpoint-on-pointer-enter :from-p)
                :on-pointer-leave (partial endpoint-on-pointer-leave :from-p)
                :on-lost-pointer-capture (partial endpoint-on-pointer-up :from-p)}]]

     [:g
      (when (= :to-p (:hover @handler-state))
        [:circle {:filter "url(#gradient-drop-shadow)"
                  :cx (:x to-p)
                  :cy (:y to-p)
                  :fill gradient-square-stroke-color-selected
                  :r (/ gradient-endpoint-radius-selected zoom)}])

      [:circle {:filter "url(#gradient-drop-shadow)"
                :cx (:x to-p)
                :cy (:y to-p)
                :fill "var(--app-white)"
                :r (/ gradient-endpoint-radius zoom)}]

      [:circle {:data-allow-click-modal "colorpicker"
                :pointer-events "all"
                :cx (:x to-p)
                :cy (:y to-p)
                :fill "transparent"
                :r (/ gradient-endpoint-radius-handler zoom)
                :on-pointer-down (partial endpoint-on-pointer-down :to-p)
                :on-pointer-up (partial endpoint-on-pointer-up :to-p)
                :on-pointer-enter (partial endpoint-on-pointer-enter :to-p)
                :on-pointer-leave (partial endpoint-on-pointer-leave :to-p)
                :on-lost-pointer-capture (partial endpoint-on-pointer-up :from-p)}]]

     (for [[index stop] (d/enumerate stops)]
       (let [stop-p
             (gpt/add
              from-p
              (-> (gpt/to-vec from-p to-p)
                  (gpt/scale (:offset stop))))]

         [:& gradient-color-handler
          {:key index
           :selected (= editing index)
           :zoom zoom
           :point stop-p
           :color {:value (:color stop) :opacity (:opacity stop)}
           :angle angle
           :index index
           :on-pointer-down handle-marker-pointer-down
           :on-pointer-move handle-marker-pointer-move
           :on-lost-pointer-capture handle-marker-lost-pointer-capture}]))

     (when (:display? @handler-state)
       (let [p (gpt/add from-p
                        (-> (gpt/to-vec from-p to-p)
                            (gpt/scale (:offset @handler-state))))]
         [:circle {:filter "url(#gradient-drop-shadow)"
                   :cx (:x p)
                   :cy (:y p)
                   :r (/ 4 zoom)
                   :fill "var(--app-white)"}]))]))

(mf/defc gradient-handlers-impl*
  {::mf/props :obj}
  [{:keys [zoom stops gradient editing shape]}]
  (let [transform         (gsh/transform-matrix shape)
        transform-inverse (gsh/inverse-transform-matrix shape)

        {:keys [x y width height] :as sr} (:selrect shape)

        from-p (-> (gpt/point (+ x (* width (:start-x gradient)))
                              (+ y (* height (:start-y gradient))))
                   (gpt/transform transform))
        to-p   (-> (gpt/point (+ x (* width (:end-x gradient)))
                              (+ y (* height (:end-y gradient))))
                   (gpt/transform transform))

        gradient-vec    (gpt/to-vec from-p to-p)
        gradient-length (gpt/length gradient-vec)

        width-v (-> gradient-vec
                    (gpt/normal-right)
                    (gpt/multiply (gpt/point (* (:width gradient) (/ gradient-length (/ height 2)))))
                    (gpt/multiply (gpt/point (/ width 2))))

        width-p (gpt/add from-p width-v)

        change!
        (mf/use-fn
         (fn [changes]
           (st/emit! (dc/update-colorpicker-gradient changes))))

        on-change-start
        (mf/use-fn
         (mf/deps transform-inverse width height)
         (fn [point]
           (let [point (gpt/transform point transform-inverse)
                 start-x (/ (- (:x point) x) width)
                 start-y (/ (- (:y point) y) height)]
             (change! {:start-x start-x :start-y start-y}))))

        on-change-finish
        (mf/use-fn
         (mf/deps transform-inverse width height)
         (fn [point]
           (let [point (gpt/transform point transform-inverse)
                 end-x (/ (- (:x point) x) width)
                 end-y (/ (- (:y point) y) height)]
             (change! {:end-x end-x :end-y end-y}))))

        on-change-width
        (mf/use-fn
         (mf/deps gradient-length width height)
         (fn [point]
           (let [scale-factor-y (/ gradient-length (/ height 2))
                 norm-dist (/ (gpt/distance point from-p)
                              (* (/ width 2) scale-factor-y))]
             (when (and norm-dist (d/num? norm-dist))
               (change! {:width norm-dist})))))]

    [:& gradient-handler-transformed
     {:editing editing
      :from-p from-p
      :to-p to-p
      :width-p (when (= :radial (:type gradient)) width-p)
      :stops stops
      :zoom zoom
      :on-change-start on-change-start
      :on-change-finish on-change-finish
      :on-change-width on-change-width}]))

(mf/defc gradient-handlers*
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [id zoom]}]
  (let [shape-ref    (mf/use-memo (mf/deps id) #(refs/object-by-id id))
        shape        (mf/deref shape-ref)
        state        (mf/deref refs/colorpicker)
        gradient     (:gradient state)
        stops        (:stops state)
        editing-stop (:editing-stop state)]

    (when (and (some? gradient) (= id (:shape-id gradient)))
      [:> gradient-handlers-impl*
       {:zoom zoom
        :gradient gradient
        :stops stops
        :editing editing-stop
        :shape shape}])))

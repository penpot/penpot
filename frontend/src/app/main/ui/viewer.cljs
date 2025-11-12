;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.text :as txt]
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.modal :refer [modal-container*]]
   [app.main.ui.viewer.comments :refer [comments-layer comments-sidebar*]]
   [app.main.ui.viewer.header :as header]
   [app.main.ui.viewer.inspect :as inspect]
   [app.main.ui.viewer.interactions :as interactions]
   [app.main.ui.viewer.login]
   [app.main.ui.viewer.share-link]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.util.dom :as dom]
   [app.util.dom.normalize-wheel :as nw]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def current-animations-ref
  (l/derived :viewer-animations st/state))

(def current-overlays-ref
  (l/derived :viewer-overlays st/state))

(defn- calculate-size
  "Calculate the total size we must reserve for the frame, including possible paddings
   added because shadows or blur."
  [objects frame zoom]
  (let [{:keys [x y width height]} (gsb/get-object-bounds objects frame)]
    {:base-width  width
     :base-height height
     :x           x
     :y           y
     :width       (* width zoom)
     :height      (* height zoom)
     :vbox        (dm/fmt "% % % %" 0 0 width height)}))

(defn calculate-delta
  "Calculate the displacement we need to apply so that the original selrect appears in the
   same position as if it had no extra paddings, depending on the side the frame will
   be snapped to."
  [size selrect [snap-v snap-h] zoom]
  (let [delta-x (case snap-h
                  :left   (- (:x1 selrect) (:x size))
                  :right  (- (:x2 selrect) (+ (:x size) (/ (:width size) zoom)))
                  :center (- (/ (- (:width selrect) (/ (:width size) zoom)) 2)
                             (- (:x size) (:x1 selrect))))
        delta-y (case snap-v
                  :top    (- (:y1 selrect) (:y size))
                  :bottom (- (:y2 selrect) (+ (:y size) (/ (:height size) zoom)))
                  :center (- (/ (- (:height selrect) (/ (:height size) zoom)) 2)
                             (- (:y size) (:y1 selrect))))]
    (gpt/point (* delta-x zoom) (* delta-y zoom))))

(defn- calculate-wrapper
  [size1 size2 zoom]
  (cond
    (nil? size1) size2
    (nil? size2) size1
    :else (let [width  (max (:base-width size1) (:base-width size2))
                height (max (:base-height size1) (:base-height size2))]
            {:width  (* width zoom)
             :height (* height zoom)
             :vbox   (str "0 0 " width " " height)})))

(mf/defc viewer-pagination
  [{:keys [index num-frames left-bar right-bar comment-sidebar] :as props}]
  (let [go-prev-frame  (mf/use-fn #(st/emit! dv/select-prev-frame))
        go-next-frame  (mf/use-fn #(st/emit! dv/select-next-frame))
        go-first-frame (mf/use-fn #(st/emit! dv/select-first-frame))]
    [:*
     (when (pos? index)
       [:button {:class (stl/css-case :viewer-go-prev true
                                      :left-bar left-bar)
                 :on-click go-prev-frame
                 :aria-label (tr "labels.previous")}
        deprecated-icon/arrow])
     (when (< (+ index 1) num-frames)
       [:button {:class (stl/css-case :viewer-go-next  true
                                      :comment-sidebar comment-sidebar
                                      :right-bar right-bar)
                 :on-click go-next-frame
                 :aria-label (tr "labels.next")}
        deprecated-icon/arrow])
     [:div {:class (stl/css-case :viewer-bottom true
                                 :left-bar left-bar)}
      [:button {:on-click go-first-frame
                :class (stl/css :reset-button)}
       deprecated-icon/reload]
      [:span {:class (stl/css :counter)}
       (str/join " / " [(+ index 1) num-frames])]
      [:span]]]))

(mf/defc viewer-pagination-and-sidebar
  {::mf/wrap [mf/memo]}
  [{:keys [section index users frame page]}]
  (let [comments-local  (mf/deref refs/comments-local)
        show-sidebar?   (and (= section :comments) (:show-sidebar? comments-local))]
    [:*
     [:& viewer-pagination
      {:index index
       :num-frames (count (:frames page))
       :comment-sidebar show-sidebar?}]

     (when show-sidebar?
       [:> comments-sidebar*
        {:profiles users
         :frame frame
         :page page}])]))

(mf/defc viewer-overlay
  [{:keys [overlay page frame zoom wrapper-size interactions-mode]}]
  (let [close-click-outside? (:close-click-outside overlay)
        background-overlay?  (:background-overlay overlay)
        overlay-frame        (:frame overlay)
        overlay-position     (:position overlay)
        fixed-base?          (:fixed-source? overlay)

        size
        (mf/with-memo [page overlay zoom]
          (calculate-size (:objects page) (:frame overlay) zoom))

        delta
        (mf/with-memo [size overlay-frame overlay zoom]
          (calculate-delta size (:selrect overlay-frame) (:snap-to overlay) zoom))

        on-click
        (mf/use-fn
         (mf/deps overlay close-click-outside?)
         (fn [_]
           (when close-click-outside?
             (if-let [animation (:animation overlay)]
               (st/emit! (dv/close-overlay (:id overlay) (ctsi/invert-direction animation)))
               (st/emit! (dv/close-overlay (:id overlay)))))))]

    [:*
     (when (or close-click-outside? background-overlay?)
       [:div {:class (stl/css-case  :viewer-overlay-background true
                                    :visible background-overlay?)
              :style {:width (:width wrapper-size)
                      :height (:height wrapper-size)
                      :position "absolute"
                      :left 0
                      :top 0}
              :on-click on-click}])

     (if fixed-base?
       [:div {:class (stl/css :viewport-container-wrapper)
              :style {:position "absolute"
                      :left (* (:x overlay-position) zoom)
                      :top (* (:y overlay-position) zoom)
                      :width (:width size)
                      :height (:height size)
                      :z-index 2}}
        [:div {:class (stl/css :viewer-overlay :viewport-container)
               :id (dm/str "overlay-" (:id overlay-frame))
               :style {:width (:width size)
                       :height (:height size)
                       :position "fixed"}}
         [:& interactions/viewport
          {:frame overlay-frame
           :base-frame frame
           :frame-offset overlay-position
           :size size
           :delta delta
           :page page
           :interactions-mode interactions-mode}]]]

       [:div {:class (stl/css :viewer-overlay :viewport-container)
              :id (dm/str "overlay-" (:id overlay-frame))
              :style {:width (:width size)
                      :height (:height size)
                      :left (* (:x overlay-position) zoom)
                      :top (* (:y overlay-position) zoom)}}
        [:& interactions/viewport
         {:frame overlay-frame
          :base-frame frame
          :frame-offset overlay-position
          :size size
          :delta delta
          :page page
          :interactions-mode interactions-mode}]])]))

(mf/defc viewer-wrapper
  {::mf/wrap-props false}
  [{:keys [wrapper-size orig-frame orig-viewport-ref orig-size page file users current-viewport-ref
           size frame interactions-mode overlays zoom section index]}]

  [:*
   [:& viewer-pagination-and-sidebar
    {:section section
     :index index
     :page page
     :users users
     :frame frame
     :interactions-mode interactions-mode}]

   [:div {:class (stl/css :viewer-wrapper)
          :style {:width (:width wrapper-size)
                  :height (:height wrapper-size)}}
    [:div {:class (stl/css :viewer-clipper)}

     (when orig-frame
       [:div {:class (stl/css :viewport-container)
              :ref orig-viewport-ref
              :style {:width (:width orig-size)
                      :height (:height orig-size)
                      :position "relative"}}

        [:& interactions/viewport
         {:frame orig-frame
          :base-frame orig-frame
          :frame-offset (gpt/point 0 0)
          :size orig-size
          :page page
          :users users
          :interactions-mode interactions-mode}]])

     [:div {:class (stl/css :viewport-container)
            :ref current-viewport-ref
            :style {:width (:width size)
                    :height (:height size)
                    :position "relative"}}

      [:& interactions/viewport
       {:frame frame
        :base-frame frame
        :frame-offset (gpt/point 0 0)
        :size size
        :page page
        :interactions-mode interactions-mode}]

      (for [overlay overlays]
        [:& viewer-overlay
         {:overlay overlay
          :key (dm/str (:id overlay))
          :page page
          :frame frame
          :zoom zoom
          :wrapper-size wrapper-size
          :interactions-mode interactions-mode}])]]


    (when (= section :comments)
      [:& comments-layer {:file file
                          :users users
                          :frame frame
                          :page page
                          :zoom zoom}])]])

(mf/defc viewer-content*
  {::mf/props :obj}
  [{:keys [data page-id share-id section index interactions-mode share]}]
  (let [{:keys [file users project permissions]} data
        allowed (or
                 (= section :interactions)
                 (and (= section :comments)
                      (or (:can-edit permissions)
                          (and (true? (:is-logged permissions))
                               (= (:who-comment permissions) "all"))))
                 (and (= section :inspect)
                      (or (:can-edit permissions)
                          (and (true? (:is-logged permissions))
                               (= (:who-inspect permissions) "all")))))

        local (mf/deref refs/viewer-local)

        nav-scroll (:nav-scroll local)
        orig-viewport-ref    (mf/use-ref nil)
        current-viewport-ref (mf/use-ref nil)
        viewer-section-ref   (mf/use-ref nil)

        current-animations (mf/deref current-animations-ref)

        page-id (or page-id (-> file :data :pages first))

        page (mf/use-memo
              (mf/deps data page-id)
              (fn []
                (get-in data [:pages page-id])))

        text-shapes
        (hooks/use-equal-memo
         (->> (:objects page)
              (vals)
              (filter cfh/text-shape?)))

        zoom      (:zoom local)
        zoom-type (:zoom-type local)

        frames    (:frames page)
        frame     (get frames index)

        fullscreen? (mf/deref header/fullscreen-ref)
        overlays    (mf/deref current-overlays-ref)

        orig-frame
        (mf/with-memo [current-animations]
          ;; We assume there can only be one animation with origin (this is used only in
          ;; navigation animations, and we cannot navigate to two different destinations
          ;; at the same time).
          (let [animation-with-origin (d/seek :orig-frame-id (vals current-animations))]
            (when animation-with-origin
              (d/seek #(= (:id %) (:orig-frame-id animation-with-origin)) frames))))

        size
        (mf/with-memo [frame zoom]
          (when frame
            (calculate-size (:objects page) frame zoom)))

        orig-size
        (mf/with-memo [orig-frame zoom]
          (when orig-frame
            (calculate-size (:objects page) orig-frame zoom)))

        wrapper-size
        (mf/with-memo [size orig-size zoom]
          (calculate-wrapper size orig-size zoom))

        click-on-screen
        (mf/use-fn
         (fn [event]
           (let [origin (dom/get-target event)
                 over-section? (dom/get-data origin "viewer-section")
                 layout (dom/get-element "viewer-layout")
                 has-force? (dom/get-data layout "force-visible")]

             (when over-section?
               (if (= has-force? "true")
                 (dom/set-data! layout "force-visible" false)
                 (dom/set-data! layout "force-visible" true))))))

        on-click
        (mf/use-fn
         (mf/deps section)
         (fn [_]
           (when (= section :comments)
             (st/emit! (dcm/close-thread)))))

        set-up-new-size
        (mf/use-fn
         (fn [_]
           (let [viewer-section (dom/get-element "viewer-section")
                 size (dom/get-client-size viewer-section)]
             (st/emit! (dv/set-viewport-size {:size size})))))

        on-wheel
        (mf/use-fn
         (fn [event]
           (let [event  (.getBrowserEvent ^js event)
                 wrapper (dom/get-element "inspect-svg-wrapper")
                 section (dom/get-element "inspect-svg-container")
                 target (.-target event)]
             (when (or (dom/child? target wrapper) (dom/id? target "inspect-svg-container"))
               (let [norm-event ^js (nw/normalize-wheel event)
                     mod? (kbd/mod? event)
                     shift? (kbd/shift? event)
                     delta (.-pixelY norm-event)
                     scroll-pos (if shift?
                                  (dom/get-h-scroll-pos section)
                                  (dom/get-scroll-pos section))
                     new-scroll-pos (+ scroll-pos delta)]
                 (when-not mod?
                   (do
                     (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (if shift?
                       (dom/set-h-scroll-pos! section new-scroll-pos)
                       (dom/set-scroll-pos! section new-scroll-pos)))))))))
        on-thumbnails-close
        (mf/use-fn
         #(st/emit! dv/close-thumbnails-panel))


        on-exit-fullscreen
        (mf/use-fn
         (fn []
           (when (not (dom/fullscreen?))
             (st/emit! (dv/exit-fullscreen)))))]

    (hooks/use-shortcuts ::viewer sc/shortcuts)
    (when (nil? page)
      (ex/raise :type :not-found))

    (mf/with-effect []
      (when (not allowed)
        (st/emit! (dv/go-to-section :interactions))))

    ;; Set the page title
    (mf/use-effect
     (mf/deps (:name file))
     (fn []
       (let [name (:name file)]
         (dom/set-html-title (str "\u25b6 " (tr "title.viewer" name))))))

    (mf/with-effect []
      (let [events
            [(events/listen globals/window "click" on-click)
             (events/listen (mf/ref-val viewer-section-ref) "wheel" on-wheel #js {"passive" false})]]

        (doseq [event dom/fullscreen-events]
          (.addEventListener globals/document event on-exit-fullscreen false))

        (fn []
          (doseq [key events]
            (events/unlistenByKey key))

          (doseq [event dom/fullscreen-events]
            (.removeEventListener globals/document event on-exit-fullscreen)))))

    (mf/use-effect
     (fn []
       (set-up-new-size)
       (.addEventListener js/window "resize" set-up-new-size)
       (fn []
         (.removeEventListener js/window "resize" set-up-new-size))))

    (mf/use-effect
     (mf/deps nav-scroll)
     (fn []
        ;; Set scroll position after navigate
       (when (number? nav-scroll)
         (let [viewer-section (dom/get-element "viewer-section")]
           (st/emit! (dv/reset-nav-scroll))
           (dom/set-scroll-pos! viewer-section nav-scroll)))))

    (mf/use-layout-effect
     (mf/deps fullscreen?)
     (fn []
       ;; Trigger dom fullscreen depending on our state
       (let [wrapper         (dom/get-element "viewer-layout")
             fullscreen-dom? (dom/fullscreen?)]
         (when (not= fullscreen? fullscreen-dom?)
           (if fullscreen?
             (let [layout (dom/get-element "viewer-layout")]
               (dom/set-data! layout "force-visible" false)
               (wapi/request-fullscreen wrapper))
             (wapi/exit-fullscreen))))))

    (mf/use-effect
     (mf/deps zoom-type)
     (fn []
       (case zoom-type
         :fit (st/emit! dv/zoom-to-fit)
         :fill (st/emit! dv/zoom-to-fill)
         nil)))

    (mf/use-effect
     (mf/deps index current-animations zoom-type)
     (fn []
       (case zoom-type
         :fit  (st/emit! dv/zoom-to-fit)
         :fill (st/emit! dv/zoom-to-fill)
         nil)
        ;; Navigate animation needs to be started after navigation
        ;; is complete, and we have the next page index.
       (let [nav-animation (d/seek #(= (:kind %) :go-to-frame) (vals current-animations))]
         (when nav-animation
           (let [orig-viewport    (mf/ref-val orig-viewport-ref)
                 current-viewport (mf/ref-val current-viewport-ref)]
             (interactions/animate-go-to-frame
              (:animation nav-animation)
              current-viewport
              orig-viewport
              size
              orig-size
              wrapper-size))))))

    (mf/use-effect
     (mf/deps current-animations)
     (fn []
        ;; Overlay animations may be started when needed.
       (when current-animations
         (doseq [[overlay-frame-id animation-vals] current-animations]
           (let [overlay-viewport (dom/get-element (str "overlay-" (str (:overlay-id animation-vals))))
                 overlay          (d/seek #(= (:id (:frame %)) overlay-frame-id)
                                          overlays)
                 overlay-size     (calculate-size (:objects page) (:frame overlay) zoom)
                 overlay-position {:x (* (:x (:position overlay)) zoom)
                                   :y (* (:y (:position overlay)) zoom)}
                 orig-frame       (when (:orig-frame-id animation-vals)
                                    (d/seek #(= (:id %) (:orig-frame-id animation-vals)) frames))
                 size             (calculate-size (:objects page) frame zoom)
                 orig-size        (when orig-frame
                                    (calculate-size (:objects page) orig-frame zoom))
                 wrapper-size     (calculate-wrapper size orig-size zoom)]
             (case (:kind animation-vals)
               :open-overlay
               (interactions/animate-open-overlay
                (:animation animation-vals)
                overlay-viewport
                wrapper-size
                overlay-size
                overlay-position)

               :close-overlay
               (interactions/animate-close-overlay
                (:animation animation-vals)
                overlay-viewport
                wrapper-size
                overlay-size
                overlay-position
                (:id (:frame overlay)))

               nil))))))

    (mf/use-effect
     (mf/deps text-shapes)
     (fn []
       (let [text-nodes (->> text-shapes (mapcat #(txt/node-seq txt/is-text-node? (:content %))))
             fonts (into #{} (keep :font-id) text-nodes)]
         (run! fonts/ensure-loaded! fonts))))

    [:div#viewer-layout
     {:class (stl/css-case
              :force-visible (:show-thumbnails local)
              :viewer-layout (not= section :inspect)
              :inspect-layout (= section :inspect))
      :data-fullscreen fullscreen?
      :data-force-visible (:show-thumbnails local)}


     [:div {:class (stl/css :viewer-content)}


      [:button {:on-click on-thumbnails-close
                :class (stl/css-case :thumbnails-close true
                                     :invisible (not (:show-thumbnails local false)))}]

      [:& thumbnails-panel {:frames frames
                            :show? (:show-thumbnails local false)
                            :page page
                            :index index
                            :thumbnail-data (:thumbnails file)}]

      [:section#viewer-section {:ref viewer-section-ref
                                :data-viewer-section true
                                :class (stl/css-case :viewer-section true
                                                     :fullscreen fullscreen?)
                                :on-click click-on-screen}
       (cond
         (empty? frames)
         [:section {:class (stl/css :empty-state)}
          [:span (tr "viewer.empty-state")]]

         (nil? frame)
         [:section {:class (stl/css :empty-state)}
          (when (some? index)
            [:span (tr "viewer.frame-not-found")])]

         (some? frame)
         (if (= :inspect section)
           [:& inspect/viewport
            {:frame frame
             :page page
             :file file
             :section section
             :local local
             :size size
             :index index
             :viewer-pagination viewer-pagination
             :interactions-mode interactions-mode
             :share-id share-id}]

           [:& (mf/provider ctx/current-zoom) {:value zoom}
            [:& viewer-wrapper
             {:wrapper-size wrapper-size
              :orig-frame orig-frame
              :orig-viewport-ref orig-viewport-ref
              :orig-size orig-size
              :page page
              :file file
              :users users
              :current-viewport-ref current-viewport-ref
              :size size
              :frame frame
              :interactions-mode interactions-mode
              :overlays overlays
              :zoom zoom
              :section section
              :index index}]]))]]

     [:& header/header {:project project
                        :index index
                        :file file
                        :page page
                        :frame frame
                        :permissions permissions
                        :zoom zoom
                        :section section
                        :shown-thumbnails (:show-thumbnails local)
                        :interactions-mode interactions-mode
                        :share share}]]))

;; --- Component: Viewer

(mf/defc viewer*
  {::mf/props :obj}
  [{:keys [file-id share-id page-id] :as props}]
  (mf/with-effect [file-id page-id share-id]
    (let [params {:file-id file-id
                  :page-id page-id
                  :share-id share-id}]
      (st/emit! (dv/initialize params))
      (fn []
        (st/emit! (dv/finalize params)))))

  (if-let [data (mf/deref refs/viewer-data)]
    (let [props (obj/merge props #js {:data data :key (dm/str file-id)})]
      [:*
       [:> modal-container*]
       [:> viewer-content* props]])

    [:> loader*  {:title (tr "labels.loading")
                  :overlay true}]))


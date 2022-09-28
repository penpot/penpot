;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.common.types.shape.interactions :as ctsi]
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.static :as static]
   [app.main.ui.viewer.comments :refer [comments-layer comments-sidebar]]
   [app.main.ui.viewer.handoff :as handoff]
   [app.main.ui.viewer.header :as header]
   [app.main.ui.viewer.interactions :as interactions]
   [app.main.ui.viewer.login]
   [app.main.ui.viewer.share-link]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.util.dom :as dom]
   [app.util.dom.normalize-wheel :as nw]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
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
  [objects frame zoom]
  (let [{:keys [x y width height]} (gsb/get-object-bounds objects frame)]
    {:base-width  width
     :base-height height
     :x           x
     :y           y
     :width       (* width zoom)
     :height      (* height zoom)
     :vbox        (dm/fmt "% % % %" 0 0 width height)}))

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
  [{:keys [index num-frames left-bar right-bar] :as props}]
  [:*
   (when (pos? index)
     [:div.viewer-go-prev {:class (when left-bar "left-bar")}
      [:div.arrow {:on-click #(st/emit! dv/select-prev-frame)} i/go-prev]])
   (when (< (+ index 1) num-frames)
     [:div.viewer-go-next {:class (when right-bar "right-bar")}
      [:div.arrow {:on-click #(st/emit! dv/select-next-frame)} i/go-next]])
   [:div.viewer-bottom {:class (when left-bar "left-bar")}
    [:div.reset {:on-click #(st/emit! dv/select-first-frame)} i/reset]
    [:div.counter (str/join " / " [(+ index 1) num-frames])]
    [:span]]])

(mf/defc viewer-pagination-and-sidebar
  {::mf/wrap [mf/memo]}
  [{:keys [section index users frame page]}]
  (let [comments-local  (mf/deref refs/comments-local)
        show-sidebar?   (and (= section :comments) (:show-sidebar? comments-local))]
    [:*
     [:& viewer-pagination
      {:index index
       :num-frames (count (:frames page))
       :right-bar show-sidebar?}]

     (when show-sidebar?
       [:& comments-sidebar
        {:users users
         :frame frame
         :page page}])]))

(mf/defc viewer-overlay
  [{:keys [overlay page frame zoom wrapper-size interactions-mode]}]
  (let [close-click-outside? (:close-click-outside overlay)
        background-overlay?  (:background-overlay overlay)
        overlay-frame        (:frame overlay)
        overlay-position     (:position overlay)

        size
        (mf/with-memo [page overlay zoom]
          (calculate-size (:objects page) (:frame overlay) zoom))

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
       [:div.viewer-overlay-background
        {:class (dom/classnames :visible background-overlay?)
         :style {:width (:width wrapper-size)
                 :height (:height wrapper-size)
                 :position "absolute"
                 :left 0
                 :top 0}
         :on-click on-click}])

     [:div.viewport-container.viewer-overlay
      {:id (dm/str "overlay-" (:id overlay-frame))
       :style {:width (:width size)
               :height (:height size)
               :left (* (:x overlay-position) zoom)
               :top (* (:y overlay-position) zoom)}}

      [:& interactions/viewport
       {:frame overlay-frame
        :base-frame frame
        :frame-offset overlay-position
        :size size
        :page page
        :interactions-mode interactions-mode}]]]))


(mf/defc viewer-wrapper
  [{:keys [wrapper-size orig-frame orig-viewport-ref orig-size page file users current-viewport-ref
           size frame interactions-mode overlays zoom section index] :as props}]
  [:*
   [:& viewer-pagination-and-sidebar
    {:section section
     :index index
     :page page
     :users users
     :frame frame}]

   [:div.viewer-wrapper
    {:style {:width (:width wrapper-size)
             :height (:height wrapper-size)}}
    [:div.viewer-clipper
     (when orig-frame
       [:div.viewport-container
        {:ref orig-viewport-ref
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
          :interactions-mode :hide}]])

     [:div.viewport-container
      {:ref current-viewport-ref
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
        [:& viewer-overlay {:overlay overlay
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

(mf/defc viewer
  [{:keys [params data]}]

  (let [{:keys [page-id section index]} params
        {:keys [file users project permissions]} data

        allowed (or
                 (= section :interactions)
                 (and (= section :comments)
                      (or (:can-edit permissions)
                          (and (true? (:is-logged permissions))
                               (= (:who-comment permissions) "all"))))
                 (and (= section :handoff)
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
              (filter cph/text-shape?)))

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
          (calculate-size (:objects page) frame zoom))

        orig-size
        (mf/with-memo [orig-frame zoom]
          (when orig-frame
            (calculate-size (:objects page) orig-frame zoom)))

        wrapper-size
        (mf/with-memo [size orig-size zoom]
          (calculate-wrapper size orig-size zoom))

        interactions-mode
        (:interactions-mode local)

        click-on-screen
        (mf/use-callback
         (fn [event]
           (let [origin (dom/get-target event)
                 over-section? (dom/class? origin "viewer-section")
                 layout (dom/get-element "viewer-layout")
                 has-force? (dom/class? layout "force-visible")]

             (when over-section?
               (if has-force?
                 (dom/remove-class! layout "force-visible")
                 (dom/add-class! layout "force-visible"))))))

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
                 norm-event ^js (nw/normalize-wheel event)
                 mod? (kbd/mod? event)
                 shift? (kbd/shift? event)
                 delta (.-pixelY norm-event)
                 viewer-section (mf/ref-val viewer-section-ref)
                 scroll-pos (if shift?
                              (dom/get-h-scroll-pos viewer-section)
                              (dom/get-scroll-pos viewer-section))
                 new-scroll-pos (+ scroll-pos delta)]
             (when-not mod?
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if shift?
                   (dom/set-h-scroll-pos! viewer-section new-scroll-pos)
                   (dom/set-scroll-pos! viewer-section new-scroll-pos)))))))]

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
      (dom/set-html-theme-color clr/gray-50 "dark")
      (let [key1 (events/listen js/window "click" on-click)
            key2 (events/listen (mf/ref-val viewer-section-ref) "wheel" on-wheel #js {"passive" false})]
        (fn []
          (events/unlistenByKey key1)
          (events/unlistenByKey key2))))

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
             (wapi/request-fullscreen wrapper)
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
     {:class (dom/classnames
              :force-visible (:show-thumbnails local)
              :viewer-layout (not= section :handoff)
              :handoff-layout (= section :handoff)
              :fullscreen fullscreen?)}

     [:div.viewer-content
      [:& header/header {:project project
                         :index index
                         :file file
                         :page page
                         :frame frame
                         :permissions permissions
                         :zoom zoom
                         :section section}]
      [:div.thumbnail-close {:on-click #(st/emit! dv/close-thumbnails-panel)
                             :class (dom/classnames :invisible (not (:show-thumbnails local false)))}]
      [:& thumbnails-panel {:frames frames
                            :show? (:show-thumbnails local false)
                            :page page
                            :index index
                            :thumbnail-data (:thumbnails file)}]
      [:section.viewer-section {:id "viewer-section"
                                :ref viewer-section-ref
                                :class (if fullscreen? "fullscreen" "")
                                :on-click click-on-screen}
       (cond
         (empty? frames)
         [:section.empty-state
          [:span (tr "viewer.empty-state")]]

         (nil? frame)
         [:section.empty-state
          (when (some? index)
            [:span (tr "viewer.frame-not-found")])]

         (some? frame)
         (if (= :handoff section)
           [:& handoff/viewport
            {:frame frame
             :page page
             :file file
             :section section
             :local local
             :size size
             :index index
             :viewer-pagination viewer-pagination}]


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
              :index index}]]))]]]))

;; --- Component: Viewer Page

(mf/defc viewer-page
  [{:keys [file-id] :as props}]
  (mf/use-effect
   (mf/deps file-id)
   (fn []
     (st/emit! (dv/initialize props))
     (fn []
       (st/emit! (dv/finalize props)))))

  (when-let [data (mf/deref refs/viewer-data)]
    (let [key (str (get-in data [:file :id]))]
      [:& viewer {:params props :data data :key key}])))

(mf/defc breaking-change-notice
  []
  [:> static/static-header {}
   [:div.image i/unchain]
   [:div.main-message (tr "viewer.breaking-change.message")]
   [:div.desc-message (tr "viewer.breaking-change.description")]])

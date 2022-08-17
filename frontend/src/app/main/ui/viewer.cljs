;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

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
   [app.main.ui.viewer.header :refer [header]]
   [app.main.ui.viewer.interactions :as interactions]
   [app.main.ui.viewer.login]
   [app.main.ui.viewer.share-link]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.alpha :as mf]))

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

(mf/defc viewer-wrapper
  [{:keys [wrapper-size scroll orig-frame orig-viewport-ref orig-size page file users current-viewport-ref
           size frame interactions-mode overlays zoom close-overlay section index] :as props}]
  (let [{clist :list} (mf/deref refs/comments-local)
        show-comments-list (and (= section :comments) (= :show clist))]
    [:*
     [:& viewer-pagination {:index index :num-frames (count (:frames page)) :right-bar show-comments-list}]

     (when show-comments-list
       [:& comments-sidebar {:users users :frame frame :page page}])

     [:div.viewer-wrapper
      {:style {:width (:width wrapper-size)
               :height (:height wrapper-size)}}
      [:& (mf/provider ctx/scroll-ctx) {:value @scroll}
       [:div.viewer-clipper
        [:*
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
              :file file
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
            :file file
            :users users
            :interactions-mode interactions-mode}]

          (for [overlay overlays]
            (let [size-over (calculate-size (:objects page) (:frame overlay) zoom)]
              [:*
               (when (or (:close-click-outside overlay)
                         (:background-overlay  overlay))
                 [:div.viewer-overlay-background
                  {:class (dom/classnames
                           :visible (:background-overlay overlay))
                   :style {:width (:width wrapper-size)
                           :height (:height wrapper-size)
                           :position "absolute"
                           :left 0
                           :top 0}
                   :on-click #(when (:close-click-outside overlay)
                                (close-overlay (:frame overlay)))}])
               [:div.viewport-container.viewer-overlay

                {:id (str "overlay-" (-> overlay :frame :id))
                 :style {:width (:width size-over)
                         :height (:height size-over)
                         :left (* (:x (:position overlay)) zoom)
                         :top (* (:y (:position overlay)) zoom)}}
                [:& interactions/viewport
                 {:frame (:frame overlay)
                  :base-frame frame
                  :frame-offset (:position overlay)
                  :size size-over
                  :page page
                  :file file
                  :users users
                  :interactions-mode interactions-mode}]]]))]]

        (when (= section :comments)
          [:& comments-layer {:file file
                              :users users
                              :frame frame
                              :page page
                              :zoom zoom}])]]]]))

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
        orig-viewport-ref (mf/use-ref nil)
        current-viewport-ref (mf/use-ref nil)
        viewer-section-ref (mf/use-ref nil)
        current-animation (:current-animation local)

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

        fullscreen? (mf/deref refs/viewer-fullscreen?)
        overlays (:overlays local)
        scroll (mf/use-state nil)

        orig-frame
        (when (:orig-frame-id current-animation)
          (d/seek #(= (:id %) (:orig-frame-id current-animation)) frames))

        size (mf/use-memo
              (mf/deps frame zoom)
              (fn [] (calculate-size (:objects page) frame zoom)))

        orig-size (mf/use-memo
                   (mf/deps orig-frame zoom)
                   (fn [] (when orig-frame
                            (calculate-size (:objects page) orig-frame zoom))))

        wrapper-size (mf/use-memo
                      (mf/deps size orig-size zoom)
                      (fn [] (calculate-wrapper size orig-size zoom)))

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
        (mf/use-callback
         (mf/deps section)
         (fn [_]
           (when (= section :comments)
             (st/emit! (dcm/close-thread)))))

        set-up-new-size
        (mf/use-callback
         (fn [_]
           (let [viewer-section (dom/get-element "viewer-section")
                 size (dom/get-client-size viewer-section)]
             (st/emit! (dv/set-viewport-size {:size size})))))

        on-scroll
        (fn [event]
          (reset! scroll (dom/get-target-scroll event)))]

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

    (mf/use-effect
     (fn []
       (dom/set-html-theme-color clr/gray-50 "dark")
       (let [key1 (events/listen js/window "click" on-click)
             key2 (events/listen (mf/ref-val viewer-section-ref) "scroll" on-scroll)]
         (fn []
           (events/unlistenByKey key1)
           (events/unlistenByKey key2)))))

    (mf/use-layout-effect
     (fn []
       (set-up-new-size)
       (.addEventListener js/window "resize" set-up-new-size)
       (fn []
         (.removeEventListener js/window "resize" set-up-new-size))))

    (mf/use-layout-effect
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

    (mf/use-layout-effect
     (mf/deps page)
     (fn []
       (case zoom-type
         :fit (st/emit! dv/zoom-to-fit)
         :fill (st/emit! dv/zoom-to-fill)
         nil)))

    (mf/use-layout-effect
     (mf/deps index)
     (fn []
       (case zoom-type
         :fit (st/emit! dv/zoom-to-fit)
         :fill (st/emit! dv/zoom-to-fill)
         nil)
        ;; Navigate animation needs to be started after navigation
        ;; is complete, and we have the next page index.
       (when (and current-animation
                  (= (:kind current-animation) :go-to-frame))
         (let [orig-viewport    (mf/ref-val orig-viewport-ref)
               current-viewport (mf/ref-val current-viewport-ref)]
           (interactions/animate-go-to-frame
            (:animation current-animation)
            current-viewport
            orig-viewport
            size
            orig-size
            wrapper-size)))))

    (mf/use-layout-effect
     (mf/deps current-animation)
     (fn []
        ;; Overlay animations may be started when needed.
       (when current-animation
         (case (:kind current-animation)

           :open-overlay
           (let [overlay-viewport (dom/get-element (str "overlay-" (str (:overlay-id current-animation))))
                 overlay (d/seek #(= (:id (:frame %)) (:overlay-id current-animation))
                                 overlays)
                 overlay-size (calculate-size (:objects page) (:frame overlay) zoom)
                 overlay-position {:x (* (:x (:position overlay)) zoom)
                                   :y (* (:y (:position overlay)) zoom)}]
             (interactions/animate-open-overlay
              (:animation current-animation)
              overlay-viewport
              wrapper-size
              overlay-size
              overlay-position))

           :close-overlay
           (let [overlay-viewport (dom/get-element (str "overlay-" (str (:overlay-id current-animation))))
                 overlay (d/seek #(= (:id (:frame %)) (:overlay-id current-animation))
                                 overlays)
                 overlay-size (calculate-size (:objects page) (:frame overlay) zoom)
                 overlay-position {:x (* (:x (:position overlay)) zoom)
                                   :y (* (:y (:position overlay)) zoom)}]
             (interactions/animate-close-overlay
              (:animation current-animation)
              overlay-viewport
              wrapper-size
              overlay-size
              overlay-position
              (:id (:frame overlay))))

           nil))))

    (mf/use-effect
     (mf/deps text-shapes)
     (fn []
       (let [text-nodes (->> text-shapes (mapcat #(txt/node-seq txt/is-text-node? (:content %))))
             fonts (into #{} (keep :font-id) text-nodes)]
         (run! fonts/ensure-loaded! fonts))))

    [:div#viewer-layout {:class (dom/classnames
                                 :force-visible (:show-thumbnails local)
                                 :viewer-layout (not= section :handoff)
                                 :handoff-layout (= section :handoff)
                                 :fullscreen fullscreen?)}

     [:div.viewer-content
      [:& header {:project project
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

           [:& viewer-wrapper
            {:wrapper-size wrapper-size
             :scroll scroll
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
             :index index}]))]]]))

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

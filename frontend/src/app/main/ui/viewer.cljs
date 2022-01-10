;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.share-link]
   [app.main.ui.static :as static]
   [app.main.ui.viewer.comments :refer [comments-layer]]
   [app.main.ui.viewer.handoff :as handoff]
   [app.main.ui.viewer.header :refer [header]]
   [app.main.ui.viewer.interactions :as interactions]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [goog.events :as events]
   [rumext.alpha :as mf]))

(defn- calculate-size
  [frame zoom]
  (let [{:keys [_ _ width height]} (filters/get-filters-bounds frame)]
    {:base-width  width
     :base-height height
     :width       (* width zoom)
     :height      (* height zoom)
     :vbox        (str "0 0 " width " " height)}))

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

(mf/defc viewer
  [{:keys [params data]}]

  (let [{:keys [page-id section index]} params
        {:keys [file users project permissions]} data

        local (mf/deref refs/viewer-local)

        nav-scroll (:nav-scroll local)
        orig-viewport-ref (mf/use-ref nil)
        current-viewport-ref (mf/use-ref nil)
        current-animation (:current-animation local)

        page-id (or page-id (-> file :data :pages first))

        page (mf/use-memo
              (mf/deps data page-id)
              (fn []
                (get-in data [:pages page-id])))

        zoom   (:zoom local)
        frames (:frames page)
        frame  (get frames index)

        overlays (:overlays local)

        orig-frame
        (when (:orig-frame-id current-animation)
          (d/seek #(= (:id %) (:orig-frame-id current-animation)) frames))

        size (mf/use-memo
              (mf/deps frame zoom)
              (fn [] (calculate-size frame zoom)))

        orig-size (mf/use-memo
                    (mf/deps orig-frame zoom)
                    (fn [] (when orig-frame (calculate-size orig-frame zoom))))

        wrapper-size (mf/use-memo
                       (mf/deps size orig-size zoom)
                       (fn [] (calculate-wrapper size orig-size zoom)))

        interactions-mode
        (:interactions-mode local)

        on-click
        (mf/use-callback
         (mf/deps section)
         (fn [_]
           (when (= section :comments)
             (st/emit! (dcm/close-thread)))))

        close-overlay
        (mf/use-callback
          (fn [frame]
            (st/emit! (dv/close-overlay (:id frame)))))]

    (hooks/use-shortcuts ::viewer sc/shortcuts)

    (when (nil? page)
      (ex/raise :type :not-found))

    ;; Set the page title
    (mf/use-effect
     (mf/deps (:name file))
     (fn []
       (let [name (:name file)]
         (dom/set-html-title (str "\u25b6 " (tr "title.viewer" name))))))

    (mf/use-effect
     (fn []
       (let [key1 (events/listen js/window "click" on-click)]
         (fn []
           (events/unlistenByKey key1)))))

    (mf/use-layout-effect
      (mf/deps nav-scroll)
      (fn []
        ;; Set scroll position after navigate
        (when (number? nav-scroll)
          (let [viewer-section (dom/get-element "viewer-section")]
            (st/emit! (dv/reset-nav-scroll))
            (dom/set-scroll-pos! viewer-section nav-scroll)))))

    (mf/use-layout-effect
      (mf/deps index)
      (fn []
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
                  overlay-size (calculate-size (:frame overlay) zoom)
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
                  overlay-size (calculate-size (:frame overlay) zoom)
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

    [:div {:class (dom/classnames
                   :force-visible (:show-thumbnails local)
                   :viewer-layout (not= section :handoff)
                   :handoff-layout (= section :handoff))}

     [:& header {:project project
                 :index index
                 :file file
                 :page page
                 :frame frame
                 :permissions permissions
                 :zoom zoom
                 :section section}]

     [:div.viewer-content
      [:& thumbnails-panel {:frames frames
                            :show? (:show-thumbnails local false)
                            :page page
                            :index index}]
      [:section.viewer-section {:id "viewer-section"}
       (cond
         (empty? frames)
         [:section.empty-state
          [:span (tr "viewer.empty-state")]]

         (nil? frame)
         [:section.empty-state
          [:span (tr "viewer.frame-not-found")]]

         (some? frame)
         (if (= :handoff section)
           [:& handoff/viewport
            {:frame frame
             :page page
             :file file
             :section section
             :local local}]

           [:*
            [:div.viewer-wrapper
             {:style {:width (:width wrapper-size)
                      :height (:height wrapper-size)}}

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
                  (let [size-over (calculate-size (:frame overlay) zoom)]
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
                      {:id (str "overlay-" (str (:id (:frame overlay))))
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
                                    :zoom zoom}])]]]))]]]))

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

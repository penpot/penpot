;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer
  (:require
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
    {:width  (* width zoom)
     :height (* height zoom)
     :vbox   (str "0 0 " width " " height)}))

(mf/defc viewer
  [{:keys [params data]}]

  (let [{:keys [page-id section index]} params
        {:keys [file users project permissions]} data

        local   (mf/deref refs/viewer-local)

        page-id (or page-id (-> file :data :pages first))

        page    (mf/use-memo
                 (mf/deps data page-id)
                 (fn []
                   (get-in data [:pages page-id])))

        zoom    (:zoom local)
        frames  (:frames page)
        frame   (get frames index)

        size    (mf/use-memo
                 (mf/deps frame zoom)
                 (fn [] (calculate-size frame zoom)))

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
      [:section.viewer-preview
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

           [:div.viewport-container
            {:style {:width (:width size)
                     :height (:height size)
                     :position "relative"}}

            (when (= section :comments)
              [:& comments-layer {:file file
                                  :users users
                                  :frame frame
                                  :page page
                                  :zoom zoom}])

            [:& interactions/viewport
             {:frame frame
              :base-frame frame
              :frame-offset (gpt/point 0 0)
              :size size
              :page page
              :file file
              :users users
              :interactions-mode interactions-mode}]

            (for [overlay (:overlays local)]
              (let [size-over (calculate-size (:frame overlay) zoom)]
                [:*
                 (when (or (:close-click-outside overlay)
                           (:background-overlay  overlay))
                   [:div.viewer-overlay-background
                    {:class (dom/classnames
                              :visible (:background-overlay overlay))
                     :style {:width (:width frame)
                             :height (:height frame)
                             :position "absolute"
                             :left 0
                             :top 0}
                     :on-click #(when (:close-click-outside overlay)
                                  (close-overlay (:frame overlay)))}])
                 [:div.viewport-container.viewer-overlay
                  {:style {:width (:width size-over)
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
                    :interactions-mode interactions-mode}]]]))]))]]]))

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

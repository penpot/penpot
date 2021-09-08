;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer
  (:require
   [app.main.data.comments :as dcm]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
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
  {:width  (* (:width frame) zoom)
   :height (* (:height frame) zoom)
   :vbox   (str "0 0 " (:width frame 0) " " (:height frame 0))})

(mf/defc viewer
  [{:keys [params data]}]

  (let [{:keys [page-id section index]} params

        local   (mf/deref refs/viewer-local)

        file    (:file data)
        users   (:users data)
        project (:project data)
        perms   (:permissions data)

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

        on-click
        (mf/use-callback
         (mf/deps section)
         (fn [_]
           (when (= section :comments)
             (st/emit! (dcm/close-thread)))))]

    (hooks/use-shortcuts ::viewer sc/shortcuts)

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
                 :file file
                 :page page
                 :frame frame
                 :permissions perms
                 :zoom (:zoom local)
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
              :size size
              :page page
              :file file
              :users users
              :local local}]]))]]]))

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

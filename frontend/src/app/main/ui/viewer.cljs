;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages-helpers :as cph]
   [app.main.data.viewer :as dv]
   [app.main.data.comments :as dcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.fullscreen :as fs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.viewer.header :refer [header]]
   [app.main.ui.viewer.shapes :as shapes :refer [frame-svg]]
   [app.main.ui.viewer.thumbnails :refer [thumbnails-panel]]
   [app.main.ui.comments :as cmt]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn- frame-contains?
  [{:keys [x y width height]} {px :x py :y}]
  (let [x2 (+ x width)
        y2 (+ y height)]
    (and (<= x px x2)
         (<= y py y2))))

(def threads-ref
  (l/derived :comment-threads st/state))

(def comments-local-ref
  (l/derived :comments-local st/state))

(mf/defc comments-layer
  [{:keys [width height zoom frame data] :as props}]
  (let [profile     (mf/deref refs/profile)

        modifier1   (-> (gpt/point (:x frame) (:y frame))
                        (gpt/negate)
                        (gmt/translate-matrix))

        modifier2   (-> (gpt/point (:x frame) (:y frame))
                        (gmt/translate-matrix))

        threads-map (->> (mf/deref threads-ref)
                         (d/mapm #(update %2 :position gpt/transform modifier1)))

        cstate      (mf/deref refs/comments-local)

        mframe      (geom/transform-shape frame)
        threads     (->> (vals threads-map)
                         (dcm/apply-filters cstate profile)
                         (filter (fn [{:keys [seqn position]}]
                                   (frame-contains? mframe position))))

        on-bubble-click
        (mf/use-callback
         (mf/deps cstate)
         (fn [thread]
           (if (= (:open cstate) (:id thread))
             (st/emit! (dcm/close-thread))
             (st/emit! (dcm/open-thread thread)))))

        on-click
        (mf/use-callback
         (mf/deps cstate data frame)
         (fn [event]
           (dom/stop-propagation event)
           (if (some? (:open cstate))
             (st/emit! (dcm/close-thread))
             (let [event    (.-nativeEvent ^js event)
                   position (-> (dom/get-offset-position event)
                                (gpt/transform modifier2))
                   params   {:position position
                             :page-id (get-in data [:page :id])
                             :file-id (get-in data [:file :id])}]
               (st/emit! (dcm/create-draft params))))))

        on-draft-cancel
        (mf/use-callback
         (mf/deps cstate)
         (st/emitf (dcm/close-thread)))

        on-draft-submit
        (mf/use-callback
         (mf/deps frame)
         (fn [draft]
           (let [params (update draft :position gpt/transform modifier2)]
             (st/emit! (dcm/create-thread params)
                       (dcm/close-thread)))))]

    [:div.comments-section {:on-click on-click}
     [:div.viewer-comments-container
      [:div.threads
       (for [item threads]
         [:& cmt/thread-bubble {:thread item
                                :zoom zoom
                                :on-click on-bubble-click
                                :open? (= (:id item) (:open cstate))
                                :key (:seqn item)}])

       (when-let [id (:open cstate)]
         (when-let [thread (get threads-map id)]
           [:& cmt/thread-comments {:thread thread
                                    :users (:users data)
                                    :zoom zoom}]))

       (when-let [draft (:draft cstate)]
         [:& cmt/draft-thread {:draft (update draft :position gpt/transform modifier1)
                               :on-cancel on-draft-cancel
                               :on-submit on-draft-submit
                               :zoom zoom}])]]]))



(mf/defc viewport
  {::mf/wrap [mf/memo]}
  [{:keys [state data index section] :or {zoom 1} :as props}]
  (let [zoom          (:zoom state)
        objects       (:objects data)

        frame         (get-in data [:frames index])
        frame-id      (:id frame)

        modifier      (-> (gpt/point (:x frame) (:y frame))
                          (gpt/negate)
                          (gmt/translate-matrix))

        update-fn     #(assoc-in %1 [%2 :modifiers :displacement] modifier)

        objects       (->> (d/concat [frame-id] (cph/get-children frame-id objects))
                           (reduce update-fn objects))

        interactions? (:interactions-show? state)
        wrapper       (mf/use-memo (mf/deps objects) #(shapes/frame-container-factory objects interactions?))

        ;; Retrieve frame again with correct modifier
        frame         (get objects frame-id)

        width         (* (:width frame) zoom)
        height        (* (:height frame) zoom)
        vbox          (str "0 0 " (:width frame 0) " " (:height frame 0))]

    [:div.viewport-container
     {:style {:width width
              :height height
              :state state
              :position "relative"}}

     (when (= section :comments)
       [:& comments-layer {:width width
                           :height height
                           :frame frame
                           :data data
                           :zoom zoom}])

     [:svg {:view-box vbox
            :width width
            :height height
            :version "1.1"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :xmlns "http://www.w3.org/2000/svg"}
      [:& wrapper {:shape frame
                   :show-interactions? interactions?
                   :view-box vbox}]]]))

(mf/defc main-panel
  [{:keys [data state index section]}]
  (let [locale  (mf/deref i18n/locale)
        frames  (:frames data)
        frame   (get frames index)]
    [:section.viewer-preview
     (cond
       (empty? frames)
       [:section.empty-state
        [:span (t locale "viewer.empty-state")]]

       (nil? frame)
       [:section.empty-state
        [:span (t locale "viewer.frame-not-found")]]

       (some? state)
       [:& viewport
        {:data data
         :section section
         :index index
         :state state
         }])]))

(mf/defc viewer-content
  [{:keys [data state index section] :as props}]
  (let [on-click
        (fn [event]
          (dom/stop-propagation event)
          (let [mode (get state :interactions-mode)]
            (when (= mode :show-on-click)
              (st/emit! dv/flash-interactions))))

        on-mouse-wheel
        (fn [event]
          (when (kbd/ctrl? event)
            (dom/prevent-default event)
            (let [event (.getBrowserEvent ^js event)]
              (if (pos? (.-deltaY ^js event))
                (st/emit! dv/decrease-zoom)
                (st/emit! dv/increase-zoom)))))

        on-click
        (fn [event]
          (st/emit! (dcm/close-thread)))

        on-key-down
        (fn [event]
          (when (kbd/esc? event)
            (st/emit! (dcm/close-thread))))

        on-mount
        (fn []
          ;; bind with passive=false to allow the event to be cancelled
          ;; https://stackoverflow.com/a/57582286/3219895
          (let [key1 (events/listen goog/global "wheel" on-mouse-wheel #js {"passive" false})
                key2 (events/listen js/document "keydown" on-key-down)
                key3 (events/listen js/document "click" on-click)]
            (fn []
              (events/unlistenByKey key1)
              (events/unlistenByKey key2)
              (events/unlistenByKey key3))))]

    (mf/use-effect on-mount)
    (hooks/use-shortcuts dv/shortcuts)

    [:& fs/fullscreen-wrapper {}
     [:div.viewer-layout
      [:& header
       {:data data
        :state state
        :section section
        :index index}]

      [:div.viewer-content {:on-click on-click}
       (when (:show-thumbnails state)
         [:& thumbnails-panel {:screen :viewer
                               :index index
                               :data data}])
       [:& main-panel {:data data
                       :section section
                       :state state
                       :index index}]]]]))


;; --- Component: Viewer Page

(mf/defc viewer-page
  [{:keys [file-id page-id index token section] :as props}]

  (mf/use-effect
   (mf/deps file-id page-id token)
   (st/emitf (dv/initialize props)))

  (let [data  (mf/deref refs/viewer-data)
        state (mf/deref refs/viewer-local)]
    (when (and data state)
      [:& viewer-content
       {:index index
        :section section
        :state state
        :data data}])))

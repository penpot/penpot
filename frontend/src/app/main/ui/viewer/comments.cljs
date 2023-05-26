;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.comments
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.comments :as dcm]
   [app.main.data.events :as ev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.comments :as wc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc comments-menu
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  []
  (let [{cmode :mode cshow :show show-sidebar? :show-sidebar?} (mf/deref refs/comments-local)

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        update-mode
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:mode mode}))))

        update-show
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:show mode}))))

        update-options
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-options {:show-sidebar? mode}))))]

    [:div.view-options {:on-click toggle-dropdown}
     [:span.label (tr "labels.comments")]
     [:span.icon i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}

      [:ul.dropdown.with-check
       [:li {:class (dom/classnames :selected (or (= :all cmode) (nil? cmode)))
             :on-click #(update-mode :all)}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-all-comments")]]

       [:li {:class (dom/classnames :selected (= :yours cmode))
             :on-click #(update-mode :yours)}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-your-comments")]]

       [:hr]

       [:li {:class (dom/classnames :selected (= :pending cshow))
             :on-click #(update-show (if (= :pending cshow) :all :pending))}
        [:span.icon i/tick]
        [:span.label (tr "labels.hide-resolved-comments")]]

       [:hr]
       [:li {:class (dom/classnames :selected show-sidebar?)
             :on-click #(update-options (not show-sidebar?))}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-comments-list")]]]]]))


(defn- update-thread-position [positions {:keys [id] :as thread}]
  (if-let [data (get positions id)]
    (-> thread
        (assoc :position (:position data))
        (assoc :frame-id (:frame-id data)))
    thread))

(mf/defc comments-layer
  [{:keys [zoom file users frame page] :as props}]
  (let [profile        (mf/deref refs/profile)
        local          (mf/deref refs/comments-local)

        open-thread-id (:open local)
        page-id        (:id page)
        file-id        (:id file)
        frame-id       (:id frame)

        tpos-ref     (mf/with-memo [page-id]
                       (-> (l/in [:pages page-id :options :comment-threads-position])
                           (l/derived refs/viewer-data)))

        positions    (mf/deref tpos-ref)
        threads-map  (mf/deref refs/comment-threads)

        frame-corner (mf/with-memo [frame]
                       (-> frame :points grc/points->rect gpt/point))

        modifier1    (mf/with-memo [frame-corner]
                       (-> (gmt/matrix)
                           (gmt/translate (gpt/negate frame-corner))))

        modifier2    (mf/with-memo [frame-corner]
                       (-> (gpt/point frame-corner)
                           (gmt/translate-matrix)))

        threads      (mf/with-memo [threads-map positions frame local profile]
                       (->> (vals threads-map)
                            (map (partial update-thread-position positions))
                            (filter #(= (:frame-id %) (:id frame)))
                            (dcm/apply-filters local profile)
                            (filter (fn [{:keys [position]}]
                                      (gsh/has-point? frame position)))))

        on-bubble-click
        (mf/use-fn
         (mf/deps open-thread-id)
         (fn [{:keys [id] :as thread}]
           (st/emit! (if (= open-thread-id id)
                       (dcm/close-thread)
                       (-> (dcm/open-thread thread)
                           (with-meta {::ev/origin "viewer"}))))))

        on-click
        (mf/use-fn
         (mf/deps open-thread-id zoom page-id file-id modifier2)
         (fn [event]
           (dom/stop-propagation event)
           (if (some? open-thread-id)
             (st/emit! (dcm/close-thread))
             (let [event    (dom/event->native-event event)
                   position (-> (dom/get-offset-position event)
                                (update :x #(/ % zoom))
                                (update :y #(/ % zoom))
                                (gpt/transform modifier2))
                   params   {:position position
                             :page-id (:id page)
                             :file-id (:id file)}]
               (st/emit! (dcm/create-draft params))))))

        on-draft-cancel
        (mf/use-fn #(st/emit! (dcm/close-thread)))

        on-draft-submit
        (mf/use-fn
         (mf/deps frame-id modifier2)
         (fn [draft]
           (let [params (assoc draft :frame-id frame-id)]
             (st/emit! (dcm/create-thread-on-viewer params)
                       (dcm/close-thread)))))]

    [:div.comments-section {:on-click on-click}
     [:div.viewer-comments-container
      [:div.threads
       (for [item threads]
         [:& cmt/thread-bubble
          {:thread item
           :position-modifier modifier1
           :zoom zoom
           :on-click on-bubble-click
           :open? (= (:id item) (:open local))
           :key (:seqn item)
           :origin :viewer}])

       (when-let [thread (get threads-map open-thread-id)]
         [:& cmt/thread-comments
          {:thread thread
           :position-modifier modifier1
           :users users
           :zoom zoom}])

       (when-let [draft (:draft local)]
         [:& cmt/draft-thread
          {:draft draft
           :position-modifier modifier1
           :on-cancel on-draft-cancel
           :on-submit on-draft-submit
           :zoom zoom}])]]]))

(mf/defc comments-sidebar
  [{:keys [users frame page]}]
  (let [profile     (mf/deref refs/profile)
        local      (mf/deref refs/comments-local)
        threads-map (mf/deref refs/comment-threads)
        threads     (->> (vals threads-map)
                         (dcm/apply-filters local profile)
                         (filter (fn [{:keys [position]}]
                                   (gsh/has-point? frame position))))]
    [:aside.settings-bar.settings-bar-right.comments-right-sidebar
     [:div.settings-bar-inside
      [:& wc/comments-sidebar {:users users :threads threads :page-id (:id page)}]]]))

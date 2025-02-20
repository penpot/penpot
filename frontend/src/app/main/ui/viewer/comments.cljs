;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.comments :as dcm]
   [app.main.data.event :as ev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.comments :as cmt]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.comments :as wc]
   [app.main.ui.workspace.viewport.utils :as utils]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc comments-menu
  {::mf/props :obj
   ::mf/memo true}
  []
  (let [state           (mf/deref refs/comments-local)
        cmode           (:mode state)
        cshow           (:show state)
        show-sidebar?   (:show-sidebar? state false)

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        update-mode
        (mf/use-fn
         (fn [event]
           (let [mode (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (keyword))]
             (st/emit! (dcm/update-filters {:mode mode})))))

        update-show
        (mf/use-fn
         (fn [event]
           (let [mode (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (keyword))
                 mode (if (= :pending mode) :all :pending)]
             (st/emit! (dcm/update-filters {:show mode})))))

        update-options
        (mf/use-fn
         (fn [event]
           (let [mode (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (parse-boolean))]
             (st/emit! (dcm/update-options {:show-sidebar? (not mode)})))))]

    [:div {:class (stl/css :view-options)
           :data-testid "viewer-comments-dropdown"
           :on-click toggle-dropdown}
     [:span {:class (stl/css :dropdown-title)} (tr "labels.comments")]
     [:span {:class (stl/css :icon-dropdown)} i/arrow]

     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul {:class (stl/css :dropdown)}

       [:li {:class (stl/css-case
                     :dropdown-element true
                     :selected (or (= :all cmode) (nil? cmode)))
             :data-value "all"
             :on-click update-mode}
        [:span {:class (stl/css :label)} (tr "labels.show-all-comments")]
        (when (or (= :all cmode) (nil? cmode))
          [:span {:class (stl/css :icon)} i/tick])]

       [:li {:class (stl/css-case
                     :dropdown-element true
                     :selected (= :yours cmode))
             :data-value "yours"
             :on-click update-mode}
        [:span {:class (stl/css :label)} (tr "labels.show-your-comments")]
        (when (= :yours cmode)
          [:span {:class (stl/css :icon)}
           i/tick])]

       [:li {:class (stl/css :separator)}]

       [:li {:class (stl/css-case
                     :dropdown-element true
                     :selected (= :pending cshow))
             :data-value (d/name cshow)
             :on-click update-show}
        [:span {:class (stl/css :label)} (tr "labels.hide-resolved-comments")]
        (when  (= :pending cshow)
          [:span {:class (stl/css :icon)}
           i/tick])]

       [:li {:class (stl/css :separator)}]

       [:li {:class (stl/css-case
                     :dropdown-element true
                     :selected show-sidebar?)
             :data-value (dm/str show-sidebar?)
             :on-click update-options}
        [:span {:class (stl/css :label)} (tr "labels.show-comments-list")]
        (when show-sidebar?
          [:span {:class (stl/css :icon)} i/tick])]]]]))


(defn- update-thread-position
  [positions {:keys [id] :as thread}]
  (if-let [data (get positions id)]
    (-> thread
        (assoc :position (:position data))
        (assoc :frame-id (:frame-id data)))
    thread))

(mf/defc comments-layer
  {::mf/props :obj}
  [{:keys [zoom file frame page]}]
  (let [profile        (mf/deref refs/profile)
        local          (mf/deref refs/comments-local)

        cursor         (utils/get-cursor :comments)

        open-thread-id (:open local)
        page-id        (:id page)
        file-id        (:id file)
        frame-id       (:id frame)
        vsize          (-> (mf/deref refs/viewer-local)
                           :viewport-size)

        tpos-ref     (mf/with-memo [page-id]
                       (-> (l/in [:pages page-id :comment-thread-positions])
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

    [:div {:class (stl/css :comments-section)
           :on-click on-click}
     [:div {:class (dm/str cursor " " (stl/css :viewer-comments-container))}
      [:div {:class (stl/css :threads)}
       (for [item threads]
         [:> cmt/comment-floating-bubble*
          {:thread item
           :position-modifier modifier1
           :zoom zoom
           :on-click on-bubble-click
           :is-open (= (:id item) (:open local))
           :key (:seqn item)
           :origin :viewer}])

       (when-let [thread (get threads-map open-thread-id)]
         [:> cmt/comment-floating-thread*
          {:thread thread
           :position-modifier modifier1
           :viewport {:offset-x 0 :offset-y 0 :width (:width vsize) :height (:height vsize)}
           :zoom zoom}])

       (when-let [draft (:draft local)]
         [:> cmt/comment-floating-thread-draft*
          {:draft draft
           :position-modifier modifier1
           :on-cancel on-draft-cancel
           :on-submit on-draft-submit
           :zoom zoom}])]]]))

(mf/defc comments-sidebar*
  {::mf/props :obj}
  [{:keys [profiles frame page]}]
  (let [profile     (mf/deref refs/profile)
        local       (mf/deref refs/comments-local)
        threads-map (mf/deref refs/comment-threads)
        threads     (->> (vals threads-map)
                         (dcm/apply-filters local profile)
                         (filter (fn [{:keys [position]}]
                                   (gsh/has-point? frame position))))]
    [:aside {:class (stl/css :comments-sidebar)}
     [:div {:class (stl/css :settings-bar-inside)}
      [:> wc/comments-sidebar*
       {:from-viewer true
        :profiles profiles
        :threads threads
        :page-id (:id page)}]]]))

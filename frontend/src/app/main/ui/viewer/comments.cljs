;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.comments
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
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
   [rumext.alpha :as mf]))

(mf/defc comments-menu
  []
  (let [{cmode :mode cshow :show clist :list} (mf/deref refs/comments-local)

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

        update-list
        (mf/use-callback
         (fn [show-list]
           (st/emit! (dcm/update-filters {:list show-list}))))]

    [:div.view-options {:on-click toggle-dropdown}
     [:span.label (tr "labels.comments")]
     [:span.icon i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul.dropdown.with-check
       [:li {:class (dom/classnames :selected (= :all cmode))
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

       [:li {:class (dom/classnames :selected (= :show clist))
             :on-click #(update-list (if (= :show clist) :hide :show))}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-comments-list")]]]]]))


(def threads-ref
  (l/derived :comment-threads st/state))

(def comments-local-ref
  (l/derived :comments-local st/state))

(mf/defc comments-layer
  [{:keys [zoom file users frame page] :as props}]
  (let [profile     (mf/deref refs/profile)
        threads-map (mf/deref threads-ref)

        frame-corner (-> frame :points gsh/points->selrect gpt/point)
        modifier1   (-> (gmt/matrix)
                        (gmt/translate (gpt/negate frame-corner)))

        modifier2   (-> (gpt/point frame-corner)
                        (gmt/translate-matrix))

        cstate      (mf/deref refs/comments-local)

        threads     (->> (vals threads-map)
                         (dcm/apply-filters cstate profile)
                         (filter (fn [{:keys [position]}]
                                   (gsh/has-point? frame position))))

        on-bubble-click
        (mf/use-callback
         (mf/deps cstate)
         (fn [thread]
           (if (= (:open cstate) (:id thread))
             (st/emit! (dcm/close-thread))
             (st/emit! (-> (dcm/open-thread thread)
                           (with-meta {::ev/origin "viewer"}))))))

        on-click
        (mf/use-callback
         (mf/deps cstate frame page file zoom)
         (fn [event]
           (dom/stop-propagation event)
           (if (some? (:open cstate))
             (st/emit! (dcm/close-thread))
             (let [event    (.-nativeEvent ^js event)
                   viewport-point (dom/get-offset-position event)
                   viewport-point (-> viewport-point (update :x #(/ % zoom)) (update :y #(/ % zoom)))
                   position (gpt/transform viewport-point modifier2)
                   params   {:position position
                             :page-id (:id page)
                             :file-id (:id file)}]
               (st/emit! (dcm/create-draft params))))))

        on-draft-cancel
        (mf/use-callback
         (mf/deps cstate)
         #(st/emit! (dcm/close-thread)))

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
         (let [item (update item :position gpt/transform modifier1)]
           [:& cmt/thread-bubble {:thread item
                                  :zoom zoom
                                  :on-click on-bubble-click
                                  :open? (= (:id item) (:open cstate))
                                  :key (:seqn item)}]))

       (when-let [id (:open cstate)]
         (when-let [thread (as-> (get threads-map id) $
                             (when (some? $)
                               (update $ :position gpt/transform modifier1)))]
           [:& cmt/thread-comments {:thread thread
                                    :users users
                                    :zoom zoom}]))

       (when-let [draft (:draft cstate)]
         [:& cmt/draft-thread {:draft (update draft :position gpt/transform modifier1)
                               :on-cancel on-draft-cancel
                               :on-submit on-draft-submit
                               :zoom zoom}])]]]))

(mf/defc comments-sidebar
  [{:keys [users frame page]}]
  (let [profile     (mf/deref refs/profile)
        cstate      (mf/deref refs/comments-local)
        threads-map (mf/deref threads-ref)
        threads     (->> (vals threads-map)
                         (dcm/apply-filters cstate profile)
                         (filter (fn [{:keys [position]}]
                                   (gsh/has-point? frame position))))]
    [:aside.settings-bar.settings-bar-right.comments-right-sidebar
     [:div.settings-bar-inside
      [:& wc/comments-sidebar {:users users :threads threads :page-id (:id page)}]]]))
